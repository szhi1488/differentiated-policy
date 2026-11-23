package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.formatTime;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.now;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.policyNumber;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import com.company.policy.mapper.PolicyApplyMapper.DetailLine;
import com.company.policy.mapper.PolicyReviewMapper;
import com.company.policy.mapper.PolicyReviewMapper.DraftScope;
import com.company.policy.mapper.PolicyReviewMapper.WinnerRow;
import com.company.policy.service.PolicyCoverageService;
import com.company.policy.support.policy.PolicyApplicabilityEvaluator;
import com.company.policy.support.policy.PolicyFormValidation;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.vo.PolicyApplicability;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.company.policy.vo.PolicyReviewResponse.Applied;
import com.company.policy.vo.PolicyReviewResponse.Coverage;
import com.company.policy.vo.PolicyReviewResponse.CoverageItem;
import com.company.policy.vo.PolicyReviewResponse.Effective;
import com.company.policy.vo.PolicyReviewResponse.Winner;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class PolicyCoverageServiceImpl implements PolicyCoverageService {
    private final PolicyReviewMapper reviews;
    private final PolicySnapshotCodec snapshots;

    private enum DateBasis {
        SNAPSHOT,
        BEFORE_CUTOVER,
        UNKNOWN_CUTOVER
    }

    private final LocalDateTime cutover;

    PolicyCoverageServiceImpl(
            PolicyReviewMapper reviews,
            PolicySnapshotCodec snapshots,
            @Value("${policy.applicability-cutover-at:}") String cutoverAt) {
        this.cutover = cutoverAt.isBlank() ? null : PolicyFormValidation.time(cutoverAt.strip());
        this.reviews = reviews;
        this.snapshots = snapshots;
    }

    @Override
    public Coverage submitted(PolicyApply entry, PolicyAuditLog log, Snapshot snapshot) {
        boolean pending = "PENDING".equals(entry.getStatus());
        var currentTime = now();
        var decisionTime = pending ? currentTime : log.getAuditTime();
        var effectiveFrom =
                entry.getStartTime().isAfter(decisionTime) ? entry.getStartTime() : decisionTime;
        var at = pending ? effectiveFrom : currentTime;
        var rows =
                reviews.winners(
                        entry.getTenantKey(),
                        entry.getChannelType(),
                        at,
                        entry.getId(),
                        entry.getCurrentVersion(),
                        null,
                        null,
                        null);
        return assemble(entry, log, snapshot, currentTime, effectiveFrom, at, rows, pending);
    }

    @Override
    public Coverage previewDraft(
            PolicyApply entry,
            Snapshot snapshot,
            List<DetailLine> lines,
            LocalDateTime submittedAt) {
        var at = entry.getStartTime().isAfter(submittedAt) ? entry.getStartTime() : submittedAt;
        var rows = new ArrayList<WinnerRow>();
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
                            new DraftScope(entry.getApplicantId(), batch)));
        }
        return assemble(entry, null, snapshot, submittedAt, at, at, rows, true);
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
        var evaluator = new PolicyApplicabilityEvaluator(snapshot, asOf, submittedAt, auditId);
        includeHistory(
                entry.getTenantKey(),
                entry.getId(),
                entry.getChannelType(),
                asOf,
                evaluator,
                DateBasis.SNAPSHOT);
        return evaluator.result();
    }

    @Override
    public void lockScope(PolicyDraftRequest form) {
        var ids =
                form.goodsForm().cards().stream()
                        .flatMap(c -> c.targets().stream())
                        .map(t -> Long.valueOf(t.spuId()))
                        .distinct()
                        .sorted()
                        .toList();
        for (int i = 0; i < ids.size(); i += 500) {
            var batch = ids.subList(i, Math.min(i + 500, ids.size()));
            if (reviews.lockScopeModels(batch).size() != batch.size()) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "POLICY_GOODS_CHANGED", "机型资料已变化，请返回编辑并重新选择商品。");
            }
        }
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
        var history = includeHistory(tenant, 0, channel, at, evaluator, basis);
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

    private Map<String, Snapshot> includeHistory(
            String tenant,
            long draftId,
            String channel,
            LocalDateTime asOf,
            PolicyApplicabilityEvaluator evaluator,
            DateBasis basis) {
        var history = new HashMap<String, Snapshot>();
        var scopes = evaluator.scopes();
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
                            basis != DateBasis.SNAPSHOT));
        }
        // 一个历史轮次可命中多项配置和多批范围。快照只读取、解析一次。
        var ids = List.copyOf(auditIds);
        for (int offset = 0; offset < ids.size(); offset += 500) {
            var batch = ids.subList(offset, Math.min(offset + 500, ids.size()));
            for (var log : reviews.coverageSnapshots(tenant, batch)) {
                var prior =
                        PolicyApplyServiceImpl.displaySnapshot(
                                snapshots.read(log.getSnapshotData()));
                history.put(prior.id(), prior);
                if (basis == DateBasis.UNKNOWN_CUTOVER && prior.consent() == null) {
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "POLICY_CUTOVER_REQUIRED",
                            "该历史时点涉及旧政策，请管理员配置实际规则上线时间后重查。");
                }
                boolean legacy =
                        prior.consent() == null
                                && (cutover == null || log.getAuditTime().isBefore(cutover));
                evaluator.include(
                        prior,
                        log.getId(),
                        log.getCreatedAt(),
                        log.getAuditTime(),
                        legacy,
                        basis == DateBasis.BEFORE_CUTOVER);
            }
        }
        return history;
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
