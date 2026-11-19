package com.company.policy.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "jackyun")
public class JackyunProperties {
    private String appKey = "";
    private String appSecret = "";
    private String token = "";
    // 账套标识由服务端配置。
    private String sourceKey = "jackyun";
    private int importIntervalSeconds = 5;
    private int importMaxPages = 2000;

    @Min(1)
    @Max(60)
    private int requestTimeoutSeconds = 20;

    @Min(1000)
    @Max(60000)
    private int importPollIntervalMs = 2000;

    @Min(1000)
    @Max(60000)
    private int cleanPollIntervalMs = 2000;

    public boolean isConfigured() {
        return StringUtils.hasText(appKey) && StringUtils.hasText(appSecret);
    }

    public boolean hasImportScope() {
        return sourceKey != null && sourceKey.matches("[a-zA-Z0-9_-]{1,64}");
    }

    public boolean isImportReady() {
        return isConfigured()
                && hasImportScope()
                && importIntervalSeconds >= 2
                && importIntervalSeconds <= 600
                && importMaxPages > 0;
    }
}
