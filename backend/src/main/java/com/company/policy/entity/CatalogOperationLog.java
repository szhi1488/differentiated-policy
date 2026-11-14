package com.company.policy.entity;

import java.time.LocalDateTime;

/** 商品导入操作和来源变更记录，变更前后值保存为 JSON 对象。 */
public record CatalogOperationLog(
        Long id,
        String actorType,
        Long operatorId,
        String operatorName,
        String entityType,
        long entityId,
        String actionType,
        String batchKey,
        String beforeData,
        String afterData,
        String reason,
        LocalDateTime createdAt) {
    public static CatalogOperationLog syncAction(UserInfo operator, long jobId, String action) {
        return new CatalogOperationLog(
                null,
                "USER",
                operator.getId(),
                operator.getName(),
                "SYNC_JOB",
                jobId,
                action,
                null,
                null,
                null,
                null,
                null);
    }

    public static CatalogOperationLog sourceUpdate(
            long sourceId, String batchKey, String beforeData, String afterData) {
        return new CatalogOperationLog(
                null,
                "SYSTEM",
                null,
                null,
                "SOURCE_GOODS_ITEM",
                sourceId,
                "UPDATE",
                batchKey,
                beforeData,
                afterData,
                "吉客云 SKU 来源字段更新。",
                null);
    }
}
