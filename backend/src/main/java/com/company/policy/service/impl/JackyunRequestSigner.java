/*
 * Copyright(C) 2017 Hangzhou Differsoft Co., Ltd. All rights reserved.
 */
package com.company.policy.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.SortedMap;

/** 吉客云开放平台签名，依据官方 Java 示例。 */
final class JackyunRequestSigner {
    private JackyunRequestSigner() {}

    static String sign(SortedMap<String, String> parameters, String secret) {
        // 原始 bizcontent 参与签名，签名完成后再做表单编码。
        StringBuilder material = new StringBuilder(secret);
        parameters.forEach((key, value) -> material.append(key).append(value));
        material.append(secret);
        byte[] input =
                material.toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(input));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 环境缺少吉客云协议所需的 MD5", exception);
        }
    }
}
