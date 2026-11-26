package com.company.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.policy.common.config.FeishuProperties;
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
import com.company.policy.dto.PolicyTimelineQuery;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.mapper.PolicyDeliveryAudienceMapper;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
            "feishu.app-id=review-test-app",
            "feishu.policy-chat-id=",
            "feishu.allowed-origins=http://localhost:5173",
            "jackyun.import-scheduling-enabled=false",
            "jackyun.clean-scheduling-enabled=false"
        })
@AutoConfigureMockMvc
class PolicyReviewSubmissionIntegrationTest {
    @Autowired com.company.policy.service.AuthorizationService roles;
    private static final CurrentUserResponse OWNER = user(1, "OPERATIONS");
    private static final CurrentUserResponse BUYER = user(4, "ADMIN", "PURCHASE");
    @Autowired PolicyApplyService policies;
    @Autowired PolicyReviewService procurement;
    @Autowired PolicyReviewNotifications notifications;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired LoginSessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired PolicyDeliveryWorker deliveries;
    @Autowired PolicyDeliveryAudienceMapper deliveryAudience;
    @Autowired PolicyCardCallback callbacks;
    @Autowired ExecutionNotices execution;
    @Autowired PolicyTimeAdjustments directAdjustments;
    @Autowired com.company.policy.mapper.ExecutionNoticeMapper notices;
    @Autowired FeishuProperties feishu;
    @MockitoBean FeishuWorkflowClient remote;

