package com.company.policy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.mapper.PolicyProductCardMapper;
import com.company.policy.service.FeishuTenantTokenService;
import com.company.policy.service.PolicyDeliveryAttemptGuard;
import com.company.policy.service.PolicyFeishuSender;
import com.company.policy.service.PolicyFeishuSender.Delivery;
import com.company.policy.service.PolicyFeishuSender.Failure;
import com.company.policy.service.PolicyFeishuSender.Kind;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 仅在回环地址模拟平台的成功、限频、丢回执和错误响应。 */
class FeishuWorkflowClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final FeishuProperties config = new FeishuProperties();
    private final FeishuTenantTokenService tokens = mock(FeishuTenantTokenService.class);
    private final Queue<Answer> answers = new ConcurrentLinkedQueue<>();
    private final Queue<Request> requests = new ConcurrentLinkedQueue<>();
    private HttpServer server;
    private FeishuWorkflowClientImpl client;
    private static final String KEY = "53951bf1-5690-4961-a326-37bf941bfbee";
    private static final Runnable VALID_LEASE = () -> {};
    private static final String LINK =
            "https://erp.example.test/#/admin/policies?policyId=7&version=2";

    @BeforeEach
    void startMock() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        requests.add(
                                new Request(
                                        exchange.getRequestMethod(),
                                        exchange.getRequestURI().toString(),
                                        exchange.getRequestHeaders().getFirst("Authorization"),
                                        json.readTree(exchange.getRequestBody())));
                        var answer = answers.remove();
                        byte[] bytes = answer.body.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(answer.status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
        server.start();
        when(tokens.getTenantAccessToken()).thenReturn("mock-token");
        config.setAppId("cli_test");
        config.setTenantKey("test-company");
        client =
                new FeishuWorkflowClientImpl(
                        HttpClient.newHttpClient(),
                        json,
                        tokens,
                        config,
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void closeServer() {
        server.stop(0);
    }

    @Test
    void sendsInteractiveCardsToEachOpenIdAndUpdatesOriginalReceipt() throws Exception {
        var card = json.readTree("{\"schema\":\"2.0\",\"body\":{\"elements\":[]}}");
        reply(200, "{\"code\":0,\"data\":{\"message_id\":\"om_a\"}}");
        reply(200, "{\"code\":0,\"data\":{\"message_id\":\"om_b\"}}");
        reply(200, "{\"code\":0,\"data\":{}}");
        assertThat(client.sendCard("ou_a", card, KEY, VALID_LEASE)).isEqualTo("om_a");
        assertThat(client.sendCard("ou_b", card, "second-key", VALID_LEASE)).isEqualTo("om_b");
        assertThat(client.updateCard("om_a", card)).isEqualTo("om_a");
        var first = requests.remove();
        assertThat(first.path).isEqualTo("/open-apis/im/v1/messages?receive_id_type=open_id");
        assertThat(first.authorization).isEqualTo("Bearer mock-token");
        assertThat(first.body.path("msg_type").asText()).isEqualTo("interactive");
        assertThat(first.body.path("uuid").asText()).isEqualTo(KEY);
        assertThat(first.body.path("receive_id").asText()).isEqualTo("ou_a");
        assertThat(json.readTree(first.body.path("content").textValue())).isEqualTo(card);
        assertThat(requests.remove().body.path("receive_id").asText()).isEqualTo("ou_b");
        var patch = requests.remove();
        assertThat(patch.method).isEqualTo("PATCH");
        assertThat(patch.path).isEqualTo("/open-apis/im/v1/messages/om_a");
        assertThat(json.readTree(patch.body.path("content").textValue())).isEqualTo(card);
    }

    @Test
    void postsRealDeduplicatedMentionsAndReusesUuidAfterMissingReceipt() throws Exception {
        var recipients = List.of("ou_a", "ou_b", "ou_a");
        String summary = "ZC-000007，张某初审通过";
        members(List.of("ou_a"), true, "page/2+");
        members(List.of("ou_b"), true, "unused-page");
        reply(200, "{\"code\":0,\"data\":{}}");
        members(List.of("ou_a", "ou_b"), false, "");
        reply(200, "{\"code\":0,\"data\":{\"message_id\":\"om_group\"}}");
        assertFailure(
                Kind.UNKNOWN,
                () -> client.sendGroup("oc_group", summary, recipients, LINK, KEY, VALID_LEASE));
        assertThat(client.sendGroup("oc_group", summary, recipients, LINK, KEY, VALID_LEASE))
                .isEqualTo("om_group");
        assertThat(requests.remove().path)
                .isEqualTo(
                        "/open-apis/im/v1/chats/oc_group/members?member_id_type=open_id&page_size=100");
        assertThat(requests.remove().path).endsWith("&page_token=page%2F2%2B");
        var first = requests.remove();
        assertThat(requests.remove().method).isEqualTo("GET");
        assertThat(requests.remove().body).isEqualTo(first.body);
        assertThat(first.path).isEqualTo("/open-apis/im/v1/messages?receive_id_type=chat_id");
        assertThat(first.body.path("receive_id").asText()).isEqualTo("oc_group");
        assertThat(first.body.path("msg_type").asText()).isEqualTo("post");
        assertThat(first.body.path("uuid").asText()).isEqualTo(KEY);
        assertThat(first.body.path("content").asText()).doesNotContain(KEY, "投递编号");
        var rows = json.readTree(first.body.path("content").textValue()).at("/zh_cn/content");
        assertThat(rows.size()).isEqualTo(3);
        assertThat(rows.at("/0/0/text").asText()).isEqualTo(summary);
        assertThat(rows.path(1).findValuesAsText("tag")).containsExactly("at", "at");
        assertThat(rows.path(1).findValuesAsText("user_id")).containsExactly("ou_a", "ou_b");
        assertThat(rows.at("/2/0/href").asText()).isEqualTo(LINK);
    }

    @Test
    void createsIndividualTaskWithStableClientTokenAndThreeErpLinks() {
        reply(503, "unknown");
        reply(200, "{\"code\":0,\"data\":{\"task\":{\"guid\":\"guid-a\",\"task_id\":\"t71\"}}}");
        assertFailure(
                Kind.UNKNOWN,
                () -> client.createTask("ou_a", "ZC-000007 待终审", LINK, KEY, VALID_LEASE));
        assertThat(client.createTask("ou_a", "ZC-000007 待终审", LINK, KEY, VALID_LEASE))
                .isEqualTo("guid-a");
        var first = requests.remove();
        assertThat(first.path).isEqualTo("/open-apis/task/v2/tasks?user_id_type=open_id");
        assertThat(requests.remove().body).isEqualTo(first.body);
        assertThat(first.body.path("client_token").asText()).isEqualTo(KEY);
        assertThat(first.body.path("extra").asText()).isEqualTo(KEY);
        assertThat(first.body.path("members")).hasSize(1);
        assertThat(first.body.at("/members/0/id").asText()).isEqualTo("ou_a");
        assertThat(first.body.at("/members/0/role").asText()).isEqualTo("assignee");
        assertThat(first.body.at("/members/0/type").asText()).isEqualTo("user");
        assertThat(first.body.at("/origin/href/url").asText()).isEqualTo(LINK);
        for (var device : List.of("pc", "ios", "android")) {
            assertThat(first.body.at("/custom_complete/" + device + "/href").asText())
                    .isEqualTo(LINK);
        }
    }

    @Test
    void closesByGuidAndRecoversAfterPreviousCompletion() {
        var time = Instant.parse("2026-11-24T01:02:03Z");
        String done = "{\"code\":0,\"data\":{\"task\":{\"guid\":\"guid-a\",\"status\":\"done\"}}}";
        reply(200, done);
        assertThat(client.closeTask("guid-a", "同组他人已处理", time)).isEqualTo("guid-a");
        var first = requests.remove();
        assertThat(first.path).isEqualTo("/open-apis/task/v2/tasks/guid-a?user_id_type=open_id");
        assertThat(first.method).isEqualTo("PATCH");
        assertThat(first.body.at("/task/completed_at").asText())
                .isEqualTo(String.valueOf(time.toEpochMilli()));
        assertThat(first.body.at("/task/description").asText()).isEqualTo("同组他人已处理");
        assertThat(first.body.path("update_fields").toString())
                .isEqualTo("[\"description\",\"completed_at\"]");
        reply(400, "{\"code\":1470400}");
        reply(200, done);
        reply(200, done);
        assertThat(client.closeTask("guid-a", "同组他人已处理", time)).isEqualTo("guid-a");
        assertThat(requests.remove().method).isEqualTo("PATCH");
        assertThat(requests.remove().method).isEqualTo("GET");
        assertThat(requests.remove().body.at("/task/completed_at").isMissingNode()).isTrue();
    }

    @Test
    void separatesDefiniteFailureFromAmbiguousWritesAndRefreshesRejectedToken() {
        var card = json.createObjectNode();
        reply(400, "{\"code\":230049}");
        reply(429, "retry");
        reply(200, "{\"code\":99991663}");
        reply(403, "{\"code\":99991672,\"msg\":\"sensitive-response\"}");
        reply(200, "malformed response");
        assertFailure(Kind.UNKNOWN, () -> client.sendCard("ou_a", card, KEY, VALID_LEASE));
        assertFailure(Kind.RETRYABLE, () -> client.sendCard("ou_a", card, KEY, VALID_LEASE));
        assertFailure(Kind.RETRYABLE, () -> client.sendCard("ou_a", card, KEY, VALID_LEASE));
        verify(tokens).invalidateTenantAccessToken("mock-token");
        assertFailure(Kind.PERMANENT, () -> client.sendCard("ou_a", card, KEY, VALID_LEASE));
        assertFailure(Kind.UNKNOWN, () -> client.sendCard("ou_a", card, KEY, VALID_LEASE));
    }

    @Test
    void malformedReceiptNeverBecomesAPlatformPath() {
        assertFailure(
                Kind.PERMANENT, () -> client.updateCard("../another", json.createObjectNode()));
        assertThat(requests).isEmpty();
    }

    @Test
    void measuresEscapedUtf8EnvelopeBeforePostingOversizedCard() {
        var card = json.createObjectNode().put("text", "机".repeat(10_000));
        assertFailure(Kind.PERMANENT, () -> client.sendCard("ou_a", card, KEY, VALID_LEASE));
        assertThat(requests).isEmpty();
    }

    @Test
    void reconcilesGroupOnlyWithOriginalSenderChatContentAndTime() throws Exception {
        members(List.of("ou_a"), false, "");
        reply(200, "{\"code\":0,\"data\":{\"message_id\":\"om_group\"}}");
        client.sendGroup("oc_group", "初审通过", List.of("ou_a"), LINK, KEY, VALID_LEASE);
        assertThat(requests.remove().method).isEqualTo("GET");
        var content = requests.remove().body.path("content").asText();
        var message = platformMessage("post", "om_group", content);
        var fingerprint =
                PolicyDeliveryFingerprint.compute(
                        json,
                        "cli_test",
                        "GROUP_MESSAGE",
                        "oc_group",
                        List.of("初审通过", List.of("ou_a"), LINK));
        var sentAt = LocalDateTime.of(2026, 1, 1, 8, 0);
        reply(200, message);
        assertThat(client.verifyGroupReceipt("om_group", "oc_group", KEY, fingerprint, sentAt))
                .isEqualTo(sentAt);
        for (String bad :
                List.of(
                        message.replace("oc_group", "oc_other"),
                        message.replace("初审通过", "初审退回"),
                        message.replace("1767225600000", "1767232800000"),
                        message.replace("cli_test", "cli_else"),
                        message.replace("test-company", "other-company"))) {
            reply(200, bad);
            assertFailure(
                    Kind.PERMANENT,
                    () ->
                            client.verifyGroupReceipt(
                                    "om_group", "oc_group", KEY, fingerprint, sentAt));
        }
    }

    @Test
    void acceptsOnlyTheMatchingFooterOnHistoricalMessages() throws Exception {
        var old = json.createObjectNode();
        var rows = old.putArray("content");
        rows.addArray().addObject().put("tag", "text").put("text", "初审通过");
        rows.addArray().addObject().put("tag", "text").put("text", "投递编号 " + KEY);
        var message = platformMessage("post", "om_old", old.toString());
        reply(200, message);
        assertThat(client.verifyGroupReceipt("om_old", "oc_group", KEY, null, null)).isNull();
        reply(200, message);
        assertFailure(
                Kind.PERMANENT,
                () -> client.verifyGroupReceipt("om_old", "oc_group", "other", null, null));
    }

    @Test
    void noticeWithoutLinkHasOnlyItsBusinessTextAndUsesTheRequestUuid() throws Exception {
        reply(200, "{\"code\":0,\"data\":{\"message_id\":\"om_platform\"}}");
        client.sendGroup("oc_group", "平台负责人已变更", List.of(), null, KEY, VALID_LEASE);
        var request = requests.remove().body;
        assertThat(request.path("uuid").asText()).isEqualTo(KEY);
        assertThat(json.readTree(request.path("content").asText()).at("/zh_cn/content"))
                .isEqualTo(json.readTree("[[{\"tag\":\"text\",\"text\":\"平台负责人已变更\"}]]"));
    }

    @Test
    void absentGroupMemberStopsOnlyGroupPostAndIndividualTaskCanStillBeCreated() throws Exception {
        members(List.of("ou_present"), false, "");
        assertThatThrownBy(
                        () ->
                                client.sendGroup(
                                        "oc_group",
                                        "初审通过",
                                        List.of("ou_missing"),
                                        LINK,
                                        KEY,
                                        VALID_LEASE))
                .isInstanceOfSatisfying(
                        Failure.class,
                        e -> {
                            assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
                            assertThat(e.getMessage()).contains("未加入业务群", "ou_missing");
                        });
        reply(200, "{\"code\":0,\"data\":{\"task\":{\"guid\":\"guid-independent\"}}}");
        assertThat(client.createTask("ou_missing", "终审", LINK, "task-key", VALID_LEASE))
                .isEqualTo("guid-independent");
        assertThat(requests.remove().method).isEqualTo("GET");
        assertThat(requests.remove().path).startsWith("/open-apis/task/");
        assertThat(requests).isEmpty();
    }

    @Test
    void memberLookupFailureNeverIssuesPostAndReadTimeoutRemainsRetryable() {
        reply(403, "{\"code\":99991672}");
        assertThatThrownBy(
                        () ->
                                client.sendGroup(
                                        "oc_group",
                                        "初审通过",
                                        List.of("ou_a"),
                                        LINK,
                                        KEY,
                                        VALID_LEASE))
                .isInstanceOfSatisfying(
                        Failure.class,
                        e -> {
                            assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
                            assertThat(e.getMessage()).contains("im:chat.members:read", "99991672");
                        });
        reply(503, "unavailable");
        assertFailure(
                Kind.RETRYABLE,
                () ->
                        client.sendGroup(
                                "oc_group", "初审通过", List.of("ou_a"), LINK, KEY, VALID_LEASE));
        assertThat(requests).hasSize(2).allMatch(req -> req.method.equals("GET"));
    }

    @Test
    void tokenFetchCanInvalidateLeaseBeforePrivateCardOrTaskIsWritten() {
        var leaseActive = new AtomicBoolean(true);
        when(tokens.getTenantAccessToken())
                .thenAnswer(
                        call -> {
                            leaseActive.set(false);
                            return "mock-token";
                        });
        Runnable leaseCheck =
                () -> {
                    if (!leaseActive.get()) throw new Failure(Kind.RETRYABLE, "租约已失效");
                };
        assertFailure(
                Kind.RETRYABLE,
                () -> client.sendCard("ou_a", json.createObjectNode(), KEY, leaseCheck));
        leaseActive.set(true);
        assertFailure(
                Kind.RETRYABLE, () -> client.createTask("ou_a", "采购终审", LINK, KEY, leaseCheck));
        assertThat(requests).isEmpty();
    }

    @Test
    void expiredLeaseAfterReadingBothMemberPagesPreventsGroupPost() throws Exception {
        members(List.of("ou_a"), true, "next-page");
        members(List.of("ou_b"), false, "");
        Runnable expiredLease =
                () -> {
                    assertThat(requests)
                            .hasSize(2)
                            .allMatch(request -> "GET".equals(request.method));
                    throw new Failure(Kind.RETRYABLE, "租约已失效");
                };
        assertThatThrownBy(
                        () ->
                                client.sendGroup(
                                        "oc_group",
                                        "初审通过",
                                        List.of("ou_a", "ou_b"),
                                        LINK,
                                        KEY,
                                        expiredLease))
                .isInstanceOf(Failure.class)
                .hasMessage("租约已失效");
        assertThat(requests).hasSize(2).allMatch(request -> "GET".equals(request.method));
    }

    private void members(List<String> found, boolean hasMore, String token) throws Exception {
        var items =
                found.stream()
                        .map(id -> Map.of("member_id", id, "member_id_type", "open_id"))
                        .toList();
        reply(
                200,
                json.writeValueAsString(
                        Map.of(
                                "code",
                                0,
                                "data",
                                Map.of("items", items, "has_more", hasMore, "page_token", token))));
    }

    @Test
    void reconcilesPrivateCardUsingOriginalRecipientsBoundCallbackAndRound() throws Exception {
        var card =
                json.valueToTree(
                        Map.of(
                                "body",
                                Map.of(
                                        "elements",
                                        List.of(
                                                Map.of(
                                                        "tag",
                                                        "button",
                                                        "behaviors",
                                                        List.of(
                                                                Map.of(
                                                                        "type",
                                                                        "callback",
                                                                        "value",
                                                                        Map.of(
                                                                                "policyId",
                                                                                "7",
                                                                                "round",
                                                                                2,
                                                                                "memberId",
                                                                                "51"))))))));
        reply(200, "{\"code\":0,\"data\":{\"message_id\":\"om_card\"}}");
        client.sendCard("ou_a", card, KEY, VALID_LEASE);
        var message =
                platformMessage(
                        "interactive", "om_card", requests.remove().body.path("content").asText());
        reply(200, message);
        client.verifyReceipt("SEND_CARD", "om_card", "ou_a", KEY, 51, 7, 2);
        reply(200, message);
        assertFailure(
                Kind.PERMANENT,
                () -> client.verifyReceipt("SEND_CARD", "om_card", "ou_b", KEY, 51, 7, 2));
        reply(200, message);
        assertFailure(
                Kind.PERMANENT,
                () -> client.verifyReceipt("SEND_CARD", "om_card", "ou_a", KEY, 52, 7, 2));
        reply(200, message);
        assertFailure(
                Kind.PERMANENT,
                () -> client.verifyReceipt("SEND_CARD", "om_card", "ou_a", KEY, 51, 7, 3));
        assertThat(requests.peek().path).endsWith("?card_msg_content_type=user_card_content");
    }

    private static final String LAN = "http://erp.internal.test:5173";
    private static final String TEST_TENANT = "test-company";

    private Delivery productDelivery() {
        return new Delivery(
                81,
                TEST_TENANT,
                7,
                2,
                "SUBMITTED",
                "SEND_CARD",
                51,
                "PRODUCT",
                "ou_a",
                "ACTIVE",
                1,
                json.createObjectNode(),
                null,
                KEY,
                LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    private PolicyFeishuSender productCardSender() {
        config.setTenantKey(TEST_TENANT);
        config.setAllowedOrigins(List.of(LAN));
        config.setPolicyErpUrl(LAN);
        var mapper = mock(PolicyProductCardMapper.class);
        when(mapper.content(TEST_TENANT, 7, 2, 51))
                .thenReturn(
                        new PolicyProductCardMapper.Content(
                                "ACTIVE",
                                null,
                                null,
                                null,
                                1,
                                1,
                                true,
                                """
                                {"requiresProductReview":true,"changes":[],"adjustments":[{"policyCode":"ZC-000041",
                                 "target":{"policyId":"41","auditLogId":"11","previousEnd":"2026-12-22T00:00:00+08:00"},
                                 "endTime":"2026-12-20T00:00:00+08:00","extension":false,"accompanying":true}]}
                                """,
                                """
                                {"submissionVersion":2,"consent":{"endAdjustments":[{"target":{"policyId":"41","auditLogId":"11",
                                 "previousEnd":"2026-12-22T00:00:00+08:00"},"endTime":"2026-12-20T00:00:00+08:00","reason":"渠道提前结束"}]}}
                                """));
        return new PolicyFeishuSenderImpl(
                client,
                config,
                new PolicyProductCardBuilder(mapper, json, new PolicySnapshotCodec()),
                null,
                mock(PolicyDeliveryAttemptGuard.class),
                json,
                new JsSdkSignatureServiceImpl(config, null, null),
                null);
    }

    @Test
    void sendsSingleNetworkCardWithBoundErpLinkAndVerifiableReceipt() throws Exception {
        var sender = productCardSender();
        config.setPolicyCallbackMode(FeishuProperties.PolicyCallbackMode.LONG_CONNECTION);
        config.setPolicyErpUrl(LAN + "/erp");
        reply(200, "{\"code\":0,\"data\":{\"message_id\":\"om_trial\"}}");
        assertThat(sender.send(productDelivery())).isEqualTo("om_trial");
        var body = requests.remove().body;
        assertThat(body.path("receive_id").asText()).isEqualTo("ou_a");
        assertThat(body.path("uuid").asText()).isEqualTo(KEY);
        var rawCard = body.path("content").asText();
        var card = json.readTree(rawCard);
        assertThat(rawCard)
                .contains("渠道提前结束", "ZC-000041", "查看本轮详情", "callback", "rejection")
                .doesNotContain("前往 ERP 办理", "待我审核", "投递核对编号");
        assertThat(card.findValues("value"))
                .extracting(value -> value.path("decision").asText())
                .containsExactlyInAnyOrder("APPROVE", "REJECT");
        assertThat(card.findValues("value"))
                .allMatch(
                        value ->
                                KEY.equals(value.path("deliveryKey").asText())
                                        && "ou_a".equals(value.path("recipientOpenId").asText()));
        assertThat(card.findValuesAsText("default_url"))
                .containsExactly(LAN + "/erp/#/reviews/7?round=2");
        var message = platformMessage("interactive", "om_trial", rawCard);
        reply(200, message);
        client.verifyReceipt("SEND_CARD", "om_trial", "ou_a", KEY, 51, 7, 2);
        reply(200, message);
        assertFailure(
                Kind.PERMANENT,
                () -> client.verifyReceipt("SEND_CARD", "om_trial", "ou_b", KEY, 51, 7, 2));
        reply(200, message);
        assertFailure(
                Kind.PERMANENT,
                () -> client.verifyReceipt("SEND_CARD", "om_trial", "ou_a", KEY, 52, 7, 2));
        reply(200, message);
        assertFailure(
                Kind.PERMANENT,
                () -> client.verifyReceipt("SEND_CARD", "om_trial", "ou_a", KEY, 51, 7, 3));
    }

    @Test
    void httpLinksRequireListedOriginsAndLongConnectionMode() {
        var sender = productCardSender();
        var item = productDelivery();
        assertFailure(Kind.PERMANENT, () -> sender.send(item));
        config.setPolicyCallbackMode(FeishuProperties.PolicyCallbackMode.LONG_CONNECTION);
        for (var badRoot :
                List.of(
                        "http://erp.internal.test:5174",
                        "http://erp.internal.test.example:5173",
                        "http://user@erp.internal.test:5173",
                        LAN + "?send=1",
                        LAN + "/#page",
                        "file:///erp",
                        "")) {
            config.setPolicyErpUrl(badRoot);
            assertFailure(Kind.PERMANENT, () -> sender.send(item));
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void reconcilesTaskOnlyWhenApplicationAssigneeAndBusinessKeyAgree() throws Exception {
        var task =
                Map.of(
                        "guid",
                        "guid-a",
                        "creator",
                        Map.of("type", "app", "id", "cli_test"),
                        "extra",
                        KEY,
                        "members",
                        List.of(Map.of("role", "assignee", "type", "user", "id", "ou_a")));
        var reply = json.writeValueAsString(Map.of("code", 0, "data", Map.of("task", task)));
        reply(200, reply);
        client.verifyReceipt("CREATE_TASK", "guid-a", "ou_a", KEY, 51, 7, 2);
        for (String bad :
                List.of(
                        reply.replace("assignee", "follower"),
                        reply.replace(KEY, "wrong-key"),
                        reply.replace("ou_a", "ou_other"),
                        reply.replace("cli_test", "other-app"))) {
            reply(200, bad);
            assertFailure(
                    Kind.PERMANENT,
                    () -> client.verifyReceipt("CREATE_TASK", "guid-a", "ou_a", KEY, 51, 7, 2));
        }
    }

    private String platformMessage(String type, String id, String content) throws Exception {
        var sender =
                Map.of(
                        "sender_type",
                        "app",
                        "id_type",
                        "app_id",
                        "id",
                        "cli_test",
                        "tenant_key",
                        "test-company");
        var message =
                Map.of(
                        "message_id",
                        id,
                        "msg_type",
                        type,
                        "create_time",
                        "1767225600000",
                        "sender",
                        sender,
                        "body",
                        Map.of("content", content),
                        "chat_id",
                        "oc_group");
        return json.writeValueAsString(
                Map.of("code", 0, "data", Map.of("items", List.of(message))));
    }

    private void assertFailure(Kind kind, Runnable send) {
        assertThatThrownBy(send::run)
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> {
                            assertThat(failure.kind()).isEqualTo(kind);
                            assertThat(failure.getMessage())
                                    .doesNotContain("sensitive-response", "mock-token");
                        });
    }

    private void reply(int status, String body) {
        answers.add(new Answer(status, body));
    }

    private record Answer(int status, String body) {}

    private record Request(String method, String path, String authorization, JsonNode body) {}
}
