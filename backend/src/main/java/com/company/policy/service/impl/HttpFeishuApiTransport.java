package com.company.policy.service.impl;

import com.company.policy.common.exception.ApiException;
import com.company.policy.service.FeishuApiTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class HttpFeishuApiTransport implements FeishuApiTransport {
    private static final String ORIGIN = "https://open.feishu.cn";
    private final HttpClient client;
    private final ObjectMapper mapper;

    public HttpFeishuApiTransport(HttpClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public JsonNode post(String path, Map<String, String> body, String bearerToken) {
        if (!path.equals("/open-apis/auth/v3/tenant_access_token/internal")
                && !path.equals("/oauth/v3/token")
                && !path.equals("/open-apis/jssdk/ticket/get")) {
            throw new IllegalArgumentException("Unsupported Feishu endpoint");
        }
        try {
            String origin = path.equals("/oauth/v3/token") ? "https://accounts.feishu.cn" : ORIGIN;
            var builder = HttpRequest.newBuilder(URI.create(origin + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (bearerToken != null) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }
            return send(builder.build(), path.equals("/oauth/v3/token"));
        } catch (IOException | IllegalArgumentException ignored) {
            throw ApiException.upstream();
        }
    }

    @Override
    public JsonNode getUserInfo(String bearerToken) {
        var request = HttpRequest.newBuilder(URI.create(ORIGIN + "/open-apis/authen/v1/user_info"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + bearerToken).GET().build();
        return send(request, false);
    }

    private JsonNode send(HttpRequest request, boolean oauth) {
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode payload = mapper.readTree(response.body());
            if (oauth && payload != null && payload.path("code").isIntegralNumber()) {
                int code = payload.path("code").intValue();
                if (code == 20003 || code == 20004 || code == 20024 || code == 20065) {
                    throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                            "FEISHU_CODE_INVALID", "飞书授权码已失效，请重新授权登录。");
                }
                if (code == 20008 || code == 20009 || code == 20010 || code == 20066 || code == 20069) {
                    throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                            "FEISHU_ACCESS_DENIED", "当前用户无法使用此应用，请联系管理员检查应用状态和可用范围。");
                }
            }
            if (response.statusCode() != 200 || payload == null || !payload.path("code").isIntegralNumber()
                    || !payload.path("code").canConvertToInt() || payload.path("code").intValue() != 0) {
                throw ApiException.upstream();
            }
            return payload;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw ApiException.upstream();
        } catch (IOException | IllegalArgumentException ignored) {
            throw ApiException.upstream();
        }
    }
}
