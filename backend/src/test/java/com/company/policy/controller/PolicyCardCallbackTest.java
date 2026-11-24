package com.company.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyReviewRequest;
import com.company.policy.service.PolicyCardCallback;
import com.company.policy.service.PolicyReviewService;
import com.company.policy.service.impl.PolicyProductCardBuilder;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(
        properties = {
            "spring.config.import=",
            "spring.datasource.url=jdbc:h2:mem:private_card;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.sql.init.schema-locations=classpath:user-info-schema.sql,classpath:catalog-import-schema.sql",
            "feishu.tenant-key=card-tenant",
            "feishu.app-id=card-app",
            "feishu.callback-encrypt-key=callback-test-key",
            "feishu.callback-verification-token=test-token"
        })
@AutoConfigureMockMvc
class PolicyCardCallbackTest {
    private static final AtomicLong IDS = new AtomicLong(5000);
    private static final String TENANT = "card-tenant";
    private static final String URL = "/callbacks/feishu/policy-review";
    private static final String KEY = "callback-test-key";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PolicyProductCardBuilder builder;
    @Autowired PolicyCardCallback callbacks;
    @Autowired PolicyReviewService reviews;
    long id, first, second, firstMember, secondMember;

    @BeforeEach
    void prepare() {
        id = IDS.incrementAndGet();
        long owner = actor("APPLICANT");
        first = actor("APPLICANT");
        second = actor("APPLICANT");
        jdbc.update(
                "INSERT INTO policy_apply(id,tenant_key,policy_no,applicant_id,applicant_name,channel_type,status,current_version,record_version) VALUES(?,?,?,?,'原申请人','RETAIL','PENDING',1,2)",
                id,
                TENANT,
                "TEST" + id,
                owner);
        jdbc.update(
                "INSERT INTO policy_audit_log(tenant_key,policy_id,version,snapshot_data) VALUES(?,?,1,?)",
                TENANT,
                id,
                """
            {"submissionVersion":1,"consent":{"endAdjustments":[{"target":{"policyId":"41","auditLogId":"11","previousEnd":"2026-12-22T00:00:00+08:00"},
            "endTime":"2026-12-20T00:00:00+08:00","reason":"渠道提前结束"}]}}
            """);
        jdbc.update(
                "INSERT INTO policy_review_round(tenant_key,policy_id,version,settings_version,stage,route_json) VALUES(?,?,1,1,'PRODUCT_REVIEW',?)",
                TENANT,
                id,
                """
            {"requiresProductReview":true,"changes":[],"adjustments":[{"policyCode":"ZC-000041",
            "target":{"policyId":"41","auditLogId":"11","previousEnd":"2026-12-22T00:00:00+08:00"},
            "endTime":"2026-12-20T00:00:00+08:00","extension":false,"accompanying":true}]}
            """);
        firstMember = assign(first, "PRODUCT");
        secondMember = assign(second, "PRODUCT");
        assign(actor("ADMIN"), "PURCHASE");
        for (long member : List.of(firstMember, secondMember)) {
            jdbc.update(
                    "INSERT INTO policy_review_delivery(tenant_key,policy_id,version,event_key,action_type,recipient_key,payload_json,delivery_status,receipt_id) VALUES(?,?,1,'SUBMITTED','SEND_CARD',?,'{}','SENT',?)",
                    TENANT,
                    id,
                    member,
                    messageId(member));
        }
    }

    private long actor(String role) {
        long user = IDS.incrementAndGet();
        jdbc.update(
                "INSERT INTO user_info(id,tenant_key,open_id,name,role_type) VALUES(?,?,?,?,?)",
                user,
                TENANT,
                "ou_" + user,
                "姓名" + user,
                role);
        return user;
    }

    private long assign(long user, String group) {
        long member = IDS.incrementAndGet();
        jdbc.update(
                "INSERT INTO policy_review_member(id,tenant_key,policy_id,version,group_code,user_id,display_name) VALUES(?,?,?,1,?,?,?)",
                member,
                TENANT,
                id,
                group,
                user,
                "姓名" + user);
        return member;
    }

