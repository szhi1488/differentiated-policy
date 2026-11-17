package com.company.policy.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "本公司用户列表项，仅返回列表所需资料，不含飞书身份标识和令牌。")
public record UserSummaryResponse(
        @Schema(description = "本地用户 ID，使用字符串传输") String id,
        String name,
        @Schema(description = "可空的飞书头像 HTTPS 地址") String avatarUrl,
        @Schema(description = "可空的所属部门，当前不自动同步飞书部门") String department,
        @Schema(allowableValues = {"APPLICANT", "ADMIN"}) String roleType,
        @Schema(allowableValues = {"ENABLED", "DISABLED"}) String status,
        @Schema(description = "加入系统时间，北京时间，精确到秒", example = "2026-11-17 09:00:00")
                String createdAt) {}
