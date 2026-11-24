package com.company.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.company.policy.mapper.PolicyReviewWorkflowMapper;
import com.company.policy.service.PolicyFeishuSender.Delivery;
import com.company.policy.service.PolicyFeishuSender.Failure;
import com.company.policy.service.PolicyFeishuSender.Kind;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        properties = {
            "spring.config.import=",
            "spring.datasource.url=jdbc:h2:mem:delivery_worker;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.sql.init.schema-locations=classpath:user-info-schema.sql,classpath:catalog-import-schema.sql",
            "feishu.tenant-key=company",
            "jackyun.import-scheduling-enabled=false",
            "jackyun.clean-scheduling-enabled=false",
            "policy.review-delivery-enabled=false"
        })
class PolicyDeliveryWorkerTest {
    @Autowired PolicyDeliveryWorker worker;
    @Autowired PolicyDeliveryAttemptGuard guard;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired PolicyReviewWorkflowMapper workflow;
    @Autowired PolicyReviewNotifications events;
    @MockitoBean PolicyFeishuSender sender;

    private final List<Delivery> calls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void prepare() {
        for (var table :
                List.of(
                        "policy_review_delivery",
                        "policy_review_member",
                        "policy_review_round",
                        "policy_audit_log",
                        "policy_apply",
                        "user_info")) jdbc.update("DELETE FROM " + table);
        for (int i = 1; i <= 5; i++)
            jdbc.update(
                    "INSERT INTO user_info(id,tenant_key,open_id,name,role_type) VALUES (?,'company',?,'用户',?)",
                    i,
                    "open-" + i,
                    i >= 4 ? "ADMIN" : "APPLICANT");
        jdbc.update(
                """
                INSERT INTO policy_apply(id,tenant_key,policy_no,applicant_id,applicant_name,channel_type,status,current_version,record_version)
                VALUES(11,'company','D001',1,'申请人','RETAIL','PENDING',1,2)
                """);
        jdbc.update(
                "INSERT INTO policy_audit_log(tenant_key,policy_id,version,snapshot_data) VALUES('company',11,1,'{}')");
        jdbc.update(
                "INSERT INTO policy_review_round(tenant_key,policy_id,version,settings_version,stage,route_json) VALUES('company',11,1,1,'PRODUCT_REVIEW','{}')");
        for (int i = 2; i <= 5; i++)
            jdbc.update(
                    "INSERT INTO policy_review_member(id,policy_id,version,tenant_key,group_code,user_id,display_name) VALUES(?,11,1,'company',?,?,'成员')",
                    i,
                    i >= 4 ? "PURCHASE" : "PRODUCT",
                    i);
        doAnswer(
                        call -> {
                            Delivery d = call.getArgument(0);
                            calls.add(d);
                            return d.priorReceipt() != null
                                    ? d.priorReceipt()
                                    : "receipt-" + d.id();
                        })
                .when(sender)
                .send(any());
    }

    @Test
    void trialCardClaimsOnlyTheSelectedRegistrationAndKeepsItsOriginalReceipt() {
        registerSubmitted();
        add("SUBMITTED", "GROUP_MESSAGE", 0);
        String target =
                PolicyFeishuSender.idempotencyKey("company", 11, 1, "SUBMITTED", "SEND_CARD", 3);
        String group =
                PolicyFeishuSender.idempotencyKey(
                        "company", 11, 1, "SUBMITTED", "GROUP_MESSAGE", 0);
        doAnswer(
                        c -> {
                            Delivery item = c.getArgument(0);
                            assertThat(item.memberId()).isEqualTo(3);
                            assertThat(item.idempotencyKey()).isEqualTo(target);
                            guard.prepare(item, item.openId(), "trial-card");
                            return "om_trial";
                        })
                .when(sender)
                .send(any());
        assertThat(worker.processTrialCard(11, 2, target).status()).isEqualTo("NOT_FOUND");
        assertThat(worker.processTrialCard(11, 1, group).status()).isEqualTo("NOT_FOUND");
        var result = worker.processTrialCard(11, 1, target);
        assertThat(result)
                .isEqualTo(new PolicyDeliveryWorker.TrialResult("SENT", "om_trial", null));
        assertThat(worker.processTrialCard(11, 1, target)).isEqualTo(result);
        assertThat(value("attempts", "SEND_CARD", 2)).isEqualTo("0");
        assertThat(value("attempts", "GROUP_MESSAGE", 0)).isEqualTo("0");
        assertThat(value("payload_json", "SEND_CARD", 3)).contains("trial-card");
        verify(sender).send(any());
        verifyNoMoreInteractions(sender);
    }

