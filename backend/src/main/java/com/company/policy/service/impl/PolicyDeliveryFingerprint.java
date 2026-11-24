package com.company.policy.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class PolicyDeliveryFingerprint {
    private PolicyDeliveryFingerprint() {}

    // 写前登记和飞书回读使用同一格式，字段排序须与已有投递记录一致。
    static String compute(
            ObjectMapper json, String appId, String action, String recipient, Object content) {
        try {
            // 群消息更换正文格式后，旧的未知请求须先核对回执，不能用同一 UUID 改发新正文。
            var fields =
                    "GROUP_MESSAGE".equals(action)
                            ? List.of(appId, action, recipient, "post-v2", content)
                            : List.of(appId, action, recipient, content);
            var canonical = json.convertValue(fields, Object.class);
            byte[] body =
                    json.writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                            .writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("无法计算投递内容的摘要。", e);
        }
    }
}
