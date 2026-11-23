package com.company.policy.support.policy;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import com.company.policy.entity.PolicyConsent;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.CatalogGoodsMapper;
import com.company.policy.mapper.PolicyApplyMapper;
import com.company.policy.mapper.PolicyReviewMapper;
import com.company.policy.vo.ApplicantCatalogResponse;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 从原单已通过快照还原延期段。父单获批时在同一事务写入真实子单和补贴明细。 */
@Component
public class PolicyBatchExtensions {
    private final PolicyApplyMapper policies;
    private final PolicyReviewMapper reviews;
    private final CatalogGoodsMapper catalog;
    private final PolicySnapshotCodec codec;
    private final ObjectMapper json;

    public PolicyBatchExtensions(
            PolicyApplyMapper policies,
            PolicyReviewMapper reviews,
            CatalogGoodsMapper catalog,
            PolicySnapshotCodec codec,
            ObjectMapper json) {
        this.policies = policies;
        this.reviews = reviews;
        this.catalog = catalog;
        this.codec = codec;
        this.json = json;
    }

    public List<Snapshot> proposals(PolicyApply parent, Snapshot request) {
        if (request.consent() == null) return List.of();
        var changes =
                request.consent().endAdjustments().stream()
                        .filter(PolicyEndTimes::isExtension)
                        .toList();
        if (changes.isEmpty()) return List.of();
        var byAudit = new HashMap<String, Snapshot>();
        var auditIds =
                changes.stream()
                        .map(a -> PolicyFormValidation.id(a.target().auditLogId()))
                        .toList();
        for (int i = 0; i < auditIds.size(); i += 500) {
            for (var log :
                    reviews.coverageSnapshots(
                            parent.getTenantKey(),
                            auditIds.subList(i, Math.min(i + 500, auditIds.size())))) {
                byAudit.put(log.getId().toString(), approvedSource(log, parent));
            }
        }
        var proposals = new ArrayList<Snapshot>();
        for (var change : changes) {
            var source = byAudit.get(change.target().auditLogId());
            if (source == null || !source.id().equals(change.target().policyId())) throw changed();
            var form = source.form();
            var originalCode = policyCode(Long.parseLong(source.id()));
            var extensionForm =
                    new PolicyDraftRequest(
                            null,
                            form.perspective(),
                            form.selectedPlatforms(),
                            form.verifyType(),
                            form.sceneType(),
                            form.sceneTypes(),
                            form.estimated(),
                            PolicyApplicabilityEvaluator.time(
                                    PolicyFormValidation.time(change.target().previousEnd())
                                            .plusSeconds(1)),
                            change.endTime(),
                            form.specialRemark(),
                            form.attachmentInfo(),
                            new PolicyDraftRequest.GoodsForm(form.goodsForm().cards(), change));
            proposals.add(
                    new Snapshot(
                            "extension:" + parent.getId() + ":" + source.id(),
                            originalCode + "（延期）",
                            source.applicantId(),
                            source.applicantName(),
                            1,
                            extensionForm,
                            source.goods(),
                            request.consent(),
                            originalCode));
        }
        return proposals;
    }

    private Snapshot approvedSource(PolicyAuditLog log, PolicyApply parent) {
        try {
            var source = codec.read(log.getSnapshotData());
            if (!String.valueOf(log.getPolicyId()).equals(source.id())
                    || log.getVersion() != source.submissionVersion()
                    || !parent.getApplicantId().toString().equals(source.applicantId()))
                throw changed();
            PolicySnapshotCodec.requireReplayableCards(source);
            return source;
        } catch (IllegalStateException
                | IllegalArgumentException
                | NullPointerException invalidSource) {
            throw changed();
        }
    }

    public void verifyGoods(List<Snapshot> proposals) {
        var ids =
                proposals.stream()
                        .flatMap(p -> p.form().goodsForm().cards().stream())
                        .flatMap(c -> c.targets().stream())
                        .map(t -> Long.valueOf(t.goodsId()))
                        .distinct()
                        .sorted()
                        .toList();
        var current = new HashMap<String, ApplicantCatalogResponse>();
        for (int i = 0; i < ids.size(); i += 500) {
            for (var item : catalog.findForPolicy(ids.subList(i, Math.min(i + 500, ids.size())))) {
                var good = ApplicantCatalogResponse.from(item);
                current.put(good.goodsId(), good);
            }
        }
        for (var proposal : proposals) {
            for (var card : proposal.form().goodsForm().cards()) {
                for (var target : card.targets()) {
                    var good = current.get(target.goodsId());
                    if (good == null
                            || !good.onSale()
                            || !target.spuId().equals(good.spuId())
                            || !target.ram().equals(good.ram())
                            || !target.rom().equals(good.rom())) {
                        throw new ApiException(
                                HttpStatus.CONFLICT,
                                "POLICY_ADJUSTMENT_CHANGED",
                                "延期目标的商品已下架或配置已变化，请重新核对。");
                    }
                }
            }
        }
    }

