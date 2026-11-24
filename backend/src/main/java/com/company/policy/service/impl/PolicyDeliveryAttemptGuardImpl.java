package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.now;

import com.company.policy.mapper.PolicyDeliveryMapper;
import com.company.policy.service.PolicyDeliveryAttemptGuard;
import com.company.policy.service.PolicyFeishuSender.Delivery;
import com.company.policy.service.PolicyFeishuSender.Failure;
import com.company.policy.service.PolicyFeishuSender.Kind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class PolicyDeliveryAttemptGuardImpl implements PolicyDeliveryAttemptGuard {
    private final PolicyDeliveryMapper queue;
    private final ObjectMapper json;

    @Transactional
    @Override
    public void prepare(Delivery item, String recipient, String fingerprint) {
        var content = queue.lockBeforeSend(item.id(), item.leaseOwner(), now());
        if (content == null) throw new Failure(Kind.RETRYABLE, "投递租约已失效，重新领取后核对。");
        ObjectNode payload;
        try {
            payload = (ObjectNode) json.readTree(content);
        } catch (JsonProcessingException invalid) {
            throw new Failure(Kind.PERMANENT, "无法读取请求核对记录。");
        }
        if (item.uncertain()
                && !PolicyDeliveryWorkerImpl.withinWindow(
                        item.action(),
                        LocalDateTime.parse(payload.path("firstAttemptAt").asText()),
                        now())) throw new Failure(Kind.PERMANENT, "原请求结果未知且已超出安全重试时限，请先核对飞书回执。");
        if (item.uncertain()
                && (!recipient.equals(payload.path("requestRecipient").asText())
                        || !fingerprint.equals(payload.path("requestFingerprint").asText())))
            throw new Failure(Kind.PERMANENT, "原请求结果未知，实际收件人或请求内容已变化，请核对原投递。");
        if (item.policyId() == 0) {
            payload.remove("unreachableUserIds");
            if (item.payload().has("unreachableUserIds"))
                payload.set("unreachableUserIds", item.payload().get("unreachableUserIds"));
        }
        payload.put("requestRecipient", recipient);
        payload.put("requestFingerprint", fingerprint);
        queue.updateLockedPayload(item.id(), payload.toString());
    }
}
