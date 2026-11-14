package com.company.policy.entity;

/** 清洗任务明细，以任务 ID 和来源商品 ID 为复合主键。 */
public record CatalogCleaningJobItem(
        long jobId, long sourceId, int sourceVersion, String outcome) {}
