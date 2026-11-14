package com.company.policy.service;

public interface CatalogCleaningWorker {
    /** 领取清洗租约并处理一批待处理明细。 */
    void processNextBatch();
}