    private String messageId(long member) {
        return "om_" + member;
    }

    private JsonNode card(long member) {
        return json.valueToTree(
                builder.build(
                        TENANT, id, 1, member, "https://erp.example/#/reviews/" + id + "?round=1"));
    }

    private ObjectNode event(long user, long member, String decision, String reason) {
        var value =
                card(member).findValues("value").stream()
                        .filter(v -> v.path("decision").asText().equals(decision))
                        .findFirst()
                        .orElseThrow();
        return json.valueToTree(
                Map.of(
                        "schema",
                        "2.0",
                        "header",
                        Map.of(
                                "token",
                                "test-token",
                                "tenant_key",
                                TENANT,
                                "app_id",
                                "card-app",
                                "event_type",
                                "card.action.trigger"),
                        "event",
                        Map.of(
                                "host",
                                "im_message",
                                "operator",
                                Map.of("open_id", "ou_" + user, "tenant_key", TENANT),
                                "context",
                                Map.of("open_message_id", messageId(member)),
                                "action",
                                Map.of(
                                        "tag",
                                        "button",
                                        "value",
                                        value,
                                        "form_value",
                                        Map.of("reason", reason)))));
    }

    private ObjectNode segment() {
        var product =
                Map.of(
                        "goodsId",
                        "8",
                        "spuId",
                        "2",
                        "modelName",
                        "Reno",
                        "colorName",
                        "星夜黑",
                        "ram",
                        "8GB",
                        "rom",
                        "128GB");
        var selection =
                Map.of(
                        "cardType",
                        "NORMAL",
                        "products",
                        List.of(product),
                        "subsidy",
                        Map.of("subsidyMode", "FIXED", "fixedAmount", "25.00"));
        var before =
                Map.of(
                        "policyId",
                        "77",
                        "policyCode",
                        "ZC-000077",
                        "submissionVersion",
                        1,
                        "selections",
                        List.of(selection));
        var after =
                Map.of(
                        "policyId",
                        "88",
                        "policyCode",
                        "ZC-000088",
                        "submissionVersion",
                        2,
                        "selections",
                        List.of(selection));
        var segment =
                json.createObjectNode()
                        .put("perspective", "RETAIL")
                        .put("kind", "REPLACEMENT")
                        .put("from", "2026-12-01T00:00:00")
                        .put("through", "2026-12-04T23:59:59");
        segment.set("scope", json.valueToTree(Map.of("platformCode", "JD", "goodsId", "8")));
        segment.set("before", json.valueToTree(List.of(before)));
        segment.set("after", json.valueToTree(List.of(after)));
        segment.set("added", json.valueToTree(List.of(after)));
        segment.set("removed", json.valueToTree(List.of(before)));
        segment.set("candidates", json.valueToTree(List.of(before, after)));
        return segment;
    }

    @Test
    void segmentShowsFrozenTimeProductAndBothPolicyLists() throws Exception {
        var route =
                Map.of(
                        "requiresProductReview",
                        true,
                        "changes",
                        List.of(segment()),
                        "adjustments",
                        List.of());
        jdbc.update(
                "UPDATE policy_review_round SET route_json=? WHERE policy_id=?",
                json.writeValueAsString(route),
                id);
        assertThat(card(firstMember).toString())
                .contains(
                        "京东零售",
                        "Reno",
                        "星夜黑",
                        "12-01",
                        "12-04",
                        "原采用：ZC-000077",
                        "拟采用：ZC-000088",
                        "25 元/台");
    }

