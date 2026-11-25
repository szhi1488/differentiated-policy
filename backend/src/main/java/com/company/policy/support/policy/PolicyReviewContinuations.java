package com.company.policy.support.policy;

import com.company.policy.support.policy.PolicyWorkflowProposals.Adjustment;
import com.company.policy.vo.PolicyApplicability.Policy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 将本轮试算的调整归到获批原单，识别只由原截止引起的时间边界。商品和补贴仍取各段冻结的值。 */
final class PolicyReviewContinuations {
    private final Map<String, Adjustment> adjustedCandidates;
    private final Map<String, Adjustment> byAudit = new HashMap<>();
    private final Map<String, Policy> originals = new HashMap<>();

    static Map<String, Adjustment> adjustedCandidates(
            String parentId, List<Adjustment> adjustments) {
        if (adjustments == null || parentId == null) return Map.of();
        var result = new HashMap<String, Adjustment>();
        for (var adjustment : adjustments) {
            if (!adjustment.accompanying()) continue;
            var id = adjustment.target().policyId();
            var key =
                    adjustment.extension() ? PolicyBatchExtensions.extensionKey(parentId, id) : id;
            result.put(key, adjustment);
        }
        return result;
    }

    private static Stream<Policy> participants(PolicyExecutionChange change) {
        return Stream.concat(change.after().stream(), change.candidates().stream());
    }

    PolicyReviewContinuations(
            Map<String, Adjustment> adjustedCandidates, List<PolicyExecutionChange> track) {
        this.adjustedCandidates = adjustedCandidates;
        for (var adjustment : adjustedCandidates.values())
            byAudit.put(adjustment.target().auditLogId(), adjustment);
        // 同一获批轮次在不同商品上有不同的补贴和范围，只在当前轨道内查原单。
        for (var change : track) {
            Stream.concat(change.before().stream(), participants(change))
                    .filter(p -> !p.proposed() && byAudit.containsKey(p.auditLogId()))
                    .forEach(p -> originals.put(p.auditLogId(), p));
        }
    }

    PolicyExecutionChange normalize(PolicyExecutionChange change) {
        var after = change.after().stream().map(this::policy).toList();
        var candidates = change.candidates().stream().map(this::policy).toList();
        if (after.equals(change.after()) && candidates.equals(change.candidates())) return change;
        return change.withProposedPolicies(after, candidates);
    }

    private Policy policy(Policy candidate) {
        var adjustment = adjustedCandidates.get(candidate.policyId());
        var source = candidate;
        if (adjustment == null || !adjustment.extension()) {
            adjustment = byAudit.get(candidate.auditLogId());
            if (adjustment == null || !adjustment.target().policyId().equals(candidate.policyId()))
                return candidate;
        } else {
            source = originals.get(adjustment.target().auditLogId());
        }
        return new Policy(
                adjustment.target().policyId(),
                adjustment.policyCode(),
                source == null ? candidate.submissionVersion() : source.submissionVersion(),
                adjustment.target().auditLogId(),
                false,
                source == null ? null : source.submittedAt(),
                source == null ? null : source.approvedAt(),
                source == null ? candidate.effectiveFrom() : source.effectiveFrom(),
                adjustment.endTime(),
                candidate.verifyType(),
                candidate.sceneTypes(),
                candidate.selections());
    }

    boolean sameBaselineAtHandoff(PolicyExecutionChange previous, PolicyExecutionChange next) {
        if (!next.from().equals(previous.through().plusSeconds(1))) return false;
        // 延期和提前结束都可能使旧截止只剩下“原安排到期”这一项差别。
        // 逐个核对上一段原执行单的获批轮次，保留同时发生的其他换单或退出。
        return previous.before().stream()
                .filter(
                        policy -> {
                            var adjustment = byAudit.get(policy.auditLogId());
                            return adjustment == null
                                    || !adjustment.target().policyId().equals(policy.policyId())
                                    || !PolicyFormValidation.time(adjustment.previousEnd())
                                            .equals(previous.through());
                        })
                .toList()
                .equals(next.before());
    }
}