    @Test
    void trialStopsForRemovedAssignmentAndRespectsManualReconciliation() {
        registerSubmitted();
        var removed =
                PolicyFeishuSender.idempotencyKey("company", 11, 1, "SUBMITTED", "SEND_CARD", 2);
        var uncertain =
                PolicyFeishuSender.idempotencyKey("company", 11, 1, "SUBMITTED", "SEND_CARD", 3);
        jdbc.update("UPDATE policy_review_member SET member_status='REMOVED' WHERE id=2");
        jdbc.update(
                "UPDATE policy_review_delivery SET delivery_status='UNKNOWN',next_attempt_at=NULL WHERE recipient_key=3");
        assertThat(worker.processTrialCard(11, 1, removed).status()).isEqualTo("CANCELLED");
        assertThat(worker.processTrialCard(11, 1, uncertain).status()).isEqualTo("UNKNOWN");
        verifyNoInteractions(sender);
    }

    @Test
    void rolledBackSubmissionCannotBeClaimedAndOnlyOneWorkerClaimsInFlightCard() throws Exception {
        tx.executeWithoutResult(
                s -> {
                    events.submitted("company", 11, 1, true, "确认段", LocalDateTime.now());
                    s.setRollbackOnly();
                });
        assertThat(worker.processOne()).isFalse();
        add("SUBMITTED", "SEND_CARD", 2);
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(
                        c -> {
                            started.countDown();
                            if (!finish.await(8, TimeUnit.SECONDS))
                                throw new AssertionError("模拟发送未释放");
                            return "card-2";
                        })
                .when(sender)
                .send(any());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var one = pool.submit(worker::processOne);
            assertThat(started.await(8, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.processOne()).isFalse();
            finish.countDown();
            assertThat(one.get(8, TimeUnit.SECONDS)).isTrue();
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
        assertThat(value("receipt_id", "SEND_CARD", 2)).isEqualTo("card-2");
        verify(sender, times(1)).send(any());
    }

    @Test
    void lostCreateResponseReusesStableKeyAndUpdatesWaitForTheIndividualReceipt() {
        registerSubmitted();
        var remote = new HashMap<String, String>();
        doAnswer(
                        c -> {
                            Delivery d = c.getArgument(0);
                            calls.add(d);
                            guard.prepare(d, d.openId(), "same-card");
                            var previous =
                                    remote.putIfAbsent(d.idempotencyKey(), "card-" + d.memberId());
                            if (d.memberId() == 2 && previous == null)
                                throw new Failure(Kind.UNKNOWN, "响应丢失");
                            return remote.get(d.idempotencyKey());
                        })
                .when(sender)
                .send(any());
        assertThat(worker.processOne()).isTrue();
        assertThat(worker.processOne()).isTrue();
        assertThat(value("delivery_status", "SEND_CARD", 2)).isEqualTo("UNKNOWN");
        assertThat(value("receipt_id", "SEND_CARD", 3)).isEqualTo("card-3");
        due();
        assertThat(worker.processOne()).isTrue();
        assertThat(worker.processOne()).isFalse();
        assertThat(remote).hasSize(2);
        assertThat(
                        calls.stream()
                                .filter(d -> d.memberId() == 2)
                                .map(Delivery::idempotencyKey)
                                .distinct())
                .hasSize(1);
        assertThat(value("receipt_id", "SEND_CARD", 2)).isEqualTo("card-2");

        // 决定落库，所有卡片各自更新。第一次更新失败，仅重试这张卡。
        jdbc.update("UPDATE policy_review_round SET stage='PURCHASE_REVIEW'");
        jdbc.update(
                "UPDATE policy_review_member SET member_status=CASE id WHEN 2 THEN 'HANDLED' ELSE 'PEER_HANDLED' END WHERE group_code='PRODUCT'");
        tx.executeWithoutResult(
                s ->
                        events.decided(
                                "company",
                                11,
                                1,
                                "PRODUCT",
                                2,
                                "APPROVE",
                                null,
                                LocalDateTime.now()));
        calls.clear();
        doAnswer(
                        c -> {
                            Delivery d = c.getArgument(0);
                            calls.add(d);
                            if (d.memberId() == 2 && calls.size() == 1)
                                throw new Failure(Kind.RETRYABLE, "限频");
                            return d.priorReceipt() == null ? "ext-" + d.id() : d.priorReceipt();
                        })
                .when(sender)
                .send(any());
        drain();
        assertThat(value("delivery_status", "UPDATE_CARD", 2)).isEqualTo("RETRY");
        assertThat(value("receipt_id", "UPDATE_CARD", 3)).isEqualTo("card-3");
        assertThat(calls.stream().filter(d -> d.action().equals("GROUP_MESSAGE"))).hasSize(1);
        assertThat(calls.stream().filter(d -> d.action().equals("CREATE_TASK"))).hasSize(2);
        due();
        drain();
        assertThat(value("receipt_id", "UPDATE_CARD", 2)).isEqualTo("card-2");
        assertThat(calls).hasSize(6);
        assertThat(
                        jdbc.queryForList(
                                "SELECT task_guid FROM policy_review_member WHERE group_code='PURCHASE'",
                                String.class))
                .containsExactlyInAnyOrder(
                        value("receipt_id", "CREATE_TASK", 4),
                        value("receipt_id", "CREATE_TASK", 5));
    }

    @Test
    void removedAndReaddedAssignmentsHaveDifferentCardsAndStaleUnsentCardsAreCancelled() {
        registerSubmitted();
        assertThat(worker.processOne()).isTrue();
        jdbc.update("UPDATE policy_review_member SET member_status='REMOVED' WHERE id=2");
        jdbc.update(
                "INSERT INTO policy_review_member(id,policy_id,version,tenant_key,group_code,user_id,display_name,assignment_no) VALUES(6,11,1,'company','PRODUCT',2,'重新加入',2)");
        add("REASSIGNED_3", "UPDATE_CARD", 2);
        add("REASSIGNED_4", "SEND_CARD", 6);
        jdbc.update("UPDATE policy_review_member SET member_status='REMOVED' WHERE id=3");
        add("REASSIGNED_5", "UPDATE_CARD", 3);
        drain();
        assertThat(value("receipt_id", "UPDATE_CARD", 2))
                .isEqualTo(value("receipt_id", "SEND_CARD", 2));
        assertThat(value("receipt_id", "SEND_CARD", 6))
                .isNotEqualTo(value("receipt_id", "SEND_CARD", 2));
        assertThat(value("delivery_status", "SEND_CARD", 3)).isEqualTo("CANCELLED");
        assertThat(value("delivery_status", "UPDATE_CARD", 3)).isEqualTo("CANCELLED");
        assertThat(calls).extracting(Delivery::memberId).containsExactly(2L, 2L, 6L);
    }

    @Test
    void crashedWorkerOutsideThePlatformWindowRequiresReconciliationWithoutBlindResend() {
        jdbc.update("UPDATE policy_review_round SET stage='PURCHASE_REVIEW'");
        add("SUBMITTED", "CREATE_TASK", 4);
        jdbc.update(
                "UPDATE policy_review_delivery SET delivery_status='SENDING',attempts=1,lease_owner='crashed',lease_until=DATE '2001-01-01',payload_json=?",
                "{\"firstAttemptAt\":\"2001-01-01T00:00:00\"}");
        assertThat(worker.processOne()).isTrue();
        assertThat(value("delivery_status", "CREATE_TASK", 4)).isEqualTo("UNKNOWN");
        assertThat(value("next_attempt_at", "CREATE_TASK", 4)).isNull();
        assertThat(worker.processOne()).isFalse();
        verifyNoInteractions(sender);
    }

    @Test
    void crashBeforeGuardRetriesOnlyTheStillActiveAssignment() {
        registerSubmitted();
        // 模拟两条动作均已领取，但进程在写前指纹落库前退出。
        jdbc.update(
                """
                UPDATE policy_review_delivery SET delivery_status='SENDING',attempts=1,
                  lease_owner='crashed',lease_until=DATE '2001-01-01',payload_json=?
                """,
                "{\"requiresWriteGuard\":true,\"firstAttemptAt\":\"2001-01-01T00:00:00\"}");
        jdbc.update("UPDATE policy_review_member SET member_status='REMOVED' WHERE id=3");
        drain();
        assertThat(value("delivery_status", "SEND_CARD", 2)).isEqualTo("SENT");
        assertThat(value("delivery_status", "SEND_CARD", 3)).isEqualTo("CANCELLED");
        assertThat(calls).singleElement().satisfies(d -> assertThat(d.uncertain()).isFalse());
    }

    @Test
    void duplicateCreationChecksTheTimeWindowAgainImmediatelyBeforeHttpWrite() {
        add("SUBMITTED", "SEND_CARD", 2);
        doAnswer(
                        c -> {
                            Delivery d = c.getArgument(0);
                            if (d.uncertain()) {
                                // 模拟领取时尚在窗口内，构造请求期间时间已超过窗口。
                                var expired =
                                        ((com.fasterxml.jackson.databind.node.ObjectNode)
                                                        d.payload())
                                                .deepCopy();
                                expired.put("firstAttemptAt", "2001-01-01T00:00:00");
                                jdbc.update(
                                        "UPDATE policy_review_delivery SET payload_json=? WHERE id=?",
                                        expired.toString(),
                                        d.id());
                            }
                            guard.prepare(d, d.openId(), "same-card");
                            calls.add(d);
                            throw new Failure(Kind.UNKNOWN, "网络响应丢失");
                        })
                .when(sender)
                .send(any());
        drain();
        due();
        drain();
        assertThat(calls).hasSize(1);
        assertThat(value("delivery_status", "SEND_CARD", 2)).isEqualTo("UNKNOWN");
        assertThat(value("next_attempt_at", "SEND_CARD", 2)).isNull();
    }

    @Test
    void expiredLeaseIsReclaimedAndGroupFailureIsIndependentOfTwoPersonalTasks() {
        jdbc.update("UPDATE policy_review_round SET stage='PURCHASE_REVIEW'");
        tx.executeWithoutResult(
                s -> events.submitted("company", 11, 1, false, "直接采购", LocalDateTime.now()));
        var first = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).minusSeconds(95);
        jdbc.update(
                "UPDATE policy_review_delivery SET delivery_status='SENDING',attempts=1,lease_owner='crashed',lease_until=DATE '2001-01-01',payload_json=? WHERE recipient_key=4",
                "{\"stage\":\"PURCHASE_REVIEW\",\"requestFingerprint\":\"same-task\",\"requestRecipient\":\"open-4\",\"firstAttemptAt\":\""
                        + first
                        + "\"}");
        var seen = new HashSet<String>();
        doAnswer(
                        c -> {
                            Delivery d = c.getArgument(0);
                            calls.add(d);
                            if (d.memberId() != 4 && seen.add(d.idempotencyKey()))
                                throw new Failure(Kind.RETRYABLE, "限频");
                            return "recovered-" + d.memberId();
                        })
                .when(sender)
                .send(any());
        drain();
        assertThat(value("delivery_status", "GROUP_MESSAGE", 0)).isEqualTo("RETRY");
        assertThat(value("receipt_id", "CREATE_TASK", 4)).isEqualTo("recovered-4");
        assertThat(value("delivery_status", "CREATE_TASK", 5)).isEqualTo("RETRY");
        due();
        drain();
        assertThat(value("receipt_id", "GROUP_MESSAGE", 0)).isEqualTo("recovered-0");
        assertThat(value("receipt_id", "CREATE_TASK", 5)).isEqualTo("recovered-5");
        assertThat(calls.stream().filter(d -> d.memberId() == 4)).hasSize(1);
    }

