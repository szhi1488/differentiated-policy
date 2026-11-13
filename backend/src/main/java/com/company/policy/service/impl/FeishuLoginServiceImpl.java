package com.company.policy.service.impl;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.service.FeishuApiTransport;
import com.company.policy.service.FeishuLoginService;
import com.company.policy.service.UserInfoService;
import com.company.policy.vo.AuthSessionResponse;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class FeishuLoginServiceImpl implements FeishuLoginService {
    private static final long MAX_SESSION_SECONDS = 7200;
    private final FeishuProperties properties;
    private final FeishuApiTransport transport;
    private final Clock clock;
    private final UserInfoService users;

    public FeishuLoginServiceImpl(
            FeishuProperties properties,
            FeishuApiTransport transport,
            Clock clock,
            UserInfoService users) {
        this.properties = properties;
        this.transport = transport;
        this.clock = clock;
        this.users = users;
    }

    @Override
    public AuthSessionResponse login(String code) {
        return login(code, null);
    }

    @Override
    public AuthSessionResponse login(String code, String redirectUri) {
        if (!properties.isConfigured()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "FEISHU_NOT_CONFIGURED", "请先配置后端飞书应用凭证。");
        }
        if (!properties.hasCompanyTenant()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "FEISHU_TENANT_NOT_CONFIGURED", "请先配置公司飞书租户。");
        }
        var startedAt = clock.instant();
        var body =
                new HashMap<>(
                        Map.of(
                                "grant_type",
                                "authorization_code",
                                "client_id",
                                properties.getAppId(),
                                "client_secret",
                                properties.getAppSecret(),
                                "code",
                                code));
        if (redirectUri != null) {
            body.put("redirect_uri", redirectUri);
        }
        var token = transport.post("/oauth/v3/token", body, null);
        if (!token.path("access_token").isTextual()
                || token.path("access_token").asText().isBlank()
                || !token.path("expires_in").isIntegralNumber()
                || !token.path("expires_in").canConvertToLong()
                || token.path("expires_in").asLong() <= 0) {
            throw ApiException.upstream();
        }
        var data = transport.getUserInfo(token.path("access_token").asText()).path("data");
        if (!data.path("open_id").isTextual()
                || data.path("open_id").asText().isBlank()
                || !data.path("tenant_key").isTextual()
                || data.path("tenant_key").asText().isBlank()) {
            throw ApiException.upstream();
        }
        String tenant = data.path("tenant_key").asText();
        if (!properties.getTenantKey().equals(tenant)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FEISHU_TENANT_DENIED", "当前飞书账号不属于本公司。");
        }
        // 仅保留已核验的身份，不保存访问令牌、刷新令牌，也不将其发送给浏览器。
        var expiresAt =
                startedAt.plusSeconds(
                        Math.min(token.path("expires_in").asLong(), MAX_SESSION_SECONDS));
        if (!clock.instant().isBefore(expiresAt)) {
            throw ApiException.upstream();
        }
        String unionId =
                data.path("union_id").isTextual() && !data.path("union_id").asText().isBlank()
                        ? data.path("union_id").asText()
                        : null;
        var user =
                users.saveVerifiedIdentity(
                        tenant,
                        data.path("open_id").asText(),
                        unionId,
                        data.path("name").asText("飞书用户"));
        return new AuthSessionResponse(user, expiresAt);
    }
}