    @Test
    void largeRoundKeepsBothSummariesAndErpLinkWithinEncodedMessageAndElementLimits()
            throws Exception {
        var segments = json.createArrayNode();
        var adjustments = json.createArrayNode();
        var reasons = json.createArrayNode();
        var normal = segment();
        var longSegment = normal.deepCopy();
        var longName = "商品·" + "🟩\"\\\n\u0002".repeat(100);
        ((ObjectNode) longSegment.at("/before/0/selections/0/products/0"))
                .put("modelName", longName);
        ((ObjectNode) longSegment.at("/after/0/selections/0/products/0"))
                .put("modelName", longName);
        for (String key : List.of("before", "after")) {
            var list = longSegment.withArray(key);
            for (int i = 0; i < 180; i++) {
                ObjectNode item = list.get(0).deepCopy();
                item.put("policyCode", "ZC-" + (900000 + i));
                list.add(item);
            }
        }
        for (int i = 0; i < 205; i++) {
            segments.add(i < 3 ? longSegment : normal);
            var target =
                    Map.of(
                            "policyId",
                            Integer.toString(i + 42),
                            "auditLogId",
                            "11",
                            "previousEnd",
                            "2026-12-22T00:00:00+08:00");
            adjustments.add(
                    json.valueToTree(
                            Map.of(
                                    "target",
                                    target,
                                    "policyCode",
                                    "ZC-" + (i + 42),
                                    "endTime",
                                    "2026-12-20T00:00:00+08:00",
                                    "accompanying",
                                    true)));
            reasons.add(
                    json.valueToTree(
                            Map.of(
                                    "target",
                                    target,
                                    "endTime",
                                    "2026-12-20T00:00:00+08:00",
                                    "reason",
                                    longName)));
        }
        jdbc.update(
                "UPDATE policy_review_round SET route_json=? WHERE policy_id=?",
                json.writeValueAsString(
                        Map.of(
                                "changes",
                                segments,
                                "adjustments",
                                adjustments,
                                "requiresProductReview",
                                true)),
                id);
        jdbc.update(
                "UPDATE policy_audit_log SET snapshot_data=? WHERE policy_id=?",
                json.writeValueAsString(
                        Map.of(
                                "submissionVersion",
                                1,
                                "consent",
                                Map.of("endAdjustments", reasons))),
                id);
        var output = card(firstMember);
        String openId = "ou_" + "a".repeat(64);
        String uuid = "b".repeat(50);
        for (var value : output.findValues("value")) {
            ((ObjectNode) value).put("deliveryKey", uuid).put("recipientOpenId", openId);
        }
        var request =
                Map.of(
                        "receive_id",
                        openId,
                        "uuid",
                        uuid,
                        "msg_type",
                        "interactive",
                        "content",
                        output.toString());
        assertThat(json.writeValueAsBytes(request).length).isLessThanOrEqualTo(30_000);
        assertThat(output.findValues("tag").size()).isLessThan(200);
        assertThat(output.toString())
                .contains(
                        "2026-12-01",
                        "原采用：ZC-000077",
                        "拟采用：ZC-000088",
                        "原因：商品",
                        "其余 202 段",
                        "其余 202 项调整",
                        "…（详见本轮详情）",
                        "https://erp.example/#/reviews/" + id + "?round=1");
    }

    @Test
    void rejectionNeedsValidReasonAndOnlyDecidingCardShowsOwnResult() throws Exception {
        var initialCard = card(firstMember);
        assertThat(initialCard.toString())
                .contains(
                        "ZC-000041",
                        "2026-12-22",
                        "2026-12-20",
                        "渠道提前结束",
                        "form_action_type",
                        "PRODUCT_REVIEW");
        var approve = event(second, secondMember, "APPROVE", "");
        var invalid = event(first, firstMember, "REJECT", "  ");
        postCallback(invalid).andExpect(jsonPath("$.toast.content").value("请填写初审退回原因。"));
        ((ObjectNode) invalid.at("/event/action/form_value")).put("reason", "字".repeat(513));
        postCallback(invalid).andExpect(jsonPath("$.toast.type").value("error"));
        ((ObjectNode) invalid.at("/event/action/form_value")).put("reason", 42);
        postCallback(invalid).andExpect(jsonPath("$.toast.type").value("error"));
        assertThat(count("policy_review_decision")).isZero();
        var reject = event(first, firstMember, "REJECT", "  核对补贴  ");
        postCallback(reject).andExpect(jsonPath("$.toast.type").value("success"));
        postCallback(reject).andExpect(jsonPath("$.toast.type").value("info"));
        postCallback(approve).andExpect(jsonPath("$.toast.content").value("同组他人已处理"));
        assertThat(count("policy_review_decision")).isOne();
        assertThat(card(firstMember).toString())
                .contains("姓名" + first, "核对补贴")
                .doesNotContain("form_action_type", "callback");
        assertThat(card(secondMember).toString())
                .contains("同组他人已处理")
                .doesNotContain("form_action_type", "callback");
    }

