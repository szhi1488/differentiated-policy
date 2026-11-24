package com.company.policy.service.impl;

import com.company.policy.common.exception.ApiException;
import com.company.policy.mapper.PolicyDeliveryAdminMapper;
import com.company.policy.mapper.PolicyDeliveryAdminMapper.Row;
import com.company.policy.service.CatalogAccess;
import com.company.policy.service.FeishuWorkflowClient;
import com.company.policy.service.PolicyDeliveryRecovery;
import com.company.policy.service.PolicyFeishuSender;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
class PolicyDeliveryRecoveryImpl implements PolicyDeliveryRecovery {
    private final CatalogAccess access;
    private final PolicyDeliveryAdminMapper mapper;
    private final ObjectMapper json;
    private final FeishuWorkflowClient client;
    private final TransactionTemplate transactions;

    @Override
    public List<Item> list(CurrentUserResponse session, long policyId, int version) {
        var tenant = access.requireUserAdmin(session).getTenantKey();
        if (policyId < 1 || version < 1) throw invalid("请提供有效单号及提交轮次。");
        return mapper.list(tenant, policyId, version).stream()
                .map(row -> item(tenant, row))
                .toList();
    }

    @Override
    public List<Item> notices(CurrentUserResponse session, long before) {
        var tenant = access.requireUserAdmin(session).getTenantKey();
        return mapper.notices(tenant, before).stream().map(row -> item(tenant, row)).toList();
    }

