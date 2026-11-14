package com.company.policy.common.config;

import com.company.policy.service.CatalogCleaningWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "jackyun.clean-scheduling-enabled", matchIfMissing = true)
class CatalogCleaningScheduling {
    private final CatalogCleaningWorker worker;

    CatalogCleaningScheduling(CatalogCleaningWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelay = 2000)
    void poll() {
        worker.processNextBatch();
    }
}
