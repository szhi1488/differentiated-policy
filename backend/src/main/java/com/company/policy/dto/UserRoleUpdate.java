package com.company.policy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record UserRoleUpdate(
        @NotNull
                @Pattern(regexp = "APPLICANT|ADMIN")
                @Schema(
                        description = "目标角色",
                        allowableValues = {"APPLICANT", "ADMIN"})
                String roleType,
        @NotNull
                @Pattern(regexp = "APPLICANT|ADMIN")
                @Schema(
                        description = "读取用户详情时的角色",
                        allowableValues = {"APPLICANT", "ADMIN"})
                String expectedRoleType,
        @NotNull
                @Pattern(regexp = "ENABLED|DISABLED")
                @Schema(
                        description = "读取用户详情时的启停状态",
                        allowableValues = {"ENABLED", "DISABLED"})
                String expectedStatus) {}
