package com.company.policy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyGroupReceiptTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LocalDateTime first = LocalDateTime.of(2026, 11, 24, 9, 0, 0, 800_000_000);
    private final String url = "https://erp.example.test/#/apply/submissions";

    @Test
    void comparesUnwrappedPostWithOrderedMentionPlaceholdersAndExactLink() throws Exception {
        var post =
                (ObjectNode)
                        json.readTree(
                                """
                {"title":"","content":[[{"tag":"text","text":"原申请人和接手人"}],
                [{"tag":"at","user_id":"@_user_2","user_name":"乙"},{"tag":"at","user_id":"@_user_1"}],
                [{"tag":"a","text":"打开我的提交","href":"https://erp.example.test/#/apply/submissions"}]]}
                """);
        var message = messageAt(first);
        message.set(
                "mentions",
                json.readTree(
                        """
                [{"key":"@_user_1","id":"ou_a","id_type":"open_id"},
                 {"key":"@_user_2","id":"ou_b","id_type":"open_id"}]
                """));
        var expected = digest("原申请人和接手人", List.of("ou_b", "ou_a"), url);
        assertThat(matches(message, post, expected)).isTrue();
        assertThat(matches(message, post, digest("原申请人和接手人", List.of("ou_a", "ou_b"), url)))
                .isFalse();
        assertThat(matches(message, post, digest("原申请人和接手人", List.of("ou_b", "ou_a"), "")))
                .isFalse();
        ((ObjectNode) post.at("/content/2/0")).put("text", "其他详情");
        assertThat(matches(message, post, expected)).isFalse();
    }

    @Test
    void acceptsOriginalChineseContentWithNoLinkAndRejectsUnresolvedOrConflictingMentions()
            throws Exception {
        var post =
                json.readTree(
                        """
                {"zh_cn":{"content":[[{"tag":"text","text":"平台交接"}],[{"tag":"at","user_id":"ou_a"}]]}}
                """);
        var message = messageAt(first);
        var expected = digest("平台交接", List.of("ou_a"), "");
        assertThat(matches(message, post, expected)).isTrue();
        assertThat(matches(message, post, digest("另一条交接", List.of("ou_a"), ""))).isFalse();
        ((ObjectNode) post.at("/zh_cn/content/1/0")).put("user_id", "@_user_1");
        assertThat(matches(message, post, expected)).isFalse();
        message.set(
                "mentions",
                json.readTree(
                        """
                [{"key":"@_user_1","id":"ou_a","id_type":"open_id"},
                 {"key":"@_user_1","id":"ou_b","id_type":"open_id"}]
                """));
        assertThat(matches(message, post, expected)).isFalse();
    }

    @Test
    void rejectsEditsAndUnrecognizedVisibleContent() throws Exception {
        var post =
                (ObjectNode)
                        json.readTree(
                                """
                {"content":[[{"tag":"text","text":"政策已截止"}]]}
                """);
        var message = messageAt(first);
        var expected = digest("政策已截止", List.of(), "");
        assertThat(matches(message, post, expected)).isTrue();
        message.put("updated", true);
        assertThat(matches(message, post, expected)).isFalse();
        message.put("updated", false);
        post.put("title", "额外标题");
        assertThat(matches(message, post, expected)).isFalse();
        post.remove("title");
        post.withArray("content").addArray().addObject().put("tag", "text").put("text", "额外内容");
        assertThat(matches(message, post, expected)).isFalse();
    }

    @Test
    void usesBeijingMessageTimeAndTheGroupRetryWindow() {
        assertThat(PolicyGroupReceipt.messageTime(messageAt(first))).isEqualTo(first);
        assertThat(PolicyGroupReceipt.messageTime(json.createObjectNode().put("create_time", "坏值")))
                .isNull();
        assertThat(PolicyGroupReceipt.inWindow(first, null)).isFalse();
        assertThat(PolicyGroupReceipt.inWindow(first, first.withNano(0))).isTrue();
        assertThat(PolicyGroupReceipt.inWindow(first, first.withNano(0).minusNanos(1))).isFalse();
        assertThat(PolicyGroupReceipt.inWindow(first, first.plusMinutes(55).minusNanos(1)))
                .isTrue();
        assertThat(PolicyGroupReceipt.inWindow(first, first.plusMinutes(55))).isFalse();
    }

    private ObjectNode messageAt(LocalDateTime at) {
        long millis = at.toInstant(ZoneOffset.ofHours(8)).toEpochMilli();
        return json.createObjectNode().put("create_time", Long.toString(millis));
    }

    private String digest(String summary, List<String> mentions, String href) {
        return PolicyDeliveryFingerprint.compute(
                json, "app", "GROUP_MESSAGE", "oc_group", List.of(summary, mentions, href));
    }

    private boolean matches(JsonNode message, JsonNode post, String fingerprint) {
        return PolicyGroupReceipt.matches(
                json, "app", "oc_group", message, post, fingerprint, first);
    }
}
