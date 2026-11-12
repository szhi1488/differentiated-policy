package com.company.policy.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@Getter
@Setter
@ConfigurationProperties(prefix = "jackyun")
public class JackyunProperties {
    private String appKey = "";
    private String appSecret = "";
    private String token = "";
    // 一组凭证只绑定一个受控租户和账套，不从浏览器接收这两个值。
    private String tenantKey = "";
    private String sourceKey = "jackyun";
    private int importIntervalSeconds = 5;
    private int importMaxPages = 2000;

    public boolean isConfigured() {
        return StringUtils.hasText(appKey) && StringUtils.hasText(appSecret);
    }

    public boolean hasImportScope() {
        return StringUtils.hasText(tenantKey)
                && tenantKey.length() <= 64
                && sourceKey != null
                && sourceKey.matches("[a-zA-Z0-9_-]{1,64}");
    }

    public boolean isImportReady() {
        return isConfigured()
                && hasImportScope()
                && importIntervalSeconds >= 2
                && importIntervalSeconds <= 600
                && importMaxPages > 0;
    }
}
