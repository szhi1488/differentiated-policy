package com.company.policy.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

/** 单次外部调用。所有重试复用同一业务标识，成功时返回消息 ID 或任务 GUID。 */
public interface PolicyFeishuSender {
    String INVALID_ERP_HTTPS = "请配置飞书可访问的 ERP HTTPS 根地址 FEISHU_POLICY_ERP_URL。";

    /** 将单个 ERP 投递动作发送到飞书并返回回执。 */
    String send(Delivery item);

    /** 按轮次、业务事件和指派生成在恢复及补发时使用的稳定 UUID。 */
    static String idempotencyKey(
            String tenant,
            long policyId,
            int version,
            String event,
            String action,
            long recipientKey) {
        var key =
                String.join(
                        "/",
                        tenant,
                        Long.toString(policyId),
                        Integer.toString(version),
                        event,
                        action,
                        Long.toString(recipientKey));
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    record Delivery(
            long id,
            String tenant,
            long policyId,
            int version,
            String event,
            String action,
            long memberId,
            String group,
            String openId,
            String memberStatus,
            int recordVersion,
            JsonNode payload,
            String priorReceipt,
            String idempotencyKey,
            LocalDateTime createdAt,
            String leaseOwner,
            boolean uncertain) {
        public Delivery(
                long id,
                String tenant,
                long policyId,
                int version,
                String event,
                String action,
                long memberId,
                String group,
                String openId,
                String memberStatus,
                int recordVersion,
                JsonNode payload,
                String priorReceipt,
                String idempotencyKey,
                LocalDateTime createdAt) {
            this(
                    id,
                    tenant,
                    policyId,
                    version,
                    event,
                    action,
                    memberId,
                    group,
                    openId,
                    memberStatus,
                    recordVersion,
                    payload,
                    priorReceipt,
                    idempotencyKey,
                    createdAt,
                    null,
                    false);
        }
    }

    enum Kind {
        RETRYABLE,
        UNKNOWN,
        OBSOLETE,
        PERMANENT
    }

    /** 消息须可直接保存供管理员查看，不能包含凭证或外部响应原文。 */
    final class Failure extends RuntimeException {
        private final Kind kind;

        public Failure(Kind kind, String safeMessage) {
            super(safeMessage);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }
}
