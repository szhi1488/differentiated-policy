package com.company.policy.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "本公司用户全量统计，不受用户列表关键词、角色、状态或分页影响。")
public record UserStatsResponse(
        @Schema(description = "用户总数，包含启用和停用用户") long total,
        @Schema(description = "启用的申请人数") long enabledApplicants,
        @Schema(description = "启用的管理员人数") long enabledAdmins,
        @Schema(description = "停用人数，包含两种角色") long disabledUsers) {}
