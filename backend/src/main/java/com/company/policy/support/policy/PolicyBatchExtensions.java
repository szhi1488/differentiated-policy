package com.company.policy.support.policy;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import com.company.policy.entity.PolicyConsent;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.entity.PolicyTimeChange;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.CatalogGoodsMapper;
import com.company.policy.mapper.PolicyReviewMapper;
import com.company.policy.mapper.PolicyTimeChangeMapper;
import com.company.policy.vo.ApplicantCatalogResponse;
import com.company.policy.vo.PolicyApplicability.Group;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 按原获批快照试算新增时段。改期生效时更新原政策并逐项保存调整依据。 */
@Component
public class PolicyBatchExtensions {
    private final PolicyTimeChangeMapper history;
    private final PolicyReviewMapper reviews;
    private final CatalogGoodsMapper catalog;
    private final PolicySnapshotCodec codec;
    private final ObjectMapper json;

    public PolicyBatchExtensions(
            PolicyTimeChangeMapper history,
            PolicyReviewMapper reviews,
            CatalogGoodsMapper catalog,
            PolicySnapshotCodec codec,
            ObjectMapper json) {
        this.history = history;
        this.reviews = reviews;
        this.catalog = catalog;
        this.codec = codec;
        this.json = json;
    }

    public List<Snapshot> extensionProposals(PolicyApply parent, PolicyConsent consent) {
        if (consent == null) return List.of();
        var changes =
                consent.endAdjustments().stream().filter(PolicyEndTimes::isExtension).toList();
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
                byAudit.put(log.getId().toString(), approvedSource(log));
            }
        }
        var proposals = new ArrayList<Snapshot>();
        for (var change : changes) {
            var source = byAudit.get(change.target().auditLogId());
            if (source == null || !source.id().equals(change.target().policyId())) throw changed();
            if (!parent.getApplicantId().toString().equals(source.applicantId())) throw changed();
            var form = source.form();
            var originalCode = source.policyCode();
            var adjustmentForm =
                    new PolicyDraftRequest(
                            null,
                            form.perspective(),
                            form.selectedPlatforms(),
                            form.verifyType(),
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
                            extensionKey(parent, change),
                            originalCode + "（延期）",
                            parent.getApplicantId().toString(),
                            source.applicantName(),
                            source.submissionVersion(),
                            adjustmentForm,
                            source.goods(),
                            consent,
                            originalCode,
                            source.wholeConfigurationSelection()));
        }
        return proposals;
    }

    private Snapshot approvedSource(PolicyAuditLog log) {
        try {
            var source = codec.read(log.getSnapshotData());
            if (!String.valueOf(log.getPolicyId()).equals(source.id())
                    || log.getVersion() != source.submissionVersion()) throw changed();
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
                        .map(c -> PolicyFormValidation.id(c.target().auditLogId()))
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

    public List<PolicyTimeChange> approve(
            PolicyApply parent, Snapshot request, UserInfo admin, LocalDateTime approved) {
        if (request.consent() == null || request.consent().endAdjustments().isEmpty())
            return List.of();
        return saveApproved(
                parent,
                request.consent().endAdjustments(),
                request.consent(),
                admin,
                approved,
                null,
                null);
    }

    /** 写入顺序与用户核对顺序一致；事务由独立提交或采购终审统一管理。 */
    public List<PolicyTimeChange> saveApproved(
            PolicyApply parent,
            List<EndAdjustment> changes,
            PolicyConsent confirmed,
            UserInfo actor,
            LocalDateTime approved,
            String requestKey,
            String requestHash) {
        var aliases = new HashMap<String, String>();
        for (var change : changes)
            if (PolicyEndTimes.isExtension(change))
                aliases.put(extensionKey(parent, change), change.target().policyId());

        var saved = new ArrayList<PolicyTimeChange>();
        for (var change : changes) {
            var target = change.target();
            var policyId = PolicyFormValidation.id(target.policyId());
            var original = history.original(parent.getTenantKey(), policyId);
            var before = PolicyFormValidation.time(target.previousEnd());
            if (original == null
                    || !before.equals(original.getEndTime())
                    || !Objects.equals(original.getRecordVersion(), target.recordVersion()))
                throw changed();
            var requested = PolicyFormValidation.time(change.endTime());
            boolean extension = PolicyEndTimes.isExtension(change);
            if (!extension && approved.isAfter(before)) throw changed();
            var actualEnd =
                    !extension && requested.isBefore(approved)
                            ? approved.minusSeconds(1)
                            : requested;
            var effective = extension ? before.plusSeconds(1) : actualEnd.plusSeconds(1);
            if (extension && effective.isBefore(approved)) effective = approved;
            if (extension && actualEnd.isBefore(effective)) throw changed();

            var item = new PolicyTimeChange();
            item.setTenantKey(parent.getTenantKey());
            item.setPolicyId(policyId);
            item.setPolicyVersion(original.getCurrentVersion());
            item.setAuditLogId(PolicyFormValidation.id(target.auditLogId()));
            item.setPreviousEnd(before);
            item.setRequestedEnd(requested);
            item.setActualEnd(actualEnd);
            item.setEffectiveAt(effective);
            item.setChangedAt(approved);
            item.setDirection(extension ? "EXTEND" : "SHORTEN");
            item.setReason(change.reason());
            item.setActorId(actor.getId());
            item.setActorName(actor.getName());
            item.setAttachmentJson(encode(change.attachmentInfo()));
            item.setConsentJson(
                    encode(originalConsent(extensionKey(parent, change), confirmed, aliases)));
            item.setRequestKey(requestKey);
            item.setRequestHash(requestHash);
            if (requestKey == null) {
                item.setSourcePolicyId(parent.getId());
                item.setSourceVersion(parent.getCurrentVersion());
            }
            item.setAfterRecordVersion(original.getRecordVersion() + 1);
            if (history.updateEnd(
                                    item.getTenantKey(),
                                    policyId,
                                    original.getRecordVersion(),
                                    before,
                                    actualEnd,
                                    approved)
                            != 1
                    || history.insert(item) != 1) throw changed();
            saved.add(item);
        }
        return saved;
    }

    private static String extensionKey(PolicyApply parent, EndAdjustment change) {
        return extensionKey(parent.getId().toString(), change.target().policyId());
    }

    static String extensionKey(String parentId, String sourceId) {
        return "extension:" + parentId + ":" + sourceId;
    }

    /** 预览用增量时段区分同一张政策。落库的候选、分组和最终采用名单全部使用真实政策 ID。 */
    private PolicyConsent originalConsent(
            String proposalId, PolicyConsent confirmed, Map<String, String> aliases) {
        var decisions =
                confirmed.decisions().stream()
                        .filter(d -> d.eligiblePolicyIds().contains(proposalId))
                        .map(
                                d ->
                                        new PolicyConsent.Decision(
                                                d.scope(),
                                                d.from(),
                                                d.through(),
                                                d.action(),
                                                d.groups().stream()
                                                        .map(
                                                                g ->
                                                                        new Group(
                                                                                g.sceneTypes(),
                                                                                g.verifyType(),
                                                                                policyIds(
                                                                                        g
                                                                                                .policyIds(),
                                                                                        aliases)))
                                                        .toList(),
                                                policyIds(d.eligiblePolicyIds(), aliases),
                                                policyIds(d.selectedPolicyIds(), aliases)))
                        .toList();
        return new PolicyConsent(4, confirmed.comparedRounds(), decisions);
    }

    private List<String> policyIds(List<String> ids, Map<String, String> aliases) {
        return ids.stream().map(id -> aliases.getOrDefault(id, id)).distinct().toList();
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法保存政策时间调整记录。", error);
        }
    }

    static String policyCode(long id) {
        return String.format(Locale.ROOT, "ZC-%06d", id);
    }

    private static ApiException changed() {
        return new ApiException(
                HttpStatus.CONFLICT, "POLICY_ADJUSTMENT_CHANGED", "原政策已变化，请重新核对截止和版本。");
    }
}
