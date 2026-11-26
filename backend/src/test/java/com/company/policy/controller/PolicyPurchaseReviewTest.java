package com.company.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyReviewRequest;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.PolicyReviewNotifications;
import com.company.policy.service.PolicyReviewService;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyReviewResponse.Review;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        properties = {
            "spring.config.import=",
            "spring.datasource.url=jdbc:h2:mem:purchase_review;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.sql.init.schema-locations=classpath:user-info-schema.sql,classpath:catalog-import-schema.sql",
            "feishu.tenant-key=workflow-tenant"
        })
@AutoConfigureMockMvc
class PolicyPurchaseReviewTest {
    @Autowired com.company.policy.service.AuthorizationService roles;
    private static final String TENANT = "workflow-tenant";
    private static final AtomicLong IDS = new AtomicLong(500);
    private static final String PATH = "/api/admin/policies";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired LoginSessionService sessions;
    @Autowired PolicyReviewService reviews;
    @Autowired TransactionTemplate transactions;
    @MockitoSpyBean PolicyReviewNotifications notifications;
    private Actor owner;
    private Actor buyer;
    private Actor peer;

    private record Actor(long id, MockHttpSession session, CurrentUserResponse account) {}

    @BeforeEach
    void createActors() {
        jdbc.update("MERGE INTO brand(id,name) KEY(id) VALUES(80000,'审核品牌')");
        jdbc.update("MERGE INTO series(id,brand_id,name) KEY(id) VALUES(80000,80000,'测试系列')");
        owner = actor("OPERATIONS");
        buyer = actor("ADMIN", "PURCHASE");
        peer = actor("ADMIN", "PURCHASE");
    }

    @Test
    void noAuditMarkerOrMissingStageCanGrantPurchasePermission() throws Exception {
        long unassigned = policy(0, null),
                incomplete = policy(1, null),
                product = policy(1, "PRODUCT_REVIEW");
        member(product, buyer, "PURCHASE", "ACTIVE");
        for (long blocked : List.of(unassigned, incomplete, product)) {
            assertReadable(buyer, blocked, false);
            for (String choice : List.of("APPROVE", "REJECT"))
                review(buyer, blocked, choice).andExpect(status().isForbidden());
            assertThat(state(blocked)).isEqualTo("PENDING");
            assertThat(decisions(blocked)).isZero();
        }
        verify(notifications, never())
                .decided(
                        eq(TENANT),
                        anyLong(),
                        anyInt(),
                        anyString(),
                        anyLong(),
                        anyString(),
                        any(),
                        any());
    }

