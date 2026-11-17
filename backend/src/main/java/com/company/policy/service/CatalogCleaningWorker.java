package com.company.policy.service;

/** 在后台分批处理待清洗的源商品，并更新清洗任务进度。 */
public interface CatalogCleaningWorker {
    /** 领取清洗租约并处理一批待处理明细。 */
    void processNextBatch();
}
