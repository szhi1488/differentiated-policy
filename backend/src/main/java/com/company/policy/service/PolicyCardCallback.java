package com.company.policy.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 飞书卡片回调复用站内初审事务，重复点击返回该指派的已有结果。 */
public interface PolicyCardCallback {
    Map<String, Object> receive(byte[] body, String timestamp, String nonce, String signature);

    /** 仅供应用凭证认证的 SDK 长连接派发。HTTP 入口始终走解密和签名校验。 */
    Map<String, Object> handleLongConnection(JsonNode envelope);
}