    @Test
    void creationAcknowledgedAfterRevocationIsClosedWithItsOwnReceipt() throws Exception {
        add("SUBMITTED", "SEND_CARD", 2);
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(
                        c -> {
                            Delivery d = c.getArgument(0);
                            calls.add(d);
                            if (d.action().equals("SEND_CARD")) {
                                guard.prepare(d, d.openId(), "same-card");
                                started.countDown();
                                if (!finish.await(8, TimeUnit.SECONDS))
                                    throw new AssertionError("模拟发送未释放");
                                return "late-card";
                            }
                            return d.priorReceipt();
                        })
                .when(sender)
                .send(any());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var sending = pool.submit(worker::processOne);
            assertThat(started.await(8, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE policy_review_member SET member_status='REMOVED' WHERE id=2");
            add("REASSIGNED_3", "UPDATE_CARD", 2);
            assertThat(worker.processOne()).isFalse();
            jdbc.update(
                    "UPDATE policy_review_delivery SET lease_until=DATE '2001-01-01' WHERE action_type='SEND_CARD'");
            assertThat(worker.processOne()).isTrue();
            assertThat(value("delivery_status", "SEND_CARD", 2)).isEqualTo("UNKNOWN");
            finish.countDown();
            assertThat(sending.get(8, TimeUnit.SECONDS)).isTrue();
            drain();
            assertThat(calls)
                    .extracting(Delivery::action)
                    .containsExactly("SEND_CARD", "UPDATE_CARD");
            assertThat(value("receipt_id", "UPDATE_CARD", 2)).isEqualTo("late-card");
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void expiredLeaseOwnerDoesNotOverwriteSuccessorAndItsAckRecoversTheSuccessorsTimeout()
            throws Exception {
        add("SUBMITTED", "SEND_CARD", 2);
        var enteredA = new CountDownLatch(1);
        var enteredB = new CountDownLatch(1);
        var finishA = new CountDownLatch(1);
        var finishB = new CountDownLatch(1);
        doAnswer(
                        c -> {
                            Delivery d = c.getArgument(0);
                            guard.prepare(d, d.openId(), "same-request");
                            if (d.uncertain()) {
                                enteredB.countDown();
                                if (!finishB.await(8, TimeUnit.SECONDS))
                                    throw new AssertionError("模拟重试未释放");
                                throw new Failure(Kind.UNKNOWN, "重试响应丢失");
                            }
                            enteredA.countDown();
                            if (!finishA.await(8, TimeUnit.SECONDS))
                                throw new AssertionError("模拟首次发送未释放");
                            return "original-card";
                        })
                .when(sender)
                .send(any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(worker::processOne);
            assertThat(enteredA.await(8, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE policy_review_delivery SET lease_until=DATE '2001-01-01'");
            var next = pool.submit(worker::processOne);
            assertThat(enteredB.await(8, TimeUnit.SECONDS)).isTrue();
            finishA.countDown();
            assertThat(first.get(8, TimeUnit.SECONDS)).isTrue();
            assertThat(value("delivery_status", "SEND_CARD", 2)).isEqualTo("SENDING");
            finishB.countDown();
            assertThat(next.get(8, TimeUnit.SECONDS)).isTrue();
            assertThat(value("receipt_id", "SEND_CARD", 2)).isEqualTo("original-card");
            assertThat(worker.processOne()).isFalse();
        } finally {
            finishA.countDown();
            finishB.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void uncertainRequestCannotBeResentToAChangedRecipientOrWithChangedContent() {
        add("SUBMITTED", "SEND_CARD", 2);
        add("SUBMITTED", "SEND_CARD", 3);
        doAnswer(
                        c -> {
                            Delivery d = c.getArgument(0);
                            String recipient =
                                    d.uncertain() && d.memberId() == 2
                                            ? "other-recipient"
                                            : d.openId();
                            String fingerprint =
                                    d.uncertain() && d.memberId() == 3
                                            ? "different-card"
                                            : "original";
                            guard.prepare(d, recipient, fingerprint);
                            calls.add(d);
                            throw new Failure(Kind.UNKNOWN, "网络响应丢失");
                        })
                .when(sender)
                .send(any());
        drain();
        due();
        drain();
        for (int member : List.of(2, 3)) {
            assertThat(value("delivery_status", "SEND_CARD", member)).isEqualTo("UNKNOWN");
            assertThat(value("next_attempt_at", "SEND_CARD", member)).isNull();
            assertThat(value("payload_json", "SEND_CARD", member))
                    .contains("open-" + member, "original")
                    .doesNotContain("different-card", "other-recipient");
        }
        assertThat(calls).hasSize(2);
    }

    private void registerSubmitted() {
        tx.executeWithoutResult(
                s -> events.submitted("company", 11, 1, true, "确认段", LocalDateTime.now()));
    }

    private void add(String event, String action, int member) {
        workflow.register("company", 11, 1, event, action, member, "{}", LocalDateTime.now());
    }

    private String value(String column, String action, int member) {
        return jdbc.queryForObject(
                "SELECT "
                        + column
                        + " FROM policy_review_delivery WHERE action_type=? AND recipient_key=?",
                String.class,
                action,
                member);
    }

    private void due() {
        jdbc.update(
                "UPDATE policy_review_delivery SET next_attempt_at=DATE '2001-01-01' WHERE next_attempt_at IS NOT NULL");
    }

    private void drain() {
        for (int i = 0; i < 15; i++) if (!worker.processOne()) return;
        throw new AssertionError("投递队列一直未清空");
    }
}
