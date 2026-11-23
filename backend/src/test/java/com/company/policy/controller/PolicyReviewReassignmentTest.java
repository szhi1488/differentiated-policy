package com.company.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.service.LoginSessionService;
import com.company.policy.service.PolicyReviewNotifications;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        properties = {
            "spring.config.import=",
            "spring.datasource.url=jdbc:h2:mem:review_reassign;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.sql.init.schema-locations=classpath:user-info-schema.sql,classpath:catalog-import-schema.sql",
            "feishu.tenant-key=reassign"
        })
@AutoConfigureMockMvc
class PolicyReviewReassignmentTest {
    private static final String PRODUCT = "PRODUCT_REVIEW";
    private static final String PURCHASE = "PURCHASE_REVIEW";
    private static final String TENANT = "reassign";
    private static final AtomicLong IDS = new AtomicLong(2000);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoginSessionService sessions;
    @Autowired ObjectMapper json;
    @Autowired PolicyReviewNotifications notifications;
    @Autowired TransactionTemplate transactions;

    private record Actor(long id, MockHttpSession session) {}

    private Actor owner, operator, first, keep, next;
    private long id;

    @BeforeEach
    void prepare() {
        owner = actor("APPLICANT", TENANT);
        operator = actor("ADMIN", TENANT);
        first = actor("ADMIN", TENANT);
        keep = actor("ADMIN", TENANT);
        next = actor("ADMIN", TENANT);
        id = IDS.incrementAndGet();
    }

