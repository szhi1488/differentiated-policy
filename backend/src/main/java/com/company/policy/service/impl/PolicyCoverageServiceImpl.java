package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.formatTime;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.now;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.policyNumber;

import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import com.company.policy.mapper.PolicyApplyMapper.DetailLine;
import com.company.policy.mapper.PolicyReviewMapper;
import com.company.policy.mapper.PolicyReviewMapper.DraftScope;
import com.company.policy.mapper.PolicyReviewMapper.WinnerRow;
import com.company.policy.service.PolicyCoverageService;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.company.policy.vo.PolicyReviewResponse.Coverage;
import com.company.policy.vo.PolicyReviewResponse.CoverageItem;
import com.company.policy.vo.PolicyReviewResponse.Winner;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
class PolicyCoverageServiceImpl implements PolicyCoverageService {
    private final PolicyReviewMapper reviews;
    private final PolicySnapshotCodec snapshots;

    PolicyCoverageServiceImpl(PolicyReviewMapper reviews, PolicySnapshotCodec snapshots) {
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
