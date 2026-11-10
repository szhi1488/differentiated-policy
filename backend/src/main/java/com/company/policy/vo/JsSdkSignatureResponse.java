package com.company.policy.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record JsSdkSignatureResponse(
        String appId,
        @Schema(description = "UTC Unix 毫秒时间戳，与 h5sdk.config 合同一致") long timestamp,
        String nonceStr,
        @Schema(description = "当前页面的 SHA-1 签名；不返回 ticket 或 access token") String signature,
        List<String> jsApiList) {}
