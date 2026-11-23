package com.company.policy.support.policy;

import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.entity.PolicySnapshotData;
import com.company.policy.vo.ApplicantCatalogResponse;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** 提交时冻结表单和商品，读取旧记录时只转换接口需要的字段。 */
@Component
public final class PolicySnapshotCodec {
    // 快照独立于 HTTP 的 Jackson 配置。旧记录可能带有已移除的 otherExplanation 字段。
    private final JsonMapper json =
            JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public String write(Snapshot snapshot) {
        var stored =
                new PolicySnapshotData(
                        snapshot.id(),
                        snapshot.policyCode(),
                        snapshot.applicantId(),
                        snapshot.applicantName(),
                        snapshot.submissionVersion(),
                        storeForm(snapshot.form()),
                        map(snapshot.goods(), PolicySnapshotCodec::storeGoods),
                        snapshot.consent(),
                        snapshot.sourcePolicyCode());
        try {
            return json.writeValueAsString(stored);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法保存政策提交快照。", error);
        }
    }

    public List<com.company.policy.entity.PolicyConsent.EndAdjustment> readEndAdjustments(
            String value) {
        try {
            return json.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法读取已通过的整单提前结束记录。", error);
        }
    }

    public Snapshot read(String value) {
        if (value == null) {
            throw new IllegalStateException("政策提交快照缺失。");
        }
        try {
            var stored = json.readValue(value, PolicySnapshotData.class);
            if (stored == null) {
                throw new IllegalStateException("政策提交快照缺失。");
            }
            return new Snapshot(
                    stored.id(),
                    stored.policyCode(),
                    stored.applicantId(),
                    stored.applicantName(),
                    stored.submissionVersion(),
                    readForm(stored.form()),
                    map(stored.goods(), PolicySnapshotCodec::readGoods),
                    stored.consent(),
                    stored.sourcePolicyCode());
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法读取政策提交快照。", error);
        }
    }

    private static PolicySnapshotData.Form storeForm(PolicyDraftRequest value) {
        if (value == null) {
            return null;
        }
        return new PolicySnapshotData.Form(
                value.recordVersion(),
                value.perspective(),
                value.selectedPlatforms(),
                value.verifyType(),
                value.sceneType(),
                value.sceneTypes(),
                value.estimated(),
                value.startTime(),
                value.endTime(),
                value.specialRemark(),
                map(value.attachmentInfo(), PolicySnapshotCodec::storeLink),
                storeGoodsForm(value.goodsForm()));
    }

    private static PolicyDraftRequest readForm(PolicySnapshotData.Form value) {
        if (value == null) {
            return null;
        }
        var scenes = PolicyScenes.fromLegacy(value.sceneTypes(), value.sceneType());
        return new PolicyDraftRequest(
                value.recordVersion(),
                value.perspective(),
                value.selectedPlatforms(),
                value.verifyType(),
                PolicyScenes.single(scenes),
                scenes,
                value.estimated(),
                value.startTime(),
                value.endTime(),
                value.specialRemark(),
                map(value.attachmentInfo(), PolicySnapshotCodec::readLink),
                readGoodsForm(value.goodsForm()));
    }

    private static PolicySnapshotData.Link storeLink(PolicyDraftRequest.Link value) {
        return new PolicySnapshotData.Link(
                value.type(), value.url(), value.id(), value.originalName());
    }

    private static PolicyDraftRequest.Link readLink(PolicySnapshotData.Link value) {
        return new PolicyDraftRequest.Link(
                value.type(), value.url(), value.id(), value.originalName());
    }

    private static PolicySnapshotData.GoodsForm storeGoodsForm(PolicyDraftRequest.GoodsForm value) {
        if (value == null) {
            return null;
        }
        return new PolicySnapshotData.GoodsForm(
                map(value.cards(), PolicySnapshotCodec::storeCard), value.timeAdjustment());
    }

