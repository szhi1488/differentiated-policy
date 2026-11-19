package com.company.policy.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 外部接口共用的 HTTP 连接配置。 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "external.http")
public class ExternalHttpProperties {
    @Min(1)
    @Max(60)
    private int connectTimeoutSeconds = 5;
}
