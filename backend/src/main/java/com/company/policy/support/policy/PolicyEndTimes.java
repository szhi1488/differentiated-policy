package com.company.policy.support.policy;

import static com.company.policy.support.policy.PolicyApplicabilityEvaluator.time;

import com.company.policy.common.exception.ApiException;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.entity.PolicyConsent.Target;
import com.company.policy.support.policy.PolicyApplicabilityEvaluator.Round;
import com.company.policy.support.policy.PolicyApplicabilityEvaluator.Window;
import com.company.policy.vo.PolicyApplicability.AdjustmentOption;
import com.company.policy.vo.PolicyApplicability.Policy;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 已通过的整单提前结束决定按旧单审核轮次索引，原快照继续供历史回查。 */
public final class PolicyEndTimes {
    private final Map<String, LocalDateTime> cutoffs = new HashMap<>();

    void cap(String auditLogId, LocalDateTime through) {
        cutoffs.merge(auditLogId, through, (a, b) -> a.isBefore(b) ? a : b);
    }

    LocalDateTime through(String auditLogId, LocalDateTime original) {
        var cap = cutoffs.get(auditLogId);
        return cap != null && cap.isBefore(original) ? cap : original;
    }

    List<Round> apply(List<Round> rounds) {
        if (cutoffs.isEmpty()) return rounds;
        return rounds.stream()
                .map(
                        r -> {
                            if (r.policy().proposed()) return r;
                            var end = through(r.policy().auditLogId(), r.window().through());
                            if (end.equals(r.window().through())) return r;
                            var p = r.policy();
                            var adjusted =
                                    new Policy(
                                            p.policyId(),
                                            p.policyCode(),
                                            p.submissionVersion(),
                                            p.auditLogId(),
                                            false,
                                            p.submittedAt(),
                                            p.approvedAt(),
                                            p.effectiveFrom(),
                                            time(end),
                                            p.verifyType(),
                                            p.sceneTypes(),
                                            p.selections());
                            return new Round(
                                    adjusted,
                                    new Window(r.window().from(), end),
                                    new Window(
                                            r.original().from(),
                                            end.isBefore(r.original().through())
                                                    ? end
                                                    : r.original().through()),
                                    r.estimated(),
                                    r.submittedAt(),
                                    r.auditOrder(),
                                    r.legacy(),
                                    r.missingConsent(),
                                    r.decisions(),
                                    r.batchKey());
                        })
                .toList();
    }

    PolicyEndTimes withProposals(
            List<EndAdjustment> proposedCuts, Map<Target, AdjustmentOption> options) {
        if (proposedCuts.isEmpty()) return this;
        var updated = new PolicyEndTimes();
        updated.cutoffs.putAll(cutoffs);
        var seen = new HashSet<Target>();
        for (var change : proposedCuts) {
            if (change == null
                    || change.target() == null
                    || change.reason() == null
                    || change.reason().isBlank()
                    || change.reason().codePointCount(0, change.reason().length()) > 512
                    || !seen.add(change.target())) {
                throw PolicyFormValidation.invalid("每张旧政策只可调整一次，且必须填写 1 至 512 字的调整原因。");
            }
            var target = change.target();
            var option = options.get(target);
            if (option == null)
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "POLICY_ADJUSTMENT_CHANGED",
                        "旧政策的截止时间、状态或负责平台已变化，请重新选择。");
            var end = PolicyFormValidation.time(change.endTime());
            if (isExtension(change)) {
                if (!option.canExtend())
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "POLICY_ADJUSTMENT_CHANGED",
                            "仅原提交人仍负责全部原单平台且尚无延期申请时可延长。");
                if (!end.isAfter(PolicyFormValidation.time(target.previousEnd()).plusSeconds(1))) {
                    throw PolicyFormValidation.invalid("新截止须晚于延长段开始时刻。");
                }
                continue;
            }
            if (end == null
                    || !option.canShorten()
                    || change.endTime().compareTo(option.latestEnd()) > 0
                    || change.endTime().compareTo(option.earliestEnd()) < 0) {
                throw PolicyFormValidation.invalid("新截止须早于旧政策当前截止时间，最早可选该政策起效前一秒。");
            }
            updated.cap(target.auditLogId(), end);
        }
        return updated;
    }

    public static void validate(
            List<EndAdjustment> changes, Map<Target, AdjustmentOption> options) {
        new PolicyEndTimes().withProposals(changes, options);
    }

    public static boolean isExtension(EndAdjustment change) {
        return change != null
                && change.target() != null
                && change.endTime() != null
                && change.target().previousEnd() != null
                && change.endTime().compareTo(change.target().previousEnd()) > 0;
    }
}