    @Test
    void longConnectionUsesTheSameDecisionAndReceiptsAsTheHttpCallback() throws Exception {
        var reject = event(first, firstMember, "REJECT", "重新核对截止时间");
        var peer = event(second, secondMember, "APPROVE", "");
        ((ObjectNode) reject.path("header")).remove("token");
        // 通过 SDK 主动连接收到的原始信封仍不能直接提交到公开的 HTTP 地址。
        mvc.perform(
                        post(URL)
                                .contentType("application/json")
                                .content(json.writeValueAsBytes(reject)))
                .andExpect(status().isForbidden());
        postCallback(reject).andExpect(status().isForbidden());

        var forwarded = reject.deepCopy();
        ((ObjectNode) forwarded.at("/event/operator")).put("open_id", "ou_" + second);
        var unavailable =
                Map.of("toast", Map.of("type", "error", "content", "此卡当前无法办理，请核对最新的飞书审核卡。"));
        assertThat(callbacks.handleLongConnection(forwarded)).isEqualTo(unavailable);
        var stale = reject.deepCopy();
        ((ObjectNode) stale.at("/event/action/value")).put("round", 2);
        assertThat(callbacks.handleLongConnection(stale)).isEqualTo(unavailable);
        assertThat(count("policy_review_decision")).isZero();

        assertThat(callbacks.handleLongConnection(reject))
                .isEqualTo(Map.of("toast", Map.of("type", "success", "content", "已退回原申请人")));
        assertThat(callbacks.handleLongConnection(reject))
                .isEqualTo(Map.of("toast", Map.of("type", "info", "content", "本轮初审已退回")));
        postCallback(peer).andExpect(jsonPath("$.toast.content").value("同组他人已处理"));
        assertThat(count("policy_review_decision")).isOne();
    }

    @Test
    void longConnectionRequiresMatchingAppOperatorTenantAndCardEvent() {
        var event = event(first, firstMember, "APPROVE", "");
        var wrongOperatorTenant = event.deepCopy();
        ((ObjectNode) wrongOperatorTenant.at("/event/operator")).put("tenant_key", "foreign");
        var wrongTenant = event.deepCopy();
        ((ObjectNode) wrongTenant.path("header")).put("tenant_key", "foreign");
        var wrongApp = event.deepCopy();
        ((ObjectNode) wrongApp.path("header")).put("app_id", "other-app");
        var wrongEvent = event.deepCopy();
        ((ObjectNode) wrongEvent.path("header")).put("event_type", "im.message.receive_v1");
        for (var wrong :
                List.of(
                        wrongOperatorTenant,
                        wrongTenant,
                        wrongApp,
                        wrongEvent,
                        event.deepCopy().put("schema", "1.0"))) {
            assertThatExceptionOfType(ApiException.class)
                    .isThrownBy(() -> callbacks.handleLongConnection(wrong))
                    .satisfies(
                            error ->
                                    assertThat(error.getCode())
                                            .isEqualTo("FEISHU_CALLBACK_DENIED"));
        }
        assertThat(count("policy_review_decision")).isZero();
    }

