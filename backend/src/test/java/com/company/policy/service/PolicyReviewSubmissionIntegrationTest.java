package com.company.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyDraftRequest.Card;
import com.company.policy.dto.PolicyDraftRequest.Choice;
import com.company.policy.dto.PolicyDraftRequest.GoodsForm;
import com.company.policy.dto.PolicyDraftRequest.PlatformAssignment;
import com.company.policy.dto.PolicyDraftRequest.Submission;
import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.dto.PolicyDraftRequest.Target;
import com.company.policy.dto.PolicyReviewRequest;
import com.company.policy.dto.PolicyReviewSettingsUpdate;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        properties = {
            "spring.config.import=",
            "spring.datasource.url=jdbc:h2:mem:review_submission;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.sql.init.schema-locations=classpath:user-info-schema.sql,classpath:catalog-import-schema.sql",
            "feishu.tenant-key=company",
            "feishu.policy-chat-id=",
            "feishu.allowed-origins=http://localhost:5173",
            "policy.review-workflow-enabled=true",
            "jackyun.import-scheduling-enabled=false",
            "jackyun.clean-scheduling-enabled=false"
        })
@AutoConfigureMockMvc
class PolicyReviewSubmissionIntegrationTest {
    private static final CurrentUserResponse OWNER = user(1, "APPLICANT");
    private static final CurrentUserResponse BUYER = user(4, "ADMIN");
    @Autowired PolicyApplyService policies;
    @Autowired PolicyReviewService procurement;
    @Autowired PolicyReviewNotifications notifications;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired LoginSessionService sessions;
    @Autowired MockMvc mvc;

    @BeforeEach
    void fixtures() {
        for (String table :
                List.of(
                        "policy_review_delivery",
                        "policy_review_decision",
                        "policy_review_member",
                        "policy_review_round",
                        "policy_goods_detail",
                        "policy_audit_log",
                        "policy_apply",
                        "policy_review_setting_member",
                        "policy_review_settings",
                        "user_platform_assignment",
                        "user_info",
                        "goods",
                        "color_dict",
                        "spu",
                        "series",
                        "brand")) jdbc.update("DELETE FROM " + table);
        for (int i = 1; i <= 6; i++)
            jdbc.update(
                    "INSERT INTO user_info(id,tenant_key,open_id,name,department,role_type) VALUES (?,'company',?,?,?,?)",
                    i,
                    "open-" + i,
                    "同名",
                    "部门" + i,
                    (i == 4 || i == 5) ? "ADMIN" : "APPLICANT");
        jdbc.update("INSERT INTO user_platform_assignment VALUES ('company','BILLION','PDD',1)");
        jdbc.update("INSERT INTO brand(id,name) VALUES (1,'OPPO')");
        jdbc.update("INSERT INTO series(id,brand_id,name) VALUES (1,1,'Reno')");
        jdbc.update("INSERT INTO spu(id,brand_id,series_id,name) VALUES (1,1,1,'Reno')");
        jdbc.update("INSERT INTO color_dict(id,color_name) VALUES (1,'白色')");
        jdbc.update(
                "INSERT INTO goods(id,brand_id,series_id,spu_id,goods_name,ram,rom,color_id,status) VALUES (1,1,1,1,'Reno 白色','12GB','256GB',1,1)");
    }

    private void configure() {
        jdbc.update(
                "INSERT INTO policy_review_settings(tenant_key,version,updated_by) VALUES ('company',1,4)");
        for (int i = 2; i <= 5; i++)
            jdbc.update(
                    "INSERT INTO policy_review_setting_member VALUES ('company',?,?)",
                    i < 4 ? "PRODUCT" : "PURCHASE",
                    i);
    }

