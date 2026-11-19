package com.company.policy.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "feishu")
public class FeishuProperties {
    private String tenantKey = "";
    private String appId = "";
    private String appSecret = "";
    private List<String> allowedOrigins = new ArrayList<>();

    @Min(1)
    @Max(60)
    private int requestTimeoutSeconds = 10;

    public boolean hasCompanyTenant() {
        return tenantKey != null && !tenantKey.isBlank() && tenantKey.length() <= 64;
    }

    public boolean isConfigured() {
        return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
    }
}
