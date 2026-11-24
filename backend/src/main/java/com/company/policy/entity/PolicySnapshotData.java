package com.company.policy.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** policy_audit_log.snapshot_data 的持久化格式。列表筛选、历史附件与补贴对照都依赖其 JSON 结构。 */
public record PolicySnapshotData(
        String id,
        String policyCode,
        String applicantId,
        String applicantName,
        int submissionVersion,
        Form form,
        List<Goods> goods,
        @JsonInclude(JsonInclude.Include.NON_NULL) PolicyConsent consent,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sourcePolicyCode) {
    public record Form(
            Integer recordVersion,
            String perspective,
            List<String> selectedPlatforms,
            String verifyType,
            List<String> sceneTypes,
            Boolean estimated,
            String startTime,
            String endTime,
            String specialRemark,
            List<Link> attachmentInfo,
            GoodsForm goodsForm) {}

    public record Link(String type, String url, String id, String originalName) {}

    public record GoodsForm(
            List<Card> cards,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    PolicyConsent.EndAdjustment timeAdjustment) {}

    public record Card(
            String key,
            String cardType,
            Boolean followsOrderPlatforms,
            List<Target> targets,
            List<SubsidyPlan> subsidyPlans,
            List<PlatformAssignment> platformAssignments) {}

    public record Target(String spuId, String ram, String rom, String goodsId) {}

    public record SubsidyPlan(
            String key,
            String subsidyMode,
            String fixedAmount,
            String maxSubsidyAmount,
            String ratio,
            String priceBase) {}

    public record PlatformAssignment(String platformCode, String planKey, Integer quantity) {}

    public record Goods(
            String goodsId,
            String brandId,
            String brandName,
            String seriesId,
            String seriesName,
            String spuId,
            String modelName,
            String edition,
            String ram,
            String rom,
            String goodsName,
            String colorName,
            boolean onSale) {}
}
