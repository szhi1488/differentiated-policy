package com.company.policy.service.impl;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.service.FeishuApiTransport;
import com.company.policy.service.FeishuTicketService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class FeishuTicketServiceImpl implements FeishuTicketService {
    private final FeishuProperties properties;
    private final FeishuApiTransport transport;
    private final Clock clock;
    private CachedValue token;
    private CachedValue ticket;

    public FeishuTicketServiceImpl(
            FeishuProperties properties, FeishuApiTransport transport, Clock clock) {
        this.properties = properties;
        this.transport = transport;
        this.clock = clock;
    }

    @Override
    public synchronized String getTicket() {
        if (!properties.isConfigured()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "FEISHU_NOT_CONFIGURED", "请先配置后端飞书应用凭证。");
        }
        if (fresh(ticket)) {
            return ticket.value();
        }
        if (!fresh(token)) {
            JsonNode response =
                    transport.post(
                            "/open-apis/auth/v3/tenant_access_token/internal",
                            Map.of(
                                    "app_id",
                                    properties.getAppId(),
                                    "app_secret",
                                    properties.getAppSecret()),
                            null);
            token = cache(response.path("tenant_access_token"), response.path("expire"));
        }
        try {
            JsonNode response =
                    transport.post("/open-apis/jssdk/ticket/get", Map.of(), token.value());
            ticket =
                    cache(
                            response.path("data").path("ticket"),
                            response.path("data").path("expire_in"));
            return ticket.value();
        } catch (ApiException error) {
            // 令牌失效后立即清除缓存，避免原有效期内的后续重试持续失败。
            token = null;
            ticket = null;
            throw error;
        }
    }

    private boolean fresh(CachedValue cached) {
        return cached != null && clock.instant().isBefore(cached.refreshAt());
    }

    private CachedValue cache(JsonNode value, JsonNode lifetime) {
        if (!value.isTextual()
                || value.asText().isBlank()
                || !lifetime.canConvertToLong()
                || lifetime.asLong() < 2) {
            throw ApiException.upstream();
        }
        long seconds = lifetime.asLong();
        // 提前刷新，同时为测试或服务端返回的短有效期保留可用的缓存时间。
        long margin = Math.min(60L, Math.max(1L, seconds / 10));
        try {
            return new CachedValue(value.asText(), clock.instant().plusSeconds(seconds - margin));
        } catch (RuntimeException ignored) {
            throw ApiException.upstream();
        }
    }

    private record CachedValue(String value, Instant refreshAt) {}
}
