package com.company.policy.service.impl;

import com.company.policy.service.PolicyFeishuSender.Failure;
import com.company.policy.service.PolicyFeishuSender.Kind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 构建群公告富文本，按飞书实际接收的完整消息请求计算字节数。 */
final class FeishuGroupPost {
    static final int MAX_MESSAGE_BYTES = 30_000;

    private FeishuGroupPost() {}

    static boolean fits(
            ObjectMapper json,
            String chatId,
            String summary,
            List<String> mentionOpenIds,
            String uuid) {
        var content = content(json, summary, mentionOpenIds, "");
        try {
            return json.writeValueAsBytes(messageRequest(chatId, "post", content, uuid)).length
                    <= MAX_MESSAGE_BYTES;
        } catch (JsonProcessingException invalid) {
            throw new Failure(Kind.PERMANENT, "飞书投递内容无法编码");
        }
    }

    static JsonNode content(
            ObjectMapper json, String summary, List<String> mentionOpenIds, String detailsUrl) {
        var content = json.createObjectNode();
        var rows = content.putObject("zh_cn").putArray("content");
        for (String section : summary.split("\n\n(?=【)", -1))
            rows.addArray().addObject().put("tag", "text").put("text", section);
        if (!mentionOpenIds.isEmpty()) {
            var people = rows.addArray();
            for (String id : new LinkedHashSet<>(mentionOpenIds)) {
                people.addObject().put("tag", "at").put("user_id", id);
            }
        }
        if (!detailsUrl.isEmpty())
            rows.addArray()
                    .addObject()
                    .put("tag", "a")
                    .put("text", "查看本轮政策")
                    .put("href", detailsUrl);
        return content;
    }

    static Map<String, String> messageRequest(
            String recipient, String messageType, JsonNode content, String uuid) {
        return Map.of(
                "receive_id",
                recipient,
                "msg_type",
                messageType,
                "content",
                content.toString(),
                "uuid",
                uuid);
    }
}
