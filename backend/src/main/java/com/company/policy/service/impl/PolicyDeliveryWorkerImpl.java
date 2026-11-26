package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.now;

import com.company.policy.mapper.PolicyDeliveryMapper;
import com.company.policy.mapper.PolicyDeliveryMapper.Item;
import com.company.policy.mapper.PolicyDeliveryMapper.TrialCard;
import com.company.policy.service.PolicyDeliveryWorker;
import com.company.policy.service.PolicyFeishuSender;
import com.company.policy.service.PolicyFeishuSender.Delivery;
import com.company.policy.service.PolicyFeishuSender.Failure;
import com.company.policy.service.PolicyFeishuSender.Kind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class PolicyDeliveryWorkerImpl implements PolicyDeliveryWorker {
    private static final int MAX_ATTEMPTS = 5;
    private final String tenant;
    private final PolicyDeliveryMapper queue;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final PolicyFeishuSender sender;

    PolicyDeliveryWorkerImpl(
            @Value("${feishu.tenant-key:}") String tenant,
            PolicyDeliveryMapper queue,
            TransactionTemplate tx,
            ObjectMapper json,
            PolicyFeishuSender sender) {
        this.tenant = tenant;
        this.queue = queue;
        this.tx = tx;
        this.json = json;
        this.sender = sender;
    }

    private record Claim(
            Delivery delivery,
            String owner,
            int attempt,
            boolean uncertain,
            LocalDateTime firstAttempt) {}

    private static final Claim COMPLETED_LOCALLY = new Claim(null, null, 0, false, null);

    // 领取和保存回执各用短事务，HTTP 调用期间不持有数据库锁。
    @Override
    public boolean processOne() {
        var owner = UUID.randomUUID().toString();
        var claim =
                tx.execute(
                        ignored -> {
                            var at = now();
                            return claim(queue.next(tenant, at), owner, at);
                        });
        return deliver(claim);
    }

    @Override
    public TrialResult processTrialCard(long policyId, int version, String deliveryKey) {
        // 投递编号是按业务键派生的 UUID。只读取指定单据及轮次，在行锁下再次比对完整业务键。
        var row = originalCard(policyId, version, deliveryKey);
        if (row == null) return new TrialResult("NOT_FOUND", null, "在指定轮次未找到此初审卡投递编号。");
        var owner = UUID.randomUUID().toString();
        var claim =
                tx.execute(
                        ignored -> {
                            var at = now();
                            var id = queue.nextTrialCard(tenant, row, at);
                            return claim(id, owner, at);
                        });
        deliver(claim);
        var current = queue.trialState(tenant, row.id());
        if (current == null) return new TrialResult("NOT_FOUND", null, "申请或投递登记已移除。");
        return new TrialResult(current.deliveryStatus(), current.receiptId(), current.lastError());
    }

    private TrialCard originalCard(long id, int version, String key) {
        return queue.trialCards(tenant, id, version).stream()
                .filter(
                        row ->
                                PolicyFeishuSender.idempotencyKey(
                                                tenant,
                                                id,
                                                version,
                                                row.eventKey(),
                                                "SEND_CARD",
                                                row.recipientKey())
                                        .equals(key))
                .findFirst()
                .orElse(null);
    }

    private boolean deliver(Claim claim) {
        // null 表示空队列；已过期且无需发送的记录仍算作处理一项。
        if (claim == null) return false;
        if (claim.delivery() == null) return true;
        String receipt;
        try {
            receipt = sender.send(claim.delivery());
            if (receipt == null || receipt.isBlank() || receipt.length() > 128)
                throw new Failure(Kind.UNKNOWN, "飞书未返回可核对的消息或待办回执。");
        } catch (Failure failure) {
            failed(claim, failure);
            return true;
        } catch (RuntimeException unexpected) {
            failed(claim, new Failure(Kind.UNKNOWN, "投递响应未确认，请按本项业务标识核对。"));
            return true;
        }
        var item = claim.delivery();
        tx.executeWithoutResult(
                ignored -> {
                    var at = now();
                    if (queue.finish(item.id(), claim.owner(), "SENT", receipt, at, null, null) == 0
                            && !acceptLateReceipt(item, receipt, at)) return;
                    attachTask(item.action(), item.memberId(), receipt);
                });
        return true;
    }

    // 租约转手后，迟到回执仍可确认同一次投递。
    private boolean acceptLateReceipt(Delivery item, String receipt, LocalDateTime at) {
        var attempt = queue.lockAttempt(item.id());
        if (attempt == null) return false;
        try {
            var current = (ObjectNode) json.readTree(attempt.payloadJson());
            if (!item.payload().path("firstAttemptAt").equals(current.path("firstAttemptAt")))
                return false;
            if ("SENDING".equals(attempt.deliveryStatus())) {
                // 记录可信回执供当前执行者收口，不改动它的租约或状态。
                current.put("lateReceipt", receipt);
                queue.updateLockedPayload(item.id(), current.toString());
                return false;
            }
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("无法核对迟到回执对应的投递记录。", invalid);
        }
        return queue.recordLateReceipt(item.id(), receipt, at) == 1;
    }

    private Claim claim(Long id, String owner, LocalDateTime at) {
        if (id == null) return null;
        var item = queue.item(id);
        ObjectNode payload;
        try {
            payload = (ObjectNode) json.readTree(item.payloadJson());
        } catch (JsonProcessingException | ClassCastException invalid) {
            return stop(id, false, "登记内容无法读取，请核对业务记录。", "FAILED");
        }
        String action = item.actionType();
        if (payload.hasNonNull("lateReceipt")) {
            var receipt = payload.get("lateReceipt").asText();
            queue.recordLateReceipt(id, receipt, at);
            attachTask(action, item.recipientKey(), receipt);
            return COMPLETED_LOCALLY;
        }
        // 指派已有 GUID 即可补齐创建回执，节点是否仍在途只影响新的创建。
        if ("CREATE_TASK".equals(action) && item.taskGuid() != null) {
            queue.recordLateReceipt(id, item.taskGuid(), at);
            return COMPLETED_LOCALLY;
        }
        boolean create = isCreate(action);
        boolean uncertain =
                "SENDING".equals(item.deliveryStatus()) || "UNKNOWN".equals(item.deliveryStatus());
        boolean guardedCreate = !isClosing(action);
        if (guardedCreate && uncertain && !payload.hasNonNull("requestFingerprint")) {
            if (!payload.path("requiresWriteGuard").asBoolean())
                return stop(id, true, "投递缺少写前指纹，请核对原请求是否送达。", "UNKNOWN");
            // 租约已转手，旧执行者无法再通过写前检查。缺少指纹表示没有发出创建请求。
            uncertain = false;
        }
        if (item.deleted() != 0 && !isClosing(action))
            return stop(id, uncertain, "申请已移除。", "CANCELLED");
        if (item.policyId() != 0
                && "GROUP_MESSAGE".equals(action)
                && !("PURCHASE_DECIDED".equals(item.eventKey())
                        && "APPROVE".equals(payload.path("decision").asText())))
            return stop(id, uncertain, "仅采购终审通过后可发送审核群公告。", "CANCELLED");
        if (create && !active(item)) return stop(id, uncertain, "本轮节点已结束或该指派已失效。", "CANCELLED");

        if (create && (item.openId() == null || item.openId().isBlank() || !item.eligible()))
            return stop(id, uncertain, "收件人缺少有效飞书身份或对应岗位资格。", "FAILED");
        String prior = null;
        if (isClosing(action)) {
            prior = action.equals("CLOSE_TASK") ? item.taskGuid() : null;
            if (prior == null)
                prior =
                        queue.receipt(
                                tenant,
                                item.recipientKey(),
                                action.equals("CLOSE_TASK") ? "CREATE_TASK" : "SEND_CARD");
            if (prior == null) return stop(id, false, "该指派没有已送达的原卡或待办。", "CANCELLED");
        }
        var first =
                payload.hasNonNull("firstAttemptAt")
                        ? LocalDateTime.parse(payload.get("firstAttemptAt").asText())
                        : (uncertain ? item.createdAt() : at);
        if (uncertain && (item.attempts() >= MAX_ATTEMPTS || !withinWindow(action, first, at)))
            return stop(id, true, "上一次发送结果未知，须核对外部记录后处理。", "UNKNOWN");
        if (guardedCreate && !uncertain) {
            if (item.attempts() >= MAX_ATTEMPTS)
                return stop(id, false, "创建请求尚未送达且已达到领取上限。", "FAILED");
            payload.put("requiresWriteGuard", true);
            payload.remove("requestFingerprint");
            payload.remove("requestRecipient");
            first = at;
        }
        payload.put("firstAttemptAt", first.toString());
        String uuid =
                PolicyFeishuSender.idempotencyKey(
                        tenant,
                        item.policyId(),
                        item.version(),
                        item.eventKey(),
                        action,
                        item.recipientKey());
        var delivery =
                new Delivery(
                        id,
                        tenant,
                        item.policyId(),
                        item.version(),
                        item.eventKey(),
                        action,
                        item.recipientKey(),
                        item.groupCode(),
                        item.openId(),
                        item.memberStatus(),
                        item.recordVersion(),
                        payload,
                        prior,
                        uuid,
                        item.createdAt(),
                        owner,
                        uncertain);
        queue.claim(id, owner, at.plusSeconds(90), payload.toString());
        return new Claim(delivery, owner, item.attempts() + 1, uncertain, first);
    }

    private Claim stop(long id, boolean uncertain, String reason, String status) {
        queue.stopLocked(id, uncertain ? "UNKNOWN" : status, reason);
        return COMPLETED_LOCALLY;
    }

    private static boolean isCreate(String action) {
        return "SEND_CARD".equals(action) || "CREATE_TASK".equals(action);
    }

    private static boolean isClosing(String action) {
        return "UPDATE_CARD".equals(action) || "CLOSE_TASK".equals(action);
    }

    static boolean active(Item item) {
        if (!"ACTIVE".equals(item.memberStatus()) || item.version() != item.currentVersion())
            return false;
        return switch (item.groupCode()) {
            case "PRODUCT" ->
                    "PRODUCT_REVIEW".equals(item.stage()) && "PENDING".equals(item.status());
            case "PURCHASE" ->
                    "PURCHASE_REVIEW".equals(item.stage()) && "PENDING".equals(item.status());
            case "REVISION" -> "FINISHED".equals(item.stage()) && "REJECTED".equals(item.status());
            default -> false;
        };
    }

    // 飞书按创建标识去重，保留网络时间余量；原 ID 上的更新和结办可安全重放。
    static boolean withinWindow(String action, LocalDateTime first, LocalDateTime at) {
        return isClosing(action)
                || at.isBefore(first.plusMinutes(action.equals("CREATE_TASK") ? 4 : 55));
    }

    private void failed(Claim claim, Failure failure) {
        boolean uncertain = failure.kind() == Kind.UNKNOWN || claim.uncertain();
        boolean retry =
                failure.kind() != Kind.PERMANENT
                        && failure.kind() != Kind.OBSOLETE
                        && claim.attempt() < MAX_ATTEMPTS;
        var next = now().plusSeconds(1L << claim.attempt());
        if (uncertain && !withinWindow(claim.delivery().action(), claim.firstAttempt(), next))
            retry = false;
        String status =
                uncertain
                        ? "UNKNOWN"
                        : failure.kind() == Kind.OBSOLETE
                                ? "CANCELLED"
                                : retry ? "RETRY" : "FAILED";
        var due = retry ? next : null;
        var message = failure.getMessage() == null ? "投递失败。" : failure.getMessage();
        var item = claim.delivery();
        tx.executeWithoutResult(
                ignored -> {
                    var attempt = queue.lockAttempt(item.id());
                    if (attempt == null || !claim.owner().equals(attempt.leaseOwner())) return;
                    String late;
                    try {
                        late =
                                json.readTree(attempt.payloadJson())
                                        .path("lateReceipt")
                                        .asText(null);
                    } catch (JsonProcessingException invalid) {
                        throw new IllegalStateException("无法核对迟到回执。", invalid);
                    }
                    if (late != null) {
                        queue.recordLateReceipt(item.id(), late, now());
                        attachTask(item.action(), item.memberId(), late);
                    } else {
                        queue.finish(
                                item.id(),
                                claim.owner(),
                                status,
                                null,
                                null,
                                due,
                                message.substring(0, Math.min(message.length(), 512)));
                    }
                });
    }

    private void attachTask(String action, long memberId, String receipt) {
        if ("CREATE_TASK".equals(action) && queue.saveTaskGuid(tenant, memberId, receipt) != 1)
            throw new IllegalStateException("指派已有其他待办 GUID，需核对此项回执。");
    }
}