    @Override
    public Item resolve(CurrentUserResponse session, long id, Resolution request) {
        var admin = access.requireUserAdmin(session);
        String tenant = admin.getTenantKey();
        if (request == null
                || request.operation() == null
                || request.reason() == null
                || request.reason().isBlank()
                || request.reason().strip().length() > 512) throw invalid("请填写 1 至 512 字的核对依据。");
        var row = requireCurrent(tenant, id, request);
        boolean delivered = "CONFIRM_RECEIPT".equals(request.operation());
        String receipt = request.receiptId();
        var original = payload(row);
        LocalDateTime groupMessageTime;
        if (delivered) {
            if (!"UNKNOWN".equals(row.deliveryStatus())
                    || !Set.of("CREATE_TASK", "GROUP_MESSAGE", "SEND_CARD")
                            .contains(row.actionType())
                    || receipt == null
                    || !receipt.matches("[A-Za-z0-9_-]{1,128}"))
                throw invalid("此动作需要有效的飞书消息 ID 或任务 GUID。");
            try {
                String recipient = original.path("requestRecipient").asText(null);
                if (recipient == null || recipient.isBlank()) throw invalid("缺少首次请求的收件记录，无法认领该回执。");
                if ("GROUP_MESSAGE".equals(row.actionType())) {
                    groupMessageTime =
                            client.verifyGroupReceipt(
                                    receipt,
                                    recipient,
                                    key(tenant, row),
                                    original.path("requestFingerprint").textValue(),
                                    firstAttempt(original));
                } else {
                    groupMessageTime = null;
                    client.verifyReceipt(
                            row.actionType(),
                            receipt,
                            recipient,
                            key(tenant, row),
                            row.recipientKey(),
                            row.policyId(),
                            row.version());
                }
            } catch (PolicyFeishuSender.Failure error) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "POLICY_RECEIPT_UNVERIFIED", error.getMessage());
            }
        } else {
            groupMessageTime = null;
            if (!"RETRY_CONFIRMED".equals(request.operation())
                    || (receipt != null && !receipt.isBlank())) throw invalid("请选择已核实的处理方式。");
        }
        return transactions.execute(
                tx -> {
                    mapper.lock(tenant, id);
                    // 首先取得投递行锁；本事务随后读取的人员和状态包含等待期间已提交的变化。
                    access.requireUserAdmin(session);
                    var current = requireCurrent(tenant, id, request);
                    var content = payload(current);
                    if (delivered && "GROUP_MESSAGE".equals(current.actionType())) {
                        checkGroupReceipt(tenant, id, original, content, receipt, groupMessageTime);
                    }
                    var now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
                    var audit = content.withArray("manualHistory").addObject();
                    audit.put("operatorId", admin.getId())
                            .put("at", now.toString())
                            .put("operation", request.operation())
                            .put("reason", request.reason().strip())
                            .put("previousStatus", current.deliveryStatus())
                            .put("attempts", current.attempts())
                            .put("previousError", current.lastError());
                    if (delivered) {
                        audit.put("receiptId", receipt);
                        if ("CREATE_TASK".equals(current.actionType())
                                && mapper.attachTask(tenant, current.recipientKey(), receipt) != 1)
                            throw conflict();
                    } else {
                        if ("GROUP_MESSAGE".equals(current.actionType())) {
                            for (String field :
                                    List.of(
                                            "firstAttemptAt",
                                            "requestRecipient",
                                            "requestFingerprint",
                                            "lateReceipt"))
                                if (content.hasNonNull(field)) audit.set(field, content.get(field));
                        }
                        content.remove(
                                List.of(
                                        "firstAttemptAt",
                                        "requestRecipient",
                                        "requestFingerprint",
                                        "lateReceipt"));
                    }
                    mapper.resolve(
                            tenant,
                            id,
                            delivered ? "SENT" : "PENDING",
                            delivered ? current.attempts() : 0,
                            content.toString(),
                            delivered ? receipt : null,
                            delivered ? now : null);
                    return item(tenant, mapper.read(tenant, id));
                });
    }

    private void checkGroupReceipt(
            String tenant,
            long id,
            ObjectNode original,
            ObjectNode current,
            String receipt,
            LocalDateTime created) {
        for (String field : List.of("firstAttemptAt", "requestRecipient", "requestFingerprint"))
            if (!original.path(field).equals(current.path(field))) throw conflict();
        if (current.hasNonNull("lateReceipt")
                && !receipt.equals(current.path("lateReceipt").textValue()))
            throw unverified("该项已有不同的飞书回执，请核对原消息。");
        String fingerprint = current.path("requestFingerprint").textValue();
        if (created != null
                && (fingerprint == null
                        || fingerprint.isBlank()
                        || !PolicyGroupReceipt.inWindow(firstAttempt(current), created)))
            throw unverified("缺少与群消息发送时间吻合的原请求记录。");
        for (var candidate :
                mapper.receiptCandidates(
                        tenant, id, receipt, created == null ? null : fingerprint)) {
            var body = payload(candidate.payloadJson());
            if (receipt.equals(candidate.receiptId())
                    || matchesAttempt(body, receipt, fingerprint, created))
                throw unverified("该消息与其他投递记录关联或无法唯一归属，请核对飞书原消息。");
            for (var attempt : body.path("manualHistory"))
                if (matchesAttempt(attempt, receipt, fingerprint, created))
                    throw unverified("该消息与其他投递记录的历史请求吻合，无法唯一归属。");
        }
    }

    private static boolean matchesAttempt(
            JsonNode attempt, String receipt, String fingerprint, LocalDateTime created) {
        if (receipt.equals(attempt.path("lateReceipt").textValue())
                || receipt.equals(attempt.path("receiptId").textValue())) return true;
        if (created == null || !fingerprint.equals(attempt.path("requestFingerprint").textValue()))
            return false;
        var first = firstAttempt(attempt);
        // 同一请求摘要缺少发送起点时，无法排除它也发出了这条消息。
        return first == null || PolicyGroupReceipt.inWindow(first, created);
    }

    private static LocalDateTime firstAttempt(JsonNode attempt) {
        String value = attempt.path("firstAttemptAt").textValue();
        if (value == null) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    private static ApiException unverified(String message) {
        return new ApiException(HttpStatus.CONFLICT, "POLICY_RECEIPT_UNVERIFIED", message);
    }

    private Row requireCurrent(String tenant, long id, Resolution request) {
        var row = mapper.read(tenant, id);
        if (row == null)
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "POLICY_DELIVERY_NOT_FOUND", "未找到本公司的投递记录。");
        var view = item(tenant, row);
        if (!view.canResolve()
                || !view.status().equals(request.expectedStatus())
                || !Objects.equals(view.attempts(), request.expectedAttempts())
                || !Objects.equals(view.revision(), request.expectedRevision())) throw conflict();
        return row;
    }

    private Item item(String tenant, Row row) {
        return new Item(
                Long.toString(row.id()),
                key(tenant, row),
                row.eventKey(),
                row.actionType(),
                row.recipientKey() == 0 ? null : Long.toString(row.recipientKey()),
                row.policyId() == 0
                        ? noticeLabel(payload(row))
                        : row.recipientKey() == 0 ? "业务群" : row.displayName(),
                row.deliveryStatus(),
                row.attempts(),
                row.receiptId(),
                deliveryError(row),
                row.nextAttemptAt() == null
                        ? null
                        : PolicyApplyServiceImpl.formatTime(row.nextAttemptAt()),
                payload(row).path("manualHistory").size(),
                "FAILED".equals(row.deliveryStatus())
                        || ("UNKNOWN".equals(row.deliveryStatus()) && row.nextAttemptAt() == null));
    }

    private String deliveryError(Row row) {
        if (row.lastError() != null || row.policyId() != 0) return row.lastError();
        var ids = payload(row).path("unreachableUserIds");
        return "SENT".equals(row.deliveryStatus()) && ids.isArray() && !ids.isEmpty()
                ? "群消息已送达，以下账号无法点名：" + ids
                : null;
    }

    private static String noticeLabel(ObjectNode body) {
        String label =
                switch (body.path("kind").asText()) {
                    case "START" -> "开始执行";
                    case "NEAR" -> "临期";
                    case "END" -> "截止";
                    case "SHORTENED" -> "提前结束";
                    case "EXTENDED" -> "延期获批";
                    case "PLATFORM" ->
                            ("RETAIL".equals(body.path("channel").asText()) ? "零售" : "百亿")
                                    + " "
                                    + body.path("platform").asText()
                                    + " 交接";
                    case "CLEAN" -> "清洗批次 " + body.path("job").asText();
                    case "IMPORT" -> "导入批次 " + body.path("job").asText();
                    default -> "业务提醒";
                };
        return body.has("policy")
                ? PolicyApplyServiceImpl.policyNumber(body.path("policy").asLong()) + " " + label
                : label;
    }

    private static String key(String tenant, Row row) {
        return PolicyFeishuSender.idempotencyKey(
                tenant,
                row.policyId(),
                row.version(),
                row.eventKey(),
                row.actionType(),
                row.recipientKey());
    }

    private ObjectNode payload(Row row) {
        return payload(row.payloadJson());
    }

    private ObjectNode payload(String value) {
        try {
            return (ObjectNode) json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取投递登记。", e);
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "POLICY_DELIVERY_INVALID", message);
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "POLICY_DELIVERY_CHANGED", "投递状态已变化，请重新查询。");
    }
}
