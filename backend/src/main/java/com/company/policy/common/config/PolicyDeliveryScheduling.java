package com.company.policy.common.config;

import com.company.policy.service.ExecutionNotices;
import com.company.policy.service.PolicyDeliveryWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "policy.review-delivery-enabled", havingValue = "true")
class PolicyDeliveryScheduling {
    private final PolicyDeliveryWorker worker;

    private final ExecutionNotices notices;
    private boolean initialized;

    PolicyDeliveryScheduling(PolicyDeliveryWorker worker, ExecutionNotices notices) {
        this.notices = notices;
        this.worker = worker;
    }

    // 首次启用和每次重启均从当前窗口恢复。后续获批单由终审事务直接登记。
    @Scheduled(fixedDelay = 60000)
    void initializeSchedules() {
        if (initialized) return;
        notices.initialize(
                java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).withNano(0));
        initialized = true;
    }

    @Scheduled(fixedDelayString = "${policy.review-delivery-poll-ms:3000}")
    void poll() {
        worker.processOne();
    }
}
