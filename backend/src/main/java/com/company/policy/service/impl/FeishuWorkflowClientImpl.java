package com.company.policy.service.impl;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.service.FeishuTenantTokenService;
import com.company.policy.service.FeishuWorkflowClient;
import com.company.policy.service.PolicyFeishuSender.Failure;
import com.company.policy.service.PolicyFeishuSender.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FeishuWorkflowClientImpl implements FeishuWorkflowClient {
    private static final String MESSAGES = "/open-apis/im/v1/messages";
    private static final String TASKS = "/open-apis/task/v2/tasks";
    private static final String OPEN_ID = "?user_id_type=open_id";

    private final HttpClient http;
    private final ObjectMapper json;
    private final FeishuTenantTokenService tokens;
    private final FeishuProperties config;
    private final URI endpoint;

    @Autowired
    public FeishuWorkflowClientImpl(
            HttpClient http,
            ObjectMapper json,
            FeishuTenantTokenService tokens,
            FeishuProperties config) {
        this(http, json, tokens, config, URI.create("https://open.feishu.cn"));
    }

    FeishuWorkflowClientImpl(
            HttpClient http,
            ObjectMapper json,
            FeishuTenantTokenService tokens,
            FeishuProperties config,
            URI endpoint) {
        this.http = http;
        this.json = json;
        this.tokens = tokens;
        this.config = config;
        this.endpoint = endpoint;
    }

    @Override
    public String sendCard(
            String openId, JsonNode card, String idempotencyKey, Runnable beforePost) {
        return sendMessage(
                "open_id",
                openId,
                "interactive",
                boundCard(card, openId, idempotencyKey),
                idempotencyKey,
                beforePost);
    }

    private JsonNode boundCard(JsonNode card, String openId, String idempotencyKey) {
        JsonNode copy = card.deepCopy();
        for (var value : copy.findValues("value")) {
            if (value instanceof ObjectNode action
                    && action.has("memberId")
                    && action.has("policyId")) {
                action.put("deliveryKey", idempotencyKey).put("recipientOpenId", openId);
            }
        }
        return copy;
    }

    @Override
    public String updateCard(String messageId, JsonNode card) {
        var body = Map.of("content", card.toString());
        call("PATCH", MESSAGES + "/" + pathId(messageId), body).data();
        return messageId;
    }

    @Override
    public String sendGroup(
            String chatId,
            String summary,
            List<String> mentionOpenIds,
            String detailUrl,
            String idempotencyKey,
            Runnable beforePost) {
        var content = json.createObjectNode();
        var rows = content.putObject("zh_cn").putArray("content");
        rows.addArray().addObject().put("tag", "text").put("text", summary);
        if (!mentionOpenIds.isEmpty()) {
            var people = rows.addArray();
            for (String id : new LinkedHashSet<>(mentionOpenIds)) {
                if (id == null || !id.matches("ou_[A-Za-z0-9_-]{1,128}")) {
                    throw new Failure(Kind.PERMANENT, "缺少有效的飞书收件人");
                }
                people.addObject().put("tag", "at").put("user_id", id);
            }
            verifyGroupMembers(chatId, mentionOpenIds);
        }
        if (detailUrl != null && !detailUrl.isBlank()) {
            rows.addArray()
                    .addObject()
                    .put("tag", "a")
                    .put("text", detailUrl.endsWith("#/apply/submissions") ? "打开我的提交" : "查看本轮政策")
                    .put("href", detailUrl);
        }
        return sendMessage("chat_id", chatId, "post", content, idempotencyKey, beforePost);
    }

    private void verifyGroupMembers(String chatId, List<String> recipients) {
        var missing = new LinkedHashSet<>(recipients);
        var pages = new HashSet<String>();
        String base =
                "/open-apis/im/v1/chats/"
                        + pathId(chatId)
                        + "/members?member_id_type=open_id&page_size=100";
        String path = base;
        do {
            JsonNode data;
            try {
                data = call("GET", path, null).data();
            } catch (Failure failure) {
                // 尚未发送群消息，读取超时可以直接重试。只提示脱敏业务码及必要权限。
                Kind kind = failure.kind() == Kind.PERMANENT ? Kind.PERMANENT : Kind.RETRYABLE;
                throw new Failure(
                        kind,
                        "未能核对业务群成员，请确认机器人在群内且有 im:chat.members:read 权限。" + failure.getMessage());
            }
            if (!data.path("items").isArray() || !data.path("has_more").isBoolean()) {
                throw new Failure(Kind.RETRYABLE, "飞书未返回完整的群成员分页状态");
            }
            for (JsonNode member : data.path("items")) {
                missing.remove(member.path("member_id").asText());
            }
            if (missing.isEmpty()) return;
            if (!data.path("has_more").booleanValue()) {
                var sample = String.join("、", missing.stream().limit(3).toList());
                throw new Failure(
                        Kind.PERMANENT, "有 " + missing.size() + " 位收件人未加入业务群，请邀请入群后补发。" + sample);
            }
            String next = data.path("page_token").asText("");
            if (next.isBlank() || !pages.add(next)) {
                throw new Failure(Kind.RETRYABLE, "飞书群成员分页游标异常，请稍后重试");
            }
            path = base + "&page_token=" + URLEncoder.encode(next, StandardCharsets.UTF_8);
        } while (true);
    }

    private String sendMessage(
            String recipientType,
            String recipient,
            String messageType,
            JsonNode content,
            String idempotencyKey,
            Runnable beforePost) {
        var body =
                Map.of(
                        "receive_id",
                        recipient,
                        "msg_type",
                        messageType,
                        "content",
                        content.toString(),
                        "uuid",
                        idempotencyKey);
        JsonNode data =
                call("POST", MESSAGES + "?receive_id_type=" + recipientType, body, beforePost)
                        .data();
        return receipt(data.path("message_id"));
    }

    @Override
    public String createTask(
            String openId,
            String summary,
            String detailUrl,
            String idempotencyKey,
            Runnable beforePost) {
        var link = Map.of("href", detailUrl);
        var body =
                Map.of(
                        "summary",
                        summary,
                        "client_token",
                        idempotencyKey,
                        "extra",
                        idempotencyKey,
                        "members",
                        List.of(Map.of("type", "user", "role", "assignee", "id", openId)),
                        "origin",
                        Map.of(
                                "platform_i18n_name",
                                Map.of("zh_cn", "差异化政策管理系统"),
                                "href",
                                Map.of("url", detailUrl, "title", "查看本轮政策")),
                        "custom_complete",
                        Map.of("pc", link, "ios", link, "android", link));
        JsonNode data = call("POST", TASKS + OPEN_ID, body, beforePost).data();
        return receipt(data.at("/task/guid"));
    }

    @Override
    public String closeTask(String guid, String outcome, Instant completedAt) {
        String path = TASKS + "/" + pathId(guid) + OPEN_ID;
        var changes =
                Map.of(
                        "description",
                        outcome,
                        "completed_at",
                        String.valueOf(completedAt.toEpochMilli()));
        var response =
                call(
                        "PATCH",
                        path,
                        Map.of(
                                "task",
                                changes,
                                "update_fields",
                                List.of("description", "completed_at")));
        // 平台对重复结办返回通用参数错误。按 GUID 回读成功状态，再补齐 ERP 办理结论。
        if (response.code() == 1470400) {
            var task = call("GET", path, null).data().path("task");
            if (guid.equals(task.path("guid").asText())
                    && "done".equals(task.path("status").asText())) {
                if (outcome.equals(task.path("description").asText())) {
                    return guid;
                }
                response =
                        call(
                                "PATCH",
                                path,
                                Map.of(
                                        "task",
                                        Map.of("description", outcome),
                                        "update_fields",
                                        List.of("description")));
            }
        }
        var task = response.data().path("task");
        if (!guid.equals(receipt(task.path("guid")))
                || !"done".equals(task.path("status").asText())) {
            throw new Failure(Kind.UNKNOWN, "飞书任务结办状态待核对");
        }
        return guid;
    }

    private String pathId(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9_-]{1,128}")) {
            throw new Failure(Kind.PERMANENT, "外部回执格式无效");
        }
        return value;
    }

    @Override
    public void verifyReceipt(
            String action,
            String receipt,
            String recipient,
            String idempotencyKey,
            long assignmentId,
            long policyId,
            int version) {
        if (recipient == null
                || recipient.isBlank()
                || idempotencyKey == null
                || idempotencyKey.isBlank()) {
            throw new Failure(Kind.PERMANENT, "投递收件人或业务标识缺失");
        }
        String id = pathId(receipt);
        boolean verified;
        if ("CREATE_TASK".equals(action)) {
            JsonNode task = call("GET", TASKS + "/" + id + OPEN_ID, null).data().path("task");
            verified =
                    id.equals(task.path("guid").asText())
                            && config.getAppId().equals(task.at("/creator/id").asText())
                            && "app".equals(task.at("/creator/type").asText())
                            && idempotencyKey.equals(task.path("extra").asText())
                            && hasAssignee(task, recipient);
        } else if ("SEND_CARD".equals(action)) {
            var message = ownMessage(id);
            verified =
                    matchesCard(
                            message,
                            content(message),
                            recipient,
                            idempotencyKey,
                            assignmentId,
                            policyId,
                            version);
        } else {
            throw new Failure(Kind.PERMANENT, "该投递动作不支持补录回执");
        }
        if (!verified) {
            throw new Failure(Kind.PERMANENT, "飞书回执与本应用、本轮投递或收件人不符");
        }
    }

    private JsonNode ownMessage(String id) {
        var message =
                call(
                                "GET",
                                MESSAGES
                                        + "/"
                                        + pathId(id)
                                        + "?card_msg_content_type=user_card_content",
                                null)
                        .data()
                        .at("/items/0");
        if (!id.equals(message.path("message_id").asText())
                || message.path("deleted").asBoolean()
                || !config.getAppId().equals(message.at("/sender/id").asText())
                || !"app".equals(message.at("/sender/sender_type").asText())
                || !"app_id".equals(message.at("/sender/id_type").asText())
                || !config.getTenantKey().equals(message.at("/sender/tenant_key").asText())) {
            throw new Failure(Kind.PERMANENT, "飞书回执与本应用或本企业不符");
        }
        return message;
    }

    private boolean hasAssignee(JsonNode task, String recipient) {
        for (JsonNode member : task.path("members")) {
            if ("assignee".equals(member.path("role").asText())
                    && "user".equals(member.path("type").asText())
                    && recipient.equals(member.path("id").asText())) return true;
        }
        return false;
    }

    private JsonNode content(JsonNode message) {
        try {
            return json.readTree(message.at("/body/content").asText());
        } catch (IOException malformed) {
            return null;
        }
    }

    @Override
    public LocalDateTime verifyGroupReceipt(
            String receipt,
            String chatId,
            String key,
            String fingerprint,
            LocalDateTime firstAttemptAt) {
        var message = ownMessage(receipt);
        var body = content(message);
        if (body != null
                && "post".equals(message.path("msg_type").asText())
                && message.path("chat_id").asText().equals(chatId)) {
            var rows = body.has("zh_cn") ? body.at("/zh_cn/content") : body.path("content");
            var last = rows.path(rows.size() - 1);
            if (key != null
                    && !key.isBlank()
                    && last.size() == 1
                    && "text".equals(last.path(0).path("tag").asText())
                    && ("投递编号 " + key).equals(last.path(0).path("text").asText())) return null;
            if (PolicyGroupReceipt.matches(
                    json, config.getAppId(), chatId, message, body, fingerprint, firstAttemptAt)) {
                return PolicyGroupReceipt.messageTime(message);
            }
        }
        throw new Failure(Kind.PERMANENT, "飞书群消息与本次投递的收件群、内容或发送时间不符");
    }

    private boolean matchesCard(
            JsonNode message,
            JsonNode content,
            String recipient,
            String key,
            long assignmentId,
            long policyId,
            int version) {
        if (content == null || !"interactive".equals(message.path("msg_type").asText()))
            return false;
        for (JsonNode value : content.findValues("value")) {
            if (key.equals(value.path("deliveryKey").asText())
                    && recipient.equals(value.path("recipientOpenId").asText())
                    && Long.toString(assignmentId).equals(value.path("memberId").asText())
                    && Long.toString(policyId).equals(value.path("policyId").asText())
                    && version == value.path("round").asInt(-1)) return true;
        }
        return false;
    }

    private String receipt(JsonNode node) {
        if (!node.isTextual() || !node.textValue().matches("[a-zA-Z0-9_-]{1,128}")) {
            throw new Failure(Kind.UNKNOWN, "飞书未返回有效的外部回执");
        }
        return node.textValue();
    }

    private Reply call(String method, String path, Object body) {
        return call(method, path, body, null);
    }

    private Reply call(String method, String path, Object body, Runnable beforePost) {
        byte[] bytes;
        try {
            bytes = body == null ? null : json.writeValueAsBytes(body);
        } catch (IOException invalid) {
            throw new Failure(Kind.PERMANENT, "飞书投递内容无法编码");
        }
        if (bytes != null && path.startsWith(MESSAGES) && bytes.length > 30_000) {
            throw new Failure(Kind.PERMANENT, "飞书卡片或富文本消息超出 30 KB 限制");
        }
        String token;
        try {
            token = tokens.getTenantAccessToken();
        } catch (ApiException failure) {
            var kind =
                    "FEISHU_NOT_CONFIGURED".equals(failure.getCode())
                            ? Kind.PERMANENT
                            : Kind.RETRYABLE;
            throw new Failure(kind, "无法取得飞书应用凭证");
        }
        try {
            var publisher =
                    bytes == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(bytes);
            var request =
                    HttpRequest.newBuilder(endpoint.resolve(path))
                            .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json; charset=utf-8")
                            .method(method, publisher)
                            .build();
            // 群成员分页及获取凭证都可能耗尽租约。只在即将写飞书时复核本次领取和指纹。
            if ("POST".equals(method)) java.util.Objects.requireNonNull(beforePost).run();
            var response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 429) {
                throw new Failure(Kind.RETRYABLE, "飞书请求限频 HTTP 429");
            }
            if (status == 401) {
                tokens.invalidateTenantAccessToken(token);
                throw new Failure(Kind.RETRYABLE, "飞书应用凭证已失效 HTTP 401");
            }
            if (status >= 500 || status == 408) {
                throw new Failure(Kind.UNKNOWN, "飞书响应待核对 HTTP " + status);
            }
            var payload = json.readTree(response.body());
            if (payload == null
                    || !payload.path("code").isIntegralNumber()
                    || !payload.path("code").canConvertToInt()) {
                throw new Failure(Kind.UNKNOWN, "飞书响应缺少业务状态");
            }
            var reply = new Reply(status, payload.path("code").intValue(), payload.path("data"));
            if (reply.code == 99991663 || reply.code == 99991665) {
                tokens.invalidateTenantAccessToken(token);
                throw new Failure(Kind.RETRYABLE, "飞书应用凭证已失效 " + reply.code);
            }
            return reply;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Failure(Kind.UNKNOWN, "等待飞书响应时中断");
        } catch (IOException unavailable) {
            throw new Failure(Kind.UNKNOWN, "飞书响应未取得，须按原投递项核对");
        }
    }

    private record Reply(int status, int code, JsonNode value) {
        JsonNode data() {
            if (status >= 200 && status < 300 && code == 0) {
                return value;
            }
            Kind kind =
                    switch (code) {
                        case 230020, 99991400 -> Kind.RETRYABLE;
                        case 230049, 1470422, 1470500 -> Kind.UNKNOWN;
                        default -> Kind.PERMANENT;
                    };
            throw new Failure(kind, "飞书返回 HTTP " + status + "，业务码 " + code);
        }
    }
}