    @ParameterizedTest
    @CsvSource({
        "APPROVE, HTTP",
        "REJECT, HTTP",
        "APPROVE, LONG_CONNECTION",
        "REJECT, LONG_CONNECTION"
    })
    void decisionsPrivatelyNotifyNextAssigneesAndOnlyFinalApprovalPublishesTheFrozenForm(
            String decision, FeishuProperties.PolicyCallbackMode callbackMode) throws Exception {
        String productSigner =
                callbackMode == FeishuProperties.PolicyCallbackMode.HTTP ? "Becca" : "初审小林";
        jdbc.update("UPDATE user_info SET name=? WHERE id=2", productSigner);
        jdbc.update("UPDATE user_info SET name='运营小周' WHERE id=3");
        configure();
        var original = draft();
        long source = Long.parseLong(original.id());
        submit(source, direct(original)).andExpect(status().isOk());
        procurement.review(BUYER, source, new PolicyReviewRequest(2, 1, "APPROVE", null));
        when(remote.sendGroup(anyString(), anyString(), anyList(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            call.getArgument(4, Runnable.class).run();
                            return "om_" + call.getArgument(3);
                        });
        when(remote.createTask(anyString(), anyString(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            call.getArgument(3, Runnable.class).run();
                            return "task_" + call.getArgument(2);
                        });
        when(remote.sendCard(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            call.getArgument(3, Runnable.class).run();
                            return "om_" + call.getArgument(0);
                        });
        when(remote.updateCard(anyString(), any())).thenAnswer(call -> call.getArgument(0));
        var originalMode = feishu.getPolicyCallbackMode();
        feishu.setPolicyCallbackMode(callbackMode);
        feishu.setPolicyChatId("oc_local_test");
        try {
            drainDeliveries();
            var directAnnouncement = ArgumentCaptor.forClass(String.class);
            verify(remote)
                    .sendGroup(
                            eq("oc_local_test"),
                            directAnnouncement.capture(),
                            anyList(),
                            anyString(),
                            any());
            assertThat(directAnnouncement.getValue())
                    .contains("终审人：")
                    .doesNotContain("运营初审人", "【叠加政策】");
            clearInvocations(remote);
            var change = draft();
            long id = Long.parseLong(change.id());
            submit(id, stack(id, change.recordVersion())).andExpect(status().isOk());
            drainDeliveries();

            var sentCard = ArgumentCaptor.forClass(JsonNode.class);
            verify(remote).sendCard(eq("open-2"), sentCard.capture(), anyString(), any());
            var combinations =
                    sentCard.getValue().findValuesAsText("content").stream()
                            .filter(text -> text.startsWith("拟叠加执行 · "))
                            .map(text -> text.substring("拟叠加执行 · ".length()).split(" ＋ "))
                            .toList();
            assertThat(combinations).hasSize(1);
            assertThat(combinations.get(0))
                    .containsExactlyInAnyOrder(original.policyCode(), change.policyCode());
            assertThat(String.join("\n", sentCard.getValue().findValuesAsText("content")))
                    .containsOnlyOnce("整组配置")
                    .contains(
                            "12GB+256GB", original.policyCode(), change.policyCode(), "固定金额", "20")
                    .doesNotContain("核销类型");
            assertThat(sentCard.getValue().findValuesAsText("default_url")).isEmpty();
            verify(remote, never()).sendCard(eq("open-3"), any(), anyString(), any());
            verify(remote, never())
                    .sendGroup(anyString(), anyString(), anyList(), anyString(), any());

            var actionValue =
                    sentCard.getValue().findValues("value").stream()
                            .filter(value -> decision.equals(value.path("decision").asText()))
                            .findFirst()
                            .orElseThrow();
            var envelope = json.createObjectNode().put("schema", "2.0");
            envelope.putObject("header")
                    .put("tenant_key", "company")
                    .put("app_id", feishu.getAppId())
                    .put("event_type", "card.action.trigger");
            var event = envelope.putObject("event").put("host", "im_message");
            event.putObject("operator").put("open_id", "open-2").put("tenant_key", "company");
            event.putObject("context").put("open_message_id", "om_open-2");
            var action = event.putObject("action").put("tag", "button");
            action.set("value", actionValue);
            if ("REJECT".equals(decision)) action.putObject("form_value").put("reason", "请重核");
            assertThat(
                            json.valueToTree(callbacks.handleLongConnection(envelope))
                                    .at("/toast/type")
                                    .asText())
                    .isEqualTo("success");
            assertThat(
                            json.valueToTree(callbacks.handleLongConnection(envelope))
                                    .at("/toast/type")
                                    .asText())
                    .isEqualTo("info");
            drainDeliveries();
            boolean approved = "APPROVE".equals(decision);

            var receivers = approved ? List.of("open-4") : List.of("open-1");
            verify(remote, never())
                    .sendGroup(anyString(), anyString(), anyList(), anyString(), any());
            for (var receiver : receivers) {
                var notice = ArgumentCaptor.forClass(JsonNode.class);
                verify(remote).sendCard(eq(receiver), notice.capture(), anyString(), any());
                String title = approved ? "待你采购终审" : "产品运营初审退回";
                assertThat(notice.getValue().at("/header/title/content").asText())
                        .isEqualTo(title + " · " + change.policyCode() + " · 第 1 轮");
                String expectedBody =
                        approved
                                ? "请打开飞书工作台里的「差异化政策管理系统」，进入「政策管理」→「差异化政策」，找到 "
                                        + change.policyCode()
                                        + "，点击「审核」，及时完成终审。\n"
                                        + productSigner
                                        + " 已完成产品运营初审。"
                                : "办理人：" + productSigner + "\n退回原因：请重核";
                assertThat(notice.getValue().at("/body/elements/0/text/content").asText())
                        .isEqualTo(expectedBody);
                assertTextOnlyNotice(notice.getValue());
                verify(remote).createTask(eq(receiver), anyString(), anyString(), any());
            }
            verify(remote).updateCard(eq("om_open-2"), any());
            verify(remote, never())
                    .updateCard(
                            eq("om_open-3"),
                            argThat(
                                    card ->
                                            card.toString().contains("同组他人已处理")
                                                    && card.findValuesAsText("default_url")
                                                            .isEmpty()));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM policy_review_delivery WHERE policy_id=? AND (delivery_status<>'SENT' OR receipt_id IS NULL)",
                                    Integer.class,
                                    id))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(task_guid) FROM policy_review_member WHERE policy_id=?",
                                    Integer.class,
                                    id))
                    .isEqualTo(receivers.size());
            if (approved) {
                jdbc.update(
                        "INSERT INTO user_platform_assignment(tenant_key,perspective,platform_code,user_id,member_kind) VALUES ('company','BILLION','PDD',6,'MEMBER')");
                postAs(
                                BUYER,
                                "/api/admin/policies/{id}/review",
                                id,
                                new PolicyReviewRequest(3, 1, "APPROVE", null))
                        .andExpect(status().isOk());
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT status FROM policy_apply WHERE id=?",
                                        String.class,
                                        id))
                        .isEqualTo("APPROVED");
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT stage FROM policy_review_round WHERE policy_id=? AND version=1",
                                        String.class,
                                        id))
                        .isEqualTo("FINISHED");
                var frozen =
                        json.readTree(
                                jdbc.queryForObject(
                                        "SELECT payload_json FROM policy_review_delivery WHERE policy_id=? AND action_type='GROUP_MESSAGE'",
                                        String.class,
                                        id));
                assertThat(frozen.at("/approvedPlatforms/0/platformCode").asText())
                        .isEqualTo("PDD");
                assertThat(frozen.path("informedUserIds").toString()).isEqualTo("[1,6]");
                jdbc.update("DELETE FROM user_platform_assignment WHERE user_id=6");
                drainDeliveries();
                var announcement = ArgumentCaptor.forClass(String.class);
                verify(remote)
                        .sendGroup(
                                eq("oc_local_test"),
                                announcement.capture(),
                                eq(List.of("open-1", "open-6")),
                                anyString(),
                                any());
                assertThat(announcement.getValue())
                        .contains(
                                "采购终审通过",
                                "运营初审人：" + productSigner,
                                "【基础信息】",
                                "百亿渠道",
                                "拼多多",
                                "【政策时间与详情】",
                                "2099-05-01 00:00:00",
                                "【商品及补贴信息】",
                                "12GB+256GB 整组配置",
                                "固定补贴 20.00 元")
                        .doesNotContain(
                                "核销类型", "是否为预估政策", "运营小周", "逐段采用名单", "确认时段", "【其他信息】", "附件及链接");
                String[] sections = announcement.getValue().split("【叠加政策】", -1);
                assertThat(sections).hasSize(2);
                assertThat(sections[0]).contains("【商品及补贴信息】", "固定补贴 20.00 元");
                assertThat(sections[1])
                        .contains(
                                original.policyCode(),
                                change.policyCode(),
                                "拼多多百亿",
                                "2099-05-01 00:00:00",
                                "2099-05-10 23:59:59",
                                "12GB+256GB 整组配置")
                        .containsOnlyOnce("固定金额 20 元/台")
                        .doesNotContain("本次申请未在本段采用");
            }
        } finally {
            feishu.setPolicyCallbackMode(originalMode);
            feishu.setPolicyChatId("");
        }
    }

    @Test
    void directPurchaseAndFinalRejectionAreDeliveredPrivatelyWithNoBusinessGroupConfigured()
            throws Exception {
        configure();
        var form = draft();
        long id = Long.parseLong(form.id());
        when(remote.sendCard(anyString(), any(), anyString(), any()))
                .thenAnswer(call -> "om_" + call.getArgument(2));
        when(remote.createTask(anyString(), anyString(), anyString(), any()))
                .thenAnswer(call -> "task_" + call.getArgument(2));
        when(remote.closeTask(anyString(), anyString(), any()))
                .thenAnswer(call -> call.getArgument(0));
        submit(id, direct(form)).andExpect(status().isOk());
        drainDeliveries();
        verify(remote).sendCard(eq("open-4"), any(), anyString(), any());
        verify(remote, never()).sendCard(eq("open-5"), any(), anyString(), any());
        procurement.review(BUYER, id, new PolicyReviewRequest(2, 1, "REJECT", null));
        drainDeliveries();
        var card = ArgumentCaptor.forClass(JsonNode.class);
        verify(remote).sendCard(eq("open-1"), card.capture(), anyString(), any());
        assertThat(card.getValue().toString()).contains("采购终审退回", "审核人未填写驳回原因", "办理人：同名");
        assertTextOnlyNotice(card.getValue());
        verify(remote).closeTask(startsWith("task_"), anyString(), any());
        verify(remote, never()).sendGroup(anyString(), anyString(), anyList(), anyString(), any());
    }

    private static void assertTextOnlyNotice(JsonNode card) {
        assertThat(card.findValuesAsText("tag")).doesNotContain("button", "input", "form");
        assertThat(card.findValuesAsText("type")).doesNotContain("open_url", "callback");
        assertThat(card.findValuesAsText("default_url")).isEmpty();
    }

    private void drainDeliveries() {
        for (int i = 0; i < 40; i++) if (!deliveries.processOne()) return;
        throw new AssertionError("本轮投递超出样例中的预期项数");
    }

    @Test
    void deliveryRecordsHaveNoAdministratorReadOrWriteEndpoints() throws Exception {
        String path = "/api/admin/policy-review-deliveries";
        mvc.perform(get(path).session(session(BUYER))).andExpect(status().isNotFound());
        mvc.perform(get(path + "/notices").session(session(BUYER)))
                .andExpect(status().isNotFound());
        postAs(BUYER, path + "/{id}/resolve", 1, Map.of()).andExpect(status().isNotFound());
    }

    @BeforeEach
    void fixtures() {
        for (String table :
                List.of(
                        "policy_review_delivery",
                        "policy_review_decision",
                        "policy_review_member",
                        "policy_review_round",
                        "policy_goods_detail",
                        "policy_time_change",
                        "policy_round_platform",
                        "policy_audit_log",
                        "policy_apply",
                        "policy_review_series_owner",
                        "policy_review_brand_owner",
                        "policy_review_settings",
                        "user_platform_assignment",
                        "sys_user_role",
                        "user_info",
                        "goods",
                        "color_dict",
                        "spu",
                        "series",
                        "brand")) jdbc.update("DELETE FROM " + table);
        for (int i = 1; i <= 6; i++) {
            String role =
                    i == 1 || i == 6
                            ? "OPERATIONS"
                            : i == 2 || i == 3 ? "PRODUCT_OPS" : i == 4 ? "ADMIN" : "PURCHASE";
            com.company.policy.ReviewAccounts.create(
                    jdbc, roles, i, "company", "open-" + i, "同名", role);
            jdbc.update("UPDATE user_info SET department=? WHERE id=?", "部门" + i, i);
        }
        com.company.policy.ReviewAccounts.grant(jdbc, 4, "company", "PURCHASE");
        com.company.policy.ReviewAccounts.grant(jdbc, 6, "company", "PRODUCT_OPS");
        jdbc.update(
                "INSERT INTO user_platform_assignment(tenant_key,perspective,platform_code,user_id,member_kind) VALUES ('company','BILLION','PDD',1,'SUBMITTER')");
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
        jdbc.update(
                "INSERT INTO policy_review_brand_owner VALUES ('company',1,4,'BY_SERIES',NULL)");
        jdbc.update("INSERT INTO policy_review_series_owner VALUES ('company',1,1,2)");
    }

    private void prepareTwoPurchaseBrands() throws Exception {
        com.company.policy.ReviewAccounts.create(
                jdbc, roles, 7, "company", "open-7", "OPPO采购", "PURCHASE");
        jdbc.update("UPDATE brand SET name='OPPO手机' WHERE id=1");
        jdbc.update("INSERT INTO brand(id,name) VALUES (2,'一加手机')");
        jdbc.update("INSERT INTO series(id,brand_id,name) VALUES (2,2,'Reno')");
        jdbc.update("INSERT INTO spu(id,brand_id,series_id,name) VALUES (2,2,2,'一加测试机')");
        jdbc.update(
                "INSERT INTO goods(id,brand_id,series_id,spu_id,goods_name,ram,rom,color_id,status) VALUES (2,2,2,2,'一加测试机 白色','12GB','256GB',1,1)");
        configureBuyersForBothBrands(0, "7", "5");
    }

    @Test
    void sameSeriesNameRoutesToEachBrandsBuyerAndKeepsTheSubmittedAssignment() throws Exception {
        prepareTwoPurchaseBrands();
        var oppo = draft();
        long oppoId = Long.parseLong(oppo.id());
        submit(oppoId, direct(oppo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"));
        assertThat(members(oppoId, 1)).containsExactly(7L);

        var onePlusForm = form("2099-05", new Target("2", "12GB", "256GB", "2"));
        var onePlus = policies.create(OWNER, onePlusForm, null);
        long onePlusId = Long.parseLong(onePlus.id());
        submit(onePlusId, direct(onePlus))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"));
        assertThat(members(onePlusId, 1)).containsExactly(5L);
        var originalBuyer = user(5, "PURCHASE");
        procurement.review(
                originalBuyer, onePlusId, new PolicyReviewRequest(2, 1, "APPROVE", null));

        var stacked = policies.create(OWNER, onePlusForm, null);
        long stackedId = Long.parseLong(stacked.id());
        submit(stackedId, stack(stackedId, stacked.recordVersion()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PRODUCT_REVIEW"));
        assertThat(members(stackedId, 1)).containsExactly(3L, 5L);
        configureBuyersForBothBrands(1, "5", "7");

        var transferred =
                procurement.productReview(
                        user(3, "PRODUCT_OPS"),
                        stackedId,
                        new PolicyReviewRequest(2, 1, "APPROVE", null));
        assertThat(transferred.reviewStage()).isEqualTo("PURCHASE_REVIEW");
        assertThat(
                        jdbc.queryForList(
                                """
                        SELECT m.user_id FROM policy_review_delivery d
                        JOIN policy_review_member m ON m.id=d.recipient_key
                        WHERE d.policy_id=? AND d.version=1 AND d.action_type='CREATE_TASK'
                        """,
                                Long.class,
                                stackedId))
                .containsExactly(5L);
        var rejected =
                procurement.review(
                        originalBuyer,
                        stackedId,
                        new PolicyReviewRequest(transferred.recordVersion(), 1, "REJECT", "调整后重提"));
        submit(stackedId, stack(stackedId, rejected.recordVersion()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionVersion").value(2));
        assertThat(members(stackedId, 1)).containsExactly(3L, 5L);
        assertThat(members(stackedId, 2)).containsExactly(3L, 7L);
    }

    @Test
    void subsidyHistoryAndEffectivePoliciesFollowCurrentPurchaseBrand() throws Exception {
        prepareTwoPurchaseBrands();
        var oppoBuyer = user(7, "PURCHASE");
        var onePlusBuyer = user(5, "PURCHASE");
        record BrandCase(
                String modelId, CurrentUserResponse buyer, CurrentUserResponse otherBuyer) {}
        for (var brand :
                List.of(
                        new BrandCase("1", oppoBuyer, onePlusBuyer),
                        new BrandCase("2", onePlusBuyer, oppoBuyer))) {
            var modelId = brand.modelId();
            var draft =
                    policies.create(
                            OWNER,
                            form("2099-05", new Target(modelId, "12GB", "256GB", modelId)),
                            null);
            long id = Long.parseLong(draft.id());
            policies.submit(OWNER, id, direct(draft));
            var buyer = brand.buyer();
            procurement.review(buyer, id, new PolicyReviewRequest(2, 1, "APPROVE", null));
            for (var reader : List.of(BUYER, buyer)) {
                mvc.perform(subsidyTimelineRequest(modelId).session(session(reader)))
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.tracks[0].policies[*].policyId", contains(draft.id())));
                mvc.perform(effectiveSubsidyRequest(modelId, "BILLION").session(session(reader)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.policies[0].winner.id").value(draft.id()));
            }
            var otherBuyer = session(brand.otherBuyer());
            for (var request :
                    List.of(
                            subsidyTimelineRequest(modelId),
                            effectiveSubsidyRequest(modelId, "RETAIL"),
                            effectiveSubsidyRequest(modelId, "BILLION"))) {
                mvc.perform(request.session(otherBuyer))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("POLICY_SUBSIDY_BRAND_FORBIDDEN"));
            }
        }

        com.company.policy.ReviewAccounts.grant(jdbc, 7, "company", "OPERATIONS");
        jdbc.update(
                "INSERT INTO user_platform_assignment(tenant_key,perspective,platform_code,user_id,member_kind) VALUES ('company','BILLION','PDD',7,'MEMBER')");
        var buyerSession = session(oppoBuyer);
        mvc.perform(
                        subsidyTimelineRequest("/api/policies/subsidy-timeline", "2")
                                .session(buyerSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POLICY_SUBSIDY_BRAND_FORBIDDEN"));

        configureBuyersForBothBrands(1, "5", "7");
        mvc.perform(subsidyTimelineRequest("1").session(buyerSession))
                .andExpect(status().isForbidden());
        mvc.perform(subsidyTimelineRequest("2").session(buyerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tracks.length()").value(1));
        write(
                        BUYER,
                        put("/api/admin/policy-review-settings"),
                        new PolicyReviewSettingsUpdate(2, List.of()))
                .andExpect(status().isOk());
        mvc.perform(subsidyTimelineRequest("2").session(buyerSession))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder subsidyTimelineRequest(String model) {
        return subsidyTimelineRequest("/api/admin/policies/subsidy-timeline", model);
    }

    private MockHttpServletRequestBuilder subsidyTimelineRequest(String path, String model) {
        return get(path)
                .param("spuId", model)
                .param("ram", "12GB")
                .param("rom", "256GB")
                .param("dateFrom", "2099-05-01")
                .param("dateTo", "2099-05-10");
    }

    private MockHttpServletRequestBuilder effectiveSubsidyRequest(String goodsId, String channel) {
        return get("/api/admin/policies/effective")
                .param("goodsId", goodsId)
                .param("perspective", channel)
                .param("platformCode", "PDD")
                .param("at", "2099-05-05T12:00:00+08:00");
    }

    private void configureBuyersForBothBrands(int version, String oppoBuyer, String onePlusBuyer)
            throws Exception {
        var oppo =
                new PolicyReviewSettingsUpdate.Brand(
                        "1",
                        "BY_SERIES",
                        oppoBuyer,
                        null,
                        List.of(new PolicyReviewSettingsUpdate.Series("1", "2")));
        var onePlus =
                new PolicyReviewSettingsUpdate.Brand(
                        "2",
                        "BY_SERIES",
                        onePlusBuyer,
                        null,
                        List.of(new PolicyReviewSettingsUpdate.Series("2", "3")));
        write(
                        BUYER,
                        put("/api/admin/policy-review-settings"),
                        new PolicyReviewSettingsUpdate(version, List.of(oppo, onePlus)))
                .andExpect(status().isOk());
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
        assertThat(actions(first, 1)).containsExactly("CREATE_TASK", "SEND_CARD");
        assertThat(members(first, 1)).containsExactly(4L);
        assertThat(
                        jdbc.queryForList(
                                """
                SELECT m.user_id FROM policy_review_delivery d JOIN policy_review_member m ON d.recipient_key=m.id
                WHERE d.policy_id=? AND d.action_type='SEND_CARD' ORDER BY m.user_id
                """,
                                Long.class,
                                first))
                .containsExactly(4L);
        procurement.review(BUYER, first, new PolicyReviewRequest(2, 1, "APPROVE", null));
        transactions.executeWithoutResult(
                tx ->
                        notifications.submitted(
                                "company", first, 1, false, "办结后重放", LocalDateTime.now()));
        assertThat(actions(first, 1))
                .containsExactly("CLOSE_TASK", "CREATE_TASK", "GROUP_MESSAGE", "SEND_CARD");
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
        assertThat(members(id, 1)).containsExactly(2L, 4L);
        assertThat(actions(id, 1)).containsExactly("SEND_CARD");
        assertThat(
                        jdbc.queryForList(
                                "SELECT m.user_id FROM policy_review_delivery d JOIN policy_review_member m ON d.recipient_key=m.id WHERE d.policy_id=? ORDER BY m.user_id",
                                Long.class,
                                id))
                .containsExactly(2L);
        var payload =
                json.readTree(
                        jdbc.queryForObject(
                                "SELECT payload_json FROM policy_review_delivery WHERE policy_id=? AND action_type='SEND_CARD' ORDER BY id LIMIT 1",
                                String.class,
                                id));
        assertThat(payload.get("userId").asLong()).isEqualTo(2);
        assertThat(payload.get("summary").asText()).contains("叠加");
        transactions.executeWithoutResult(
                tx -> notifications.submitted("company", id, 1, true, "重放", LocalDateTime.now()));
        assertThat(actions(id, 1)).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload_json FROM policy_review_delivery WHERE policy_id=? AND action_type='SEND_CARD' ORDER BY id LIMIT 1",
                                String.class,
                                id))
                .doesNotContain("重放");
        jdbc.update("UPDATE policy_review_settings SET version=2 WHERE tenant_key='company'");
        jdbc.update("UPDATE policy_review_series_owner SET product_user_id=6 WHERE series_id=1");
        assertThat(members(id, 1)).containsExactly(2L, 4L);
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
        assertThat(policies.detail(OWNER, id).reviewProgress()).isNull();
        submit(id, stack(id, 1)).andExpect(status().isOk());
        var originalReviewer = user(2, "PRODUCT_OPS");
        mvc.perform(
                        get("/api/policy-reviews/{id}", id)
                                .param("submissionVersion", "1")
                                .session(session(originalReviewer)))
                .andExpect(status().isNotFound());
        var firstRound =
                round(BUYER, id, 1)
                        .andExpect(jsonPath("$.submittedAt").isString())
                        .andExpect(jsonPath("$.applicantName").isString())
                        .andExpect(jsonPath("$.requiresProductReview").value(true))
                        .andExpect(jsonPath("$.changeSummary.triggerSegments").value(1))
                        .andExpect(jsonPath("$.changes[0].kind").value("STACK"))
                        .andExpect(jsonPath("$.changes[0].before[0].policyId").value(base.id()))
                        .andExpect(jsonPath("$.changes[0].added[0].policyId").value(extra.id()))
                        .andExpect(jsonPath("$.allowedActions").isEmpty())
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
        assertThat(
                        procurement
                                .productReview(
                                        originalReviewer,
                                        id,
                                        new PolicyReviewRequest(2, 1, "REJECT", "请复核叠加范围"))
                                .status())
                .isEqualTo("REJECTED");
        assertThat(revisionStatus(id)).isEqualTo("ACTIVE");
        mvc.perform(get("/api/policies/{id}/audit-logs", id).session(session(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].submissionVersion").value(1))
                .andExpect(jsonPath("$.items[0].rejectedAtStage").value("PRODUCT_REVIEW"))
                .andExpect(jsonPath("$.items[0].auditComment").value("请复核叠加范围"));
        configureViaApi(1, List.of("3", "6"));
        submit(id, stack(id, 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyCode").value(extra.policyCode()))
                .andExpect(jsonPath("$.submissionVersion").value(2))
                .andExpect(jsonPath("$.reviewStage").value("PRODUCT_REVIEW"));
        assertThat(revisionStatus(id)).isEqualTo("HANDLED");
        assertThat(members(id, 2)).containsExactly(3L, 4L);
        var logs = policies.auditLogs(OWNER, id, 1, 20).items();
        var firstLog =
                logs.stream().filter(log -> log.submissionVersion() == 1).findFirst().orElseThrow();
        var oldProgress =
                policies.auditDetail(OWNER, id, Long.parseLong(firstLog.id())).reviewProgress();
        assertThat(oldProgress.submissionVersion()).isOne();
        assertThat(oldProgress.submittedAt()).isEqualTo(firstLog.submittedAt());
        assertThat(oldProgress.rejectedAtStage()).isEqualTo("PRODUCT_REVIEW");
        assertThat(oldProgress.productReviewers())
                .singleElement()
                .satisfies(
                        member -> {
                            assertThat(member.userId()).isEqualTo("2");
                            assertThat(member.reason()).isEqualTo("请复核叠加范围");
                            assertThat(member.decidedAt()).isNotBlank();
                        });
        var historical =
                round(originalReviewer, id, 1)
                        .andExpect(jsonPath("$.currentSubmissionVersion").value(1))
                        .andExpect(jsonPath("$.recordVersion").doesNotExist())
                        .andExpect(jsonPath("$.rejectedAtStage").value("PRODUCT_REVIEW"))
                        .andExpect(jsonPath("$.allowedActions").isEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        assertThat(json.readTree(historical).get("changes"))
                .isEqualTo(json.readTree(firstRound).get("changes"));
        assertThatThrownBy(
                        () ->
                                procurement.productReview(
                                        originalReviewer,
                                        id,
                                        new PolicyReviewRequest(2, 1, "APPROVE", null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
        var transferred =
                procurement.productReview(
                        user(3, "PRODUCT_OPS"), id, new PolicyReviewRequest(4, 2, "APPROVE", null));
        assertThat(transferred.status()).isEqualTo("PENDING");
        assertThat(transferred.reviewStage()).isEqualTo("PURCHASE_REVIEW");
        assertThat(transferred.recordVersion()).isEqualTo(5);
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
                .andExpect(jsonPath("$.reviewProgress.submissionVersion").value(2))
                .andExpect(jsonPath("$.reviewProgress.reviewStage").value("PURCHASE_REVIEW"))
                .andExpect(jsonPath("$.reviewProgress.requiresProductReview").value(true))
                .andExpect(jsonPath("$.reviewProgress.productReviewers[0].userId").value("3"))
                .andExpect(
                        jsonPath("$.reviewProgress.productReviewers[0].decision").value("APPROVE"))
                .andExpect(
                        jsonPath("$.reviewProgress.purchaseReviewers[0].memberStatus")
                                .value("ACTIVE"))
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"));
        postAs(BUYER, purchase, id, new PolicyReviewRequest(5, 2, "APPROVE", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        round(BUYER, id, 2).andExpect(jsonPath("$.allowedActions").isEmpty());
        var finalProgress = policies.detail(OWNER, id).reviewProgress();
        assertThat(finalProgress.status()).isEqualTo("APPROVED");
        assertThat(finalProgress.purchaseReviewers())
                .singleElement()
                .satisfies(
                        member -> {
                            assertThat(member.userId()).isEqualTo("4");
                            assertThat(member.decision()).isEqualTo("APPROVE");
                            assertThat(member.decidedAt()).isNotBlank();
                        });
        assertThat(
                        jdbc.queryForList(
                                """
                SELECT m.user_id FROM policy_review_decision d JOIN policy_review_member m ON d.member_id=m.id
                WHERE d.policy_id=? ORDER BY d.version, d.group_code
                """,
                                Long.class,
                                id))
                .containsExactly(2L, 3L, 4L);
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
    void selectingOneOfTwoPoliciesGoesDirectlyToProcurement(boolean keepOriginal) throws Exception {
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
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"));
        assertThat(members(id, 1)).containsExactly(4L);
        assertThat(actions(id, 1)).containsExactly("CREATE_TASK", "SEND_CARD");
        round(BUYER, id, 1)
                .andExpect(jsonPath("$.changeSummary.requiresProductReview").value(false))
                .andExpect(jsonPath("$.changeSummary.triggerSegments").value(0))
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
    }

    @Test
    void approvedDirectResubmissionOmitsThePreviousRoundsProductSigner() throws Exception {
        configure();
        var original = draft();
        long baseId = Long.parseLong(original.id());
        submit(baseId, direct(original)).andExpect(status().isOk());
        procurement.review(BUYER, baseId, new PolicyReviewRequest(2, 1, "APPROVE", null));

        var extra = draft();
        long id = Long.parseLong(extra.id());
        submit(id, stack(id, 1)).andExpect(status().isOk());
        procurement.productReview(
                user(2, "PRODUCT_OPS"), id, new PolicyReviewRequest(2, 1, "APPROVE", null));
        var rejected =
                procurement.review(BUYER, id, new PolicyReviewRequest(3, 1, "REJECT", "调整采用名单"));

        var preview = policies.preview(OWNER, id, rejected.recordVersion());
        var scope = preview.applicability().scopes().get(0);
        var choice =
                new Choice(
                        scope.scope(),
                        scope.segments().get(0).through(),
                        "CHOOSE_ONE",
                        extra.id(),
                        null);
        var direct =
                policies.submit(
                        OWNER,
                        id,
                        new Submission(
                                rejected.recordVersion(),
                                preview.confirmationToken(),
                                List.of(choice),
                                List.of()));
        assertThat(direct.reviewStage()).isEqualTo("PURCHASE_REVIEW");
        procurement.review(
                BUYER, id, new PolicyReviewRequest(direct.recordVersion(), 2, "APPROVE", null));

        var approved = deliveryAudience.approvedRound("company", id, 2);
        assertThat(approved).isNotNull();
        assertThat(approved.productReviewer()).isNull();
    }

    @Test
    void stackingJustTheSharedConfigurationRequiresConfirmationAndOpensOnlyPurchase()
            throws Exception {
        configure();
        jdbc.update("DELETE FROM policy_review_series_owner");
        var common = new Target("1", "12GB", "256GB", "1");
        var onlyOriginal = new Target("1", "12GB", "512GB", "2");
        var onlyNew = new Target("1", "16GB", "512GB", "3");
        for (var target : List.of(onlyOriginal, onlyNew)) {
            jdbc.update(
                    """
                    INSERT INTO goods(id,brand_id,series_id,spu_id,goods_name,ram,rom,color_id,status)
                    VALUES (?,1,1,1,'Reno 白色',?,?,1,1)
                    """,
                    target.goodsId(),
                    target.ram(),
                    target.rom());
        }
        var original = policies.create(OWNER, form("2099-05", common, onlyOriginal), null);
        long originalId = Long.parseLong(original.id());
        submit(originalId, direct(original)).andExpect(status().isOk());
        procurement.review(BUYER, originalId, new PolicyReviewRequest(2, 1, "APPROVE", null));
        var partial = policies.create(OWNER, form("2099-05", onlyNew, common), null);
        long id = Long.parseLong(partial.id());
        submit(id, direct(partial))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_CONFIRMATION_REQUIRED"));
        var confirmed = stack(id, 1);
        assertThat(confirmed.decisions()).hasSize(1);
        submit(id, confirmed)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"));
        assertThat(members(id, 1)).containsExactly(4L);
        assertThat(actions(id, 1)).containsExactly("CREATE_TASK", "SEND_CARD");
        round(BUYER, id, 1)
                .andExpect(jsonPath("$.changeSummary.requiresProductReview").value(false))
                .andExpect(jsonPath("$.changeSummary.triggerSegments").value(0))
                .andExpect(
                        jsonPath("$.snapshot.consent.decisions[0].selectedPolicyIds")
                                .value(containsInAnyOrder(original.id(), partial.id())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fourSuccessiveStackedPoliciesCompareEverySelectedGoodsId(boolean identicalFourth)
            throws Exception {
        configure();
        var originalGood = new Target("1", "12GB", "256GB", "1");
        var extraSku = addSecondSkuWithSameSpecificationsAndColor();
        var adoptedIds = new ArrayList<String>();
        for (int count = 1; count <= 4; count++) {
            var request =
                    count == 4 && !identicalFourth
                            ? specifiedGoods("2099-05-01", "2099-05-10", originalGood, extraSku)
                            : specifiedGoods("2099-05-01", "2099-05-10", originalGood);
            var proposal = policies.create(OWNER, request, null);
            long id = Long.parseLong(proposal.id());
            adoptedIds.add(proposal.id());
            boolean needsReview = count > 1 && (count != 4 || identicalFourth);
            submit(id, count == 1 ? direct(proposal) : stack(id, 1))
                    .andExpect(status().isOk())
                    .andExpect(
                            jsonPath("$.reviewStage")
                                    .value(needsReview ? "PRODUCT_REVIEW" : "PURCHASE_REVIEW"));
            if (count > 1) {
                round(BUYER, id, 1)
                        .andExpect(
                                jsonPath("$.changes[0].after[*].policyId")
                                        .value(
                                                containsInAnyOrder(
                                                        adoptedIds.toArray(String[]::new))))
                        .andExpect(
                                jsonPath("$.changeSummary.requiresProductReview")
                                        .value(needsReview));
            }
            if (needsReview) {
                assertThat(actions(id, 1)).containsExactly("SEND_CARD");
                assertThat(members(id, 1)).containsExactly(2L, 4L);
            } else {
                assertThat(actions(id, 1)).containsExactly("CREATE_TASK", "SEND_CARD");
                assertThat(members(id, 1)).containsExactly(4L);
            }
            if (count < 4) {
                if (needsReview)
                    procurement.productReview(
                            user(2, "PRODUCT_OPS"),
                            id,
                            new PolicyReviewRequest(2, 1, "APPROVE", null));
                procurement.review(
                        BUYER,
                        id,
                        new PolicyReviewRequest(needsReview ? 3 : 2, 1, "APPROVE", null));
            }
        }
    }

    @Test
    void anIdenticalStackInOnePeriodAndDifferentGoodsInAnotherGoDirectlyToProcurement()
            throws Exception {
        configure();
        var x = new Target("1", "12GB", "256GB", "1");
        var y = addSecondSkuWithSameSpecificationsAndColor();
        var early = policies.create(OWNER, specifiedGoods("2099-05-01", "2099-05-05", x), null);
        var later = policies.create(OWNER, specifiedGoods("2099-05-06", "2099-05-10", y), null);
        for (var source : List.of(early, later)) {
            long id = Long.parseLong(source.id());
            submit(id, direct(source)).andExpect(status().isOk());
            procurement.review(BUYER, id, new PolicyReviewRequest(2, 1, "APPROVE", null));
        }
        var next = policies.create(OWNER, specifiedGoods("2099-05-01", "2099-05-10", x), null);
        long id = Long.parseLong(next.id());
        var selected = stack(id, 1);
        assertThat(selected.decisions()).hasSize(2);
        submit(id, selected)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"));
        round(BUYER, id, 1)
                .andExpect(jsonPath("$.changeSummary.triggerSegments").value(0))
                .andExpect(
                        jsonPath("$.changes[0].after[*].policyId")
                                .value(containsInAnyOrder(early.id(), next.id())))
                .andExpect(
                        jsonPath("$.changes[1].after[*].policyId")
                                .value(containsInAnyOrder(later.id(), next.id())));
        assertThat(members(id, 1)).containsExactly(4L);
        assertThat(actions(id, 1)).containsExactly("CREATE_TASK", "SEND_CARD");
    }

    private Target addSecondSkuWithSameSpecificationsAndColor() {
        jdbc.update(
                """
                INSERT INTO goods(id,brand_id,series_id,spu_id,goods_name,ram,rom,color_id,status,sku_id)
                VALUES (2,1,1,1,'Reno 白色另一 SKU','12GB','256GB',1,1,'10002')
                """);
        return new Target("1", "12GB", "256GB", "2");
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
        jdbc.update("UPDATE policy_review_brand_owner SET purchase_user_id=5 WHERE brand_id=1");
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
        assertThat(members(id, 1)).containsExactly(4L);
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
                .containsExactly("SEND_CARD", "CREATE_TASK", "CLOSE_TASK");
        assertThat(members(id, 2)).containsExactly(5L);
        assertThat(actions(id, 2)).containsExactly("CREATE_TASK", "SEND_CARD");
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
        assertThat(actions(id, 1)).containsExactly("CREATE_TASK", "SEND_CARD");
        assertThat(actions(id, 2)).isEmpty();
    }

    @Test
    void competingBuyersApplyEachAccompanyingChangeExactlyOnce() throws Exception {
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
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"));
        round(BUYER, id, 1)
                .andExpect(jsonPath("$.changeSummary.triggerSegments").value(0))
                .andExpect(jsonPath("$.changeSummary.requiresProductReview").value(false))
                .andExpect(jsonPath("$.adjustments[*].accompanying").value(contains(true, true)));
        round(BUYER, id, 1)
                .andExpect(jsonPath("$.allowedActions").value(contains("APPROVE", "REJECT")));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var attempts =
                    List.of(BUYER, BUYER).stream()
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
                                                                        approve)
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
                .containsExactly("CLOSE_TASK", "GROUP_MESSAGE");
        assertThat(
                        jdbc.queryForList(
                                """
                SELECT decision FROM policy_review_decision WHERE policy_id=? AND version=1 AND group_code='PURCHASE'
                """,
                                String.class,
                                id))
                .containsExactly("APPROVE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_time_change WHERE source_policy_id=?",
                                Integer.class,
                                id))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM policy_apply", Integer.class))
                .isEqualTo(3);
        for (var change : changes) {
            long originalId = Long.parseLong(change.target().policyId());
            var timeHistory = policies.detail(OWNER, originalId).timeHistory();
            assertThat(timeHistory.currentEnd()).isEqualTo(change.endTime());
            assertThat(timeHistory.changes())
                    .singleElement()
                    .satisfies(
                            row -> {
                                assertThat(row.sourcePolicyCode()).isEqualTo(parent.policyCode());
                                assertThat(row.reason()).isEqualTo(change.reason());
                            });
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
                        new PolicyReviewSettingsUpdate(
                                version,
                                List.of(
                                        new PolicyReviewSettingsUpdate.Brand(
                                                "1",
                                                "BY_SERIES",
                                                "4",
                                                null,
                                                List.of(
                                                        new PolicyReviewSettingsUpdate.Series(
                                                                "1", product.get(0)))))))
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
                    assertThat(actions(id, 1)).containsExactly("CREATE_TASK", "SEND_CARD");
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
        com.company.policy.RbacFixture.setRole(jdbc, 4, "ADMIN");
        assertThatThrownBy(() -> policies.submit(OWNER, id, direct(draft)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("POLICY_REVIEW_ASSIGNEES_MISSING"));
        com.company.policy.ReviewAccounts.grant(jdbc, 4, "company", "PURCHASE");
        jdbc.update("UPDATE user_info SET is_deleted=1 WHERE id IN (4,5)");
        assertThatThrownBy(() -> policies.submit(OWNER, id, direct(draft)))
                .isInstanceOf(ApiException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM policy_apply WHERE id=?", String.class, id))
                .isEqualTo("DRAFT");
        assertThat(actions(id, 1)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"2099-05-09T12:34:56+08:00, 政策截止已提前", "2099-05-20T12:34:56+08:00, 政策已延期"})
    void directAdjustmentAnnouncesReasonAndKeepsRoundAccess(String end, String title)
            throws Exception {
        configure();
        var source = draft();
        long sourceId = Long.parseLong(source.id());
        submit(sourceId, direct(source)).andExpect(status().isOk());
        procurement.review(BUYER, sourceId, new PolicyReviewRequest(2, 1, "APPROVE", null));
        jdbc.update("DELETE FROM policy_review_delivery");
        var change =
                new EndAdjustment(directAdjustments.options(OWNER).get(0).target(), end, "平台活动改期");
        var changes = List.of(change);
        var preview = directAdjustments.preview(OWNER, changes);
        String key = java.util.UUID.randomUUID().toString();
        var adjustment =
                directAdjustments
                        .apply(OWNER, changes, preview.confirmationToken(), List.of(), key)
                        .get(0);
        directAdjustments.apply(OWNER, changes, preview.confirmationToken(), List.of(), key);

        feishu.setPolicyChatId("oc_testing");
        when(remote.sendGroup(anyString(), anyString(), anyList(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            call.getArgument(4, Runnable.class).run();
                            return "om_time_adjustment";
                        });
        try {
            drainDeliveries();
            var text = ArgumentCaptor.forClass(String.class);
            verify(remote)
                    .sendGroup(
                            eq("oc_testing"),
                            text.capture(),
                            eq(List.of("open-1")),
                            anyString(),
                            any());
            verifyNoMoreInteractions(remote);
            assertThat(text.getValue())
                    .contains(
                            title + " · " + source.policyCode(),
                            adjustment.policyCode(),
                            "操作人：",
                            "平台活动改期",
                            "12:34:56",
                            "【基础信息】",
                            "【政策时间与详情】",
                            "【商品及补贴信息】",
                            "拼多多",
                            "固定补贴")
                    .doesNotContain("采购终审通过", "终审人：", "查看本轮政策", "#/reviews/");
            mvc.perform(
                            get("/api/policy-reviews/{id}", adjustment.id())
                                    .param("submissionVersion", "1")
                                    .session(session(OWNER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.allowedActions").isEmpty())
                    .andExpect(jsonPath("$.timeHistory.changes[0].reason").value("平台活动改期"));
            mvc.perform(
                            get("/api/policy-reviews/{id}", adjustment.id())
                                    .param("submissionVersion", "1")
                                    .session(session(user(6, "OPERATIONS", "PRODUCT_OPS"))))
                    .andExpect(status().isNotFound());
        } finally {
            feishu.setPolicyChatId("");
        }
    }

    @Test
    void successiveChangesKeepEachFrozenNoticeAndRescheduleRemindersOnOriginalPolicy()
            throws Exception {
        configure();
        var source = draft();
        long original = Long.parseLong(source.id());
        submit(original, direct(source)).andExpect(status().isOk());
        procurement.review(BUYER, original, new PolicyReviewRequest(2, 1, "APPROVE", null));
        feishu.setPolicyChatId("trial-chat");
        try {
            execution.initialize(LocalDateTime.parse("2099-05-01T00:00:00"));
            var first = changeDeadline(source, "2099-05-09T11:00:00+08:00", "首次缩短");
            var second = changeDeadline(first, "2099-05-07T11:00:00+08:00", "继续缩短");
            var last = changeDeadline(second, "2099-05-12T10:00:00+08:00", "恢复活动");
            var at = LocalDateTime.parse("2099-05-07T11:00:01");
            var changed = new ArrayList<String>();
            for (var payload : noticeBodies()) {
                var body = json.readTree(payload);
                if (body.has("changeId")) {
                    assertThat(body.path("policy").asLong()).isEqualTo(original);
                    var message = execution.message("company", body, at);
                    assertThat(message.users()).containsExactly(1L);
                    changed.add(message.text());
                }
            }
            assertThat(List.of(first.id(), second.id(), last.id()))
                    .containsExactly(source.id(), source.id(), source.id());
            assertThat(changed).hasSize(3);
            assertThat(changed)
                    .anySatisfy(
                            text ->
                                    assertThat(text)
                                            .contains(
                                                    "政策截止已提前 · " + source.policyCode(),
                                                    "原因：首次缩短",
                                                    "2099-05-09 11:00:00"));
            assertThat(changed)
                    .anySatisfy(
                            text ->
                                    assertThat(text)
                                            .contains(
                                                    "政策截止已提前 · " + source.policyCode(),
                                                    "原因：继续缩短",
                                                    "调整前政策时段（北京时间）：2099-05-01 00:00:00 至 2099-05-09 11:00:00",
                                                    "2099-05-07 11:00:00"));
            assertThat(changed)
                    .anySatisfy(
                            text ->
                                    assertThat(text)
                                            .contains(
                                                    "政策已延期 · " + source.policyCode(),
                                                    "原因：恢复活动",
                                                    "2099-05-12 10:00:00")
                                            .doesNotContain("调整前政策时段"));
            var reminderAt = LocalDateTime.parse("2099-05-11T17:00:00");
            var reminder = execution.message("company", reminder(original, reminderAt), reminderAt);
            assertThat(reminder.text()).contains(source.policyCode(), "2099-05-12 10:00:00");
            assertThat(reminder.users()).containsExactly(1L);
        } finally {
            feishu.setPolicyChatId("");
        }
    }

    @Test
    void extendingBackToTheOriginalDeadlineReactivatesCancelledReminderDates() throws Exception {
        configure();
        var source = draft();
        long id = Long.parseLong(source.id());
        submit(id, direct(source)).andExpect(status().isOk());
        procurement.review(BUYER, id, new PolicyReviewRequest(2, 1, "APPROVE", null));
        feishu.setPolicyChatId("trial-chat");
        try {
            execution.initialize(LocalDateTime.parse("2099-05-01T00:00:00"));
            var initialEnd = directAdjustments.options(OWNER).get(0).target().previousEnd();
            var eve =
                    com.company.policy.support.policy.PolicyFormValidation.time(initialEnd)
                            .toLocalDate()
                            .minusDays(1)
                            .atTime(17, 0);
            changeDeadline(source, "2099-05-06T11:00:00+08:00", "场次提前");
            assertThat(reminderStatus(id, eve)).isEqualTo("CANCELLED");
            changeDeadline(source, initialEnd, "按原日期办理");
            assertThat(reminderStatus(id, eve)).isEqualTo("PENDING");
            assertThat(execution.message("company", reminder(id, eve), eve).users())
                    .containsExactly(1L);
        } finally {
            feishu.setPolicyChatId("");
        }
    }

    private PolicyResponse changeDeadline(PolicyResponse source, String end, String reason) {
        var option =
                directAdjustments.options(OWNER).stream()
                        .filter(p -> p.target().policyId().equals(source.id()))
                        .findFirst()
                        .orElseThrow();
        var changes = List.of(new EndAdjustment(option.target(), end, reason));
        var preview = directAdjustments.preview(OWNER, changes);
        String key = java.util.UUID.randomUUID().toString();
        var result =
                directAdjustments.apply(
                        OWNER, changes, preview.confirmationToken(), List.of(), key);
        assertThat(
                        directAdjustments.apply(
                                OWNER, changes, preview.confirmationToken(), List.of(), key))
                .isEqualTo(result);
        return result.get(0);
    }

    @Test
    void scheduleKeepsGroupStartAndEndWithoutReplayingExpiredHistory() throws Exception {
        configure();
        var source = draft();
        long id = Long.parseLong(source.id());
        feishu.setPolicyChatId("trial-chat");
        var start = LocalDateTime.parse("2099-05-01T00:00:00");
        var end = LocalDateTime.parse("2099-05-10T23:59:59");
        try {
            submit(id, direct(source)).andExpect(status().isOk());
            execution.initialize(start.minusDays(2));
            assertThat(noticeBodies()).isEmpty();
            procurement.review(BUYER, id, new PolicyReviewRequest(2, 1, "APPROVE", null));
            assertThat(execution.atApproval("company", id).text()).isEmpty();
            execution.initialize(end.plusSeconds(1));
            assertThat(noticeBodies()).isEmpty();
            execution.initialize(end.minusDays(1));
            var finish = noticeBody("END");
            execution.initialize(start.minusDays(1));
            execution.initialize(start.minusDays(1));
            assertThat(noticeBodies()).hasSize(4);
            var begin = noticeBody("START");
            assertThatThrownBy(() -> execution.message("company", begin, start.minusSeconds(1)))
                    .isInstanceOf(PolicyFeishuSender.Failure.class);
            assertThat(execution.message("company", begin, start).text()).contains("实际采用", "拼多多");
            assertThatThrownBy(() -> execution.message("company", finish, end))
                    .isInstanceOf(PolicyFeishuSender.Failure.class);
            assertThat(execution.message("company", finish, end.plusSeconds(1)).text())
                    .contains("自然结束");
        } finally {
            feishu.setPolicyChatId("");
        }
    }

    @Test
    void shorteningSupersedesQueuedDeadlineOnlyAfterApprovalAndRechecksAtDispatch()
            throws Exception {
        configure();
        var source = draft();
        long id = Long.parseLong(source.id());
        submit(id, direct(source)).andExpect(status().isOk());
        procurement.review(BUYER, id, new PolicyReviewRequest(2, 1, "APPROVE", null));
        feishu.setPolicyChatId("trial-chat");
        try {
            var start = LocalDateTime.parse("2099-05-01T00:00:00");
            execution.initialize(start);
            var newDeadlineEve = LocalDateTime.parse("2099-05-08T17:00:00");
            var droppedDay = newDeadlineEve.plusDays(1);
            var priorReminder = reminder(id, droppedDay);
            var parent = policies.create(OWNER, form("2099-07"), null);
            long parentId = Long.parseLong(parent.id());
            var option =
                    policies.preview(OWNER, parentId, 1).applicability().adjustmentOptions().get(0);
            var change = new EndAdjustment(option.target(), "2099-05-09T15:45:17+08:00", "提前结束");
            var preview =
                    policies.previewAdjusted(
                            OWNER,
                            parentId,
                            new PolicyDraftRequest.AdjustmentPreview(1, List.of(change)));
            submit(
                            parentId,
                            new Submission(
                                    1, preview.confirmationToken(), List.of(), List.of(change)))
                    .andExpect(status().isOk());
            assertThat(execution.message("company", priorReminder, droppedDay).users())
                    .containsExactly(1L);
            procurement.review(BUYER, parentId, new PolicyReviewRequest(2, 1, "APPROVE", null));
            assertThat(reminderStatus(id, droppedDay)).isEqualTo("CANCELLED");
            assertThatThrownBy(() -> execution.message("company", priorReminder, droppedDay))
                    .isInstanceOf(PolicyFeishuSender.Failure.class);
            var retainedReminder = reminder(id, newDeadlineEve);
            assertThat(execution.message("company", retainedReminder, newDeadlineEve).text())
                    .contains("2099-05-09 15:45:17");
            assertThat(
                            execution
                                    .message(
                                            "company",
                                            reminder(id, newDeadlineEve.minusDays(1)),
                                            newDeadlineEve.minusDays(1))
                                    .users())
                    .containsExactly(1L);
            assertThat(
                            execution
                                    .message("company", noticeBody("SHORTENED"), start.plusDays(6))
                                    .text())
                    .contains("15:45:17", parent.policyCode());
            var end = LocalDateTime.parse("2099-05-09T15:45:17");
            var ending =
                    json.readTree(
                            jdbc.queryForObject(
                                    "SELECT payload_json FROM policy_review_delivery WHERE notice_key LIKE ? AND delivery_status='PENDING'",
                                    String.class,
                                    "P/" + id + "/%/END/%"));
            assertThatThrownBy(() -> execution.message("company", ending, end))
                    .isInstanceOf(PolicyFeishuSender.Failure.class);
            assertThat(execution.message("company", ending, end.plusSeconds(1)).text())
                    .contains("提前结束");
        } finally {
            feishu.setPolicyChatId("");
        }
    }

    @Test
    void ordinaryPolicyRegistersOneDeadlineAndChecksItsExactSecondBeforeSending() throws Exception {
        configure();
        var start = LocalDateTime.parse("2099-05-10T08:40:05");
        var end = LocalDateTime.parse("2099-05-10T12:15:37");
        var form = json.valueToTree(form());
        ((com.fasterxml.jackson.databind.node.ObjectNode) form)
                .put("startTime", start + "+08:00")
                .put("endTime", end + "+08:00");
        var source = policies.create(OWNER, json.treeToValue(form, PolicyDraftRequest.class), null);
        long policy = Long.parseLong(source.id());
        submit(policy, direct(source)).andExpect(status().isOk());
        procurement.review(BUYER, policy, new PolicyReviewRequest(2, 1, "APPROVE", null));
        feishu.setPolicyChatId("trial-chat");
        try {
            execution.initialize(start.minusSeconds(1));
            var begin = noticeBody("START");
            assertThatThrownBy(() -> execution.message("company", begin, start.minusSeconds(1)))
                    .isInstanceOf(PolicyFeishuSender.Failure.class);
            assertThat(execution.message("company", begin, start).text()).contains("08:40:05");
            assertThatThrownBy(() -> execution.message("company", begin, end.plusSeconds(1)))
                    .isInstanceOf(PolicyFeishuSender.Failure.class);
            var endRow =
                    jdbc.queryForObject(
                            "SELECT id FROM policy_review_delivery WHERE notice_key LIKE 'P/%/END/%'",
                            Long.class);
            var dueSql = "SELECT next_attempt_at FROM policy_review_delivery WHERE id=?";
            assertThat(jdbc.queryForObject(dueSql, LocalDateTime.class, endRow))
                    .isEqualTo(end.plusSeconds(1));

            execution.initialize(start);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT id FROM policy_review_delivery WHERE notice_key LIKE 'P/%/END/%'",
                                    Long.class))
                    .containsExactly(endRow);
            var finish = noticeBody("END");
            assertThatThrownBy(() -> execution.message("company", finish, end))
                    .isInstanceOf(PolicyFeishuSender.Failure.class);
            assertThat(execution.message("company", finish, end.plusSeconds(1)).text())
                    .contains("12:15:37");
            var wrongEnd = finish.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) wrongEnd)
                    .put("end", end.plusSeconds(1).toString());
            assertThatThrownBy(() -> execution.message("company", wrongEnd, end.plusSeconds(2)))
                    .isInstanceOf(PolicyFeishuSender.Failure.class)
                    .hasMessageContaining("实际截止已变更");
        } finally {
            feishu.setPolicyChatId("");
        }
    }

    @Test
    void dueRemindersReachOnlyTheOriginalApplicantAtFiveOnTheTwoPrecedingDays() throws Exception {
        configure();
        var source = draft();
        long id = Long.parseLong(source.id());
        submit(id, direct(source)).andExpect(status().isOk());
        procurement.review(BUYER, id, new PolicyReviewRequest(2, 1, "APPROVE", null));
        drainDeliveries();
        clearInvocations(remote);
        jdbc.update("UPDATE user_platform_assignment SET user_id=6");
        var twoDays = LocalDateTime.parse("2099-05-08T17:00:00");
        var eve = twoDays.plusDays(1);
        execution.initialize(twoDays.minusDays(1));
        execution.initialize(twoDays.minusSeconds(1));
        assertThat(noticeBodies()).hasSize(2);
        assertThat(
                        jdbc.queryForList(
                                "SELECT next_attempt_at FROM policy_review_delivery WHERE notice_key IS NOT NULL ORDER BY next_attempt_at",
                                LocalDateTime.class))
                .containsExactly(twoDays, eve);
        var early = reminder(id, twoDays);
        assertThatThrownBy(() -> execution.message("company", early, twoDays.minusSeconds(1)))
                .isInstanceOf(PolicyFeishuSender.Failure.class);
        assertThat(
                        execution
                                .message("company", early, twoDays.toLocalDate().atTime(23, 59, 59))
                                .users())
                .containsExactly(1L);
        assertThatThrownBy(
                        () -> execution.message("company", early, eve.toLocalDate().atStartOfDay()))
                .isInstanceOf(PolicyFeishuSender.Failure.class);
        when(remote.sendCard(eq("open-1"), any(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            call.getArgument(3, Runnable.class).run();
                            return "om_" + call.getArgument(2);
                        });
        var before = twoDays.minusSeconds(1);
        try (var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            var shanghai = ZoneId.of("Asia/Shanghai");
            clock.when(() -> LocalDateTime.now(shanghai)).thenReturn(before);
            assertThat(deliveries.processOne()).isFalse();
            for (var due : List.of(twoDays, eve)) {
                clock.when(() -> LocalDateTime.now(shanghai)).thenReturn(due);
                execution.initialize(due);
                execution.initialize(due);
                assertThat(deliveries.processOne()).isTrue();
                assertThat(deliveries.processOne()).isFalse();
                assertThat(reminderStatus(id, due)).isEqualTo("SENT");
                assertThat(reminder(id, due).path("requestRecipient").asText()).isEqualTo("open-1");
            }
            var deadlineDay = eve.plusDays(1);
            clock.when(() -> LocalDateTime.now(shanghai)).thenReturn(deadlineDay);
            execution.initialize(deadlineDay);
            assertThat(deliveries.processOne()).isFalse();
            assertThat(noticeBodies()).hasSize(2);
        }
        var cards = ArgumentCaptor.forClass(JsonNode.class);
        var keys = ArgumentCaptor.forClass(String.class);
        verify(remote, times(2)).sendCard(eq("open-1"), cards.capture(), keys.capture(), any());
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();
        assertThat(cards.getAllValues())
                .allSatisfy(
                        card -> {
                            assertThat(card.toString())
                                    .contains("政策临期提醒", source.policyCode(), "2099-05-10");
                            assertTextOnlyNotice(card);
                        });
        verifyNoMoreInteractions(remote);
    }

    @ParameterizedTest
    @CsvSource({"DISABLED,FAILED", "WITHDRAWN,CANCELLED", "MISSED_DAY,CANCELLED"})
    void expiredOrUndeliverableRemindersNeverReachTheSuccessor(String scenario, String status)
            throws Exception {
        configure();
        var source = draft();
        long id = Long.parseLong(source.id());
        submit(id, direct(source)).andExpect(status().isOk());
        procurement.review(BUYER, id, new PolicyReviewRequest(2, 1, "APPROVE", null));
        drainDeliveries();
        clearInvocations(remote);
        var due = LocalDateTime.parse("2099-05-08T17:00:00");
        execution.initialize(due);
        jdbc.update("UPDATE user_platform_assignment SET user_id=6");
        if ("DISABLED".equals(scenario))
            jdbc.update("UPDATE user_info SET is_deleted=1 WHERE id=1");
        if ("WITHDRAWN".equals(scenario))
            jdbc.update("UPDATE policy_apply SET is_deleted=1 WHERE id=?", id);
        var sendAt =
                "MISSED_DAY".equals(scenario) ? due.toLocalDate().plusDays(1).atStartOfDay() : due;
        try (var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(() -> LocalDateTime.now(ZoneId.of("Asia/Shanghai"))).thenReturn(sendAt);
            assertThat(deliveries.processOne()).isTrue();
            assertThat(deliveries.processOne()).isFalse();
            assertThat(reminderStatus(id, due)).isEqualTo(status);
            verifyNoInteractions(remote);
        }
    }

    @Test
    void firstScanAfterFiveRegistersOneReminderAndKeepsItsUnconfirmedDelivery() throws Exception {
        configure();
        var source = draft();
        long id = Long.parseLong(source.id());
        submit(id, direct(source)).andExpect(status().isOk());
        procurement.review(BUYER, id, new PolicyReviewRequest(2, 1, "APPROVE", null));
        drainDeliveries();
        clearInvocations(remote);
        var late = LocalDateTime.parse("2099-05-09T18:20:43");
        execution.initialize(late);
        execution.initialize(late);
        assertThat(noticeBodies()).hasSize(1);
        var key = reminderKey(id, late);
        when(remote.sendCard(eq("open-1"), any(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            call.getArgument(3, Runnable.class).run();
                            throw new PolicyFeishuSender.Failure(
                                    PolicyFeishuSender.Kind.UNKNOWN, "请求后失去响应");
                        });
        try (var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            var shanghai = ZoneId.of("Asia/Shanghai");
            clock.when(() -> LocalDateTime.now(shanghai)).thenReturn(late);
            assertThat(deliveries.processOne()).isTrue();
            var endOfWindow = late.plusHours(1);
            clock.when(() -> LocalDateTime.now(shanghai)).thenReturn(endOfWindow);
            drainDeliveries();
        }
        assertThat(reminderStatus(id, late)).isEqualTo("UNKNOWN");
        assertThat(reminder(id, late).path("requestRecipient").asText()).isEqualTo("open-1");
        assertThat(reminder(id, late).path("requestFingerprint").asText()).isNotBlank();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_review_delivery WHERE notice_key=? AND receipt_id IS NULL AND next_attempt_at IS NULL",
                                Integer.class,
                                key))
                .isEqualTo(1);
        verify(remote).sendCard(eq("open-1"), any(), anyString(), any());
        verifyNoMoreInteractions(remote);
    }

    @Test
    void unrecognizedBusinessNoticeIsNeverDelivered() throws Exception {
        configure();
        var draft = draft();
        long policy = Long.parseLong(draft.id());
        submit(policy, direct(draft)).andExpect(status().isOk());
        procurement.review(BUYER, policy, new PolicyReviewRequest(2, 1, "APPROVE", null));
        jdbc.update("DELETE FROM policy_review_delivery");
        feishu.setPolicyChatId("trial-chat");
        try {
            var payload =
                    json.createObjectNode()
                            .put("kind", "UNKNOWN_KIND")
                            .put("policy", policy)
                            .put("version", 1);
            jdbc.update(
                    """
                    INSERT INTO policy_review_delivery (tenant_key,event_key,action_type,recipient_key,payload_json)
                    VALUES ('company','unrecognized-notice','GROUP_MESSAGE',0,?)
                    """,
                    payload.toString());
            drainDeliveries();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT delivery_status FROM policy_review_delivery",
                                    String.class))
                    .isEqualTo("FAILED");
            verifyNoInteractions(remote);
        } finally {
            feishu.setPolicyChatId("");
        }
    }

    private static String reminderKey(long policyId, LocalDateTime at) {
        return "P/" + policyId + "/EXPIRY_REMINDER/" + at.toLocalDate();
    }

    private JsonNode reminder(long policyId, LocalDateTime at) throws Exception {
        return json.readTree(
                jdbc.queryForObject(
                        "SELECT payload_json FROM policy_review_delivery WHERE notice_key=?",
                        String.class,
                        reminderKey(policyId, at)));
    }

    private String reminderStatus(long policyId, LocalDateTime at) {
        return jdbc.queryForObject(
                "SELECT delivery_status FROM policy_review_delivery WHERE notice_key=?",
                String.class,
                reminderKey(policyId, at));
    }

    private List<String> noticeBodies() {
        return jdbc.queryForList(
                "SELECT payload_json FROM policy_review_delivery WHERE notice_key IS NOT NULL",
                String.class);
    }

    private JsonNode noticeBody(String kind) throws Exception {
        for (var raw : noticeBodies()) {
            var body = json.readTree(raw);
            if (kind.equals(body.path("kind").asText())) return body;
        }
        throw new AssertionError("缺少业务提醒 " + kind);
    }

    @Test
    void queuedCleaningSummaryIsCancelledWithoutCallingFeishu() {
        var due = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1);
        notices.register(
                "company",
                "CLEAN/31",
                """
                {"kind":"CLEAN","job":31,"brand":"OPPO手机","requester":4,"reviews":5,"errors":2}
                """,
                due,
                due);
        feishu.setPolicyChatId("trial-chat");
        try {
            drainDeliveries();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT delivery_status FROM policy_review_delivery WHERE notice_key='CLEAN/31' AND receipt_id IS NULL AND next_attempt_at IS NULL",
                                    String.class))
                    .isEqualTo("CANCELLED");
            verifyNoInteractions(remote);
        } finally {
            feishu.setPolicyChatId("");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void importPauseNotifiesItsRequesterOnlyWhileEligible(boolean enabled) {
        var batch = new com.company.policy.entity.GoodsSyncJob();
        batch.setId(31L);
        batch.setRequesterId(5L);
        batch.setBrandName("OPPO手机");
        jdbc.update(
                """
                INSERT INTO goods_sync_job (id, source_key, requester_id, brand_name, idempotency_key,
                                            include_blockup, status, record_version)
                VALUES (31, 'test-import', 5, 'OPPO手机', 'paused-job', 1, 'PAUSED', 1)
                """);
        feishu.setPolicyChatId("trial-chat");
        try {
            execution.paused(batch, "RATE_LIMIT");
            execution.paused(batch, "RATE_LIMIT");
            if (enabled) {
                when(remote.sendGroup(
                                eq("trial-chat"),
                                org.mockito.ArgumentMatchers.contains("导入批次 31 异常暂停"),
                                eq(List.of("open-5")),
                                anyString(),
                                any()))
                        .thenAnswer(
                                call -> {
                                    call.getArgument(4, Runnable.class).run();
                                    return "om_import";
                                });
            } else {
                jdbc.update("UPDATE user_info SET is_deleted=1 WHERE id=5");
            }
            drainDeliveries();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT delivery_status FROM policy_review_delivery WHERE notice_key='IMPORT/31/1'",
                                    String.class))
                    .isEqualTo(enabled ? "SENT" : "FAILED");
            if (enabled) {
                verify(remote).sendGroup(anyString(), anyString(), anyList(), anyString(), any());
            } else {
                verifyNoInteractions(remote);
            }
        } finally {
            feishu.setPolicyChatId("");
            jdbc.update("DELETE FROM goods_sync_job WHERE id=31");
        }
    }

    @Test
    void handoverSendsOnceToEnabledSuccessorAndRecordsDisabledFormerOwner() throws Exception {
        var scope = new com.company.policy.dto.ChannelPlatform("BILLION", "PDD");
        execution.platformChanged("company", scope, 1L, 6L);
        jdbc.update("UPDATE user_info SET is_deleted=1 WHERE id=1");
        feishu.setPolicyChatId("trial-chat");
        try {
            when(remote.sendGroup(
                            eq("trial-chat"),
                            org.mockito.ArgumentMatchers.contains("平台分工变更"),
                            eq(List.of("open-6")),
                            anyString(),
                            any()))
                    .thenAnswer(
                            call -> {
                                ((Runnable) call.getArgument(4)).run();
                                return "om_handover";
                            });
            drainDeliveries();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM policy_review_delivery WHERE delivery_status='SENT' AND receipt_id='om_handover'",
                                    Integer.class))
                    .isEqualTo(1);
            var sent =
                    json.readTree(
                            jdbc.queryForObject(
                                    "SELECT payload_json FROM policy_review_delivery WHERE delivery_status='SENT'",
                                    String.class));
            assertThat(sent.at("/unreachableUserIds/0").asLong()).isEqualTo(1);
            assertThat(sent.path("new").asLong()).isEqualTo(6);
            verify(remote, times(1))
                    .sendGroup(anyString(), anyString(), anyList(), anyString(), any());
        } finally {
            feishu.setPolicyChatId("");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void formalSubmissionRoutesSingleRemainingCandidateAndCancellationOfStack(boolean single)
            throws Exception {
        configure();
        var a = draft();
        var aid = Long.parseLong(a.id());
        submit(aid, direct(a)).andExpect(status().isOk());
        procurement.review(BUYER, aid, new PolicyReviewRequest(2, 1, "APPROVE", null));
        var b = draft();
        var bid = Long.parseLong(b.id());
        var pb = policies.preview(OWNER, bid, 1);
        var scope = pb.applicability().scopes().get(0);
        var chooseA =
                new Choice(
                        scope.scope(),
                        scope.segments().get(0).through(),
                        "CHOOSE_ONE",
                        a.id(),
                        null);
        submit(
                        bid,
                        single
                                ? new Submission(
                                        1, pb.confirmationToken(), List.of(chooseA), List.of())
                                : stack(bid, 1))
                .andExpect(status().isOk());
        if (!single)
            procurement.productReview(
                    user(2, "PRODUCT_OPS"), bid, new PolicyReviewRequest(2, 1, "APPROVE", null));
        procurement.review(BUYER, bid, new PolicyReviewRequest(single ? 2 : 3, 1, "APPROVE", null));
        var startNotice =
                json.createObjectNode().put("kind", "START").put("policy", bid).put("version", 1);
        var start = LocalDateTime.parse("2099-05-01T00:00:00");
        if (single) {
            assertThatThrownBy(() -> execution.message("company", startNotice, start))
                    .hasMessageContaining("未采用本单");
        } else {
            assertThat(execution.message("company", startNotice, start).text())
                    .contains(b.policyCode(), "拼多多");
        }
        var c = draft();
        var cid = Long.parseLong(c.id());
        var preview = policies.preview(OWNER, cid, 1);
        List<EndAdjustment> changes = List.of();
        if (single) {
            var option =
                    preview.applicability().adjustmentOptions().stream()
                            .filter(o -> o.target().policyId().equals(a.id()))
                            .findFirst()
                            .orElseThrow();
            changes =
                    List.of(new EndAdjustment(option.target(), option.earliestEnd(), "提前结束已采用原单"));
            preview =
                    policies.previewAdjusted(
                            OWNER, cid, new PolicyDraftRequest.AdjustmentPreview(1, changes));
        }
        scope = preview.applicability().scopes().get(0);
        var segment = scope.segments().get(0);
        if (single) assertThat(segment.eligiblePolicyIds()).containsExactly(c.id());
        var selected = single ? c.id() : a.id();
        assertThat(actions(cid, 1)).isEmpty();
        submit(
                        cid,
                        new Submission(
                                1,
                                preview.confirmationToken(),
                                List.of(
                                        new Choice(
                                                scope.scope(),
                                                segment.through(),
                                                "CHOOSE_ONE",
                                                selected,
                                                null)),
                                changes))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"));
        var snapshot =
                json.readTree(
                        jdbc.queryForObject(
                                "SELECT snapshot_data FROM policy_audit_log WHERE policy_id=? AND version=1",
                                String.class,
                                cid));
        assertThat(snapshot.at("/consent/decisions/0/selectedPolicyIds/0").asText())
                .isEqualTo(selected);
        assertThat(members(cid, 1)).containsExactly(4L);
        assertThat(actions(cid, 1)).containsExactly("CREATE_TASK", "SEND_CARD");
        if (!single)
            round(BUYER, cid, 1)
                    .andExpect(
                            jsonPath("$.changes[0].before[*].policyId")
                                    .value(containsInAnyOrder(a.id(), b.id())));
    }

    @Autowired PolicyTimeAdjustments timeAdjustments;

    @Test
    void directlyExtendedPoliciesUseOneSelectionAndOneResultAcrossConcurrentRetries()
            throws Exception {
        configure();
        var first = draft();
        var second = draft();
        long a = Long.parseLong(first.id());
        long b = Long.parseLong(second.id());
        policies.submit(OWNER, a, direct(first));
        procurement.review(BUYER, a, new PolicyReviewRequest(2, 1, "APPROVE", null));
        policies.submit(OWNER, b, stack(b, 1));
        procurement.productReview(
                user(2, "PRODUCT_OPS"), b, new PolicyReviewRequest(2, 1, "APPROVE", null));
        procurement.review(BUYER, b, new PolicyReviewRequest(3, 1, "APPROVE", null));
        var changes =
                timeAdjustments.options(OWNER).stream()
                        .map(
                                o ->
                                        new EndAdjustment(
                                                o.target(), "2099-05-20T20:01:02+08:00", "活动延期至周末"))
                        .toList();
        assertThat(changes).hasSize(2);
        var shortenFirst =
                List.of(
                        new EndAdjustment(
                                changes.get(0).target(), "2099-05-09T10:00:00+08:00", "拟提前结单"));
        var oldPreview = timeAdjustments.preview(OWNER, shortenFirst);
        var preview = timeAdjustments.preview(OWNER, changes);
        var scope = preview.applicability().scopes().get(0);
        var selected =
                scope.policies().stream()
                        .filter(p -> p.policyCode().equals(first.policyCode() + "（延期）"))
                        .findFirst()
                        .orElseThrow();
        var decisions =
                List.of(
                        new Choice(
                                scope.scope(),
                                scope.segments().get(0).through(),
                                "CHOOSE_ONE",
                                selected.policyId(),
                                null));
        var key = java.util.UUID.randomUUID().toString();
        assertThatThrownBy(
                        () ->
                                timeAdjustments.apply(
                                        OWNER,
                                        changes,
                                        preview.confirmationToken(),
                                        List.of(),
                                        key))
                .isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM policy_apply", Integer.class))
                .isEqualTo(2);
        var start = new CountDownLatch(1);
        Callable<List<PolicyResponse>> call =
                () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return timeAdjustments.apply(
                            OWNER, changes, preview.confirmationToken(), decisions, key);
                };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var x = pool.submit(call);
            var y = pool.submit(call);
            start.countDown();
            var result = x.get(10, TimeUnit.SECONDS);
            assertThat(y.get(10, TimeUnit.SECONDS)).isEqualTo(result);
            assertThat(result).hasSize(2).allMatch(r -> "APPROVED".equals(r.status()));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM policy_apply", Integer.class))
                .isEqualTo(2);
        // 旧页仍持有首次截止和版本，不会覆盖本批的调整。
        assertThatThrownBy(
                        () ->
                                timeAdjustments.apply(
                                        OWNER,
                                        shortenFirst,
                                        oldPreview.confirmationToken(),
                                        List.of(),
                                        java.util.UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(409));
        assertThat(timeAdjustments.options(OWNER))
                .extracting(o -> o.target().policyId())
                .containsExactlyInAnyOrder(first.id(), second.id());
        var live = procurement.effective(BUYER, "BILLION", "PDD", 1, "2099-05-11T00:00:00+08:00");
        assertThat(live.policies()).extracting(p -> p.winner().id()).containsExactly(first.id());
    }

    @Test
    void consecutiveShorteningsAndExtensionKeepTheApprovedOriginalAndEveryChange() {
        configure();
        var original = draft();
        long originalId = Long.parseLong(original.id());
        policies.submit(OWNER, originalId, direct(original));
        procurement.review(BUYER, originalId, new PolicyReviewRequest(2, 1, "APPROVE", null));
        var originalDetail = policies.detail(OWNER, originalId);
        String firstEnd = "2099-05-09T22:12:15+08:00";
        String secondEnd = "2099-05-07T21:00:00+08:00";
        String extendedEnd = "2099-05-08T22:00:00+08:00";

        var first = adjustLatest(original, originalDetail.form().endTime(), firstEnd);
        var second = adjustLatest(first, firstEnd, secondEnd);
        assertLatestTimelineDeadline(originalDetail.form(), secondEnd);
        assertThat(policies.detail(OWNER, originalId).timeHistory().currentEnd())
                .isEqualTo(secondEnd);
        assertThat(policies.detail(OWNER, originalId).form()).isEqualTo(originalDetail.form());
        assertThat(policies.detail(OWNER, originalId).timeHistory().changes())
                .extracting(row -> row.actualEnd())
                .containsExactly(secondEnd, firstEnd);
        var nextSecond = "2099-05-07T21:00:01+08:00";
        assertThat(procurement.effective(BUYER, "BILLION", "PDD", 1, nextSecond).policies())
                .isEmpty();

        var extended = adjustLatest(second, secondEnd, extendedEnd);
        assertLatestTimelineDeadline(originalDetail.form(), extendedEnd);
        var extendedDetail = policies.detail(OWNER, Long.parseLong(extended.id()));
        assertThat(extendedDetail.form().startTime()).isEqualTo(originalDetail.form().startTime());
        assertThat(extendedDetail.timeHistory().currentEnd()).isEqualTo(extendedEnd);
        assertThat(extendedDetail.form().goodsForm().cards())
                .isEqualTo(originalDetail.form().goodsForm().cards());
        assertThat(extendedDetail.goods()).isEqualTo(originalDetail.goods());
        assertThat(procurement.effective(BUYER, "BILLION", "PDD", 1, secondEnd).policies())
                .extracting(p -> p.winner().id())
                .containsExactly(original.id());
        assertThat(procurement.effective(BUYER, "BILLION", "PDD", 1, nextSecond).policies())
                .extracting(p -> p.winner().id())
                .containsExactly(extended.id());
        assertThat(
                        procurement
                                .effective(BUYER, "BILLION", "PDD", 1, "2099-05-08T22:00:01+08:00")
                                .policies())
                .isEmpty();

        var last = adjustLatest(extended, extendedEnd, "2099-05-08T08:00:00+08:00");
        assertLatestTimelineDeadline(originalDetail.form(), "2099-05-08T08:00:00+08:00");
        assertThat(timeAdjustments.options(OWNER))
                .extracting(o -> o.target().policyId())
                .containsExactly(last.id());
        assertThat(policies.detail(OWNER, originalId).timeHistory().currentEnd())
                .isEqualTo("2099-05-08T08:00:00+08:00");
        assertThat(
                        procurement
                                .effective(BUYER, "BILLION", "PDD", 1, "2099-05-08T08:00:01+08:00")
                                .policies())
                .isEmpty();
    }

    private void assertLatestTimelineDeadline(PolicyDraftRequest filed, String actualEnd) {
        var query =
                new PolicyTimelineQuery(
                        1,
                        "12GB",
                        "256GB",
                        LocalDate.of(2099, 5, 1),
                        LocalDate.of(2099, 5, 31),
                        "BILLION",
                        "PDD");
        for (var result :
                List.of(
                        procurement.subsidyTimeline(BUYER, query),
                        policies.subsidyTimeline(OWNER, query))) {
            assertThat(result.tracks()).hasSize(1);
            var track = result.tracks().get(0);
            assertThat(track.policies()).hasSize(1);
            var policy = track.policies().get(0);
            assertThat(policy.policyFrom()).isEqualTo(filed.startTime());
            assertThat(policy.policyThrough()).isEqualTo(filed.endTime());
            assertThat(policy.effectiveThrough()).isEqualTo(actualEnd);
            assertThat(track.plannedSegments()).hasSize(1);
            var segmentEnd = OffsetDateTime.parse(track.plannedSegments().get(0).endAt());
            assertThat(segmentEnd).isEqualTo(OffsetDateTime.parse(actualEnd).plusSeconds(1));
        }
    }

    @Test
    void staleAdjustmentIsRejectedEvenWhenTheDeadlineHasReturnedToItsOriginalValue() {
        configure();
        var original = draft();
        long id = Long.parseLong(original.id());
        policies.submit(OWNER, id, direct(original));
        procurement.review(BUYER, id, new PolicyReviewRequest(2, 1, "APPROVE", null));
        var stale = timeAdjustments.options(OWNER).get(0).target();
        var change = List.of(new EndAdjustment(stale, "2099-05-07T08:00:00+08:00", "旧页面"));
        var preview = timeAdjustments.preview(OWNER, change);
        var later = "2099-05-15T09:30:00+08:00";
        adjustLatest(original, stale.previousEnd(), later);
        adjustLatest(original, later, stale.previousEnd());
        assertThatThrownBy(
                        () ->
                                timeAdjustments.apply(
                                        OWNER,
                                        change,
                                        preview.confirmationToken(),
                                        List.of(),
                                        java.util.UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM policy_time_change", Integer.class))
                .isEqualTo(2);
    }

    private PolicyResponse adjustLatest(PolicyResponse source, String previousEnd, String newEnd) {
        var options = timeAdjustments.options(OWNER);
        assertThat(options).extracting(o -> o.target().policyId()).containsExactly(source.id());
        var option = options.get(0);
        assertThat(option.target().previousEnd()).isEqualTo(previousEnd);
        assertThat(option.canExtend()).isTrue();
        assertThat(option.canShorten()).isTrue();
        var changes = List.of(new EndAdjustment(option.target(), newEnd, "核对平台档期"));
        var preview = timeAdjustments.preview(OWNER, changes);
        var key = java.util.UUID.randomUUID().toString();
        var created =
                timeAdjustments.apply(OWNER, changes, preview.confirmationToken(), List.of(), key);
        // 同一请求重放仍返回原单当次的版本。沿用旧核对依据的新请求须重新确认。
        assertThat(
                        timeAdjustments.apply(
                                OWNER, changes, preview.confirmationToken(), List.of(), key))
                .isEqualTo(created);
        assertThatThrownBy(() -> timeAdjustments.preview(OWNER, changes))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(409));
        assertThatThrownBy(
                        () ->
                                timeAdjustments.apply(
                                        OWNER,
                                        changes,
                                        preview.confirmationToken(),
                                        List.of(),
                                        java.util.UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(409));
        assertThat(created)
                .singleElement()
                .satisfies(
                        result -> {
                            assertThat(result.status()).isEqualTo("APPROVED");
                            assertThat(result.id()).isEqualTo(source.id());
                            var adjustment =
                                    policies.detail(OWNER, Long.parseLong(result.id()))
                                            .timeHistory()
                                            .changes()
                                            .get(0);
                            assertThat(adjustment.previousEnd()).isEqualTo(previousEnd);
                            assertThat(adjustment.requestedEnd()).isEqualTo(newEnd);
                        });
        return created.get(0);
    }

    @Test
    void incomingPlatformOwnerCanOnlyShortenAndReceivesNoPredecessorsPrivateDetails() {
        configure();
        var original = draft();
        long id = Long.parseLong(original.id());
        policies.submit(OWNER, id, direct(original));
        procurement.review(BUYER, id, new PolicyReviewRequest(2, 1, "APPROVE", null));
        var stale = timeAdjustments.options(OWNER).get(0);
        var earlyEnd = "2099-05-07T20:00:00+08:00";
        var changes = List.of(new EndAdjustment(stale.target(), earlyEnd, "档期提前结束"));
        var preview = timeAdjustments.preview(OWNER, changes);
        jdbc.update("UPDATE user_platform_assignment SET user_id=6 WHERE user_id=1");
        assertThatThrownBy(
                        () ->
                                timeAdjustments.apply(
                                        OWNER,
                                        changes,
                                        preview.confirmationToken(),
                                        List.of(),
                                        java.util.UUID.randomUUID().toString()))
                .isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM policy_apply", Integer.class)).isOne();
        var next = user(6, "OPERATIONS", "PRODUCT_OPS");
        var granted = timeAdjustments.options(next).get(0);
        assertThat(granted.canShorten()).isTrue();
        assertThat(granted.canExtend()).isFalse();
        var theirs = timeAdjustments.preview(next, changes);
        var applied =
                timeAdjustments
                        .apply(
                                next,
                                changes,
                                theirs.confirmationToken(),
                                List.of(),
                                java.util.UUID.randomUUID().toString())
                        .get(0);
        assertThat(applied.id()).isEqualTo(original.id());
        assertThatThrownBy(() -> policies.detail(next, id)).isInstanceOf(ApiException.class);
        assertThat(policies.detail(OWNER, id).timeHistory().currentEnd()).isEqualTo(earlyEnd);
        var latest = timeAdjustments.options(next);
        assertThat(latest)
                .singleElement()
                .satisfies(
                        o -> {
                            assertThat(o.target().policyId()).isEqualTo(applied.id());
                            assertThat(o.target().previousEnd()).isEqualTo(earlyEnd);
                            assertThat(o.canShorten()).isTrue();
                            assertThat(o.canExtend()).isFalse();
                        });
        var attemptedExtension =
                List.of(
                        new EndAdjustment(
                                latest.get(0).target(), stale.target().previousEnd(), "接手人尝试延长"));
        assertThatThrownBy(() -> timeAdjustments.preview(next, attemptedExtension))
                .isInstanceOf(ApiException.class);
        var anotherCut =
                List.of(
                        new EndAdjustment(
                                latest.get(0).target(), "2099-05-05T12:00:00+08:00", "交接后再次提前结束"));
        var checked = timeAdjustments.preview(next, anotherCut);
        var receipt =
                timeAdjustments
                        .apply(
                                next,
                                anotherCut,
                                checked.confirmationToken(),
                                List.of(),
                                java.util.UUID.randomUUID().toString())
                        .get(0);
        assertThat(receipt.id()).isEqualTo(original.id());
        assertThatThrownBy(() -> policies.detail(next, id)).isInstanceOf(ApiException.class);
        assertThat(timeAdjustments.options(OWNER)).isEmpty();
        jdbc.update("UPDATE user_platform_assignment SET user_id=1 WHERE user_id=6");
        assertThat(timeAdjustments.options(OWNER))
                .singleElement()
                .satisfies(
                        o -> {
                            assertThat(o.target().policyId()).isEqualTo(receipt.id());
                            assertThat(o.canExtend()).isTrue();
                        });
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
        return form(month, new Target("1", "12GB", "256GB", "1"));
    }

    private PolicyDraftRequest form(String month, Target... targets) {
        var card =
                new Card(
                        "c",
                        "COMMON",
                        true,
                        List.of(targets),
                        List.of(new SubsidyPlan("p", "FIXED", "20.00", null, null, null)),
                        List.of(new PlatformAssignment("PDD", "p", null)));
        return billionForm(month + "-01", month + "-10", List.of(card));
    }

    private PolicyDraftRequest specifiedGoods(String from, String through, Target... targets) {
        var cards =
                List.of(targets).stream()
                        .map(
                                target ->
                                        new Card(
                                                "c" + target.goodsId(),
                                                "SPECIFIED",
                                                true,
                                                List.of(target),
                                                List.of(
                                                        new SubsidyPlan(
                                                                "p", "FIXED", "20.00", null, null,
                                                                null)),
                                                List.of(new PlatformAssignment("PDD", "p", 2))))
                        .toList();
        return billionForm(from, through, cards);
    }

    private PolicyDraftRequest billionForm(String from, String through, List<Card> cards) {
        return new PolicyDraftRequest(
                null,
                "BILLION",
                List.of("PDD"),
                null,
                null,
                null,
                from + "T00:00:00+08:00",
                through + "T23:59:59+08:00",
                null,
                List.of(),
                new GoodsForm(cards));
    }

    private static CurrentUserResponse user(int id, String... roleCodes) {
        return new CurrentUserResponse(
                Integer.toString(id),
                "open-" + id,
                "company",
                "同名",
                java.util.List.of(roleCodes),
                java.util.List.of(),
                null);
    }
}