    private static PolicyDraftRequest.GoodsForm readGoodsForm(PolicySnapshotData.GoodsForm value) {
        if (value == null) {
            return null;
        }
        return new PolicyDraftRequest.GoodsForm(
                map(value.cards(), PolicySnapshotCodec::readCard), value.timeAdjustment());
    }

    private static PolicySnapshotData.Card storeCard(PolicyDraftRequest.Card value) {
        return new PolicySnapshotData.Card(
                value.key(),
                value.cardType(),
                value.followsOrderPlatforms(),
                map(value.targets(), PolicySnapshotCodec::storeTarget),
                map(value.subsidyPlans(), PolicySnapshotCodec::storePlan),
                map(value.platformAssignments(), PolicySnapshotCodec::storeAssignment));
    }

    private static PolicyDraftRequest.Card readCard(PolicySnapshotData.Card value) {
        return new PolicyDraftRequest.Card(
                value.key(),
                value.cardType(),
                value.followsOrderPlatforms(),
                map(value.targets(), PolicySnapshotCodec::readTarget),
                map(value.subsidyPlans(), PolicySnapshotCodec::readPlan),
                map(value.platformAssignments(), PolicySnapshotCodec::readAssignment));
    }

    private static PolicySnapshotData.Target storeTarget(PolicyDraftRequest.Target value) {
        return new PolicySnapshotData.Target(
                value.spuId(), value.ram(), value.rom(), value.goodsId());
    }

    private static PolicyDraftRequest.Target readTarget(PolicySnapshotData.Target value) {
        return new PolicyDraftRequest.Target(
                value.spuId(), value.ram(), value.rom(), value.goodsId());
    }

    private static PolicySnapshotData.SubsidyPlan storePlan(PolicyDraftRequest.SubsidyPlan value) {
        return new PolicySnapshotData.SubsidyPlan(
                value.key(),
                value.subsidyMode(),
                value.fixedAmount(),
                value.maxSubsidyAmount(),
                value.ratio(),
                value.priceBase());
    }

    private static PolicyDraftRequest.SubsidyPlan readPlan(PolicySnapshotData.SubsidyPlan value) {
        return new PolicyDraftRequest.SubsidyPlan(
                value.key(),
                value.subsidyMode(),
                value.fixedAmount(),
                value.maxSubsidyAmount(),
                value.ratio(),
                value.priceBase());
    }

    private static PolicySnapshotData.PlatformAssignment storeAssignment(
            PolicyDraftRequest.PlatformAssignment value) {
        return new PolicySnapshotData.PlatformAssignment(
                value.platformCode(), value.planKey(), value.quantity());
    }

    private static PolicyDraftRequest.PlatformAssignment readAssignment(
            PolicySnapshotData.PlatformAssignment value) {
        return new PolicyDraftRequest.PlatformAssignment(
                value.platformCode(), value.planKey(), value.quantity());
    }

    private static PolicySnapshotData.Goods storeGoods(ApplicantCatalogResponse value) {
        return new PolicySnapshotData.Goods(
                value.goodsId(),
                value.brandId(),
                value.brandName(),
                value.seriesId(),
                value.seriesName(),
                value.spuId(),
                value.modelName(),
                value.edition(),
                value.ram(),
                value.rom(),
                value.goodsName(),
                value.colorName(),
                value.onSale());
    }

    private static ApplicantCatalogResponse readGoods(PolicySnapshotData.Goods value) {
        return new ApplicantCatalogResponse(
                value.goodsId(),
                value.brandId(),
                value.brandName(),
                value.seriesId(),
                value.seriesName(),
                value.spuId(),
                value.modelName(),
                value.edition(),
                value.ram(),
                value.rom(),
                value.goodsName(),
                value.colorName(),
                value.onSale());
    }

    private static <T, R> List<R> map(List<T> values, Function<T, R> convert) {
        return values == null ? null : values.stream().map(convert).toList();
    }
}
