package com.company.policy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JsSdkSignatureRequest(
        @NotBlank @Size(max = 8192)
        @Schema(description = "当前页面完整 URL；保留 query，服务端去掉 #fragment。必须属于配置的 allowed-origins。", example = "http://localhost:5173/")
        String url) {
}
