package com.company.policy.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 飞书服务端接口的网络请求契约。 */
public interface FeishuApiTransport {
    /** 向指定的飞书授权或票据接口发送请求并返回解析后的响应。 */
    JsonNode post(String path, Map<String, String> body, String bearerToken);

    /** 使用用户访问令牌向飞书读取当前用户的身份信息。 */
    JsonNode getUserInfo(String bearerToken);
}