    @Test
    void retainedMemberKeepsTaskAndReadmissionGetsNewAssignment() throws Exception {
        round(PURCHASE);
        long removedId = member(first, "PURCHASE");
        long retainedId = member(keep, "PURCHASE");
        start(false);
        jdbc.update(
                "UPDATE policy_review_member SET task_guid='retained-guid' WHERE id=?", retainedId);
        change(operator, PURCHASE, PURCHASE, 2, 1, "分工调整", keep, next, next)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(3));
        assertThat(active("PURCHASE")).containsExactlyInAnyOrder(keep.id(), next.id());
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM policy_review_delivery WHERE recipient_key=?",
                                retainedId))
                .isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT task_guid FROM policy_review_member WHERE id=?",
                                String.class,
                                retainedId))
                .isEqualTo("retained-guid");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM policy_review_delivery WHERE recipient_key=? AND action_type='CLOSE_TASK'",
                                removedId))
                .isOne();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM policy_review_delivery WHERE policy_id=? AND event_key='REASSIGNED_3'",
                                id))
                .isEqualTo(3);
        String payload =
                jdbc.queryForObject(
                        "SELECT payload_json FROM policy_review_delivery WHERE policy_id=? AND event_key='REASSIGNED_3' AND recipient_key=0",
                        String.class,
                        id);
        assertThat(json.readTree(payload).path("removedUserIds").get(0).asLong())
                .isEqualTo(first.id());
        assertThat(json.readTree(payload).path("addedUserIds").get(0).asLong())
                .isEqualTo(next.id());
        var audit =
                jdbc.queryForMap(
                        "SELECT operator_id,reason,before_members,after_members FROM policy_review_reassignment WHERE policy_id=?",
                        id);
        assertThat(audit.get("operator_id")).isEqualTo(operator.id());
        assertThat(audit.get("reason")).isEqualTo("分工调整");
        assertThat(
                        json.readTree((String) audit.get("before_members"))
                                .get(0)
                                .path("memberId")
                                .asLong())
                .isEqualTo(removedId);
        assertThat(json.readTree((String) audit.get("after_members")).size()).isEqualTo(2);
        decide(first, 3).andExpect(status().isForbidden());
        change(operator, PURCHASE, PURCHASE, 3, 1, "仅增加", keep, next, first)
                .andExpect(status().isOk());
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM policy_review_member WHERE policy_id=? AND user_id=? AND assignment_no=2 AND member_status='ACTIVE' AND id<>?",
                                id,
                                first.id(),
                                removedId))
                .isOne();
        change(operator, PURCHASE, PURCHASE, 4, 1, "仅减少", keep, first).andExpect(status().isOk());
        change(operator, PURCHASE, PURCHASE, 5, 1, "名单相同", keep, first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(5));
        assertThat(count("SELECT COUNT(*) FROM policy_review_reassignment WHERE policy_id=?", id))
                .isEqualTo(3);
    }

    @Test
    void futureBuyersAreInformedUntilProductDecisionCreatesTheirTasks() throws Exception {
        round(PRODUCT);
        member(operator, "PRODUCT");
        member(first, "PURCHASE");
        member(keep, "PURCHASE");
        start(true);
        change(operator, PRODUCT, PURCHASE, 2, 1, "采购调整", keep, next).andExpect(status().isOk());
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM policy_review_delivery WHERE policy_id=? AND event_key='REASSIGNED_3'",
                                id))
                .isOne();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM policy_review_delivery WHERE policy_id=? AND action_type='CREATE_TASK'",
                                id))
                .isOne();
        send(
                        operator,
                        "/api/policy-reviews/" + id + "/product-review",
                        Map.of("decision", "APPROVE", "recordVersion", 3, "submissionVersion", 1))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForList(
                                """
            SELECT m.user_id FROM policy_review_delivery d JOIN policy_review_member m ON m.id=d.recipient_key
            WHERE d.policy_id=? AND d.action_type='CREATE_TASK' AND m.group_code='PURCHASE'
            """,
                                Long.class,
                                id))
                .containsExactlyInAnyOrder(keep.id(), next.id());
        change(operator, PURCHASE, PURCHASE, 4, 1, "不允许重复签署", operator, next)
                .andExpect(status().isBadRequest());
    }

    @Test
    void permissionsVersionsAndBothGroupsRemainIntactOnInvalidRequest() throws Exception {
        round(PRODUCT);
        member(first, "PRODUCT");
        member(keep, "PRODUCT");
        member(next, "PURCHASE");
        start(true);
        change(owner, PRODUCT, PRODUCT, 2, 1, "申请人改派", keep).andExpect(status().isForbidden());
        change(operator, PURCHASE, PURCHASE, 2, 1, "阶段已过期", keep).andExpect(status().isConflict());
        change(operator, PRODUCT, PRODUCT, 3, 1, "版本过期", keep).andExpect(status().isConflict());
        change(operator, PRODUCT, PRODUCT, 2, 2, "轮次过期", keep).andExpect(status().isConflict());
        change(operator, PRODUCT, PRODUCT, 2, 1, "  ", keep).andExpect(status().isBadRequest());
        change(operator, PRODUCT, PRODUCT, 2, 1, "\u00a0\u202f", keep)
                .andExpect(status().isBadRequest());
        change(operator, PRODUCT, PRODUCT, 2, 1, "自审", owner).andExpect(status().isBadRequest());
        change(operator, PRODUCT, PURCHASE, 2, 1, "不能与唯一采购重合", first)
                .andExpect(status().isBadRequest());
        change(operator, PRODUCT, PURCHASE, 2, 1, "采购角色不符", actor("APPLICANT", TENANT))
                .andExpect(status().isBadRequest());
        change(operator, PRODUCT, PRODUCT, 2, 1, "跨企业", actor("ADMIN", "outside"))
                .andExpect(status().isBadRequest());
        var disabled = actor("ADMIN", TENANT);
        jdbc.update("UPDATE user_info SET is_deleted=1 WHERE id=?", disabled.id());
        change(operator, PRODUCT, PRODUCT, 2, 1, "停用", disabled).andExpect(status().isBadRequest());
        assertThat(count("SELECT COUNT(*) FROM policy_review_reassignment WHERE policy_id=?", id))
                .isZero();
        assertThat(count("SELECT record_version FROM policy_apply WHERE id=?", id)).isEqualTo(2);
        assertThat(active("PRODUCT")).containsExactlyInAnyOrder(first.id(), keep.id());
        change(operator, PRODUCT, PRODUCT, 2, 1, "产品运营轮班", keep, operator)
                .andExpect(status().isOk());
        assertThat(active("PRODUCT")).containsExactlyInAnyOrder(keep.id(), operator.id());
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM policy_review_delivery WHERE policy_id=? AND event_key='REASSIGNED_3'",
                                id))
                .isEqualTo(3);
    }

    @Test
    void purchaseDecisionAndReassignmentAtSameVersionOnlyCommitOnce() throws Exception {
        round(PURCHASE);
        member(first, "PURCHASE");
        member(keep, "PURCHASE");
        start(false);
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var move =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return change(operator, PURCHASE, PURCHASE, 2, 1, "轮班", keep, next)
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            var decide =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return decide(first, 2).andReturn().getResponse().getStatus();
                            });
            assertThat(List.of(move.get(15, TimeUnit.SECONDS), decide.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            int audit =
                    count("SELECT COUNT(*) FROM policy_review_reassignment WHERE policy_id=?", id);
            int decisions =
                    count("SELECT COUNT(*) FROM policy_review_decision WHERE policy_id=?", id);
            assertThat(audit + decisions).isOne();
            assertThat(count("SELECT record_version FROM policy_apply WHERE id=?", id))
                    .isEqualTo(3);
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM policy_review_delivery WHERE policy_id=? AND event_key='REASSIGNED_3'",
                                    id))
                    .isEqualTo(3 * audit);
        } finally {
            pool.shutdownNow();
        }
    }

    private void round(String stage) {
        jdbc.update(
                """
            INSERT INTO policy_apply(id,tenant_key,policy_no,applicant_id,applicant_name,channel_type,status,current_version,record_version)
            VALUES(?,?,?,?,'申请人','RETAIL','PENDING',1,2)
            """,
                id,
                TENANT,
                "RA" + id,
                owner.id());
        jdbc.update(
                "INSERT INTO policy_audit_log(tenant_key,policy_id,version,snapshot_data,workflow_version) VALUES(?,?,1,?,1)",
                TENANT,
                id,
                "{\"id\":\""
                        + id
                        + "\",\"form\":{\"perspective\":\"RETAIL\",\"selectedPlatforms\":[\"JD\"]},\"submissionVersion\":1}");
        jdbc.update(
                "INSERT INTO policy_review_round(tenant_key,policy_id,version,settings_version,stage,route_json) VALUES(?,?,1,1,?,?)",
                TENANT,
                id,
                stage,
                "{\"requiresProductReview\":" + PRODUCT.equals(stage) + "}");
    }

    private void start(boolean product) {
        transactions.executeWithoutResult(
                s ->
                        notifications.submitted(
                                TENANT, id, 1, product, "本轮政策审核", LocalDateTime.now()));
    }

    private long member(Actor actor, String group) {
        long member = IDS.incrementAndGet();
        jdbc.update(
                "INSERT INTO policy_review_member(id,tenant_key,policy_id,version,group_code,user_id,display_name) VALUES(?,?,?,1,?,?,'经办人')",
                member,
                TENANT,
                id,
                group,
                actor.id());
        return member;
    }

    private List<Long> active(String group) {
        return jdbc.queryForList(
                "SELECT user_id FROM policy_review_member WHERE policy_id=? AND group_code=? AND member_status='ACTIVE'",
                Long.class,
                id,
                group);
    }

    private Actor actor(String role, String tenant) {
        long user = IDS.incrementAndGet();
        var open = "ou_" + user;
        jdbc.update(
                "INSERT INTO user_info(id,tenant_key,open_id,name,role_type) VALUES(?,?,?,?,?)",
                user,
                tenant,
                open,
                open,
                role);
        var request = new MockHttpServletRequest();
        sessions.establish(
                request,
                new AuthSessionResponse(
                        new CurrentUserResponse(
                                Long.toString(user), open, tenant, open, role, null),
                        Instant.now().plusSeconds(600)));
        return new Actor(user, (MockHttpSession) request.getSession());
    }

    private ResultActions change(
            Actor actor,
            String stage,
            String target,
            int record,
            int version,
            String reason,
            Actor... assigned)
            throws Exception {
        return send(
                actor,
                "/api/admin/policy-reviews/" + id + "/reassign",
                Map.of(
                        "recordVersion",
                        record,
                        "submissionVersion",
                        version,
                        "expectedStage",
                        stage,
                        "targetStage",
                        target,
                        "reason",
                        reason,
                        "assigneeUserIds",
                        Arrays.stream(assigned).map(a -> Long.toString(a.id())).toList()));
    }

    private ResultActions decide(Actor actor, int record) throws Exception {
        return send(
                actor,
                "/api/admin/policies/" + id + "/review",
                Map.of("recordVersion", record, "submissionVersion", 1, "decision", "REJECT"));
    }

    private ResultActions send(Actor actor, String path, Map<String, ?> body) throws Exception {
        return mvc.perform(
                post(path)
                        .session(actor.session())
                        .header("Origin", "http://localhost:5173")
                        .header("X-Policy-Request", "1")
                        .contentType("application/json")
                        .content(json.writeValueAsBytes(body)));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
