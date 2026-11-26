package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.formatTime;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.now;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.policyNumber;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.ChannelPlatform;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyTimelineQuery;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import com.company.policy.entity.PolicyConsent;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.entity.PolicyConsent.Target;
import com.company.policy.entity.PolicyTimeChange;
import com.company.policy.mapper.PolicyReviewMapper;
import com.company.policy.mapper.PolicyTimeChangeMapper;
import com.company.policy.mapper.UserPlatformMapper;
import com.company.policy.service.PolicyCoverageService;
import com.company.policy.support.policy.PolicyApplicabilityEvaluator;
import com.company.policy.support.policy.PolicyBatchExtensions;
import com.company.policy.support.policy.PolicyConfirmation;
import com.company.policy.support.policy.PolicyEffectivePeriods;
import com.company.policy.support.policy.PolicyEndTimes;
import com.company.policy.support.policy.PolicyFormValidation;
import com.company.policy.support.policy.PolicyRouteSummary;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.support.policy.PolicyTimelineBuilder;
import com.company.policy.support.policy.PolicyWorkflowProposals;
import com.company.policy.vo.PolicyApplicability;
import com.company.policy.vo.PolicyApplicability.AdjustmentOption;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.company.policy.vo.PolicyReviewResponse.Applied;
import com.company.policy.vo.PolicyReviewResponse.Effective;
import com.company.policy.vo.PolicyReviewResponse.Winner;
import com.company.policy.vo.PolicySubsidyTimeline;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PolicyCoverageServiceImpl implements PolicyCoverageService {
    private final PolicyReviewMapper reviews;
    private final PolicyTimeChangeMapper timeChanges;
    private final UserPlatformMapper platforms;
    private final PolicySnapshotCodec snapshots;
    private final PolicyBatchExtensions extensions;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    PolicyCoverageServiceImpl(
            PolicyReviewMapper reviews,
            PolicyTimeChangeMapper timeChanges,
            UserPlatformMapper platforms,
            PolicySnapshotCodec snapshots,
            PolicyBatchExtensions extensions,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        this.reviews = reviews;
        this.timeChanges = timeChanges;
        this.platforms = platforms;
        this.snapshots = snapshots;
        this.extensions = extensions;
        this.json = json;
    }

    @Override
    public PolicyApplicability applicability(
            PolicyApply entry, Snapshot snapshot, LocalDateTime asOf) {
        return applicability(entry, snapshot, asOf, asOf, Long.MAX_VALUE);
    }

    @Override
    public PolicyApplicability applicability(
            PolicyApply entry,
            Snapshot snapshot,
            LocalDateTime asOf,
            LocalDateTime submittedAt,
            long auditId) {
        return compareProposals(entry, snapshot, asOf, submittedAt, auditId, null);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PolicyRouteSummary workflowRoute(
            PolicyApply entry, Snapshot confirmed, LocalDateTime asOf) {
        var consent = confirmed.consent();
        if (!String.valueOf(entry.getId()).equals(confirmed.id())
                || !String.valueOf(entry.getApplicantId()).equals(confirmed.applicantId())
                || consent == null
                || !consent.supported()
                || consent.decisions() == null
                || consent.comparedRounds() == null) throw reconfirmWorkflow();
        var evaluation = new PolicyRouteSummary.Evaluation();
        var current = compareProposals(entry, confirmed, asOf, asOf, Long.MAX_VALUE, evaluation);
        if (current.status() == PolicyApplicability.Status.NO_EFFECTIVE_PERIOD) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "POLICY_NO_EFFECTIVE_PERIOD", "已无可生效时段，请调整截止时间后重新核对。");
        }
        if (PolicyConfirmation.requiresResubmission(consent, current)
                || !new HashSet<>(consent.comparedRounds())
                        .containsAll(PolicyConfirmation.comparedRounds(current)))
            throw reconfirmWorkflow();
        return PolicyRouteSummary.forApplication(
                confirmed.id(),
                evaluation.changes(),
                PolicyWorkflowProposals.adjustments(confirmed));
    }

    private static ApiException reconfirmWorkflow() {
        return new ApiException(
                HttpStatus.CONFLICT, "POLICY_RECONFIRM_REQUIRED", "无法还原本轮确认依据，请重新核对政策范围、时段和执行名单。");
    }

    // 提交预览只需候选范围。分流额外在相同的历史快照和机型范围上比较原、拟执行名单。
    private PolicyApplicability compareProposals(
            PolicyApply entry,
            Snapshot snapshot,
            LocalDateTime asOf,
            LocalDateTime submittedAt,
            long auditId,
            PolicyRouteSummary.Evaluation evaluation) {
        var goods = snapshot.goods();
        if (goods == null || goods.isEmpty()) throw reconfirmWorkflow();
        var category = goods.get(0);
        var options =
                adjustmentOptions(
                        entry,
                        asOf,
                        PolicyFormValidation.id(category.brandId()),
                        PolicyFormValidation.id(category.seriesId()));
        var changes =
                snapshot.consent() == null
                        ? List.<EndAdjustment>of()
                        : snapshot.consent().endAdjustments();
        PolicyEndTimes.validate(changes, options);
        var extra = extensions.extensionProposals(entry, snapshot.consent());
        extensions.verifyGoods(extra);
        var groups = PolicyWorkflowProposals.groups(snapshot, extra);
        return evaluateGroups(
                entry, groups, asOf, submittedAt, auditId, options, evaluation, snapshot.consent());
    }

    @Override
    public PolicyApplicability adjustmentCoverage(
            PolicyApply applicant,
            List<Snapshot> proposals,
            Map<Target, AdjustmentOption> options,
            LocalDateTime at) {
        if (proposals.isEmpty()) {
            return new PolicyApplicability(
                    "SNAPSHOT_DATES",
                    formatTime(at),
                    formatTime(at),
                    formatTime(at),
                    PolicyApplicability.Status.DIRECT,
                    true,
                    List.of(),
                    List.of());
        }
        var groups =
                PolicyWorkflowProposals.groups(
                        proposals.get(0), proposals.subList(1, proposals.size()));
        return evaluateGroups(applicant, groups, at, at, Long.MAX_VALUE, options, null, null);
    }

    private PolicyApplicability evaluateGroups(
            PolicyApply entry,
            List<List<Snapshot>> groups,
            LocalDateTime asOf,
            LocalDateTime submittedAt,
            long auditId,
            Map<Target, AdjustmentOption> options,
            PolicyRouteSummary.Evaluation evaluation,
            com.company.policy.entity.PolicyConsent consent) {
        var proposed = groups.stream().flatMap(List::stream).toList();
        var scopes = new ArrayList<PolicyApplicability.ScopeResult>();
        var status = PolicyApplicability.Status.DIRECT;
        var first =
                proposed.stream()
                        .map(p -> PolicyFormValidation.time(p.form().startTime()))
                        .min(LocalDateTime::compareTo)
                        .orElseThrow();
        if (first.isBefore(asOf)) first = asOf;
        var through =
                proposed.stream()
                        .map(PolicyApplicabilityEvaluator::originalThrough)
                        .max(LocalDateTime::compareTo)
                        .orElseThrow();
        for (var group : groups) {
            var evaluator = new PolicyApplicabilityEvaluator(group, asOf, submittedAt, auditId);
            includeHistory(
                    entry.getTenantKey(),
                    entry.getId(),
                    group.get(0).form().perspective(),
                    asOf,
                    evaluator,
                    evaluation != null);
            scopes.addAll(evaluator.result(options).scopes());
            if (evaluation != null) {
                evaluator.workflowSegments(consent, options, evaluation);
            }
        }
        if (!scopes.isEmpty()) status = PolicyApplicability.Status.NEEDS_CONFIRMATION;
        if (proposed.stream()
                .anyMatch(p -> PolicyApplicabilityEvaluator.originalThrough(p).isBefore(asOf)))
            status = PolicyApplicability.Status.NO_EFFECTIVE_PERIOD;
        return new PolicyApplicability(
                "SNAPSHOT_DATES",
                formatTime(asOf),
                formatTime(first),
                formatTime(through),
                status,
                status == PolicyApplicability.Status.DIRECT,
                scopes,
                options.values().stream()
                        .filter(option -> option.canExtend() || option.canShorten())
                        .toList());
    }

    @Override
    public List<AdjustmentOption> adjustableFor(PolicyApply applicant, LocalDateTime asOf) {
        return List.copyOf(adjustmentOptions(applicant, asOf, null, null).values());
    }

    private Map<Target, AdjustmentOption> adjustmentOptions(
            PolicyApply entry, LocalDateTime asOf, Long brandId, Long seriesId) {
        var options = new LinkedHashMap<Target, AdjustmentOption>();
        var assigned = platforms.assigned(entry.getTenantKey(), entry.getApplicantId());
        for (var old :
                reviews.adjustmentCandidates(
                        entry.getTenantKey(),
                        entry.getApplicantId(),
                        asOf,
                        asOf.toLocalDate().toString(),
                        brandId,
                        seriesId)) {
            var start = approvedTime(old.startTime());
            if (start.isBefore(old.auditTime())) start = old.auditTime();
            var id = Long.toString(old.policyId());
            var auditId = Long.toString(old.auditId());
            var through = old.endTime();
            if (through == null) throw reconfirmWorkflow();
            // 起效前已整单结束的未来单，以及已经自然到期的旧单，没有可再次提前的时段。
            boolean canShorten = !through.isBefore(start) && !through.isBefore(asOf);
            boolean ownsAll =
                    old.applicantId() == entry.getApplicantId()
                            && allPlatformsAssigned(old, assigned);
            boolean canExtend =
                    ownsAll && through.isBefore(LocalDateTime.of(9999, 12, 31, 23, 59, 59));
            if (!canShorten && !canExtend) continue;
            var target = new Target(id, auditId, formatTime(through), old.recordVersion());
            options.put(
                    target,
                    new AdjustmentOption(
                            target,
                            policyNumber(old.policyId()),
                            canShorten ? formatTime(start.minusSeconds(1)) : null,
                            canShorten ? formatTime(through.minusSeconds(1)) : null,
                            old.perspective(),
                            canShorten,
                            canExtend));
        }
        return options;
    }

    private boolean allPlatformsAssigned(
            PolicyReviewMapper.AdjustmentCandidate old, List<ChannelPlatform> assigned) {
        try {
            if (old.platforms() == null) throw reconfirmWorkflow();
            var codes = json.readValue(old.platforms(), String[].class);
            if (codes == null) throw reconfirmWorkflow();
            return codes.length > 0
                    && java.util.Arrays.stream(codes)
                            .allMatch(
                                    code ->
                                            assigned.contains(
                                                    new ChannelPlatform(old.perspective(), code)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw reconfirmWorkflow();
        }
    }

    private static LocalDateTime approvedTime(String value) {
        if (value == null) throw reconfirmWorkflow();
        try {
            return PolicyFormValidation.time(value);
        } catch (ApiException invalidTime) {
            throw reconfirmWorkflow();
        }
    }

    private PolicyEffectivePeriods.Change replayChange(PolicyTimeChange row) {
        try {
            var consent = json.readValue(row.getConsentJson(), PolicyConsent.class);
            if (consent == null
                    || !consent.supported()
                    || consent.comparedRounds() == null
                    || consent.decisions() == null) throw reconfirmWorkflow();
            return new PolicyEffectivePeriods.Change(
                    row.getId(),
                    "EXTEND".equals(row.getDirection()),
                    row.getEffectiveAt(),
                    row.getActualEnd(),
                    row.getChangedAt(),
                    consent.decisions(),
                    consent.comparedRounds().stream()
                            .map(PolicyConsent.ComparedRound::policyId)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw reconfirmWorkflow();
        }
    }

    @Override
    public void lockScope(PolicyApply entry, PolicyDraftRequest form, List<EndAdjustment> changes) {
        var models = new java.util.TreeSet<Long>();
        form.goodsForm().cards().stream()
                .flatMap(c -> c.targets().stream())
                .map(t -> Long.valueOf(t.spuId()))
                .forEach(models::add);
        lockScope(entry, models, changes);
    }

    @Override
    public void lockAdjustmentScope(PolicyApply applicant, List<EndAdjustment> changes) {
        lockScope(applicant, new java.util.TreeSet<>(), changes);
    }

    private void lockScope(
            PolicyApply entry, java.util.TreeSet<Long> models, List<EndAdjustment> changes) {
        boolean adjustsOthers = changes != null && !changes.isEmpty();
        if (adjustsOthers) {
            // 审批期间的分工移交与提交使用同一把申请人行锁，权限复核一直保持到事务提交。
            platforms.lockApplicant(entry.getTenantKey(), entry.getApplicantId());
            var targets = new java.util.HashSet<Long>();
            for (var change : changes) {
                if (change == null || change.target() == null)
                    throw PolicyFormValidation.invalid("请选择要提前结束的已通过政策。");
                targets.add(PolicyFormValidation.id(change.target().auditLogId()));
            }
            var logs = List.copyOf(targets);
            for (int i = 0; i < logs.size(); i += 500)
                models.addAll(
                        reviews.adjustmentModels(
                                entry.getTenantKey(),
                                entry.getApplicantId(),
                                logs.subList(i, Math.min(i + 500, logs.size()))));
        }
        var ids = List.copyOf(models);
        for (int i = 0; i < ids.size(); i += 500) {
            var batch = ids.subList(i, Math.min(i + 500, ids.size()));
            if (reviews.lockScopeModels(batch).size() != batch.size()) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "POLICY_GOODS_CHANGED", "机型资料已变化，请返回编辑并重新选择商品。");
            }
        }
        extensions.lockGoods(entry, changes);
    }

    @Override
    public Effective effective(
            String tenant,
            String channel,
            String platform,
            long goodsId,
            LocalDateTime requestedAt,
            List<String> brandNames) {
        var clock = now();
        var at = requestedAt == null ? clock : requestedAt;
        var config = reviews.configuration(goodsId);
        requireReadableBrand(brandNames, config == null ? null : config.brandName());
        if (config == null) return new Effective(formatTime(at), List.of());
        PolicyApplicability.Scope scope;
        if ("RETAIL".equals(channel))
            scope = new PolicyApplicability.Scope(platform, goodsId, null, null, null);
        else {
            scope =
                    new PolicyApplicability.Scope(
                            platform, null, config.spuId(), config.ram(), config.rom());
        }
        var evaluator = new PolicyApplicabilityEvaluator(channel, scope, at);
        var history = includeHistory(tenant, 0, channel, at, evaluator, false);
        var applied =
                evaluator.effectivePolicies(scope).stream()
                        .map(
                                p -> {
                                    var summary =
                                            new Winner(
                                                    p.policyId(),
                                                    p.policyCode(),
                                                    p.auditLogId(),
                                                    p.submissionVersion(),
                                                    p.submittedAt(),
                                                    p.effectiveFrom());
                                    return new Applied(
                                            summary, history.get(p.policyId()), p.selections());
                                })
                        .toList();
        return new Effective(formatTime(at), applied);
    }

    private static void requireReadableBrand(List<String> brandNames, String brandName) {
        if (brandNames != null && (brandName == null || !brandNames.contains(brandName)))
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "POLICY_SUBSIDY_BRAND_FORBIDDEN", "当前机型不在本人负责的采购品牌内。");
    }

    @Override
    public List<PolicyApplicability.Scope> adoptedScopes(
            String tenant,
            long policyId,
            String channel,
            List<PolicyApplicability.Scope> scopes,
            LocalDateTime at) {
        if (scopes.isEmpty()) return List.of();
        var evaluator = new PolicyApplicabilityEvaluator(channel, scopes, at);
        includeHistory(tenant, 0, channel, at, evaluator, false);
        String id = Long.toString(policyId);
        return scopes.stream()
                .filter(
                        scope ->
                                evaluator.effectivePolicies(scope).stream()
                                        .anyMatch(p -> id.equals(p.policyId())))
                .toList();
    }

    @Override
    public PolicySubsidyTimeline subsidyTimeline(
            String tenant,
            PolicyTimelineQuery query,
            List<ChannelPlatform> scopes,
            List<String> brandNames) {
        if (brandNames != null)
            requireReadableBrand(brandNames, reviews.modelBrandName(query.spuId()));
        var asOf = now();
        if (scopes.isEmpty()) return new PolicySubsidyTimeline(formatTime(asOf), 0, List.of());
        var goods = reviews.configurationGoods(query.spuId(), query.ram(), query.rom());
        if (goods.isEmpty()) return new PolicySubsidyTimeline(formatTime(asOf), 0, List.of());
        var index = reviews.timelineIndex(tenant, query, scopes, asOf);
        var ids = timelineAuditIds(index, query.dateFrom(), query.dateTo(), asOf);
        if (ids.isEmpty())
            return new PolicySubsidyTimeline(formatTime(asOf), goods.size(), List.of());
        var timelines = new LinkedHashMap<String, PolicyTimelineBuilder>();
        for (var channel : List.of("RETAIL", "BILLION")) {
            var codes =
                    scopes.stream()
                            .filter(scope -> scope.perspective().equals(channel))
                            .map(ChannelPlatform::platformCode)
                            .toList();
            if (!codes.isEmpty()) {
                timelines.put(
                        channel,
                        new PolicyTimelineBuilder(
                                channel,
                                codes,
                                query.spuId(),
                                query.ram(),
                                query.rom(),
                                goods,
                                query.dateFrom().atStartOfDay(),
                                query.dateTo().atTime(23, 59, 59),
                                asOf));
            }
        }
        for (int i = 0; i < ids.size(); i += 500) {
            var logs =
                    reviews.coverageSnapshots(
                            tenant, ids.subList(i, Math.min(i + 500, ids.size())));
            if (logs.isEmpty()) continue;
            for (var row :
                    timeChanges.through(
                            tenant,
                            asOf,
                            logs.stream().map(PolicyAuditLog::getPolicyId).toList())) {
                var change = replayChange(row);
                timelines
                        .values()
                        .forEach(
                                timeline ->
                                        timeline.includeTimeChange(row.getAuditLogId(), change));
            }
            for (var log : logs) {
                var snapshot = snapshots.read(log.getSnapshotData());
                var timeline = timelines.get(snapshot.form().perspective());
                if (timeline != null) {
                    timeline.include(snapshot, log.getId(), log.getCreatedAt(), log.getAuditTime());
                }
            }
        }
        var tracks = timelines.values().stream().flatMap(t -> t.tracks().stream()).toList();
        return new PolicySubsidyTimeline(formatTime(asOf), goods.size(), tracks);
    }

    private record IndexedPeriod(long id, LocalDate first, LocalDate last) {
        boolean overlaps(LocalDate from, LocalDate to) {
            return !first.isAfter(to) && !last.isBefore(from);
        }
    }

    // 先找命中原单，再取其完整日期内会改变采用结果的其他轮次。
    private List<Long> timelineAuditIds(
            List<PolicyReviewMapper.TimelineIndex> index,
            LocalDate queryFrom,
            LocalDate queryTo,
            LocalDateTime asOf) {
        var periods =
                index.stream()
                        .map(
                                row ->
                                        new IndexedPeriod(
                                                row.id(),
                                                LocalDate.parse(row.firstDate()),
                                                LocalDate.parse(row.lastDate())))
                        .toList();
        var matching = periods.stream().filter(p -> p.overlaps(queryFrom, queryTo)).toList();
        if (matching.isEmpty()) return List.of();
        var from =
                matching.stream().map(IndexedPeriod::first).min(LocalDate::compareTo).orElseThrow();
        var last =
                matching.stream().map(IndexedPeriod::last).max(LocalDate::compareTo).orElseThrow();
        var today = asOf.toLocalDate();
        var start = queryFrom.isAfter(today) && from.isBefore(today) ? today : from;
        var through = queryTo.isBefore(today) && last.isAfter(today) ? today : last;
        return periods.stream()
                .filter(p -> p.overlaps(start, through))
                .map(IndexedPeriod::id)
                .toList();
    }

    private Map<String, Snapshot> includeHistory(
            String tenant,
            long draftId,
            String channel,
            LocalDateTime asOf,
            PolicyApplicabilityEvaluator evaluator,
            boolean workflow) {
        var history = new HashMap<String, Snapshot>();
        var scopes = evaluator.scopes();
        if (scopes.isEmpty()) return history;
        var auditIds = new LinkedHashSet<Long>();
        for (int offset = 0; offset < scopes.size(); offset += 500) {
            auditIds.addAll(
                    reviews.overlappingAuditIds(
                            tenant,
                            draftId,
                            channel,
                            asOf,
                            evaluator.firstDate(),
                            evaluator.lastDate(),
                            scopes.subList(offset, Math.min(offset + 500, scopes.size())),
                            workflow));
        }
        // 一个历史轮次可命中多项配置和多批范围。快照只读取、解析一次。
        var ids = List.copyOf(auditIds);
        for (int offset = 0; offset < ids.size(); offset += 500) {
            var batch = ids.subList(offset, Math.min(offset + 500, ids.size()));
            var logs = reviews.coverageSnapshots(tenant, batch);
            if (workflow && logs.size() != batch.size()) throw reconfirmWorkflow();
            if (logs.isEmpty()) continue;
            for (var row :
                    timeChanges.through(
                            tenant,
                            asOf,
                            logs.stream().map(PolicyAuditLog::getPolicyId).toList())) {
                evaluator.includeTimeChange(row.getAuditLogId(), replayChange(row));
            }
            for (var log : logs) {
                var prior = replayHistory(log, channel, workflow, evaluator);
                history.put(prior.id(), prior);
            }
        }
        return history;
    }

    private Snapshot replayHistory(
            PolicyAuditLog log,
            String channel,
            boolean workflow,
            PolicyApplicabilityEvaluator evaluator) {
        try {
            var prior = snapshots.read(log.getSnapshotData());
            if (workflow) {
                if (!prior.id().equals(String.valueOf(log.getPolicyId()))
                        || prior.submissionVersion() != log.getVersion()
                        || !channel.equals(prior.form().perspective())) throw reconfirmWorkflow();
                PolicySnapshotCodec.requireReplayableCards(prior);
            }
            if (workflow) {
                evaluator.includeWorkflow(
                        prior, log.getId(), log.getCreatedAt(), log.getAuditTime());
            } else {
                evaluator.include(prior, log.getId(), log.getCreatedAt(), log.getAuditTime());
            }
            return prior;
        } catch (IllegalStateException | IllegalArgumentException | NullPointerException error) {
            if (workflow) throw reconfirmWorkflow();
            throw error;
        } catch (ApiException error) {
            // 快照中的非法时间来自已批准记录，不能让调用方误以为是当前表单填写错误。
            if (workflow && error.getStatus() == HttpStatus.BAD_REQUEST) throw reconfirmWorkflow();
            throw error;
        }
    }
}
