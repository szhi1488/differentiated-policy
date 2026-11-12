package com.company.policy.common.config;

import com.company.policy.service.GoodsSyncService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "jackyun.import-scheduling-enabled", matchIfMissing = true)
class GoodsSyncScheduling {
    private final GoodsSyncService service;

    GoodsSyncScheduling(GoodsSyncService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 2000)
    void poll() {
        service.processNextPage();
    }
}
