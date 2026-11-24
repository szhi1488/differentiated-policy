package com.company.policy.dto;

import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.vo.PolicyApplicability.Scope;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.util.List;

public record PolicyDraftRequest(
        @JsonDeserialize(using = IntOnly.class) Integer recordVersion,
        @Schema(
                        allowableValues = {"RETAIL", "BILLION"},
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String perspective,
        List<String> selectedPlatforms,
        String verifyType,
        @Schema(description = "零售所选场景，国补、地补、自补可多选；百亿为空") List<String> sceneTypes,
        Boolean estimated,
        @Schema(example = "2026-11-10T09:00:00+08:00") String startTime,
        @Schema(example = "2026-11-20T18:00:00+08:00") String endTime,
        String specialRemark,
        List<Link> attachmentInfo,
        GoodsForm goodsForm) {
    public record Link(
            @Schema(allowableValues = {"LINK", "FILE"}) String type,
            String url,
            String id,
            String originalName) {
        public Link(String type, String url) {
            this(type, url, null, null);
        }
    }

    public record GoodsForm(
            List<Card> cards,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    EndAdjustment timeAdjustment) {
        public GoodsForm(List<Card> cards) {
            this(cards, null);
        }
    }

    public PolicyDraftRequest withTimeAdjustment(EndAdjustment change) {
        return new PolicyDraftRequest(
                recordVersion,
                perspective,
                selectedPlatforms,
                verifyType,
                sceneTypes,
                estimated,
                startTime,
                endTime,
                specialRemark,
                attachmentInfo,
                new GoodsForm(goodsForm.cards(), change));
    }

    public record Card(
            String key,
            @Schema(allowableValues = {"COMMON", "SPECIFIED"}) String cardType,
            Boolean followsOrderPlatforms,
            List<Target> targets,
            List<SubsidyPlan> subsidyPlans,
            List<PlatformAssignment> platformAssignments) {}

    public record Target(
            @JsonDeserialize(using = TextOnly.class) String spuId,
            String ram,
            String rom,
            @JsonDeserialize(using = TextOnly.class) String goodsId) {}

    public record SubsidyPlan(
            String key,
            @Schema(allowableValues = {"FIXED", "RATIO"}) String subsidyMode,
            @JsonDeserialize(using = TextOnly.class) String fixedAmount,
            @JsonDeserialize(using = TextOnly.class) String maxSubsidyAmount,
            @JsonDeserialize(using = TextOnly.class) String ratio,
            String priceBase) {}

    public record PlatformAssignment(
            String platformCode,
            String planKey,
            @JsonDeserialize(using = IntOnly.class) Integer quantity) {}

    public record TimeAdjustment(
            @JsonDeserialize(using = IntOnly.class) Integer recordVersion,
            @Schema(description = "北京时间新截止时刻，精确到秒") String endTime,
            @Schema(description = "已核对的原单实际截止。创建取列表 endTime，修改取详情 adjustmentSourceEnd。该值变化返回 409")
                    String expectedSourceEnd,
            @Schema(description = "必填，1 至 512 字") String reason,
            @Schema(description = "选填的证明文件") List<Link> attachmentInfo) {}

    public record AdjustmentPreview(
            @JsonDeserialize(using = IntOnly.class) Integer recordVersion,
            List<EndAdjustment> endAdjustments) {}

    public record Submission(
            @JsonDeserialize(using = IntOnly.class) Integer recordVersion,
            String confirmationToken,
            List<Choice> decisions,
            List<EndAdjustment> endAdjustments) {}

    public record Choice(
            Scope scope,
            String through,
            @Schema(allowableValues = {"SELECT", "STACK", "CHOOSE_ONE", "RETURN_TO_EDIT"})
                    String choice,
            String policyId,
            @Schema(description = "零售 SELECT 必填，至少一张，同一完整场景组合和核销类型最多一张")
                    List<String> selectedPolicyIds) {}

    public static final class IntOnly extends StdScalarDeserializer<Integer> {
        public IntOnly() {
            super(Integer.class);
        }

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return parser.getIntValue();
            }
            return context.reportInputMismatch(Integer.class, "此字段必须为 JSON 整数");
        }
    }

    public static final class TextOnly extends StdScalarDeserializer<String> {
        public TextOnly() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.hasToken(JsonToken.VALUE_STRING)) {
                return parser.getText();
            }
            return context.reportInputMismatch(String.class, "此字段必须为 JSON 字符串");
        }
    }
}
