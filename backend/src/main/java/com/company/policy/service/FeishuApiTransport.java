package com.company.policy.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 飞书服务端接口的网络请求契约。 */
public interface FeishuApiTransport {
    JsonNode post(String path, Map<String, String> body, String bearerToken);

    JsonNode getUserInfo(String bearerToken);
}
