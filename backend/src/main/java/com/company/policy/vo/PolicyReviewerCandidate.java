package com.company.policy.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 审核设置选择器中的本地账号，用本地 ID 区分同名人员。 */
public record PolicyReviewerCandidate(
        @Schema(description = "本地用户 ID，使用字符串传输") String id,
        String name,
        @Schema(description = "可空的本地部门资料") String department) {}
