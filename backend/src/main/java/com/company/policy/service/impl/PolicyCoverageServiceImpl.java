package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.formatTime;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.now;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.policyNumber;
import static com.company.policy.support.policy.PolicyWorkflowProposals.onlyShortens;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.ChannelPlatform;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.dto.PolicyTimelineQuery;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.entity.PolicyConsent.Target;
import com.company.policy.mapper.PolicyApplyMapper.DetailLine;
import com.company.policy.mapper.PolicyReviewMapper;
import com.company.policy.mapper.PolicyReviewMapper.DraftScope;
import com.company.policy.mapper.PolicyReviewMapper.WinnerRow;
import com.company.policy.mapper.UserPlatformMapper;
import com.company.policy.service.PolicyCoverageService;
import com.company.policy.support.policy.PolicyApplicabilityEvaluator;
import com.company.policy.support.policy.PolicyBatchExtensions;
import com.company.policy.support.policy.PolicyConfirmation;
import com.company.policy.support.policy.PolicyEndTimes;
import com.company.policy.support.policy.PolicyExecutionChange;
import com.company.policy.support.policy.PolicyFormValidation;
import com.company.policy.support.policy.PolicyRouteSummary;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.support.policy.PolicyTimelineBuilder;
import com.company.policy.support.policy.PolicyWorkflowProposals;
import com.company.policy.vo.PolicyApplicability;
import com.company.policy.vo.PolicyApplicability.AdjustmentOption;
import com.company.policy.vo.PolicyResponse.ApprovedEnd;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.company.policy.vo.PolicyReviewResponse.Applied;
import com.company.policy.vo.PolicyReviewResponse.Coverage;
import com.company.policy.vo.PolicyReviewResponse.CoverageItem;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PolicyCoverageServiceImpl implements PolicyCoverageService {
    private final PolicyReviewMapper reviews;
    private final UserPlatformMapper platforms;
    private final PolicySnapshotCodec snapshots;
    private final PolicyBatchExtensions extensions;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    private enum DateBasis {
        SNAPSHOT,
        WORKFLOW,
        BEFORE_CUTOVER,
        UNKNOWN_CUTOVER
    }

    private final LocalDateTime cutover;

    PolicyCoverageServiceImpl(
            PolicyReviewMapper reviews,
            UserPlatformMapper platforms,
            PolicySnapshotCodec snapshots,
            PolicyBatchExtensions extensions,
            com.fasterxml.jackson.databind.ObjectMapper json,
            @Value("${policy.applicability-cutover-at:}") String cutoverAt) {
        this.cutover = cutoverAt.isBlank() ? null : PolicyFormValidation.time(cutoverAt.strip());
        this.reviews = reviews;
        this.platforms = platforms;
        this.snapshots = snapshots;
        this.extensions = extensions;
        this.json = json;
    }

    @Override
    public Coverage submitted(PolicyApply entry, PolicyAuditLog log, Snapshot snapshot) {
        boolean pending = "PENDING".equals(entry.getStatus());
        var currentTime = now();
        var decisionTime = pending ? currentTime : log.getAuditTime();
        var effectiveFrom =
                entry.getStartTime().isAfter(decisionTime) ? entry.getStartTime() : decisionTime;
        var at = pending ? effectiveFrom : currentTime;
        if (onlyShortens(snapshot))
            return new Coverage(formatTime(at), formatTime(effectiveFrom), List.of());
        var rows =
                reviews.winners(
                        entry.getTenantKey(),
                        entry.getChannelType(),
                        at,
                        entry.getId(),
                        entry.getCurrentVersion(),
                        null,
                        null,
                        null,
                        endedRounds(entry.getTenantKey(), currentTime, at));
        return assemble(entry, log, snapshot, currentTime, effectiveFrom, at, rows, pending);
    }

    @Override
    public Coverage previewDraft(
            PolicyApply entry,
            Snapshot snapshot,
            List<DetailLine> lines,
            LocalDateTime submittedAt) {
        var at = entry.getStartTime().isAfter(submittedAt) ? entry.getStartTime() : submittedAt;
        if (onlyShortens(snapshot)) return new Coverage(formatTime(at), formatTime(at), List.of());
        var rows = new ArrayList<WinnerRow>();
        var ended = endedRounds(entry.getTenantKey(), submittedAt, at);
        for (int offset = 0; offset < lines.size(); offset += 500) {
            var batch = lines.subList(offset, Math.min(offset + 500, lines.size()));
            rows.addAll(
                    reviews.winners(
                            entry.getTenantKey(),
                            entry.getChannelType(),
                            at,
                            null,
                            null,
                            null,
                            null,
                            new DraftScope(entry.getApplicantId(), batch),
                            ended));
        }
        return assemble(entry, null, snapshot, submittedAt, at, at, rows, true);
    }

    private List<Long> endedRounds(String tenant, LocalDateTime asOf, LocalDateTime at) {
        return approvedEnds(tenant, asOf).values().stream()
                .filter(end -> PolicyFormValidation.time(end.actualEnd()).isBefore(at))
                .map(end -> Long.valueOf(end.targetAuditId()))
                .toList();
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
        var changes = new ArrayList<PolicyExecutionChange>();
        var current = compareProposals(entry, confirmed, asOf, asOf, Long.MAX_VALUE, changes);
        if (current.status() == PolicyApplicability.Status.NO_EFFECTIVE_PERIOD) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "POLICY_NO_EFFECTIVE_PERIOD", "已无可生效时段，请调整截止时间后重新核对。");
        }
        if (PolicyConfirmation.requiresResubmission(consent, current)
                || !new HashSet<>(consent.comparedRounds())
                        .containsAll(PolicyConfirmation.comparedRounds(current)))
            throw reconfirmWorkflow();
        return PolicyRouteSummary.from(changes, PolicyWorkflowProposals.adjustments(confirmed));
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
            List<PolicyExecutionChange> workflowChanges) {
        var ends = approvedEnds(entry.getTenantKey(), asOf);
        var options = adjustmentOptions(entry, asOf, ends);
        var changes =
                snapshot.consent() == null
                        ? List.<EndAdjustment>of()
                        : snapshot.consent().endAdjustments();
        var ownChange = snapshot.form().goodsForm().timeAdjustment();
        if (onlyShortens(snapshot)) {
            if (!changes.isEmpty() && !changes.equals(List.of(ownChange))
                    || PolicyEndTimes.isExtension(ownChange)) {
                throw PolicyFormValidation.invalid("请核对本次申请的原单和调整内容。");
            }
            PolicyEndTimes.validate(List.of(ownChange), options);
            return new PolicyApplicability(
                    "SNAPSHOT_DATES",
                    formatTime(asOf),
                    formatTime(asOf),
                    ownChange.endTime(),
                    PolicyApplicability.Status.DIRECT,
                    true,
                    List.of(),
                    List.of());
        }
        PolicyEndTimes.validate(changes, options);
        if (entry.getExtensionSourceId() != null) {
            if (changes.stream()
                    .anyMatch(
                            c ->
                                    c.target()
                                            .policyId()
                                            .equals(entry.getExtensionSourceId().toString()))) {
                throw PolicyFormValidation.invalid("延期单不能同时调整自身承接的原政策。");
            }
            var expectedEnd =
                    formatTime(
                            PolicyFormValidation.time(snapshot.form().startTime()).minusSeconds(1));
            boolean stillAvailable =
                    options.values().stream()
                            .anyMatch(
                                    option ->
                                            option.target()
                                                            .policyId()
                                                            .equals(
                                                                    entry.getExtensionSourceId()
                                                                            .toString())
                                                    && option.target()
                                                            .previousEnd()
                                                            .equals(expectedEnd));
            var assigned = platforms.assigned(entry.getTenantKey(), entry.getApplicantId());
            if (!stillAvailable
                    || !assigned.containsAll(
                            snapshot.form().selectedPlatforms().stream()
                                    .map(p -> new ChannelPlatform(snapshot.form().perspective(), p))
                                    .toList())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "POLICY_ADJUSTMENT_CHANGED",
                        "延期原单实际截止或负责平台已变化，请重新核对。");
            }
        }
        var extra = extensions.proposals(entry, snapshot);
        extensions.verifyGoods(extra);
        var groups = PolicyWorkflowProposals.groups(snapshot, extra);
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
                    workflowChanges == null ? DateBasis.SNAPSHOT : DateBasis.WORKFLOW,
                    ends);
            scopes.addAll(evaluator.result(options).scopes());
            if (workflowChanges != null) {
                workflowChanges.addAll(evaluator.workflowSegments(snapshot.consent(), options));
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
                        .filter(
                                option ->
                                        !option.target()
                                                .policyId()
                                                .equals(
                                                        String.valueOf(
                                                                entry.getExtensionSourceId())))
                        .filter(option -> option.canExtend() || option.canShorten())
                        .toList());
    }

    @Override
    public List<AdjustmentOption> adjustableFor(
            PolicyApply applicant, LocalDateTime asOf, Map<String, ApprovedEnd> ends) {
        return List.copyOf(adjustmentOptions(applicant, asOf, ends).values());
    }

    private Map<Target, AdjustmentOption> adjustmentOptions(
            PolicyApply entry, LocalDateTime asOf, Map<String, ApprovedEnd> ends) {
        var options = new LinkedHashMap<Target, AdjustmentOption>();
        var assigned = platforms.assigned(entry.getTenantKey(), entry.getApplicantId());
        for (var old :
                reviews.adjustmentCandidates(
                        entry.getTenantKey(),
                        entry.getApplicantId(),
                        asOf,
                        asOf.toLocalDate().toString())) {
            var start = approvedTime(old.startTime());
            if (start.isBefore(old.auditTime())) start = old.auditTime();
            var id = Long.toString(old.policyId());
            var auditId = Long.toString(old.auditId());
            var through =
                    PolicyApplicabilityEvaluator.originalThrough(
                            approvedTime(old.endTime()), old.sourcePolicyCode());
            var cap = ends.get(id);
            if (cap != null && auditId.equals(cap.targetAuditId())) {
                var actualEnd = PolicyFormValidation.time(cap.actualEnd());
                if (actualEnd.isBefore(through)) through = actualEnd;
            }
            // 起效前已整单结束的未来单，以及已经自然到期的旧单，没有可再次提前的时段。
            boolean canShorten = !through.isBefore(start) && !through.isBefore(asOf);
            boolean ownsAll =
                    old.applicantId() == entry.getApplicantId()
                            && allPlatformsAssigned(old, assigned);
            boolean canExtend =
                    ownsAll
                            && !old.hasExtension()
                            && through.isBefore(LocalDateTime.of(9999, 12, 31, 23, 59, 58));
            // 当前独立延期占用了唯一的原单关联，复核时仍需取得原单最新实际截止。
            boolean ownExtensionSource =
                    ownsAll && Long.valueOf(old.policyId()).equals(entry.getExtensionSourceId());
            if (!canShorten && !canExtend && !ownExtensionSource) continue;
            var target = new Target(id, auditId, formatTime(through));
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

    @Override
    public Map<String, ApprovedEnd> approvedEnds(String tenant, LocalDateTime asOf) {
        var ends = new HashMap<String, ApprovedEnd>();
        for (var row : reviews.approvedChanges(tenant, asOf)) {
            List<EndAdjustment> adjustments;
            try {
                adjustments = snapshots.readEndAdjustments(row.changes());
            } catch (IllegalStateException invalidHistory) {
                throw reconfirmWorkflow();
            }
            if (adjustments == null) throw reconfirmWorkflow();
            for (var change : adjustments) {
                if (change == null
                        || change.target() == null
                        || change.target().policyId() == null
                        || change.target().auditLogId() == null
                        || change.target().previousEnd() == null) throw reconfirmWorkflow();
                var end = approvedTime(change.endTime());
                if (PolicyEndTimes.isExtension(change)) continue;
                // 已经过了拟定截止秒才批准时，保留通过时刻之前的实际历史。
                if (end.isBefore(row.auditTime())) end = row.auditTime().minusSeconds(1);
                var approved =
                        new ApprovedEnd(
                                policyNumber(row.requestId()),
                                change.target().auditLogId(),
                                formatTime(end),
                                formatTime(row.auditTime()),
                                // 附件属于发起变更的申请轮次，目标单可能由另一位申请人提交。
                                new EndAdjustment(
                                        change.target(), change.endTime(), change.reason()));
                ends.merge(
                        change.target().policyId(),
                        approved,
                        (earlier, later) ->
                                earlier.actualEnd().compareTo(later.actualEnd()) < 0
                                        ? earlier
                                        : later);
            }
        }
        return ends;
    }

    @Override
    public void lockScope(PolicyApply entry, PolicyDraftRequest form, List<EndAdjustment> changes) {
        var models = new java.util.TreeSet<Long>();
        form.goodsForm().cards().stream()
                .flatMap(c -> c.targets().stream())
                .map(t -> Long.valueOf(t.spuId()))
                .forEach(models::add);
        boolean adjustsOthers = changes != null && !changes.isEmpty();
        if (adjustsOthers
                || entry.getExtensionSourceId() != null
                || form.goodsForm().timeAdjustment() != null) {
            // 审批期间的分工移交与提交使用同一把申请人行锁，权限复核一直保持到事务提交。
            platforms.lockApplicant(entry.getTenantKey(), entry.getApplicantId());
        }
        if (adjustsOthers) {
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
            LocalDateTime requestedAt) {
        var clock = now();
        var at = requestedAt == null ? clock : requestedAt;
        PolicyApplicability.Scope scope;
        if ("RETAIL".equals(channel))
            scope = new PolicyApplicability.Scope(platform, goodsId, null, null, null);
        else {
            var config = reviews.configuration(goodsId);
            if (config == null) return new Effective(formatTime(at), null, null, List.of());
            scope =
                    new PolicyApplicability.Scope(
                            platform, null, config.spuId(), config.ram(), config.rom());
        }
        var evaluator = new PolicyApplicabilityEvaluator(channel, scope, at);
        var basis = DateBasis.SNAPSHOT;
        if (cutover != null && at.isBefore(cutover)) {
            basis = DateBasis.BEFORE_CUTOVER;
        } else if (cutover == null && at.isBefore(clock)) {
            basis = DateBasis.UNKNOWN_CUTOVER;
        }
        var history =
                includeHistory(tenant, 0, channel, at, evaluator, basis, approvedEnds(tenant, at));
        var applied =
                evaluator.effectivePolicies(scope, goodsId).stream()
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
        var only = applied.size() == 1 ? applied.get(0) : null;
        return new Effective(
                formatTime(at),
                only == null ? null : only.winner(),
                only == null ? null : only.snapshot(),
                applied);
    }

    @Override
    public PolicySubsidyTimeline subsidyTimeline(
            String tenant, PolicyTimelineQuery query, List<ChannelPlatform> scopes) {
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
                                asOf,
                                cutover));
            }
        }
        for (var end : approvedEnds(tenant, asOf).values()) {
            var time = PolicyFormValidation.time(end.actualEnd());
            timelines
                    .values()
                    .forEach(timeline -> timeline.includeApprovedEnd(end.targetAuditId(), time));
        }
        for (int i = 0; i < ids.size(); i += 500) {
            for (var log :
                    reviews.coverageSnapshots(
                            tenant, ids.subList(i, Math.min(i + 500, ids.size())))) {
                var snapshot =
                        PolicyApplyServiceImpl.displaySnapshot(
                                snapshots.read(log.getSnapshotData()));
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
                                row -> {
                                    var last = LocalDate.parse(row.lastDate());
                                    if (row.noConsent()
                                            && (cutover == null
                                                    || row.auditTime().isBefore(cutover))) {
                                        // 缺少切换时间的旧单也要载入，由实际命中的范围判定能否回查。
                                        var legacyThrough =
                                                (cutover == null ? asOf : cutover.minusSeconds(1))
                                                        .toLocalDate();
                                        if (last.isBefore(legacyThrough)) last = legacyThrough;
                                    }
                                    return new IndexedPeriod(
                                            row.id(), LocalDate.parse(row.firstDate()), last);
                                })
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
            DateBasis basis,
            Map<String, ApprovedEnd> ends) {
        var history = new HashMap<String, Snapshot>();
        var scopes = evaluator.scopes();
        if (scopes.isEmpty()) return history;
        for (var end : ends.values())
            evaluator.includeApprovedEnd(
                    end.targetAuditId(), PolicyFormValidation.time(end.actualEnd()));
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
                            basis == DateBasis.BEFORE_CUTOVER || basis == DateBasis.UNKNOWN_CUTOVER,
                            basis == DateBasis.WORKFLOW));
        }
        // 一个历史轮次可命中多项配置和多批范围。快照只读取、解析一次。
        var ids = List.copyOf(auditIds);
        for (int offset = 0; offset < ids.size(); offset += 500) {
            var batch = ids.subList(offset, Math.min(offset + 500, ids.size()));
            var logs = reviews.coverageSnapshots(tenant, batch);
            if (basis == DateBasis.WORKFLOW && logs.size() != batch.size())
                throw reconfirmWorkflow();
            for (var log : logs) {
                var prior = replayHistory(log, channel, basis, evaluator);
                history.put(prior.id(), prior);
            }
        }
        return history;
    }

    private Snapshot replayHistory(
            PolicyAuditLog log,
            String channel,
            DateBasis basis,
            PolicyApplicabilityEvaluator evaluator) {
        try {
            var prior =
                    PolicyApplyServiceImpl.displaySnapshot(snapshots.read(log.getSnapshotData()));
            if (basis == DateBasis.WORKFLOW) {
                if (!prior.id().equals(String.valueOf(log.getPolicyId()))
                        || prior.submissionVersion() != log.getVersion()
                        || !channel.equals(prior.form().perspective())) throw reconfirmWorkflow();
                PolicySnapshotCodec.requireReplayableCards(prior);
            }
            if (basis == DateBasis.UNKNOWN_CUTOVER && prior.consent() == null) {
                throw PolicyTimelineBuilder.unknownCutover();
            }
            boolean legacy =
                    prior.consent() == null
                            && (cutover == null || log.getAuditTime().isBefore(cutover));
            if (basis == DateBasis.WORKFLOW) {
                evaluator.includeWorkflow(
                        prior, log.getId(), log.getCreatedAt(), log.getAuditTime(), legacy);
            } else {
                evaluator.include(
                        prior,
                        log.getId(),
                        log.getCreatedAt(),
                        log.getAuditTime(),
                        legacy,
                        basis == DateBasis.BEFORE_CUTOVER);
            }
            return prior;
        } catch (IllegalStateException | IllegalArgumentException | NullPointerException error) {
            if (basis == DateBasis.WORKFLOW) throw reconfirmWorkflow();
            throw error;
        } catch (ApiException error) {
            // 快照中的非法时间来自已批准记录，不能让调用方误以为是当前表单填写错误。
            if (basis == DateBasis.WORKFLOW && error.getStatus() == HttpStatus.BAD_REQUEST)
                throw reconfirmWorkflow();
            throw error;
        }
    }

    private Coverage assemble(
            PolicyApply entry,
            PolicyAuditLog log,
            Snapshot snapshot,
            LocalDateTime currentTime,
            LocalDateTime effectiveFrom,
            LocalDateTime at,
            List<WinnerRow> rows,
            boolean pending) {
        var matches =
                rows.stream()
                        .collect(
                                Collectors.toMap(
                                        row ->
                                                new Scope(
                                                        row.platformCode(),
                                                        Long.toString(row.goodsId())),
                                        Function.identity()));
        // 一次批量读取命中的已通过快照，按商品和平台核对旧补贴，避免逐商品查询。
        Map<Long, Map<Scope, SubsidyPlan>> existingPlans = new HashMap<>();
        if (log != null) {
            existingPlans.put(log.getId(), indexSubsidies(snapshot));
        }
        var auditIds =
                matches.values().stream()
                        .map(WinnerRow::auditId)
                        .filter(id -> !existingPlans.containsKey(id))
                        .distinct()
                        .toList();
        if (!auditIds.isEmpty()) {
            for (var prior : reviews.coverageSnapshots(entry.getTenantKey(), auditIds)) {
                existingPlans.put(
                        prior.getId(), indexSubsidies(snapshots.read(prior.getSnapshotData())));
            }
        }
        var items = new ArrayList<CoverageItem>();
        for (var card : snapshot.form().goodsForm().cards()) {
            for (var target : card.targets()) {
                for (var assignment : card.platformAssignments()) {
                    var scope = new Scope(assignment.platformCode(), target.goodsId());
                    var match = matches.get(scope);
                    items.add(
                            new CoverageItem(
                                    scope.platformCode(),
                                    scope.goodsId(),
                                    outcome(
                                            pending,
                                            log,
                                            entry.getId(),
                                            currentTime,
                                            effectiveFrom,
                                            match),
                                    match == null ? null : winner(match),
                                    match == null
                                            ? null
                                            : existingPlans
                                                    .getOrDefault(match.auditId(), Map.of())
                                                    .get(scope)));
                }
            }
        }
        return new Coverage(formatTime(at), formatTime(effectiveFrom), items);
    }

    private static Map<Scope, SubsidyPlan> indexSubsidies(Snapshot snapshot) {
        var result = new HashMap<Scope, SubsidyPlan>();
        for (var card : snapshot.form().goodsForm().cards()) {
            var plans =
                    card.subsidyPlans().stream()
                            .collect(Collectors.toMap(SubsidyPlan::key, Function.identity()));
            for (var assignment : card.platformAssignments()) {
                var plan = plans.get(assignment.planKey());
                if (plan != null) {
                    for (var target : card.targets()) {
                        result.put(new Scope(assignment.platformCode(), target.goodsId()), plan);
                    }
                }
            }
        }
        return result;
    }

    private static String outcome(
            boolean pending,
            PolicyAuditLog log,
            long policyId,
            LocalDateTime now,
            LocalDateTime effectiveFrom,
            WinnerRow match) {
        if (pending) {
            if (match == null) {
                return "NEW";
            }
            // 新的提交轮次尚无日志 ID，同一秒提交时其新 ID 排在已有日志之后。
            if (log == null) {
                return now.isBefore(match.submittedAt()) ? "RETAINED" : "REPLACES";
            }
            int order = log.getCreatedAt().compareTo(match.submittedAt());
            return order > 0 || (order == 0 && log.getId() > match.auditId())
                    ? "REPLACES"
                    : "RETAINED";
        }
        if (effectiveFrom.isAfter(now)) {
            return "SCHEDULED";
        }
        return match != null && match.policyId() == policyId ? "EFFECTIVE" : "SUPERSEDED";
    }

    static Winner winner(WinnerRow row) {
        return new Winner(
                Long.toString(row.policyId()),
                policyNumber(row.policyId()),
                Long.toString(row.auditId()),
                row.version(),
                formatTime(row.submittedAt()),
                formatTime(row.effectiveFrom()));
    }

    private record Scope(String platformCode, String goodsId) {}
}
