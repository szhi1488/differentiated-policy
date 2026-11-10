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
                && !path.equals("/open-apis/jssdk/ticket/get")) {
            throw new IllegalArgumentException("Unsupported Feishu endpoint");
        }
        try {
            var builder = HttpRequest.newBuilder(URI.create(ORIGIN + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (bearerToken != null) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw ApiException.upstream();
            }
            JsonNode payload = mapper.readTree(response.body());
            if (payload == null || !payload.path("code").canConvertToInt() || payload.path("code").asInt() != 0) {
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
