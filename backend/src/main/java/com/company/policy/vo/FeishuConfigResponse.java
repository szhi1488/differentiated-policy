package com.company.policy.vo;

import io.swagger.v3.oas.annotations.media.Schema;

public record FeishuConfigResponse(
        String appId,
        @Schema(description = "仅表示后端配置了 App ID 和 App Secret，不表示凭证已验证或用户已登录") boolean sdkEnabled) {
}