    @Test
    void callbackVerifiesEncryptedEnvelopeAndChallengeWithoutBusinessLogin() throws Exception {
        var challenge =
                json.valueToTree(
                        Map.of(
                                "type",
                                "url_verification",
                                "challenge",
                                "verify-me",
                                "token",
                                "test-token"));
        var raw = encrypt(challenge);
        mvc.perform(post(URL).contentType("application/json").content(raw))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("verify-me"));
        var event = event(first, firstMember, "APPROVE", "");
        mvc.perform(post(URL).contentType("application/json").content(encrypt(event)))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(URL)
                                .contentType("application/json")
                                .content(json.writeValueAsBytes(event)))
                .andExpect(status().isForbidden());
        send(event, Instant.now().getEpochSecond(), "wrong-key").andExpect(status().isForbidden());
        send(event, Instant.now().getEpochSecond() - 310, KEY).andExpect(status().isForbidden());
        ((ObjectNode) event.at("/event/operator")).put("tenant_key", "foreign");
        postCallback(event).andExpect(status().isForbidden());
        ((ObjectNode) event.at("/event/operator")).put("tenant_key", TENANT);
        ((ObjectNode) event.path("header")).put("app_id", "another-app");
        postCallback(event).andExpect(status().isForbidden());
        ((ObjectNode) event.path("header"))
                .put("app_id", "card-app")
                .put("event_type", "task.task.updated_v1");
        postCallback(event).andExpect(status().isForbidden());
        assertThat(count("policy_review_decision")).isZero();
    }

    @Test
    void versionTwoUrlHandshakeUsesHeaderTokenWithoutRequiringActionSignature() throws Exception {
        var challenge =
                json.createObjectNode()
                        .put("schema", "2.0")
                        .put("type", "url_verification")
                        .put("challenge", "verify-address");
        challenge.putObject("header").put("token", "test-token");
        mvc.perform(post(URL).contentType("application/json").content(encrypt(challenge)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("verify-address"));
        // 不能通过顶层 token 覆盖新版信封中的错误 token。
        challenge.put("token", "test-token");
        ((ObjectNode) challenge.path("header")).put("token", "other");
        mvc.perform(post(URL).contentType("application/json").content(encrypt(challenge)))
                .andExpect(status().isForbidden());
        assertThat(count("policy_review_decision")).isZero();
    }

    @Test
    void rejectsForwardedWrongReceiptInactiveAccountAndRemovedAssignmentButKeepsPeerCard()
            throws Exception {
        var removed = event(first, firstMember, "APPROVE", "");
        var kept = event(second, secondMember, "APPROVE", "");
        var wrong = removed.deepCopy();
        ((ObjectNode) wrong.at("/event/operator")).put("open_id", "ou_" + second);
        postCallback(wrong).andExpect(jsonPath("$.toast.type").value("error"));
        wrong = removed.deepCopy();
        ((ObjectNode) wrong.at("/event/context")).put("open_message_id", messageId(secondMember));
        postCallback(wrong).andExpect(jsonPath("$.toast.type").value("error"));
        jdbc.update("UPDATE user_info SET is_deleted=1 WHERE id=?", first);
        postCallback(removed).andExpect(jsonPath("$.toast.type").value("error"));
        jdbc.update("UPDATE user_info SET is_deleted=0 WHERE id=?", first);
        jdbc.update(
                "UPDATE policy_review_member SET member_status='REMOVED' WHERE id=?", firstMember);
        jdbc.update("UPDATE policy_apply SET record_version=3 WHERE id=?", id);
        postCallback(removed).andExpect(jsonPath("$.toast.type").value("error"));
        assertThat(card(firstMember).toString()).contains("已撤销").doesNotContain("form_action_type");
        postCallback(kept).andExpect(jsonPath("$.toast.type").value("success"));
        assertThat(count("policy_review_decision")).isOne();
    }

    @Test
    void wrongRoundOrFutureVersionCannotHandle() throws Exception {
        var action = event(first, firstMember, "APPROVE", "");
        var pending = (ObjectNode) action.at("/event/action/value");
        pending.put("round", 2);
        postCallback(action).andExpect(jsonPath("$.toast.type").value("error"));
        pending.put("round", 1).put("recordVersion", 6);
        postCallback(action).andExpect(jsonPath("$.toast.type").value("error"));
        pending.put("recordVersion", 2);
        jdbc.update("UPDATE policy_apply SET current_version=2,record_version=4 WHERE id=?", id);
        postCallback(action).andExpect(jsonPath("$.toast.type").value("error"));
        assertThat(count("policy_review_decision")).isZero();
    }

    @Test
    void twoOppositeCardDecisionsAndErpCompeteForOneDecision() throws Exception {
        var approve = event(first, firstMember, "APPROVE", "");
        var reject = event(second, secondMember, "REJECT", "需修改");
        var gate = new CountDownLatch(1);
        var threadPool = Executors.newFixedThreadPool(3);
        try {
            var yes =
                    threadPool.submit(
                            () -> {
                                gate.await();
                                postCallback(approve).andExpect(status().isOk());
                                return null;
                            });
            var no =
                    threadPool.submit(
                            () -> {
                                gate.await();
                                postCallback(reject).andExpect(status().isOk());
                                return null;
                            });
            var erp =
                    threadPool.submit(
                            () -> {
                                gate.await();
                                var actor =
                                        new CurrentUserResponse(
                                                Long.toString(first),
                                                "ou_" + first,
                                                TENANT,
                                                "姓名" + first,
                                                "APPLICANT",
                                                null);
                                try {
                                    reviews.productReview(
                                            actor,
                                            id,
                                            new PolicyReviewRequest(2, 1, "REJECT", "站内审核"));
                                } catch (ApiException e) {
                                    assertThat(e.getCode())
                                            .isIn(
                                                    "POLICY_VERSION_CONFLICT",
                                                    "POLICY_REVIEW_FORBIDDEN");
                                }
                                return null;
                            });
            gate.countDown();
            for (var future : List.of(yes, no, erp)) future.get(15, TimeUnit.SECONDS);
        } finally {
            threadPool.shutdownNow();
        }
        assertThat(count("policy_review_decision")).isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_review_delivery WHERE policy_id=? AND event_key='PRODUCT_DECIDED' AND action_type='GROUP_MESSAGE'",
                                Integer.class,
                                id))
                .isOne();
    }

    private int count(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE policy_id=?", Integer.class, id);
    }

    private ResultActions postCallback(JsonNode event) throws Exception {
        return send(event, Instant.now().getEpochSecond(), KEY);
    }

    private ResultActions send(JsonNode event, long timestamp, String signingKey) throws Exception {
        byte[] body = encrypt(event);
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update((timestamp + "fixed-nonce" + signingKey).getBytes(StandardCharsets.UTF_8));
        String signature = HexFormat.of().formatHex(digest.digest(body));
        return mvc.perform(
                post(URL)
                        .contentType("application/json")
                        .content(body)
                        .header("X-Lark-Request-Timestamp", Long.toString(timestamp))
                        .header("X-Lark-Request-Nonce", "fixed-nonce")
                        .header("X-Lark-Signature", signature));
    }

    private byte[] encrypt(JsonNode value) throws Exception {
        var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        var iv = new byte[16];
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(
                        MessageDigest.getInstance("SHA-256")
                                .digest(KEY.getBytes(StandardCharsets.UTF_8)),
                        "AES"),
                new IvParameterSpec(iv));
        var data = cipher.doFinal(json.writeValueAsBytes(value));
        var result = Arrays.copyOf(iv, iv.length + data.length);
        System.arraycopy(data, 0, result, iv.length, data.length);
        return json.writeValueAsBytes(
                Map.of("encrypt", Base64.getEncoder().encodeToString(result)));
    }
}