    /** 在已锁定机型后固定延期范围的商品状态，商品中心并发改为下架时按取得锁的顺序生效。 */
    public void lockGoods(PolicyApply entry, List<EndAdjustment> changes) {
        if (changes == null) return;
        var ids =
                changes.stream()
                        .filter(PolicyEndTimes::isExtension)
                        .map(c -> Long.valueOf(c.target().auditLogId()))
                        .distinct()
                        .sorted()
                        .toList();
        var goods = new java.util.TreeSet<Long>();
        for (int i = 0; i < ids.size(); i += 500) {
            goods.addAll(
                    reviews.extensionGoods(
                            entry.getTenantKey(),
                            entry.getApplicantId(),
                            ids.subList(i, Math.min(i + 500, ids.size()))));
        }
        var sorted = List.copyOf(goods);
        for (int i = 0; i < sorted.size(); i += 500) {
            var batch = sorted.subList(i, Math.min(i + 500, sorted.size()));
            if (catalog.lockSelected(batch).size() != batch.size()) throw changed();
        }
    }

    public void approve(
            PolicyApply parent,
            Snapshot request,
            UserInfo admin,
            LocalDateTime submitted,
            LocalDateTime approved) {
        for (var proposal : proposals(parent, request)) {
            var form = proposal.form();
            var change = form.goodsForm().timeAdjustment();
            var child = new PolicyApply();
            child.setTenantKey(parent.getTenantKey());
            child.setApplicantId(parent.getApplicantId());
            child.setApplicantName(parent.getApplicantName());
            child.setPolicyNo(
                    UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT));
            child.setExtensionSourceId(Long.valueOf(change.target().policyId()));
            child.setChannelType(form.perspective());
            child.setPlatforms(encode(form.selectedPlatforms()));
            child.setWriteOffType(form.verifyType());
            child.setSceneTypes(form.sceneTypes() == null ? null : encode(form.sceneTypes()));
            child.setIsEstimated(form.estimated());
            child.setStartTime(PolicyFormValidation.time(form.startTime()));
            child.setEndTime(PolicyFormValidation.time(form.endTime()));
            child.setRemark(form.specialRemark());
            child.setAttachmentUrls(encode(form.attachmentInfo()));
            child.setGoodsFormJson(encode(form.goodsForm()));
            child.setStatus("DRAFT");
            child.setRecordVersion(1);
            if (policies.insert(child, submitted) != 1
                    || policies.markSubmitted(child, submitted) != 1) throw changed();
            var decisions =
                    request.consent().decisions().stream()
                            .filter(d -> d.eligiblePolicyIds().contains(proposal.id()))
                            .toList();
            var saved =
                    new Snapshot(
                            child.getId().toString(),
                            policyCode(child.getId()),
                            proposal.applicantId(),
                            proposal.applicantName(),
                            1,
                            form,
                            proposal.goods(),
                            new PolicyConsent(
                                    4,
                                    request.consent().comparedRounds(),
                                    decisions,
                                    List.of(),
                                    proposal.id()),
                            proposal.sourcePolicyCode());
            if (policies.insertAudit(
                            child.getId(),
                            child.getTenantKey(),
                            1,
                            codec.write(saved),
                            submitted,
                            0)
                    != 1) throw changed();
            var lines = new ArrayList<PolicyApplyMapper.DetailLine>();
            for (var card : form.goodsForm().cards()) {
                for (var target : card.targets()) {
                    for (var assignment : card.platformAssignments()) {
                        lines.add(
                                new PolicyApplyMapper.DetailLine(
                                        assignment.platformCode(),
                                        Long.valueOf(target.goodsId()),
                                        Long.valueOf(target.spuId()),
                                        target.ram(),
                                        target.rom()));
                    }
                }
            }
            for (int i = 0; i < lines.size(); i += 500) {
                var page = lines.subList(i, Math.min(i + 500, lines.size()));
                if (policies.insertDetails(child.getId(), 1, page, submitted) != page.size())
                    throw changed();
            }
            if (reviews.review(child.getId(), child.getTenantKey(), 2, 1, "APPROVED", approved) != 1
                    || reviews.reviewAudit(
                                    child.getId(),
                                    child.getTenantKey(),
                                    1,
                                    "APPROVED",
                                    admin.getId(),
                                    admin.getName(),
                                    null,
                                    approved)
                            != 1) throw changed();
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("不能保存延期表单。", error);
        }
    }

    static String policyCode(long id) {
        return String.format(Locale.ROOT, "ZC-%06d", id);
    }

    private static ApiException changed() {
        return new ApiException(
                HttpStatus.CONFLICT, "POLICY_ADJUSTMENT_CHANGED", "延期目标已变化，请重新核对后送审。");
    }
}