    @Test
    void initialAndReplacementRoundsFreezeCurrentSettingsAndRegisterOnlyCurrentTasks()
            throws Exception {
        configure();
        var original = draft();
        long first = Long.parseLong(original.id());
        assertThat(actions(first, 1)).isEmpty();
        submit(first, direct(original))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"));
        assertThat(actions(first, 1))
                .containsExactly("CREATE_TASK", "CREATE_TASK", "GROUP_MESSAGE");
        assertThat(members(first, 1)).containsExactly(4L, 5L);
        var firstGroup =
                json.readTree(
                        jdbc.queryForObject(
                                "SELECT payload_json FROM policy_review_delivery WHERE policy_id=? AND action_type='GROUP_MESSAGE'",
                                String.class,
                                first));
        assertThat(firstGroup.get("actingUserIds").toString()).isEqualTo("[4,5]");
        assertThat(firstGroup.get("instruction").asText()).isEqualTo("请采购办理终审。");
        assertThat(firstGroup.get("informedUserIds").size()).isZero();
        procurement.review(BUYER, first, new PolicyReviewRequest(2, 1, "APPROVE", null));
        transactions.executeWithoutResult(
                tx ->
                        notifications.submitted(
                                "company", first, 1, false, "办结后重放", LocalDateTime.now()));
        assertThat(actions(first, 1))
                .containsExactly(
                        "CLOSE_TASK",
                        "CLOSE_TASK",
                        "CREATE_TASK",
                        "CREATE_TASK",
                        "GROUP_MESSAGE",
                        "GROUP_MESSAGE");
        var extra = draft();
        long id = Long.parseLong(extra.id());
        submit(id, stack(id, extra.recordVersion()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PRODUCT_REVIEW"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT stage FROM policy_review_round WHERE policy_id=?",
                                String.class,
                                id))
                .isEqualTo("PRODUCT_REVIEW");
        assertThat(members(id, 1)).containsExactly(2L, 3L, 4L, 5L);
        assertThat(actions(id, 1)).containsExactly("CREATE_TASK", "CREATE_TASK", "GROUP_MESSAGE");
        assertThat(
                        jdbc.queryForList(
                                "SELECT m.user_id FROM policy_review_delivery d JOIN policy_review_member m ON d.recipient_key=m.id WHERE d.policy_id=? ORDER BY m.user_id",
                                Long.class,
                                id))
                .containsExactly(2L, 3L);
        var payload =
                json.readTree(
                        jdbc.queryForObject(
                                "SELECT payload_json FROM policy_review_delivery WHERE policy_id=? AND action_type='GROUP_MESSAGE'",
                                String.class,
                                id));
        assertThat(payload.get("actingUserIds").toString()).isEqualTo("[2,3]");
        assertThat(payload.get("informedUserIds").toString()).isEqualTo("[4,5]");
        assertThat(payload.get("instruction").asText()).isEqualTo("请产品运营办理初审，采购请知悉。");
        assertThat(payload.get("summary").asText()).contains("叠加");
        transactions.executeWithoutResult(
                tx -> notifications.submitted("company", id, 1, true, "重放", LocalDateTime.now()));
        assertThat(actions(id, 1)).hasSize(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload_json FROM policy_review_delivery WHERE policy_id=? AND action_type='GROUP_MESSAGE'",
                                String.class,
                                id))
                .doesNotContain("重放");
        jdbc.update("UPDATE policy_review_settings SET version=2 WHERE tenant_key='company'");
        jdbc.update("DELETE FROM policy_review_setting_member WHERE user_id=2");
        jdbc.update("INSERT INTO policy_review_setting_member VALUES ('company','PRODUCT',6)");
        assertThat(members(id, 1)).containsExactly(2L, 3L, 4L, 5L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT settings_version FROM policy_review_round WHERE policy_id=?",
                                Integer.class,
                                id))
                .isOne();
        assertThatThrownBy(
                        () ->
                                procurement.review(
                                        BUYER, id, new PolicyReviewRequest(2, 1, "REJECT", "越级")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectedStackIsResubmittedWithLatestMembersAndApprovedByTwoDistinctSigners()
            throws Exception {
        configureViaApi(0, List.of("2", "3"));
        var base = draftViaApi();
        long baseId = Long.parseLong(base.id());
        submit(baseId, direct(base)).andExpect(status().isOk());
        String purchase = "/api/admin/policies/{id}/review";
        postAs(BUYER, purchase, baseId, new PolicyReviewRequest(2, 1, "APPROVE", null))
                .andExpect(status().isOk());
        var extra = draftViaApi();
        long id = Long.parseLong(extra.id());
        submit(id, stack(id, 1)).andExpect(status().isOk());
        String product = "/api/policy-reviews/{id}/product-review";
        var originalReviewer = user(2, "APPLICANT");
        var firstRound =
                round(originalReviewer, id, 1)
                        .andExpect(jsonPath("$.changeSummary.triggerSegments").value(1))
                        .andExpect(jsonPath("$.changes[0].kind").value("STACK"))
                        .andExpect(jsonPath("$.changes[0].before[0].policyId").value(base.id()))
                        .andExpect(jsonPath("$.changes[0].added[0].policyId").value(extra.id()))
                        .andExpect(
                                jsonPath("$.allowedActions").value(contains("APPROVE", "REJECT")))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        round(BUYER, id, 1).andExpect(jsonPath("$.allowedActions").isEmpty());
        mvc.perform(
                        get("/api/admin/policies")
                                .param("reviewStage", "PRODUCT_REVIEW")
                                .session(session(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.counts.PENDING").value(1))
                .andExpect(jsonPath("$.items[0].reviewStage").value("PRODUCT_REVIEW"))
                .andExpect(jsonPath("$.items[0].reviewable").value(false));
        mvc.perform(get("/api/policies/mine").param("status", "PENDING").session(session(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reviewStage").value("PRODUCT_REVIEW"));
        postAs(originalReviewer, product, id, new PolicyReviewRequest(2, 1, "REJECT", "请复核叠加范围"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(revisionStatus(id)).isEqualTo("ACTIVE");
        mvc.perform(get("/api/policies/{id}/audit-logs", id).session(session(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].submissionVersion").value(1))
                .andExpect(jsonPath("$.items[0].workflowVersion").value(1))
                .andExpect(jsonPath("$.items[0].rejectedAtStage").value("PRODUCT_REVIEW"))
                .andExpect(jsonPath("$.items[0].auditComment").value("请复核叠加范围"));
        configureViaApi(1, List.of("3", "6"));
        submit(id, stack(id, 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyCode").value(extra.policyCode()))
                .andExpect(jsonPath("$.submissionVersion").value(2))
                .andExpect(jsonPath("$.reviewStage").value("PRODUCT_REVIEW"));
        assertThat(revisionStatus(id)).isEqualTo("HANDLED");
        assertThat(members(id, 2)).containsExactly(3L, 4L, 5L, 6L);
        var historical =
                round(originalReviewer, id, 1)
                        .andExpect(jsonPath("$.currentSubmissionVersion").value(2))
                        .andExpect(jsonPath("$.rejectedAtStage").value("PRODUCT_REVIEW"))
                        .andExpect(jsonPath("$.allowedActions").isEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        assertThat(json.readTree(historical).get("changes"))
                .isEqualTo(json.readTree(firstRound).get("changes"));
        postAs(originalReviewer, product, id, new PolicyReviewRequest(2, 1, "APPROVE", null))
                .andExpect(status().isConflict());
        postAs(user(6, "APPLICANT"), product, id, new PolicyReviewRequest(4, 2, "APPROVE", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"))
                .andExpect(jsonPath("$.recordVersion").value(5));
        round(BUYER, id, 2)
                .andExpect(jsonPath("$.allowedActions").value(contains("APPROVE", "REJECT")));
        mvc.perform(
                        get("/api/admin/policies")
                                .param("reviewStage", "PURCHASE_REVIEW")
                                .session(session(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].submissionVersion").value(2))
                .andExpect(jsonPath("$.items[0].productReviewerName").value("同名"))
                .andExpect(jsonPath("$.items[0].productReviewedAt").isString())
                .andExpect(jsonPath("$.items[0].reviewable").value(true));
        mvc.perform(get("/api/policies/{id}", id).session(session(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"));
        postAs(BUYER, purchase, id, new PolicyReviewRequest(5, 2, "APPROVE", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        round(BUYER, id, 2).andExpect(jsonPath("$.allowedActions").isEmpty());
        assertThat(
                        jdbc.queryForList(
                                """
                SELECT m.user_id FROM policy_review_decision d JOIN policy_review_member m ON d.member_id=m.id
                WHERE d.policy_id=? ORDER BY d.version, d.group_code
                """,
                                Long.class,
                                id))
                .containsExactly(2L, 6L, 4L);
        assertThat(
                        jdbc.queryForList(
                                """
                SELECT audit_status FROM policy_audit_log WHERE policy_id=? ORDER BY version
                """,
                                String.class,
                                id))
                .containsExactly("REJECTED", "APPROVED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void selectingOneOfTwoPoliciesRequiresProductReviewEvenWhenKeepingTheOriginal(
            boolean keepOriginal) throws Exception {
        configure();
        var original = draft();
        long originalId = Long.parseLong(original.id());
        submit(originalId, direct(original))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"));
        procurement.review(BUYER, originalId, new PolicyReviewRequest(2, 1, "APPROVE", null));
        var extra = draft();
        long id = Long.parseLong(extra.id());
        var preview = policies.preview(OWNER, id, extra.recordVersion());
        var scope = preview.applicability().scopes().get(0);
        var segment = scope.segments().get(0);
        String selected = keepOriginal ? original.id() : extra.id();
        var choice = new Choice(scope.scope(), segment.through(), "CHOOSE_ONE", selected, null);
        submit(
                        id,
                        new Submission(
                                extra.recordVersion(),
                                preview.confirmationToken(),
                                List.of(choice),
                                List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PRODUCT_REVIEW"));
        assertThat(members(id, 1)).containsExactly(2L, 3L, 4L, 5L);
        round(user(2, "APPLICANT"), id, 1)
                .andExpect(jsonPath("$.changeSummary.requiresProductReview").value(true))
                .andExpect(jsonPath("$.changeSummary.triggerSegments").value(1))
                .andExpect(
                        jsonPath("$.changes[0].kind")
                                .value(keepOriginal ? "UNCHANGED" : "REPLACEMENT"))
                .andExpect(
                        jsonPath("$.changes[0].before[*].policyId").value(contains(original.id())))
                .andExpect(jsonPath("$.changes[0].after[*].policyId").value(contains(selected)))
                .andExpect(
                        jsonPath("$.changes[0].candidates[*].policyId")
                                .value(containsInAnyOrder(original.id(), extra.id())))
                .andExpect(jsonPath("$.allowedActions").value(contains("APPROVE", "REJECT")));
        round(BUYER, id, 1).andExpect(jsonPath("$.allowedActions").isEmpty());
    }

    private Submission stack(long id, int recordVersion) throws Exception {
        var response =
                mvc.perform(
                                get("/api/policies/{id}/submission-preview", id)
                                        .session(session(OWNER))
                                        .param("recordVersion", Integer.toString(recordVersion)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        var preview =
                json.readValue(
                        response.getContentAsByteArray(), PolicyResponse.SubmissionPreview.class);
        var choices =
                preview.applicability().scopes().stream()
                        .flatMap(
                                scope ->
                                        scope.segments().stream()
                                                .map(
                                                        segment ->
                                                                new Choice(
                                                                        scope.scope(),
                                                                        segment.through(),
                                                                        "STACK",
                                                                        null,
                                                                        null)))
                        .toList();
        return new Submission(recordVersion, preview.confirmationToken(), choices, List.of());
    }

    @Test
    void resubmissionReadsNewBuyersAndPreservesFirstRound() {
        configure();
        var draft = draft();
        long id = Long.parseLong(draft.id());
        var pending = policies.submit(OWNER, id, direct(draft));
        procurement.review(
                BUYER, id, new PolicyReviewRequest(pending.recordVersion(), 1, "REJECT", "调整"));
        jdbc.update("UPDATE policy_review_settings SET version=2 WHERE tenant_key='company'");
        jdbc.update("DELETE FROM policy_review_setting_member WHERE user_id=4");
        jdbc.update("UPDATE user_info SET is_deleted=1 WHERE id=5");
        assertThatThrownBy(
                        () ->
                                policies.submit(
                                        OWNER, id, new Submission(3, null, List.of(), List.of())))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex ->
                                assertThat(ex.getCode())
                                        .isEqualTo("POLICY_REVIEW_ASSIGNEES_MISSING"));
        assertThat(revisionStatus(id)).isEqualTo("ACTIVE");
        assertThat(actions(id, 2)).isEmpty();
        jdbc.update("UPDATE user_info SET is_deleted=0 WHERE id=5");
        var again = policies.submit(OWNER, id, new Submission(3, null, List.of(), List.of()));
        assertThat(again.submissionVersion()).isEqualTo(2);
        assertThat(again.policyCode()).isEqualTo(draft.policyCode());
        assertThat(again.reviewStage()).isEqualTo("PURCHASE_REVIEW");
        assertThat(members(id, 1)).containsExactly(4L, 5L);
        assertThat(revisionStatus(id)).isEqualTo("HANDLED");
        assertThat(
                        jdbc.queryForList(
                                """
                        SELECT d.action_type FROM policy_review_delivery d
                        JOIN policy_review_member m ON m.id=d.recipient_key
                        WHERE d.policy_id=? AND d.version=1 AND m.group_code='REVISION' AND m.user_id=1
                        ORDER BY d.id
                        """,
                                String.class,
                                id))
                .containsExactly("CREATE_TASK", "CLOSE_TASK");
        assertThat(members(id, 2)).containsExactly(5L);
        assertThat(actions(id, 2)).containsExactly("CREATE_TASK", "GROUP_MESSAGE");
    }

    private String revisionStatus(long id) {
        return jdbc.queryForObject(
                """
                SELECT member_status FROM policy_review_member
                WHERE policy_id=? AND version=1 AND group_code='REVISION' AND user_id=1
                """,
                String.class,
                id);
    }

    @Test
    void concurrentIdenticalSubmissionsCommitOneRoundAndOneSetOfTasks() throws Exception {
        configure();
        var draft = draft();
        long id = Long.parseLong(draft.id());
        var start = new CountDownLatch(1);
        Callable<Integer> submitOnce =
                () -> {
                    if (!start.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("并发提交未开始");
                    return submit(id, direct(draft)).andReturn().getResponse().getStatus();
                };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(submitOnce);
            var second = pool.submit(submitOnce);
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_review_round WHERE policy_id=?",
                                Integer.class,
                                id))
                .isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_audit_log WHERE policy_id=?",
                                Integer.class,
                                id))
                .isOne();
        assertThat(actions(id, 1)).containsExactly("CREATE_TASK", "CREATE_TASK", "GROUP_MESSAGE");
        assertThat(actions(id, 2)).isEmpty();
    }

    @Test
    void competingBuyersApproveOneBatchAndItsTwoReadOnlyExtensionChildren() throws Exception {
        configure();
        var purchasePath = "/api/admin/policies/{id}/review";
        var approve = new PolicyReviewRequest(2, 1, "APPROVE", null);
        for (String month : List.of("2099-05", "2099-06")) {
            var source = policies.create(OWNER, form(month), null);
            long sourceId = Long.parseLong(source.id());
            submit(sourceId, direct(source)).andExpect(status().isOk());
            postAs(BUYER, purchasePath, sourceId, approve).andExpect(status().isOk());
        }
        var parent = policies.create(OWNER, form("2099-07"), null);
        long id = Long.parseLong(parent.id());
        var options = policies.preview(OWNER, id, 1).applicability().adjustmentOptions();
        assertThat(options).hasSize(2).allMatch(option -> option.canExtend());
        var changes =
                options.stream()
                        .map(
                                option ->
                                        new EndAdjustment(
                                                option.target(),
                                                option.target().previousEnd().substring(0, 7)
                                                        + "-12T15:32:18+08:00",
                                                "随本单延长活动"))
                        .toList();
        var preview =
                policies.previewAdjusted(
                        OWNER, id, new PolicyDraftRequest.AdjustmentPreview(1, changes));
        assertThat(preview.applicability().scopes()).isEmpty();
        submit(id, new Submission(1, preview.confirmationToken(), List.of(), changes))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PRODUCT_REVIEW"));
        round(user(2, "APPLICANT"), id, 1)
                .andExpect(jsonPath("$.changeSummary.triggerSegments").value(0))
                .andExpect(jsonPath("$.changeSummary.requiresProductReview").value(true))
                .andExpect(jsonPath("$.adjustments[*].accompanying").value(contains(true, true)));
        round(BUYER, id, 1).andExpect(jsonPath("$.allowedActions").isEmpty());
        postAs(user(2, "APPLICANT"), "/api/policy-reviews/{id}/product-review", id, approve)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"));

        var purchaseApproval = new PolicyReviewRequest(3, 1, "APPROVE", null);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var attempts =
                    List.of(BUYER, user(5, "ADMIN")).stream()
                            .map(
                                    buyer ->
                                            pool.submit(
                                                    () -> {
                                                        if (!start.await(5, TimeUnit.SECONDS))
                                                            throw new IllegalStateException(
                                                                    "并发终审未开始");
                                                        return postAs(
                                                                        buyer,
                                                                        purchasePath,
                                                                        id,
                                                                        purchaseApproval)
                                                                .andReturn()
                                                                .getResponse()
                                                                .getStatus();
                                                    }))
                            .toList();
            start.countDown();
            assertThat(
                            List.of(
                                    attempts.get(0).get(15, TimeUnit.SECONDS),
                                    attempts.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(
                        jdbc.queryForList(
                                """
                SELECT action_type FROM policy_review_delivery WHERE policy_id=? AND version=1
                AND event_key='PURCHASE_DECIDED' ORDER BY action_type
                """,
                                String.class,
                                id))
                .containsExactly("CLOSE_TASK", "CLOSE_TASK", "GROUP_MESSAGE");
        assertThat(
                        jdbc.queryForList(
                                """
                SELECT decision FROM policy_review_decision WHERE policy_id=? AND version=1 AND group_code='PURCHASE'
                """,
                                String.class,
                                id))
                .containsExactly("APPROVE");
        for (var option : options) {
            var childId =
                    jdbc.queryForObject(
                            """
                    SELECT p.id FROM policy_apply p JOIN policy_audit_log a ON a.policy_id=p.id AND a.version=1
                    WHERE p.extension_source_id=? AND p.status='APPROVED' AND a.workflow_version=0
                    """,
                            Long.class,
                            Long.valueOf(option.target().policyId()));
            assertThat(actions(childId, 1)).isEmpty();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM policy_review_round WHERE policy_id=?",
                                    Integer.class,
                                    childId))
                    .isZero();
            round(OWNER, childId, 1)
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.reviewStage").isEmpty())
                    .andExpect(jsonPath("$.allowedActions").isEmpty())
                    .andExpect(jsonPath("$.snapshot.sourcePolicyCode").value(option.policyCode()));
        }
    }

    @Test
    void removingTheOnlyAccompanyingAdjustmentAllowsDirectProcurement() throws Exception {
        configure();
        var source = policies.create(OWNER, form("2099-05"), null);
        var originalId = Long.parseLong(source.id());
        submit(originalId, direct(source)).andExpect(status().isOk());
        procurement.review(BUYER, originalId, new PolicyReviewRequest(2, 1, "APPROVE", null));
        var parent = policies.create(OWNER, form("2099-07"), null);
        long id = Long.parseLong(parent.id());
        var option = policies.preview(OWNER, id, 1).applicability().adjustmentOptions().get(0);
        var change = new EndAdjustment(option.target(), "2099-05-09T00:00:00+08:00", "缩短旧单");
        policies.previewAdjusted(
                OWNER, id, new PolicyDraftRequest.AdjustmentPreview(1, List.of(change)));
        var cleared =
                policies.previewAdjusted(
                        OWNER, id, new PolicyDraftRequest.AdjustmentPreview(1, List.of()));
        submit(id, new Submission(1, cleared.confirmationToken(), List.of(), List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"));
        round(BUYER, id, 1)
                .andExpect(jsonPath("$.adjustments").isEmpty())
                .andExpect(jsonPath("$.changeSummary.requiresProductReview").value(false));
    }

    private ResultActions submit(long id, Submission submission) throws Exception {
        return postAs(OWNER, "/api/policies/{id}/submit", id, submission);
    }

    private ResultActions postAs(CurrentUserResponse actor, String path, long id, Object body)
            throws Exception {
        return write(actor, post(path, id), body);
    }

    private ResultActions round(CurrentUserResponse actor, long id, int version) throws Exception {
        return mvc.perform(
                        get("/api/policy-reviews/{id}", id)
                                .session(session(actor))
                                .param("submissionVersion", Integer.toString(version)))
                .andExpect(status().isOk());
    }

    private void configureViaApi(int version, List<String> product) throws Exception {
        write(
                        BUYER,
                        put("/api/admin/policy-review-settings"),
                        new PolicyReviewSettingsUpdate(version, product, List.of("4", "5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyChatConfigured").value(false));
    }

    private PolicyResponse draftViaApi() throws Exception {
        var response =
                write(OWNER, post("/api/policies/drafts"), form())
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        return json.readValue(response.getContentAsByteArray(), PolicyResponse.class);
    }

    private MockHttpSession session(CurrentUserResponse actor) {
        var request = new MockHttpServletRequest();
        sessions.establish(request, new AuthSessionResponse(actor, Instant.now().plusSeconds(600)));
        return (MockHttpSession) request.getSession();
    }

    private ResultActions write(
            CurrentUserResponse actor, MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        return mvc.perform(
                request.session(session(actor))
                        .header("Origin", "http://localhost:5173")
                        .header("X-Policy-Request", "1")
                        .contentType("application/json")
                        .content(json.writeValueAsBytes(body)));
    }

    @Test
    void transactionRollbackRemovesTheWholeRoundIncludingEveryNotification() {
        configure();
        var draft = draft();
        var id = Long.parseLong(draft.id());
        transactions.executeWithoutResult(
                tx -> {
                    policies.submit(OWNER, id, direct(draft));
                    assertThat(actions(id, 1)).hasSize(3);
                    tx.setRollbackOnly();
                });
        assertThat(actions(id, 1)).isEmpty();
        assertThat(members(id, 1)).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM policy_apply WHERE id=?", String.class, id))
                .isEqualTo("DRAFT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_audit_log WHERE policy_id=?",
                                Integer.class,
                                id))
                .isZero();
    }

    @Test
    void enabledWorkflowRequiresConfiguredAndEligibleProcurementBeforeSubmissionCommits() {
        var draft = draft();
        var id = Long.parseLong(draft.id());
        assertThatThrownBy(() -> policies.submit(OWNER, id, direct(draft)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("POLICY_REVIEW_SETTINGS_REQUIRED"));
        configure();
        jdbc.update("UPDATE user_info SET is_deleted=1 WHERE id IN (4,5)");
        assertThatThrownBy(() -> policies.submit(OWNER, id, direct(draft)))
                .isInstanceOf(ApiException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM policy_apply WHERE id=?", String.class, id))
                .isEqualTo("DRAFT");
        assertThat(actions(id, 1)).isEmpty();
    }

    private List<String> actions(long id, int version) {
        return jdbc.queryForList(
                "SELECT action_type FROM policy_review_delivery WHERE policy_id=? AND version=? ORDER BY action_type",
                String.class,
                id,
                version);
    }

    private List<Long> members(long id, int version) {
        return jdbc.queryForList(
                "SELECT user_id FROM policy_review_member WHERE policy_id=? AND version=? AND group_code IN ('PRODUCT','PURCHASE') ORDER BY user_id",
                Long.class,
                id,
                version);
    }

    private static Submission direct(PolicyResponse draft) {
        return new Submission(draft.recordVersion(), null, List.of(), List.of());
    }

    private PolicyResponse draft() {
        return policies.create(OWNER, form(), null);
    }

    private PolicyDraftRequest form() {
        return form("2099-05");
    }

    private PolicyDraftRequest form(String month) {
        var card =
                new Card(
                        "c",
                        "COMMON",
                        true,
                        List.of(new Target("1", "12GB", "256GB", "1")),
                        List.of(new SubsidyPlan("p", "FIXED", "20.00", null, null, null)),
                        List.of(new PlatformAssignment("PDD", "p", null)));
        return new PolicyDraftRequest(
                null,
                "BILLION",
                List.of("PDD"),
                null,
                null,
                null,
                null,
                month + "-01T00:00:00+08:00",
                month + "-10T23:59:59+08:00",
                null,
                List.of(),
                new GoodsForm(List.of(card)));
    }

    private static CurrentUserResponse user(int id, String role) {
        return new CurrentUserResponse(
                Integer.toString(id), "open-" + id, "company", "同名", role, null);
    }
}
