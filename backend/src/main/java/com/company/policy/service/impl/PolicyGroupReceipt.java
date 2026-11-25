package com.company.policy.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** 将飞书回读的富文本还原成群消息首次发送时参与计算摘要的业务内容。 */
final class PolicyGroupReceipt {
    private static final String ACTION = "GROUP_MESSAGE";
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private PolicyGroupReceipt() {}

    static boolean matches(
            ObjectMapper json,
            String appId,
            String chatId,
            JsonNode message,
            JsonNode content,
            String expectedFingerprint,
            LocalDateTime firstAttemptAt) {
        if (expectedFingerprint == null
                || content == null
                || !inWindow(firstAttemptAt, messageTime(message))
                || message.path("updated").asBoolean()) return false;

        // 获取指定消息时正文可以是已选择语言的内容，发送格式则使用 zh_cn 包裹。
        var post = content.has("zh_cn") ? content.path("zh_cn") : content;
        var rows = post.path("content");
        if (!post.path("title").asText("").isEmpty() || !rows.isArray() || rows.isEmpty())
            return false;
        var paragraphs = new StringJoiner("\n\n");
        int nextRow = 0;
        while (nextRow < rows.size()) {
            var row = rows.get(nextRow);
            var entry = row.path(0);
            if (!"text".equals(entry.path("tag").textValue())) break;
            if (!row.isArray() || row.size() != 1 || !entry.path("text").isTextual()) return false;
            paragraphs.add(entry.get("text").textValue());
            nextRow++;
        }
        if (nextRow == 0) return false;
        var mentions = new LinkedHashSet<String>();
        var people = rows.path(nextRow);
        if ("at".equals(people.path(0).path("tag").textValue())) {
            var mentionIds = mentionIds(message);
            if (mentionIds == null) return false;
            for (var person : people) {
                if (!"at".equals(person.path("tag").textValue())) return false;
                var id = person.path("user_id").asText("");
                var openId = mentionIds.getOrDefault(id, id);
                if (!openId.matches("ou_[A-Za-z0-9_-]{1,128}") || !mentions.add(openId))
                    return false;
            }
            nextRow++;
        }
        String url = "";
        if (nextRow < rows.size()) {
            // 改期通知和已有群公告按首次发送的详情链接核对指纹。
            var linkRow = rows.path(nextRow++);
            var link = linkRow.path(0);
            url = link.path("href").asText("");
            if (linkRow.size() != 1 || !"a".equals(link.path("tag").textValue()) || url.isBlank())
                return false;
            String label = url.endsWith("#/apply/submissions") ? "打开我的提交" : "查看本轮政策";
            if (!label.equals(link.path("text").textValue())) return false;
        }
        if (nextRow != rows.size()) return false;
        String actual =
                PolicyDeliveryFingerprint.compute(
                        json,
                        appId,
                        ACTION,
                        chatId,
                        List.of(paragraphs.toString(), List.copyOf(mentions), url));
        return expectedFingerprint.equals(actual);
    }

    private static Map<String, String> mentionIds(JsonNode message) {
        var ids = new HashMap<String, String>();
        // 飞书读取时可能以 @_user_1 等占位符替代 at.user_id；只接受明确的 open_id 映射。
        for (var entry : message.path("mentions")) {
            String key = entry.path("key").asText("");
            String id = entry.path("id").asText("");
            if (!key.matches("@_user_[0-9]+")
                    || !"open_id".equals(entry.path("id_type").textValue())
                    || !id.matches("ou_[A-Za-z0-9_-]{1,128}")
                    || ids.putIfAbsent(key, id) != null) return null;
        }
        return ids;
    }

    static LocalDateTime messageTime(JsonNode message) {
        if (message == null) return null;
        try {
            long millis = Long.parseLong(message.path("create_time").asText());
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), BEIJING);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    static boolean inWindow(LocalDateTime firstAttemptAt, LocalDateTime messageTime) {
        return firstAttemptAt != null
                && messageTime != null
                && !messageTime.isBefore(firstAttemptAt.withNano(0))
                && PolicyDeliveryWorkerImpl.withinWindow(ACTION, firstAttemptAt, messageTime);
    }
}
