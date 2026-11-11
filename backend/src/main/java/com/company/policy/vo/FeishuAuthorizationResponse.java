package com.company.policy.vo;

import io.swagger.v3.oas.annotations.media.Schema;

public record FeishuAuthorizationResponse(
        @Schema(description = "飞书网页授权地址，只包含公开 App ID、回调地址和一次性 state") String authorizationUrl) {}
