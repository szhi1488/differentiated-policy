package com.company.policy.dto;

import com.company.policy.dto.PolicyDraftRequest.Choice;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 从「我的提交」一次核对并直接执行的旧政策时间调整。 */
public record PolicyTimeAdjustmentRequest(
        @Schema(description = "至少一张可调整的已通过政策，逐单填写新截止和 1 至 512 字原因")
                List<EndAdjustment> endAdjustments,
        @Schema(description = "正式执行时回传本批预览的凭证") String confirmationToken,
        @Schema(description = "延期交叉时段的采用名单，无交叉时传空数组") List<Choice> decisions) {}
