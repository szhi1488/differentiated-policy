package com.company.policy.support.policy;

import com.company.policy.entity.PolicyConsent.Decision;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 按改期发生顺序还原同一原单的实际有效区间。到期后才续的空档不能参与补贴。 */
public final class PolicyEffectivePeriods {
    private PolicyEffectivePeriods() {}

    public record Change(
            long id,
            boolean extension,
            LocalDateTime effectiveAt,
            LocalDateTime actualEnd,
            LocalDateTime changedAt,
            List<Decision> decisions,
            Set<String> comparedPolicyIds) {
        public Change(
                long id,
                boolean extension,
                LocalDateTime effectiveAt,
                LocalDateTime actualEnd,
                LocalDateTime changedAt,
                List<Decision> decisions) {
            this(id, extension, effectiveAt, actualEnd, changedAt, decisions, Set.of());
        }
    }

    record Period(
            PolicyApplicabilityEvaluator.Window window, LocalDateTime visibleFrom, Change change) {}

    static List<Period> resolve(
            PolicyApplicabilityEvaluator.Window first,
            LocalDateTime filedStart,
            List<Change> changes) {
        var periods = new ArrayList<Period>();
        periods.add(new Period(first, filedStart, null));
        for (var change : changes) {
            if (change.extension()) {
                periods.add(
                        new Period(
                                new PolicyApplicabilityEvaluator.Window(
                                        change.effectiveAt(), change.actualEnd()),
                                change.effectiveAt(),
                                change));
            } else {
                // 多次延期后也允许提前到任意已有时段，已执行的部分由入账时的实际截止保护。
                periods.removeIf(period -> period.visibleFrom().isAfter(change.actualEnd()));
                for (int i = 0; i < periods.size(); i++) {
                    var period = periods.get(i);
                    if (period.window().through().isAfter(change.actualEnd())) {
                        periods.set(
                                i,
                                new Period(
                                        new PolicyApplicabilityEvaluator.Window(
                                                period.window().from(), change.actualEnd()),
                                        period.visibleFrom(),
                                        period.change()));
                    }
                }
            }
        }
        return periods;
    }
}
