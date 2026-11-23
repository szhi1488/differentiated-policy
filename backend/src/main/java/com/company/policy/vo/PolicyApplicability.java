package com.company.policy.vo;

import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.entity.PolicyConsent.Target;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 申请人按本次负责的平台试算的区间。仅返回参与比较的商品及该平台的补贴。 */
public record PolicyApplicability(
        @Schema(description = "SNAPSHOT_DATES 表示按提交快照起止时间和实际审核时间判定") String dateBasis,
        String asOf,
        String effectiveFrom,
        String effectiveThrough,
        Status status,
        boolean canDirectSubmit,
        List<ScopeResult> scopes,
        @Schema(description = "当前申请人负责渠道及平台内可申请整单提前结束的旧政策，独立于本单适用范围")
                List<AdjustmentOption> adjustmentOptions) {
    public record AdjustmentOption(
            Target target,
            String policyCode,
            String earliestEnd,
            String latestEnd,
            String perspective,
            boolean canShorten,
            boolean canExtend) {
        public AdjustmentOption(
                Target target,
                String policyCode,
                String earliestEnd,
                String latestEnd,
                String perspective) {
            this(target, policyCode, earliestEnd, latestEnd, perspective, true, false);
        }
    }

    public enum Status {
        DIRECT,
        NEEDS_CONFIRMATION,
        NO_EFFECTIVE_PERIOD
    }

    public enum Action {
        ACKNOWLEDGE_EXCLUSIVE,
        CONFIRM_STACK,
        SELECT_RETAIL_POLICIES,
        STACK_OR_CHOOSE
    }

    public record Scope(
            String platformCode,
            @Schema(type = "string") @JsonSerialize(using = ToStringSerializer.class) Long goodsId,
            @Schema(type = "string") @JsonSerialize(using = ToStringSerializer.class) Long spuId,
            String ram,
            String rom) {}

    public record ScopeResult(Scope scope, List<Policy> policies, List<Segment> segments) {}

    public record Policy(
            String policyId,
            String policyCode,
            int submissionVersion,
            String auditLogId,
            boolean proposed,
            String submittedAt,
            String approvedAt,
            String effectiveFrom,
            String effectiveThrough,
            String verifyType,
            List<String> sceneTypes,
            List<Selection> selections) {}

    public record Selection(
            String cardType, List<Product> products, SubsidyPlan subsidy, Integer quantity) {}

    public record Product(
            String goodsId,
            String goodsName,
            String colorName,
            String spuId,
            String modelName,
            String edition,
            String ram,
            String rom) {}

    /** eligiblePolicyIds 已按政策轮次去重，可以通过本范围的 policies 读取补贴与商品行。 */
    public record Segment(
            String from,
            String through,
            Action action,
            List<Group> groups,
            List<String> eligiblePolicyIds) {}

    public record Group(
            String sceneType,
            String verifyType,
            List<String> policyIds,
            String winnerPolicyId,
            @Schema(description = "本组完整场景组合，旧版分组使用 sceneType") List<String> sceneTypes) {}
}
