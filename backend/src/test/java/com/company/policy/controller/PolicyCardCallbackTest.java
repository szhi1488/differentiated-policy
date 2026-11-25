package com.company.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyReviewRequest;
import com.company.policy.mapper.PolicyProductCardMapper;
import com.company.policy.service.PolicyCardCallback;
import com.company.policy.service.PolicyReviewService;
import com.company.policy.service.impl.PolicyProductCardBuilder;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        return json.valueToTree(builder.build(TENANT, id, 1, member));
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
        var before = policyForCard(77, fixed("25.00"), null);
        var after = policyForCard(88, fixed("25.00"), null).put("submissionVersion", 2);
        after.remove("verifyType");
        var segment = segmentScope().put("kind", "REPLACEMENT");
        segment.set("before", json.valueToTree(List.of(before)));
        segment.set("after", json.valueToTree(List.of(after)));
        segment.set("added", json.valueToTree(List.of(after)));
        segment.set("removed", json.valueToTree(List.of(before)));
        segment.set("candidates", json.valueToTree(List.of(before, after)));
        return segment;
    }

    private ObjectNode segmentScope() {
        var segment =
                json.createObjectNode()
                        .put("perspective", "RETAIL")
                        .put("from", "2026-12-01T00:00:00")
                        .put("through", "2026-12-04T23:59:59");
        segment.set("scope", json.valueToTree(Map.of("platformCode", "JD", "goodsId", "8")));
        return segment;
    }

    private ObjectNode policyForCard(long policyId, JsonNode subsidy, Integer quantity) {
        var policy =
                json.createObjectNode()
                        .put("policyId", Long.toString(policyId))
                        .put("policyCode", "ZC-%06d".formatted(policyId))
                        .put("submissionVersion", 1)
                        .put("verifyType", "SALES")
                        .put("proposed", policyId == id);
        var selection =
                policy.putArray("selections")
                        .addObject()
                        .put("cardType", quantity == null ? "COMMON" : "SPECIFIED");
        selection
                .putArray("products")
                .addObject()
                .put("goodsId", "8")
                .put("spuId", "2")
                .put("modelName", "Reno")
                .put("colorName", "星夜黑")
                .put("ram", "8GB")
                .put("rom", "128GB");
        if (quantity != null) selection.put("quantity", quantity);
        selection.set("subsidy", subsidy);
        return policy;
    }

    private ObjectNode stackedSegment(
            List<ObjectNode> before, List<ObjectNode> after, List<ObjectNode> candidates) {
        var segment = segmentScope().put("matchingRoundGoods", true);
        segment.put("kind", before.equals(after) ? "UNCHANGED" : "STACK");
        segment.set("before", json.valueToTree(before));
        segment.set("after", json.valueToTree(after));
        segment.set("candidates", json.valueToTree(candidates));
        segment.set(
                "added",
                json.valueToTree(after.stream().filter(p -> !before.contains(p)).toList()));
        segment.set(
                "removed",
                json.valueToTree(before.stream().filter(p -> !after.contains(p)).toList()));
        segment.set("retained", json.valueToTree(before.stream().filter(after::contains).toList()));
        return segment;
    }

    private JsonNode fixed(String amount) {
        return json.createObjectNode().put("subsidyMode", "FIXED").put("fixedAmount", amount);
    }

    private JsonNode cardWithSegments(List<ObjectNode> segments) throws Exception {
        jdbc.update(
                "UPDATE policy_review_round SET route_json=? WHERE policy_id=?",
                json.writeValueAsString(Map.of("requiresProductReview", true, "changes", segments)),
                id);
        return card(firstMember);
    }

    private String showSegments(List<ObjectNode> segments) throws Exception {
        return String.join("\n", cardWithSegments(segments).findValuesAsText("content"));
    }

    private static JsonNode element(JsonNode elements, String tag) {
        return StreamSupport.stream(elements.spliterator(), false)
                .filter(node -> tag.equals(node.path("tag").asText()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void kuaishouCardShowsTheFrozenPairInTheSamePeriodAsEachPolicysOwnSubsidy() throws Exception {
        var prior = policyForCard(18, fixed("10.00"), null);
        var current =
                policyForCard(19, fixed("20.00"), null)
                        .put("proposed", true)
                        .put("verifyType", "ACTIVATION");
        var selected = List.of(prior, current);
        for (var policy : selected) {
            policy.putArray("sceneTypes").add("NATIONAL_SUB");
            ((ObjectNode) policy.at("/selections/0")).put("wholeConfigurationGoodsCount", 1);
            ((ObjectNode) policy.at("/selections/0/subsidy")).put("maxSubsidyAmount", "100.00");
            ((ObjectNode) policy.at("/selections/0/products/0"))
                    .put("modelName", "Reno17 Pro")
                    .put("ram", "12GB")
                    .put("rom", "256GB");
        }
        var change = stackedSegment(List.of(prior), selected, selected);
        ((ObjectNode) change.path("scope")).put("platformCode", "KUAISHOU");
        change.put("from", "2026-12-12T00:00:00").put("through", "2026-12-13T00:00:00");
        var mapper = mock(PolicyProductCardMapper.class);
        when(mapper.content(TENANT, 19, 1, firstMember))
                .thenReturn(
                        new PolicyProductCardMapper.Content(
                                "ACTIVE",
                                null,
                                null,
                                null,
                                1,
                                2,
                                true,
                                json.writeValueAsString(
                                        Map.of(
                                                "requiresProductReview",
                                                true,
                                                "changes",
                                                List.of(change))),
                                "{}"));
        var cards = new PolicyProductCardBuilder(mapper, json, new PolicySnapshotCodec());
        JsonNode rendered = json.valueToTree(cards.build(TENANT, 19, 1, firstMember));
        assertThat(rendered.path("schema").asText()).isEqualTo("2.0");
        assertThat(rendered.at("/header/title/content").asText()).contains("政策叠加初审");
        assertThat(rendered.at("/header/subtitle/content").asText()).contains("ZC-000019", "第 1 轮");
        var elements = rendered.at("/body/elements");
        var group = element(elements, "column_set");
        assertThat(group.at("/columns/0/background_style").asText()).isEqualTo("blue-50");
        var visible = group.findValuesAsText("content");
        assertThat(visible.get(0)).isEqualTo("拟叠加执行 · ZC-000018 ＋ ZC-000019");
        assertThat(String.join("\n", visible))
                .contains(
                        "Reno17 Pro 12GB+256GB 整组配置",
                        "快手零售",
                        "2026-12-12 00:00:00",
                        "2026-12-13 00:00:00");
        assertThat(String.join("\n", elements.findValuesAsText("content")))
                .contains(
                        "ZC-000018（原政策）\n国补 · 核销类型：销售维度 · 固定金额 10 元/台，上限 100 元",
                        "ZC-000019（本次申请）\n国补 · 核销类型：激活维度 · 固定金额 20 元/台，上限 100 元");
        var form = element(elements, "form");
        assertThat(form.path("name").asText()).isEqualTo("rejection");
        var formElements = form.path("elements");
        var input = element(formElements, "input");
        assertThat(input.path("name").asText()).isEqualTo("reason");
        assertThat(input.path("required").isBoolean()).isTrue();
        assertThat(input.path("required").asBoolean()).isFalse();
        assertThat(input.path("max_length").asInt()).isEqualTo(512);
        assertThat(input.at("/label/content").asText()).contains("仅拒绝时必填");
        var actions = element(formElements, "column_set");
        assertThat(actions.path("flex_mode").asText()).isEqualTo("bisect");
        var buttons = actions.path("columns");
        assertThat(buttons.size()).isEqualTo(2);
        for (int i = 0; i < buttons.size(); i++) {
            var button = element(buttons.get(i).path("elements"), "button");
            String name = i == 0 ? "approve" : "reject";
            assertThat(button.path("name").asText()).isEqualTo(name);
            assertThat(button.path("type").asText())
                    .isEqualTo(i == 0 ? "primary_filled" : "danger");
            assertThat(button.path("form_action_type").asText()).isEqualTo("submit");
            assertThat(button.at("/behaviors/0/type").asText()).isEqualTo("callback");
            assertThat(button.at("/behaviors/0/value/decision").asText())
                    .isEqualTo(i == 0 ? "APPROVE" : "REJECT");
        }
        assertThat(rendered.findValuesAsText("type")).doesNotContain("open_url");
        assertThat(elements.get(elements.size() - 1)).isEqualTo(form);
        assertThat(rendered.findValuesAsText("default_url")).isEmpty();
        assertThat(rendered.findValues("value"))
                .allSatisfy(
                        value -> {
                            assertThat(value.path("policyId").asText()).isEqualTo("19");
                            assertThat(value.path("round").asInt()).isOne();
                            assertThat(value.path("memberId").asText())
                                    .isEqualTo(Long.toString(firstMember));
                            assertThat(value.path("node").asText()).isEqualTo("PRODUCT_REVIEW");
                            assertThat(value.path("recordVersion").asInt()).isEqualTo(2);
                        })
                .extracting(value -> value.path("decision").asText())
                .containsExactlyInAnyOrder("APPROVE", "REJECT");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eachSelectedPolicyKeepsItsOwnSubsidyAndQuantity(boolean threePolicies) throws Exception {
        var a = policyForCard(71, fixed("31.25"), null);
        var b = policyForCard(72, fixed("42.75"), 8);
        var ratio =
                json.createObjectNode()
                        .put("subsidyMode", "RATIO")
                        .put("priceBase", "HAND_PRICE")
                        .put("ratio", "5.50")
                        .put("maxSubsidyAmount", "49.25");
        var current = policyForCard(id, ratio, 0).put("verifyType", "ACTIVATION");
        var omitted = policyForCard(73, fixed("99.87"), null);
        a.putArray("sceneTypes").add("NATIONAL_SUB");
        b.putArray("sceneTypes").add("SELF_SUB");
        current.putArray("sceneTypes").add("LOCAL_SUB");
        omitted.putArray("sceneTypes").add("LOCAL_SUB");
        var before = threePolicies ? List.of(a, b) : List.of(a);
        var selected = threePolicies ? List.of(a, b, current) : List.of(a, current);
        var candidates =
                threePolicies ? List.of(a, b, current, omitted) : List.of(a, current, omitted);
        String body = showSegments(List.of(stackedSegment(before, selected, candidates)));
        String adopted = threePolicies ? "ZC-000071 ＋ ZC-000072" : "ZC-000071";
        assertThat(body)
                .contains("拟叠加执行 · " + adopted + " ＋ " + current.path("policyCode").asText())
                .contains("手机配置", "京东", "零售", "2026-12-01 00:00:00", "2026-12-04 23:59:59", "星夜黑")
                .containsOnlyOnce("ZC-000071（原政策）\n国补 · 核销类型：销售维度 · 固定金额 31.25 元/台")
                .containsOnlyOnce(
                        current.path("policyCode").asText()
                                + "（本次申请）\n地补 · 核销类型：激活维度 · 比例补贴 到手价 × 5.5%，上限 49.25 元，数量 0 台")
                .doesNotContain("ZC-000073", "99.87");
        if (threePolicies) {
            assertThat(body)
                    .containsOnlyOnce("ZC-000072（原政策）\n自补 · 核销类型：销售维度 · 固定金额 42.75 元/台，数量 8 台");
        } else {
            assertThat(body).doesNotContain("ZC-000072", "42.75");
        }
    }

    @Test
    void continuingAnExistingStackMarksTheUnselectedApplicationAndKeepsBothApprovedSubsidies()
            throws Exception {
        var a = policyForCard(71, fixed("31.25"), null);
        var b = policyForCard(72, fixed("42.75"), null);
        var current = policyForCard(id, fixed("99.87"), null);
        var selected = List.of(a, b);
        String body =
                showSegments(List.of(stackedSegment(selected, selected, List.of(a, b, current))));
        assertThat(body)
                .contains("继续叠加执行 · ZC-000071 ＋ ZC-000072")
                .contains("本次申请未在本段采用", "ZC-000071（原政策）", "ZC-000072（原政策）", "31.25", "42.75")
                .doesNotContain("99.87");
    }

    @Test
    void accompanyingExtensionShowsItsOwnIdentityAndUnfilledQuantity() throws Exception {
        var a = policyForCard(71, fixed("31.25"), null);
        var extension = policyForCard(81, fixed("21.50"), null);
        extension.put("proposed", true).put("policyId", "extension:" + id + ":81");
        ((ObjectNode) extension.at("/selections/0")).put("cardType", "SPECIFIED");
        var selected = List.of(a, extension);
        String body = showSegments(List.of(stackedSegment(List.of(a), selected, selected)));
        assertThat(body)
                .contains("ZC-000081（本轮随单延期）\n核销类型：销售维度 · 固定金额 21.5 元/台，数量未填写", "本次申请未在本段采用")
                .doesNotContain("数量 0 台");
    }

    @ParameterizedTest
    @ValueSource(strings = {"连续", "空档", "换单", "其他原执行单退出"})
    void accompanyingExtensionRendersTheSelectedOriginalPoliciesForTheirContinuousPeriod(
            String scenario) throws Exception {
        String oldEnd = "2027-01-10T00:00:00";
        String newEnd = "2027-01-16T00:00:00+08:00";
        var original = policyForCard(27, ratio("10", "POLICY_PRICE"), null);
        original.put("submissionVersion", 2).put("auditLogId", "127");
        var application = policyForCard(30, ratio("12", "HAND_PRICE"), null);
        application
                .put("proposed", true)
                .put("submissionVersion", 4)
                .put("verifyType", "ACTIVATION");
        for (var policy : List.of(original, application)) {
            policy.putArray("sceneTypes").add("NATIONAL_SUB");
            ((ObjectNode) policy.at("/selections/0")).put("wholeConfigurationGoodsCount", 1);
            ((ObjectNode) policy.at("/selections/0/products/0"))
                    .put("modelName", "Reno17 Pro")
                    .put("ram", "12GB")
                    .put("rom", "256GB");
        }
        var extension =
                original.deepCopy()
                        .put("policyId", "extension:30:27")
                        .put("policyCode", "ZC-000027（延期）")
                        .put("submissionVersion", 1)
                        .put("proposed", true)
                        .putNull("auditLogId");
        var firstPair = List.of(original, application);
        var other = policyForCard(32, fixed("20"), null);
        boolean otherExited = scenario.equals("其他原执行单退出");
        var firstPeriod =
                stackedSegment(
                                otherExited ? List.of(original, other) : List.of(original),
                                firstPair,
                                otherExited ? List.of(original, application, other) : firstPair)
                        .put("from", "2027-01-01T00:00:00")
                        .put("through", oldEnd);
        var nextPolicy =
                scenario.equals("换单")
                        ? application
                                .deepCopy()
                                .put("policyId", "31")
                                .put("policyCode", "ZC-000031")
                                .put("proposed", false)
                        : application;
        var secondPair = List.of(nextPolicy, extension);
        var secondPeriod =
                stackedSegment(
                                scenario.equals("换单") ? List.of(nextPolicy) : List.of(),
                                secondPair,
                                otherExited ? List.of(nextPolicy, extension, other) : secondPair)
                        .put(
                                "from",
                                scenario.equals("空档")
                                        ? "2027-01-10T00:00:02"
                                        : "2027-01-10T00:00:01")
                        .put("through", "2027-01-13T00:00:00");
        for (var period : List.of(firstPeriod, secondPeriod))
            ((ObjectNode) period.path("scope")).put("platformCode", "KUAISHOU");
        var target =
                Map.of("policyId", "27", "auditLogId", "127", "previousEnd", oldEnd + "+08:00");
        var adjustments =
                List.of(
                        Map.of(
                                "target",
                                target,
                                "policyCode",
                                "ZC-000027",
                                "endTime",
                                newEnd,
                                "extension",
                                true,
                                "accompanying",
                                true));
        var route =
                Map.of(
                        "changes",
                        List.of(firstPeriod, secondPeriod),
                        "adjustments",
                        adjustments,
                        "requiresProductReview",
                        true);
        var snapshot =
                Map.of(
                        "submissionVersion",
                        4,
                        "consent",
                        Map.of(
                                "endAdjustments",
                                List.of(
                                        Map.of(
                                                "target", target, "endTime", newEnd, "reason",
                                                "676"))));
        var mapper = mock(PolicyProductCardMapper.class);
        when(mapper.content(TENANT, 30, 4, firstMember))
                .thenReturn(
                        new PolicyProductCardMapper.Content(
                                "ACTIVE",
                                null,
                                null,
                                null,
                                4,
                                8,
                                true,
                                json.writeValueAsString(route),
                                json.writeValueAsString(snapshot)));
        var cards = new PolicyProductCardBuilder(mapper, json, new PolicySnapshotCodec());
        JsonNode card = json.valueToTree(cards.build(TENANT, 30, 4, firstMember));
        var content = card.at("/body/elements").findValuesAsText("content");
        String body = String.join("\n", content);
        assertThat(body)
                .containsOnlyOnce("随单截止调整")
                .containsOnlyOnce(
                        "ZC-000027 拟将截止从 2027-01-10 00:00:00 延长至 2027-01-16 00:00:00（北京时间）\n原因：676");
        var periods = content.stream().filter(text -> text.contains("快手零售 · ")).toList();
        if (scenario.equals("连续")) {
            assertThat(periods).containsExactly("快手零售 · 2027-01-01 00:00:00 至 2027-01-13 00:00:00");
            assertThat(body)
                    .containsOnlyOnce("拟叠加执行 · ZC-000027 ＋ ZC-000030")
                    .containsOnlyOnce("ZC-000027（原政策）\n国补 · 核销类型：销售维度 · 比例补贴 政策价 × 10%")
                    .containsOnlyOnce("ZC-000030（本次申请）\n国补 · 核销类型：激活维度 · 比例补贴 到手价 × 12%")
                    .doesNotContain("（延期）", "本轮随单延期");
        } else {
            assertThat(periods)
                    .containsExactly(
                            "快手零售 · 2027-01-01 00:00:00 至 2027-01-10 00:00:00",
                            "快手零售 · "
                                    + secondPeriod.path("from").asText().replace('T', ' ')
                                    + " 至 2027-01-13 00:00:00");
            if (scenario.equals("换单")) assertThat(body).contains("ZC-000031");
        }
    }

    private JsonNode ratio(String value, String base) {
        return json.createObjectNode()
                .put("subsidyMode", "RATIO")
                .put("priceBase", base)
                .put("ratio", value);
    }

    @Test
    void previouslyFrozenReplacementCardShowsOriginalTimeProductAndBothPolicyLists()
            throws Exception {
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
                        "退出本段：ZC-000077",
                        "ZC-000088",
                        "核销类型：未记录 · 固定金额 25 元/台")
                .contains("拟执行 · ZC-000088")
                .doesNotContain("拟叠加执行", "继续叠加执行", "政策叠加初审", "整单选择的商品完全相同");
    }

    @Test
    void twoTimeWindowsShowSeparateSelectedCombinationsEvenWithTheSameCandidates()
            throws Exception {
        var a = policyForCard(71, fixed("31.25"), null);
        var b = policyForCard(72, fixed("42.75"), null);
        var current = policyForCard(id, fixed("21.50"), null);
        var all = List.of(a, b, current);
        var firstPeriod = stackedSegment(List.of(a), List.of(a, b), all);
        var secondPeriod =
                stackedSegment(List.of(a), List.of(a, current), all)
                        .put("from", "2026-12-05T00:00:00")
                        .put("through", "2026-12-06T23:59:59");
        var elements = cardWithSegments(List.of(firstPeriod, secondPeriod)).at("/body/elements");
        var periods =
                StreamSupport.stream(elements.spliterator(), false)
                        .filter(node -> "column_set".equals(node.path("tag").asText()))
                        .map(node -> node.findValuesAsText("content"))
                        .filter(texts -> !texts.isEmpty() && texts.get(0).startsWith("拟叠加执行"))
                        .toList();
        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).get(0)).isEqualTo("拟叠加执行 · ZC-000071 ＋ ZC-000072");
        assertThat(periods.get(1).get(0))
                .isEqualTo("拟叠加执行 · ZC-000071 ＋ " + current.path("policyCode").asText());
        String body = String.join("\n", elements.findValuesAsText("content"));
        int secondHeading = body.indexOf(periods.get(1).get(0));
        assertThat(body.substring(0, secondHeading))
                .contains("2026-12-01", "本次申请未在本段采用")
                .doesNotContain(current.path("policyCode").asText(), "21.5");
        assertThat(body.substring(secondHeading))
                .contains("2026-12-05")
                .doesNotContain("ZC-000072", "42.75");
    }

    @Test
    void previouslyFrozenMixedRoundCardShowsOnlySegmentsWithMatchingConfigurations()
            throws Exception {
        var match = segment().put("kind", "STACK").put("matchingConfigurations", true);
        match.withArray("after").add(match.at("/before/0").deepCopy());
        match.withArray("retained").add(match.at("/before/0").deepCopy());
        match.withArray("removed").removeAll();
        var partial =
                match.deepCopy()
                        .put("from", "2026-12-05T00:00:00")
                        .put("through", "2026-12-08T23:59:59")
                        .put("matchingConfigurations", false);
        jdbc.update(
                "UPDATE policy_review_round SET route_json=? WHERE policy_id=?",
                json.writeValueAsString(
                        Map.of("requiresProductReview", true, "changes", List.of(partial, match))),
                id);

        assertThat(card(firstMember).toString())
                .contains("这些政策整单所选的机型配置完全相同", "12-01", "12-04", "ZC-000077", "ZC-000088")
                .doesNotContain("12-05", "12-08", "整单选择的商品完全相同");
    }

    @Test
    void largeRoundKeepsBothSummariesWithinEncodedMessageAndElementLimits() throws Exception {
        var segments = json.createArrayNode();
        var adjustments = json.createArrayNode();
        var reasons = json.createArrayNode();
        var prior = policyForCard(77, fixed("25.50"), null);
        var current = policyForCard(id, fixed("40.75"), null);
        var selected = List.of(prior, current);
        var normal = stackedSegment(List.of(prior), selected, selected);
        var longSegment = normal.deepCopy();
        var longName = "商品·" + "🟩\"\\\n\u0002".repeat(100);
        for (var name : List.of("before", "after", "candidates", "added", "retained")) {
            for (var policy : longSegment.withArray(name)) {
                ((ObjectNode) policy.at("/selections/0/products/0")).put("modelName", longName);
            }
        }
        for (int i = 0; i < 180; i++) {
            ObjectNode item = longSegment.at("/after/0").deepCopy();
            item.put("policyId", Integer.toString(900000 + i))
                    .put("policyCode", "ZC-" + (900000 + i));
            for (var key : List.of("after", "candidates", "added"))
                longSegment.withArray(key).add(item);
        }
        for (int i = 0; i < 205; i++) {
            var day = LocalDate.of(2026, 12, 1).plusDays(i * 2L);
            var item = (i < 3 ? longSegment : normal).deepCopy();
            segments.add(item.put("from", day + "T00:00:00").put("through", day + "T23:59:59"));
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
        var combination =
                element(output.at("/body/elements"), "column_set")
                        .findValuesAsText("content")
                        .get(0);
        var omitted = Pattern.compile("（另有 (\\d+) 张未展开）$").matcher(combination);
        assertThat(omitted.find()).isTrue();
        long visibleNames = Pattern.compile("ZC-\\d+").matcher(combination).results().count();
        assertThat(visibleNames + Integer.parseInt(omitted.group(1))).isEqualTo(182);
        assertThat(output.toString())
                .contains(
                        "2026-12-01",
                        "ZC-000077",
                        "25.5",
                        "张拟采用政策",
                        "原因：商品",
                        "其余 202 组",
                        "其余 202 项调整",
                        "…（已省略）")
                .doesNotContain("本轮详情", "open_url", "default_url");
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

    @ParameterizedTest
    @ValueSource(strings = {"", "不作为退回意见保存"})
    void approvingWithEitherEmptyOrPopulatedReasonNeverWritesARejectionReason(String input)
            throws Exception {
        postCallback(event(first, firstMember, "APPROVE", input))
                .andExpect(jsonPath("$.toast.content").value("初审已通过"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_review_decision WHERE policy_id=? AND version=1 AND group_code='PRODUCT' AND decision='APPROVE' AND reason IS NULL",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT stage FROM policy_review_round WHERE policy_id=? AND version=1",
                                String.class,
                                id))
                .isEqualTo("PURCHASE_REVIEW");
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
                .isZero();
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
