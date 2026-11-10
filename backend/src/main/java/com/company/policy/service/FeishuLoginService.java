package com.company.policy.service;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class FeishuLoginService {
    private final FeishuProperties properties;
    private final FeishuApiTransport transport;
    private final Clock clock;

    public FeishuLoginService(FeishuProperties properties, FeishuApiTransport transport, Clock clock) {
        this.properties = properties;
        this.transport = transport;
        this.clock = clock;
    }

    public AuthSessionResponse login(String code) {
        if (!properties.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FEISHU_NOT_CONFIGURED", "请先配置后端飞书应用凭证。");
        }
        var startedAt = clock.instant();
        var token = transport.post("/oauth/v3/token", Map.of("grant_type", "authorization_code",
                "client_id", properties.getAppId(), "client_secret", properties.getAppSecret(), "code", code), null);
        if (!token.path("access_token").isTextual() || token.path("access_token").asText().isBlank()
                || !token.path("expires_in").isIntegralNumber() || !token.path("expires_in").canConvertToLong()
                || token.path("expires_in").asLong() <= 0) {
            throw ApiException.upstream();
        }
        var data = transport.getUserInfo(token.path("access_token").asText()).path("data");
        if (!data.path("open_id").isTextual() || data.path("open_id").asText().isBlank()
                || !data.path("tenant_key").isTextual() || data.path("tenant_key").asText().isBlank()) {
            throw ApiException.upstream();
        }
        // Retain verified identity only. No token/refresh token is kept or sent to the browser.
        var expiresAt = startedAt.plusSeconds(Math.min(token.path("expires_in").asLong(), 7200));
        if (!clock.instant().isBefore(expiresAt)) throw ApiException.upstream();
        return new AuthSessionResponse(new CurrentUserResponse(data.path("open_id").asText(),
                data.path("tenant_key").asText(), data.path("name").asText("飞书用户")), expiresAt);
    }
}
