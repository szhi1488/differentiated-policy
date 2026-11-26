package com.company.policy.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
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
}
