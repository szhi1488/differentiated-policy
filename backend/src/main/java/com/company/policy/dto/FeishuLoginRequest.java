package com.company.policy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FeishuLoginRequest(
        @NotBlank @Size(max = 4096) @Schema(description = "requestAccess 返回的一次性 code，不可重复交换")
                String code,
        @NotBlank @Size(max = 128) @Schema(description = "本浏览器 challenge 返回的 state")
                String state) {}
