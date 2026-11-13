package com.company.policy.common.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "feishu")
public class FeishuProperties {
    private String tenantKey = "";
    private String appId = "";
    private String appSecret = "";
    private List<String> allowedOrigins = new ArrayList<>();

    public boolean hasCompanyTenant() {
        return tenantKey != null && !tenantKey.isBlank() && tenantKey.length() <= 64;
    }

    public boolean isConfigured() {
        return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
    }
}
