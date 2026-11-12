package com.company.policy.common.config;

import com.company.policy.service.CatalogCleaningService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "jackyun.clean-scheduling-enabled", matchIfMissing = true)
class CatalogCleaningScheduling {
    private final CatalogCleaningService service;

    CatalogCleaningScheduling(CatalogCleaningService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 2000)
    void poll() {
        service.processNextBatch();
    }
}