    @Test
    void buyersOnlySeeAndDecideTheirOwnBrandRounds() throws Exception {
        var oppoBuyer = actor("PURCHASE");
        var onePlusBuyer = actor("PURCHASE");
        long oppo = policy(1, "PURCHASE_REVIEW");
        long onePlus = policy(1, "PURCHASE_REVIEW");
        seedScopedSnapshot(oppo, catalogGoods("OPPO手机", "Reno系列", "星河银"), null);
        seedScopedSnapshot(onePlus, catalogGoods("一加手机", "Ace系列", "曜石黑"), null);
        member(oppo, oppoBuyer, "PURCHASE", "ACTIVE");
        member(onePlus, onePlusBuyer, "PURCHASE", "ACTIVE");

        record Assignment(Actor buyer, long own, long other) {}
        var assignments =
                List.of(
                        new Assignment(oppoBuyer, oppo, onePlus),
                        new Assignment(onePlusBuyer, onePlus, oppo));
        for (var assignment : assignments) {
            var purchaser = assignment.buyer();
            long other = assignment.other();
            assertReadable(purchaser, assignment.own(), true);
            mvc.perform(get(PATH).session(purchaser.session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.counts.PENDING").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(Long.toString(assignment.own())));
            mvc.perform(get(PATH + "/" + other).session(purchaser.session()))
                    .andExpect(status().isNotFound());
            mvc.perform(get(PATH + "/" + other + "/audit-logs").session(purchaser.session()))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            get("/api/policy-reviews/" + other)
                                    .session(purchaser.session())
                                    .param("submissionVersion", "1"))
                    .andExpect(status().isNotFound());
            review(purchaser, other, "APPROVE").andExpect(status().isNotFound());
            review(purchaser, other, "REJECT").andExpect(status().isNotFound());
            assertThat(decisions(other)).isZero();
        }
        for (var assignment : assignments) {
            review(assignment.buyer(), assignment.own(), "APPROVE")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
        }
        for (var assignment : assignments) {
            mvc.perform(get(PATH).session(assignment.buyer().session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.counts.APPROVED").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(Long.toString(assignment.own())));
        }
    }

    @Test
    void readAndWriteUseTheCurrentAssignmentAndDistinctActualSigners() throws Exception {
        long id = policy(1, "PURCHASE_REVIEW");
        member(id, buyer, "PURCHASE", "ACTIVE");
        assertReadable(buyer, id, true);
        assertReadable(peer, id, false);
        review(peer, id, "REJECT").andExpect(status().isForbidden());
        member(id, peer, "PURCHASE", "REMOVED");
        assertReadable(peer, id, false);
        review(peer, id, "APPROVE").andExpect(status().isForbidden());
        long productSigner = member(id, buyer, "PRODUCT", "HANDLED");
        jdbc.update(
                "INSERT INTO policy_review_decision (tenant_key,policy_id,version,group_code,member_id,decision) VALUES (?,?,1,'PRODUCT',?,'APPROVE')",
                TENANT,
                id,
                productSigner);
        assertReadable(buyer, id, false);
        review(buyer, id, "REJECT").andExpect(status().isForbidden());
        assertThat(state(id)).isEqualTo("PENDING");
        assertThat(decisions(id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void resubmissionIsVisibleOnlyAfterItsOwnProductApproval(boolean sameBuyer) throws Exception {
        var previous = actor("PURCHASE");
        var successor = sameBuyer ? previous : actor("PURCHASE");
        var product = actor("PRODUCT_OPS");
        long id = policy(1, "PURCHASE_REVIEW");
        member(id, previous, "PURCHASE", "ACTIVE");
        review(previous, id, "REJECT").andExpect(status().isOk());
        jdbc.update(
                "UPDATE policy_apply SET current_version=2,record_version=4,status='PENDING' WHERE id=?",
                id);
        var snapshot =
                (ObjectNode)
                        json.readTree(
                                jdbc.queryForObject(
                                        "SELECT snapshot_data FROM policy_audit_log WHERE policy_id=? AND version=1",
                                        String.class,
                                        id));
        snapshot.put("submissionVersion", 2);
        jdbc.update(
                "INSERT INTO policy_audit_log(policy_id,tenant_key,version,brand_id,series_id,perspective,snapshot_data) VALUES(?,?,2,80000,80000,'RETAIL',?)",
                id,
                TENANT,
                snapshot.toString());
        jdbc.update(
                "INSERT INTO policy_review_round(policy_id,tenant_key,version,settings_version,stage,route_json) VALUES(?,?,2,1,'PRODUCT_REVIEW','{\"requiresProductReview\":true}')",
                id,
                TENANT);
        jdbc.update(
                "INSERT INTO policy_review_member(policy_id,tenant_key,version,group_code,user_id,display_name) VALUES(?,?,2,'PRODUCT',?,'当前初审')",
                id,
                TENANT,
                product.id());
        jdbc.update(
                "INSERT INTO policy_review_member(policy_id,tenant_key,version,group_code,user_id,display_name) VALUES(?,?,2,'PURCHASE',?,'当前采购')",
                id,
                TENANT,
                successor.id());
        mvc.perform(get(PATH).session(previous.session()).param("keyword", "NUM" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].submissionVersion").value(1))
                .andExpect(jsonPath("$.items[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.counts.REJECTED").value(1))
                .andExpect(jsonPath("$.counts.PENDING").doesNotExist())
                .andExpect(jsonPath("$.items[0].recordVersion").doesNotExist())
                .andExpect(jsonPath("$.items[0].reviewable").value(false));
        mvc.perform(get(PATH + "/" + id).session(previous.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.submissionVersion").value(1))
                .andExpect(jsonPath("$.policy.recordVersion").doesNotExist());
        mvc.perform(
                        get("/api/policy-reviews/" + id)
                                .session(previous.session())
                                .param("submissionVersion", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentSubmissionVersion").value(1))
                .andExpect(jsonPath("$.recordVersion").doesNotExist());
        mvc.perform(get(PATH + "/" + id + "/audit-logs").session(previous.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].submissionVersion").value(1));
        long hiddenLog =
                jdbc.queryForObject(
                        "SELECT id FROM policy_audit_log WHERE policy_id=? AND version=2",
                        Long.class,
                        id);
        mvc.perform(get(PATH + "/" + id + "/audit-logs/" + hiddenLog).session(previous.session()))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/policy-reviews/" + id)
                                .session(previous.session())
                                .param("submissionVersion", "2"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get(PATH + "/" + id + "/override-preview")
                                .session(successor.session())
                                .param("recordVersion", "4")
                                .param("submissionVersion", "2"))
                .andExpect(status().isNotFound());
        review(successor, id, "REJECT", 4, 2).andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/" + id).session(buyer.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.reviewStage").value("PRODUCT_REVIEW"));
        reviews.productReview(
                product.account(), id, new PolicyReviewRequest(4, 2, "APPROVE", null));
        mvc.perform(get(PATH + "/" + id).session(successor.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.submissionVersion").value(2))
                .andExpect(jsonPath("$.policy.reviewStage").value("PURCHASE_REVIEW"))
                .andExpect(jsonPath("$.policy.reviewable").value(true));
        mvc.perform(
                        get("/api/policy-reviews/" + id)
                                .session(previous.session())
                                .param("submissionVersion", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentSubmissionVersion").value(sameBuyer ? 2 : 1));
    }

    @Test
    void initialReviewAndItsReturnStayInvisibleToTheAssignedBuyer() throws Exception {
        var purchaser = actor("PURCHASE");
        var initialReviewer = actor("PRODUCT_OPS");
        long id = policy(1, "PRODUCT_REVIEW");
        member(id, initialReviewer, "PRODUCT", "ACTIVE");
        member(id, purchaser, "PURCHASE", "ACTIVE");
        for (String expectedStatus : List.of("PENDING", "REJECTED")) {
            if ("REJECTED".equals(expectedStatus)) product(initialReviewer, id, "REJECT", "请修改");
            mvc.perform(get(PATH).session(purchaser.session()).param("keyword", "NUM" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.counts").isEmpty());
            mvc.perform(get(PATH + "/" + id).session(purchaser.session()))
                    .andExpect(status().isNotFound());
            mvc.perform(get(PATH + "/" + id + "/audit-logs").session(purchaser.session()))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            get("/api/policy-reviews/" + id)
                                    .session(purchaser.session())
                                    .param("submissionVersion", "1"))
                    .andExpect(status().isNotFound());
            mvc.perform(get(PATH + "/" + id).session(buyer.session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.policy.status").value(expectedStatus));
        }
    }

    @Test
    void wrongStageCannotSkipARequiredProductDecision() throws Exception {
        long id = policy(1, "PURCHASE_REVIEW");
        member(id, buyer, "PURCHASE", "ACTIVE");
        jdbc.update(
                "UPDATE policy_review_round SET route_json=? WHERE policy_id=?",
                "{\"requiresProductReview\":true}",
                id);
        assertReadable(buyer, id, false);
        review(buyer, id, "APPROVE").andExpect(status().isForbidden());
        long signer = member(id, peer, "PRODUCT", "HANDLED");
        jdbc.update(
                "UPDATE policy_review_round SET route_json=? WHERE policy_id=?",
                "{\"requiresProductReview\":false}",
                id);
        assertReadable(buyer, id, false);
        review(buyer, id, "REJECT").andExpect(status().isForbidden());
        jdbc.update(
                "INSERT INTO policy_review_decision (tenant_key,policy_id,version,group_code,member_id,decision) VALUES (?,?,1,'PRODUCT',?,'APPROVE')",
                TENANT,
                id,
                signer);
        assertReadable(buyer, id, true);
        review(buyer, id, "APPROVE").andExpect(status().isOk());
        assertThat(state(id)).isEqualTo("APPROVED");
    }

    @Test
    void rechecksAccountAndBothVersionsWithoutConsumingTheNode() throws Exception {
        long id = policy(1, "PURCHASE_REVIEW");
        member(id, buyer, "PURCHASE", "ACTIVE");
        review(buyer, id, "REJECT", 3, 1).andExpect(status().isConflict());
        review(buyer, id, "REJECT", 2, 2).andExpect(status().isConflict());
        com.company.policy.RbacFixture.setRole(jdbc, buyer.id(), "ADMIN");
        assertReadable(buyer, id, false);
        review(buyer, id, "REJECT").andExpect(status().isForbidden());
        jdbc.update("UPDATE user_info SET is_deleted=1 WHERE id=?", buyer.id());
        review(buyer, id, "APPROVE").andExpect(status().isForbidden());
        assertThat(state(id)).isEqualTo("PENDING");
        assertThat(decisions(id)).isZero();
    }

    @Test
    void purchaseCompetitionRecordsOneSignerAndClosesTheGroupInOneTransaction() throws Exception {
        long id = policy(1, "PURCHASE_REVIEW");
        member(id, buyer, "PURCHASE", "ACTIVE");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var results =
                    List.of(buyer, buyer).stream()
                            .map(
                                    actor ->
                                            pool.submit(
                                                    () -> {
                                                        if (!start.await(5, TimeUnit.SECONDS))
                                                            throw new IllegalStateException(
                                                                    "未开始并发审核");
                                                        return review(
                                                                        actor,
                                                                        id,
                                                                        actor.equals(buyer)
                                                                                ? "APPROVE"
                                                                                : "REJECT")
                                                                .andReturn()
                                                                .getResponse()
                                                                .getStatus();
                                                    }))
                            .toList();
            start.countDown();
            assertThat(
                            List.of(
                                    results.get(0).get(15, TimeUnit.SECONDS),
                                    results.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(decisions(id)).isOne();
        long signer =
                jdbc.queryForObject(
                        "SELECT auditor_id FROM policy_audit_log WHERE policy_id=?",
                        Long.class,
                        id);
        assertThat(
                        jdbc.queryForObject(
                                """
            SELECT m.user_id FROM policy_review_decision d JOIN policy_review_member m ON m.id=d.member_id
            WHERE d.policy_id=? AND m.member_status='HANDLED'
            """,
                                Long.class,
                                id))
                .isEqualTo(signer);
        assertThat(
                        jdbc.queryForList(
                                "SELECT member_status FROM policy_review_member WHERE policy_id=? AND group_code='PURCHASE'",
                                String.class,
                                id))
                .containsExactly("HANDLED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT stage FROM policy_review_round WHERE policy_id=?",
                                String.class,
                                id))
                .isEqualTo("FINISHED");
        verify(notifications)
                .decided(
                        eq(TENANT),
                        eq(id),
                        eq(1),
                        eq("PURCHASE"),
                        eq(signer),
                        anyString(),
                        any(),
                        any());
    }

    @Test
    void failureToRegisterCompletionRestoresTheDecisionMembersAndPolicy() throws Exception {
        long id = policy(1, "PURCHASE_REVIEW");
        member(id, buyer, "PURCHASE", "ACTIVE");
        doThrow(new IllegalStateException("模拟登记失败"))
                .when(
                        AopTestUtils.<PolicyReviewNotifications>getUltimateTargetObject(
                                notifications))
                .decided(
                        eq(TENANT),
                        eq(id),
                        eq(1),
                        eq("PURCHASE"),
                        anyLong(),
                        anyString(),
                        any(),
                        any());
        review(buyer, id, "REJECT").andExpect(status().is5xxServerError());
        assertThat(state(id)).isEqualTo("PENDING");
        assertThat(decisions(id)).isZero();
        assertReadable(buyer, id, true);
        jdbc.queryForObject(
                "SELECT id FROM policy_review_member WHERE policy_id=? AND member_status='ACTIVE'",
                Long.class,
                id);
    }

    @Test
    void platformTransferBeforeExtensionApprovalPreservesTheOpenPurchaseNode() throws Exception {
        long source = policy(1, "PURCHASE_REVIEW");
        member(source, buyer, "PURCHASE", "ACTIVE");
        long extension = policy(1, "PURCHASE_REVIEW");
        long goods = catalogGoods("测试品牌", "测试系列", "银色");
        seedScopedSnapshot(source, goods, null);
        jdbc.update(
                "INSERT INTO user_platform_assignment (tenant_key,user_id,perspective,platform_code,member_kind) VALUES (?,?,'RETAIL','JD','SUBMITTER')",
                TENANT,
                owner.id());
        review(buyer, source, "APPROVE").andExpect(status().isOk());
        seedScopedSnapshot(extension, goods, source);
        member(extension, buyer, "PURCHASE", "ACTIVE");
        mvc.perform(
                        get(PATH + "/" + extension + "/override-preview")
                                .param("recordVersion", "2")
                                .param("submissionVersion", "1")
                                .session(buyer.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresResubmission").value(false));
        var replacement = actor("OPERATIONS");
        jdbc.update(
                "UPDATE user_platform_assignment SET user_id=? WHERE tenant_key=? AND perspective='RETAIL' AND platform_code='JD'",
                replacement.id(),
                TENANT);
        review(buyer, extension, "APPROVE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_RECONFIRM_REQUIRED"));
        assertThat(state(extension)).isEqualTo("PENDING");
        assertThat(decisions(extension)).isZero();
        assertReadable(buyer, extension, true);
        verify(notifications, never())
                .decided(
                        eq(TENANT),
                        eq(extension),
                        anyInt(),
                        anyString(),
                        anyLong(),
                        anyString(),
                        any(),
                        any());
    }

    @Test
    void concurrentProductApprovalsTransferOnceAndCreateOnlyQualifiedBuyersTasks()
            throws Exception {
        long id = policy(1, "PRODUCT_REVIEW");
        var operator = actor("PRODUCT_OPS");
        member(id, operator, "PRODUCT", "ACTIVE");
        member(id, peer, "PURCHASE", "ACTIVE");
        raceProduct(id, operator, operator, "APPROVE");
        assertThat(state(id)).isEqualTo("PENDING");
        assertThat(auditStatus(id)).isEqualTo("SUBMITTED");
        assertThat(count(id, "policy_review_decision")).isOne();
        long signer =
                jdbc.queryForObject(
                        "SELECT m.user_id FROM policy_review_decision d JOIN policy_review_member m ON m.id=d.member_id WHERE d.policy_id=?",
                        Long.class,
                        id);
        var notified = taskOwners(id, "PRODUCT_DECIDED");
        assertThat(notified).containsExactly(peer.id());
        assertThat(groupMessages(id, "PRODUCT_DECIDED")).isZero();
        assertThat(closedReasons(id, "UPDATE_CARD")).containsExactly("本人已处理");
        expectProductFailure(operator, id, "REJECT", "晚到", HttpStatus.CONFLICT);
        review(peer, id, "APPROVE", 3, 1).andExpect(status().isOk());
        assertThat(state(id)).isEqualTo("APPROVED");
        assertThat(groupMessages(id, "PURCHASE_DECIDED")).isOne();
        assertThat(taskOwners(id, "PURCHASE_DECIDED")).isEmpty();
        int registered = count(id, "policy_review_delivery");
        transactions.executeWithoutResult(
                tx ->
                        notifications.decided(
                                TENANT,
                                id,
                                1,
                                "PRODUCT",
                                signer,
                                "APPROVE",
                                null,
                                LocalDateTime.now()));
        assertThat(count(id, "policy_review_delivery")).isEqualTo(registered);
        assertThat(taskOwners(id, "PRODUCT_DECIDED")).containsExactlyInAnyOrderElementsOf(notified);
    }

    @Test
    void reviewRejectsCoercedVersionsAndNumericReturnReasonsWithoutConsumingANode()
            throws Exception {
        long id = policy(1, "PURCHASE_REVIEW");
        member(id, buyer, "PURCHASE", "ACTIVE");
        var valid =
                json.createObjectNode()
                        .put("recordVersion", 2)
                        .put("submissionVersion", 1)
                        .put("decision", "REJECT")
                        .put("reviewRemark", "调整依据");
        for (var body :
                List.of(
                        valid.deepCopy().put("recordVersion", 2.9),
                        valid.deepCopy().put("submissionVersion", 1.5),
                        valid.deepCopy().put("recordVersion", "2"),
                        valid.deepCopy().put("reviewRemark", 42))) {
            mvc.perform(
                            post(PATH + "/" + id + "/review")
                                    .session(buyer.session())
                                    .header("Origin", "http://localhost:5173")
                                    .header("X-Policy-Request", "1")
                                    .contentType("application/json")
                                    .content(json.writeValueAsBytes(body)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(auditStatus(id)).isEqualTo("SUBMITTED");
        assertThat(count(id, "policy_review_decision")).isZero();
    }

    @Test
    void invalidReturnOrUnstaffedPurchaseDoesNotConsumeProductDecision() throws Exception {
        long id = policy(1, "PRODUCT_REVIEW");
        var dualReviewer = actor("PRODUCT_OPS", "PURCHASE");
        member(id, dualReviewer, "PRODUCT", "ACTIVE");
        member(id, dualReviewer, "PURCHASE", "ACTIVE");
        expectProductFailure(owner, id, "REJECT", "本人", HttpStatus.FORBIDDEN);
        expectProductFailure(peer, id, "REJECT", "未指派", HttpStatus.FORBIDDEN);
        expectProductFailure(dualReviewer, id, "REJECT", " \u3000\u00a0 ", HttpStatus.BAD_REQUEST);
        expectProductFailure(dualReviewer, id, "REJECT", "长".repeat(513), HttpStatus.BAD_REQUEST);
        assertThat(
                        expectProductFailure(dualReviewer, id, "APPROVE", null, HttpStatus.CONFLICT)
                                .getCode())
                .isEqualTo("POLICY_REVIEW_ASSIGNEES_MISSING");
        assertThat(count(id, "policy_review_decision")).isZero();
        assertThat(count(id, "policy_review_delivery")).isZero();

        var reason = "退".repeat(512);
        var rejected = product(dualReviewer, id, "REJECT", "  " + reason + "  ");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.recordVersion()).isEqualTo(3);
        expectProductFailure(dualReviewer, id, "APPROVE", null, HttpStatus.CONFLICT);
        assertThat(auditStatus(id)).isEqualTo("REJECTED");
        assertThat(groupMessages(id, "PRODUCT_DECIDED")).isZero();
        assertThat(taskOwners(id, "PRODUCT_DECIDED")).containsExactly(owner.id());
        assertThat(closedReasons(id, "UPDATE_CARD")).containsExactly("本人已处理");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload_json FROM policy_review_delivery WHERE policy_id=? AND action_type='CREATE_TASK'",
                                String.class,
                                id))
                .contains(reason);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT audit_comment FROM policy_audit_log WHERE policy_id=?",
                                String.class,
                                id))
                .isEqualTo(reason);
    }

    @Test
    void concurrentProductApprovalAndReturnCommitOnlyTheWinningNotifications() throws Exception {
        long id = policy(1, "PRODUCT_REVIEW");
        var first = actor("PRODUCT_OPS");
        var second = actor("OPERATIONS");
        member(id, first, "PRODUCT", "ACTIVE");
        member(id, buyer, "PURCHASE", "ACTIVE");
        raceProduct(id, first, first, "REJECT");
        assertThat(count(id, "policy_review_decision")).isOne();
        assertThat(groupMessages(id, "PRODUCT_DECIDED")).isZero();
        if ("PENDING".equals(state(id)))
            assertThat(taskOwners(id, "PRODUCT_DECIDED")).containsExactly(buyer.id());
        else {
            assertThat(state(id)).isEqualTo("REJECTED");
            assertThat(taskOwners(id, "PRODUCT_DECIDED")).containsExactly(owner.id());
        }
    }

    @Test
    void purchaseReturnWithoutCommentCreatesOneApplicantRevisionAndClosesBothTasks()
            throws Exception {
        long id = policy(1, "PURCHASE_REVIEW");
        member(id, buyer, "PURCHASE", "ACTIVE");
        review(buyer, id, "REJECT").andExpect(status().isOk());
        review(peer, id, "APPROVE").andExpect(status().isConflict());
        assertThat(auditStatus(id)).isEqualTo("REJECTED");
        assertThat(taskOwners(id, "PURCHASE_DECIDED")).containsExactly(owner.id());
        assertThat(groupMessages(id, "PURCHASE_DECIDED")).isZero();
        assertThat(closedReasons(id, "CLOSE_TASK")).containsExactly("本人已处理");
    }

    private void raceProduct(long id, Actor first, Actor second, String secondChoice)
            throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var results =
                    List.of(first, second).stream()
                            .map(
                                    actor ->
                                            pool.submit(
                                                    () -> {
                                                        if (!start.await(5, TimeUnit.SECONDS))
                                                            throw new IllegalStateException(
                                                                    "未开始并发初审");
                                                        try {
                                                            product(
                                                                    actor,
                                                                    id,
                                                                    actor.equals(first)
                                                                            ? "APPROVE"
                                                                            : secondChoice,
                                                                    "核对结果");
                                                            return HttpStatus.OK;
                                                        } catch (ApiException conflict) {
                                                            return conflict.getStatus();
                                                        }
                                                    }))
                            .toList();
            start.countDown();
            assertThat(
                            List.of(
                                    results.get(0).get(15, TimeUnit.SECONDS),
                                    results.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
    }

    private Review product(Actor actor, long id, String decision, String reason) {
        return reviews.productReview(
                actor.account(), id, new PolicyReviewRequest(2, 1, decision, reason));
    }

    private ApiException expectProductFailure(
            Actor actor, long id, String decision, String reason, HttpStatus status) {
        var error =
                catchThrowableOfType(
                        ApiException.class, () -> product(actor, id, decision, reason));
        assertThat(error).isNotNull();
        assertThat(error.getStatus()).isEqualTo(status);
        return error;
    }

    private int count(long id, String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE policy_id=?", Integer.class, id);
    }

    private String auditStatus(long id) {
        return jdbc.queryForObject(
                "SELECT audit_status FROM policy_audit_log WHERE policy_id=?", String.class, id);
    }

    private List<Long> taskOwners(long id, String event) {
        return jdbc.queryForList(
                "SELECT m.user_id FROM policy_review_delivery d JOIN policy_review_member m ON m.id=d.recipient_key WHERE d.policy_id=? AND d.event_key=? AND d.action_type='CREATE_TASK'",
                Long.class,
                id,
                event);
    }

    private int groupMessages(long id, String event) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_review_delivery WHERE policy_id=? AND event_key=? AND action_type='GROUP_MESSAGE'",
                Integer.class,
                id,
                event);
    }

    private List<String> closedReasons(long id, String action) {
        return jdbc.queryForList(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.reason')) FROM policy_review_delivery WHERE policy_id=? AND action_type=?",
                String.class,
                id,
                action);
    }

    private long catalogGoods(String brand, String series, String color) {
        long goods = IDS.incrementAndGet();
        jdbc.update("INSERT INTO brand (id,name) VALUES (?,?)", goods, brand);
        jdbc.update("INSERT INTO series (id,brand_id,name) VALUES (?,?,?)", goods, goods, series);
        jdbc.update(
                "INSERT INTO spu (id,brand_id,series_id,name) VALUES (?,?,?,'测试机型')",
                goods,
                goods,
                goods);
        jdbc.update("INSERT INTO color_dict (id,color_name) VALUES (?,?)", goods, color);
        jdbc.update(
                "INSERT INTO goods (id,brand_id,series_id,spu_id,color_id,goods_name,ram,rom,status) VALUES (?,?,?,?,?,'测试机','12GB','256GB',1)",
                goods,
                goods,
                goods,
                goods,
                goods);
        return goods;
    }

    private void seedScopedSnapshot(long id, long goods, Long source) throws Exception {
        var snapshot =
                (ObjectNode)
                        json.readTree(
                                jdbc.queryForObject(
                                        "SELECT snapshot_data FROM policy_audit_log WHERE policy_id=?",
                                        String.class,
                                        id));
        snapshot.putArray("goods")
                .addObject()
                .put("brandId", Long.toString(goods))
                .put("seriesId", Long.toString(goods))
                .put("goodsId", Long.toString(goods));
        jdbc.update("UPDATE policy_apply SET brand_id=?,series_id=? WHERE id=?", goods, goods, id);
        jdbc.update(
                "UPDATE policy_audit_log SET brand_id=?,series_id=? WHERE policy_id=?",
                goods,
                goods,
                id);
        jdbc.update(
                "INSERT INTO policy_round_platform(policy_id,version,tenant_key,perspective,platform_code) VALUES (?,1,?,'RETAIL','JD')",
                id,
                TENANT);
        snapshot.put("policyCode", "ZC-%06d".formatted(id));
        var form = (ObjectNode) snapshot.path("form");
        var goodsForm = (ObjectNode) form.path("goodsForm");
        var card =
                goodsForm
                        .putArray("cards")
                        .addObject()
                        .put("key", "common")
                        .put("cardType", "COMMON")
                        .put("followsOrderPlatforms", true);
        card.putArray("targets")
                .addObject()
                .put("goodsId", Long.toString(goods))
                .put("spuId", Long.toString(goods))
                .put("ram", "12GB")
                .put("rom", "256GB");
        card.putArray("subsidyPlans")
                .addObject()
                .put("key", "plan")
                .put("subsidyMode", "FIXED")
                .put("fixedAmount", "10.00");
        card.putArray("platformAssignments")
                .addObject()
                .put("platformCode", "JD")
                .put("planKey", "plan");
        if (source != null) {
            form.put("startTime", "2099-05-01T00:00:00+08:00")
                    .put("endTime", "2099-05-03T15:30:31+08:00");
            var adjustment =
                    ((ObjectNode) snapshot.get("consent"))
                            .put("ruleVersion", 4)
                            .putArray("endAdjustments")
                            .addObject()
                            .put("endTime", "2099-04-08T15:30:31+08:00")
                            .put("reason", "延长活动");
            adjustment
                    .putObject("target")
                    .put("policyId", source.toString())
                    .put("recordVersion", 3)
                    .put(
                            "auditLogId",
                            jdbc.queryForObject(
                                    "SELECT id FROM policy_audit_log WHERE policy_id=?",
                                    String.class,
                                    source))
                    .put("previousEnd", "2099-04-05T23:59:59+08:00");
        }
        jdbc.update(
                "UPDATE policy_apply SET platforms=?,start_time=?,end_time=? WHERE id=?",
                "[\"JD\"]",
                java.time.OffsetDateTime.parse(form.get("startTime").asText()).toLocalDateTime(),
                java.time.OffsetDateTime.parse(form.get("endTime").asText()).toLocalDateTime(),
                id);
        jdbc.update(
                "UPDATE policy_audit_log SET snapshot_data=? WHERE policy_id=?",
                snapshot.toString(),
                id);
        jdbc.update(
                "INSERT INTO policy_goods_detail (brand_id,series_id,policy_id,version,platform_code,spu_id,ram,rom,goods_id) VALUES (?,?,?,1,'JD',?,'12GB','256GB',?)",
                goods,
                goods,
                id,
                goods,
                goods);
    }

    private void assertReadable(Actor actor, long id, boolean expected) throws Exception {
        mvc.perform(get(PATH + "/" + id).session(actor.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.reviewable").value(expected));
        mvc.perform(
                        get(PATH)
                                .param("keyword", String.format("ZC-%06d", id))
                                .session(actor.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reviewable").value(expected));
    }

    private ResultActions review(Actor actor, long id, String choice) throws Exception {
        return review(actor, id, choice, 2, 1);
    }

    private ResultActions review(Actor actor, long id, String choice, int record, int round)
            throws Exception {
        return mvc.perform(
                post(PATH + "/" + id + "/review")
                        .session(actor.session())
                        .header("Origin", "http://localhost:5173")
                        .header("X-Policy-Request", "1")
                        .contentType("application/json")
                        .content(
                                "{\"decision\":\"%s\",\"recordVersion\":%d,\"submissionVersion\":%d}"
                                        .formatted(choice, record, round)));
    }

    private Actor actor(String... roleCodes) {
        long id = IDS.incrementAndGet();
        var openId = "purchase-" + id;
        com.company.policy.ReviewAccounts.create(
                jdbc, roles, id, TENANT, openId, openId, roleCodes);
        var user =
                new CurrentUserResponse(
                        Long.toString(id),
                        openId,
                        TENANT,
                        openId,
                        java.util.List.of(roleCodes),
                        java.util.List.of(),
                        null);
        var request = new MockHttpServletRequest();
        sessions.establish(request, new AuthSessionResponse(user, Instant.now().plusSeconds(600)));
        return new Actor(id, (MockHttpSession) request.getSession(), user);
    }

    private long policy(int marker, String stage) {
        long id = IDS.incrementAndGet();
        jdbc.update(
                """
            INSERT INTO policy_apply (brand_id,series_id,id, tenant_key,policy_no,applicant_id,applicant_name,channel_type,status,current_version,record_version)
            VALUES (80000,80000,?,?,?,?,'申请人','RETAIL','PENDING',1,2)
            """,
                id,
                TENANT,
                "NUM" + id,
                owner.id());
        var snapshot =
                """
            {"id":"%d","applicantId":"%d","applicantName":"申请人","submissionVersion":1,
             "form":{"perspective":"RETAIL","selectedPlatforms":["JD"],"verifyType":"SALES","sceneTypes":["LOCAL_SUB"],
                "startTime":"2099-04-01T00:00:00+08:00","endTime":"2099-04-05T23:59:59+08:00","goodsForm":{"cards":[]}},
             "goods":[{"brandId":"80000","seriesId":"80000"}],"consent":{"ruleVersion":2,"comparedRounds":[],"decisions":[]}}
            """
                        .formatted(id, owner.id());
        jdbc.update(
                "INSERT INTO policy_audit_log (brand_id,series_id,perspective,tenant_key,policy_id,version,snapshot_data) VALUES (80000,80000,'RETAIL',?,?,1,?)",
                TENANT,
                id,
                snapshot);
        if (stage != null)
            jdbc.update(
                    "INSERT INTO policy_review_round (tenant_key,policy_id,version,settings_version,stage,route_json) VALUES (?,?,1,1,?,?)",
                    TENANT,
                    id,
                    stage,
                    "{\"requiresProductReview\":" + "PRODUCT_REVIEW".equals(stage) + "}");
        return id;
    }

    private long member(long policy, Actor actor, String group, String state) {
        long id = IDS.incrementAndGet();
        jdbc.update(
                "INSERT INTO policy_review_member (id,tenant_key,policy_id,version,group_code,user_id,display_name,member_status) VALUES (?,?,?,1,?,?,'审核人',?)",
                id,
                TENANT,
                policy,
                group,
                actor.id(),
                state);
        return id;
    }

    private String state(long policy) {
        return jdbc.queryForObject(
                "SELECT status FROM policy_apply WHERE id=?", String.class, policy);
    }

    private int decisions(long policy) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy_review_decision WHERE policy_id=? AND group_code='PURCHASE'",
                Integer.class,
                policy);
    }
}
