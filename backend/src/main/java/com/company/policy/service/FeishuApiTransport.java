package com.company.policy.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public interface FeishuApiTransport {
    JsonNode post(String path, Map<String, String> body, String bearerToken);

    default JsonNode getUserInfo(String bearerToken) {
        throw new UnsupportedOperationException("User info transport not implemented");
    }
}
