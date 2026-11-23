package com.company.policy.support.policy;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyDraftRequest.Choice;
import com.company.policy.entity.PolicyConsent;
import com.company.policy.entity.PolicyConsent.ComparedRound;
import com.company.policy.entity.PolicyConsent.Decision;
import com.company.policy.vo.PolicyApplicability;
import com.company.policy.vo.PolicyApplicability.Action;
import com.company.policy.vo.PolicyApplicability.Scope;
import com.company.policy.vo.PolicyApplicability.Status;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 校验整单每个交叉时段的选择，审核时仅比较尚能实际生效的部分。 */
public final class PolicyConfirmation {
    private PolicyConfirmation() {}

    private record Key(Scope scope, String through) {}

    public static List<Decision> ranges(PolicyApplicability result) {
        var ranges = new ArrayList<Decision>();
        for (var scope : result.scopes()) {
            for (var segment : scope.segments()) {
                ranges.add(
                        new Decision(
                                scope.scope(),
                                segment.from(),
                                segment.through(),
                                segment.action(),
                                segment.groups(),
                                segment.eligiblePolicyIds(),
                                List.of()));
            }
        }
        return ranges;
    }

    public static List<ComparedRound> comparedRounds(PolicyApplicability result) {
        return result.scopes().stream()
                .flatMap(scope -> scope.policies().stream())
                .filter(p -> !p.proposed())
                .map(p -> new ComparedRound(p.policyId(), p.submissionVersion(), p.auditLogId()))
                .distinct()
                .sorted(Comparator.comparing(ComparedRound::policyId))
                .toList();
    }

    public static PolicyConsent confirm(PolicyApplicability result, List<Choice> input) {
        if (result.status() == Status.NO_EFFECTIVE_PERIOD)
            throw conflict("POLICY_NO_EFFECTIVE_PERIOD", "已无可生效时段，请返回编辑并调整截止日期。");
        var choices = new HashMap<Key, Choice>();
        if (input != null) {
            for (var choice : input) {
                if (choice == null || choice.choice() == null) throw incomplete();
                if ("RETURN_TO_EDIT".equals(choice.choice()))
                    throw conflict("POLICY_RETURN_TO_EDIT", "已保留草稿和附件，请返回编辑。");
                if (choices.put(new Key(choice.scope(), choice.through()), choice) != null)
                    throw incomplete();
            }
        }
        var decisions = new ArrayList<Decision>();
        for (var range : ranges(result)) {
            var choice = choices.remove(new Key(range.scope(), range.through()));
            if (choice == null) throw incomplete();
            var selected = range.eligiblePolicyIds();
            if (range.action() == Action.SELECT_RETAIL_POLICIES) {
                selected = retailSelection(range, choice);
            } else if (choice.selectedPolicyIds() != null) {
                throw incomplete();
            } else if ("CHOOSE_ONE".equals(choice.choice())
                    && range.action() == Action.STACK_OR_CHOOSE) {
                if (choice.policyId() == null || !selected.contains(choice.policyId()))
                    throw incomplete();
                selected = List.of(choice.policyId());
            } else {
                String expected =
                        range.action() == Action.ACKNOWLEDGE_EXCLUSIVE ? "ACKNOWLEDGE" : "STACK";
                if (!expected.equals(choice.choice()) || choice.policyId() != null)
                    throw incomplete();
            }
            decisions.add(
                    new Decision(
                            range.scope(),
                            range.from(),
                            range.through(),
                            range.action(),
                            range.groups(),
                            range.eligiblePolicyIds(),
                            selected));
        }
        if (!choices.isEmpty()) throw incomplete();
        return new PolicyConsent(2, comparedRounds(result), decisions);
    }

    private static List<String> retailSelection(Decision range, Choice choice) {
        var ids = choice.selectedPolicyIds();
        if (!"SELECT".equals(choice.choice())
                || choice.policyId() != null
                || ids == null
                || ids.isEmpty()) throw incomplete();
        var selected = new HashSet<>(ids);
        if (selected.size() != ids.size()
                || !new HashSet<>(range.eligiblePolicyIds()).containsAll(selected))
            throw incomplete();
        for (var group : range.groups()) {
            if (group.policyIds().stream().filter(selected::contains).limit(2).count() > 1)
                throw incomplete();
        }
        return range.eligiblePolicyIds().stream().filter(selected::contains).toList();
    }

    public static boolean requiresResubmission(PolicyConsent saved, PolicyApplicability current) {
        if (current.status() == Status.NO_EFFECTIVE_PERIOD) return true;
        if (saved != null && saved.ruleVersion() != 1 && saved.ruleVersion() != 2)
            throw new IllegalStateException("无法识别该轮适用范围规则版本。");
        if (saved != null
                && current.scopes().stream().anyMatch(s -> s.scope().goodsId() != null)
                && !new HashSet<>(saved.comparedRounds()).containsAll(comparedRounds(current)))
            return true;
        var before =
                comparable(saved == null ? List.of() : saved.decisions(), current.effectiveFrom());
        return !before.equals(comparable(ranges(current), current.effectiveFrom()));
    }

    private record Outcome(
            Action action, Map<GroupKey, List<String>> groups, List<String> eligible) {}

    private record GroupKey(List<String> scenes, String verification) {}

    private record Interval(String from, String through, Outcome outcome) {}

    // 仅复核仍能生效的区间，显示顺序不参与比较。
    private static Map<Scope, List<Interval>> comparable(List<Decision> decisions, String start) {
        var result = new HashMap<Scope, List<Interval>>();
        for (var decision : decisions) {
            if (decision.through().compareTo(start) < 0) continue;
            var groups = new LinkedHashMap<GroupKey, List<String>>();
            for (var group : decision.groups()) {
                groups.put(
                        new GroupKey(
                                PolicyScenes.fromLegacy(group.sceneTypes(), group.sceneType()),
                                group.verifyType()),
                        group.policyIds().stream().sorted().toList());
            }
            // 旧单已确认的单场景分组可沿用。旧预览的 eligible 仅含胜出者，完整候选保存在 groups。
            // 旧单拆过多场景时，这里的分组键会与新预览不同，须重新确认。
            var outcome =
                    switch (decision.action()) {
                        case ACKNOWLEDGE_EXCLUSIVE, CONFIRM_STACK ->
                                new Outcome(
                                        Action.SELECT_RETAIL_POLICIES,
                                        groups,
                                        groups.values().stream()
                                                .flatMap(List::stream)
                                                .distinct()
                                                .sorted()
                                                .toList());
                        default ->
                                new Outcome(
                                        decision.action(),
                                        groups,
                                        decision.eligiblePolicyIds().stream().sorted().toList());
                    };
            var from = decision.from().compareTo(start) < 0 ? start : decision.from();
            var intervals = result.computeIfAbsent(decision.scope(), unused -> new ArrayList<>());
            if (!intervals.isEmpty()) {
                var previous = intervals.get(intervals.size() - 1);
                if (outcome.equals(previous.outcome())
                        && PolicyFormValidation.time(previous.through())
                                .plusSeconds(1)
                                .equals(PolicyFormValidation.time(from))) {
                    from = previous.from();
                    intervals.remove(intervals.size() - 1);
                }
            }
            intervals.add(new Interval(from, decision.through(), outcome));
        }
        return result;
    }

    private static ApiException incomplete() {
        return conflict("POLICY_CONFIRMATION_REQUIRED", "请重新预览并选择各交叉时段执行的政策，每段至少一张，零售同组最多一张。");
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
