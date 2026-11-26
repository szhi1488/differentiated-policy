package com.company.policy.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** 以本企业应用身份投递审核消息和任务，创建返回平台回执，更新核对原回执。 */
public interface FeishuWorkflowClient {
    String sendCard(String openId, JsonNode card, String idempotencyKey, Runnable beforePost);

    String updateCard(String messageId, JsonNode card);

    String sendGroup(
            String chatId,
            String summary,
            List<String> mentionOpenIds,
            String idempotencyKey,
            Runnable beforePost);

    String createTask(String openId, String summary, String idempotencyKey, Runnable beforePost);

    String closeTask(String guid, String outcome, Instant completedAt);

    /** 回读群消息并返回用于区分同内容投递的北京时间。旧消息以正文中的唯一编号核对成功时返回 null。 */
    LocalDateTime verifyGroupReceipt(
            String receipt,
            String chatId,
            String key,
            String fingerprint,
            LocalDateTime firstAttemptAt);

    /** 回查管理员提供的个人卡片或任务 ID，核对成功后方可关联本地登记。 */
    void verifyReceipt(
            String action,
            String receipt,
            String recipient,
            String idempotencyKey,
            long assignmentId,
            long policyId,
            int version);
}
