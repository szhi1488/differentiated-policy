package com.company.policy.support.policy;

import com.company.policy.common.exception.ApiException;
import com.company.policy.entity.PolicyConsent.Decision;
import com.company.policy.support.policy.PolicyApplicabilityEvaluator.Round;
import com.company.policy.support.policy.PolicyApplicabilityEvaluator.Window;
import com.company.policy.vo.PolicyApplicability.Action;
import com.company.policy.vo.PolicyApplicability.Policy;
import com.company.policy.vo.PolicyApplicability.Scope;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;

/** 同时回放原安排和本轮确认方案。只有与本单或同批延期实际相交的范围才进入分流。 */
final class PolicyWorkflowReplay {
    private PolicyWorkflowReplay() {}

    private record Events(List<Round> start, List<Round> end) {
        Events() {
            this(new ArrayList<>(), new ArrayList<>());
        }

        void update(SortedSet<Round> active) {
            active.removeAll(end);
            active.addAll(start);
        }
    }

    private record Boundary(Events before, Events after) {
        Boundary() {
            this(new Events(), new Events());
        }
    }

    static void compare(
            boolean retail,
            Scope scope,
            Window requested,
            List<Round> approved,
            List<Round> adjusted,
            List<Decision> confirmed,
            List<PolicyExecutionChange> changes) {
        var events = new TreeMap<LocalDateTime, Boundary>();
        for (var round : approved) {
            var window = requested.intersect(round.window());
            if (window.expired()) continue;
            at(events, window.from()).before.start.add(round);
            at(events, window.through().plusSeconds(1)).before.end.add(round);
            decisions(retail, round.decisions(), window, events);
        }
        for (var round : adjusted) {
            var window = requested.intersect(round.window());
            if (window.expired()) continue;
            at(events, window.from()).after.start.add(round);
            at(events, window.through().plusSeconds(1)).after.end.add(round);
        }
        var choices = decisions(retail, confirmed, requested, events);
        var before = new TreeSet<>(PolicyApplicabilityEvaluator.NEWEST);
        var after = new TreeSet<>(PolicyApplicabilityEvaluator.NEWEST);
        for (var entry : events.entrySet()) {
            entry.getValue().before.update(before);
            entry.getValue().after.update(after);
            if (after.stream().noneMatch(r -> r.policy().proposed())) continue;
            var start = entry.getKey();
            var end = events.higherKey(start).minusSeconds(1);
            if (before.stream().anyMatch(Round::missingConsent)) throw uncertain();
            var choice = choiceAt(choices, start);
            if (choice == null && after.size() != 1) throw uncertain();
            var selected =
                    choice == null
                            ? List.of(after.first().policy())
                            : policiesById(after, choice.selectedPolicyIds());
            var candidates =
                    choice == null
                            ? List.<Policy>of()
                            : policiesById(after, choice.eligiblePolicyIds());
            var remainingOld = new TreeSet<>(PolicyApplicabilityEvaluator.NEWEST);
            after.stream().filter(r -> !r.policy().proposed()).forEach(remainingOld::add);
            if (!retail && (perProduct(before) || perProduct(remainingOld))) {
                // 旧单只在当时实际申报的颜色上执行，新单的决定则对整组配置生效。
                var goods = new TreeSet<Long>();
                for (var round : before) addGoods(goods, round);
                for (var round : after) addGoods(goods, round);
                for (long id : goods) {
                    var productScope =
                            new Scope(
                                    scope.platformCode(),
                                    id,
                                    scope.spuId(),
                                    scope.ram(),
                                    scope.rom());
                    compareAt(
                            false,
                            productScope,
                            start,
                            end,
                            before,
                            remainingOld,
                            selected,
                            candidates,
                            changes);
                }
            } else {
                compareAt(
                        retail,
                        scope,
                        start,
                        end,
                        before,
                        remainingOld,
                        selected,
                        candidates,
                        changes);
            }
        }
    }

    private static boolean perProduct(SortedSet<Round> active) {
        return !active.isEmpty() && active.stream().allMatch(Round::legacy);
    }

    private static TreeMap<LocalDateTime, Decision> decisions(
            boolean retail,
            List<Decision> decisions,
            Window window,
            TreeMap<LocalDateTime, Boundary> events) {
        var byStart = new TreeMap<LocalDateTime, Decision>();
        for (var decision : decisions) {
            var interval = decisionWindow(retail, decision).intersect(window);
            if (interval.expired()) continue;
            if (byStart.put(interval.from(), decision) != null) throw uncertain();
            at(events, interval.from());
            at(events, interval.through().plusSeconds(1));
        }
        String previousEnd = null;
        for (var decision : byStart.values()) {
            if (previousEnd != null && previousEnd.compareTo(decision.from()) >= 0)
                throw uncertain();
            previousEnd = decision.through();
        }
        return byStart;
    }

    private static Decision choiceAt(TreeMap<LocalDateTime, Decision> choices, LocalDateTime at) {
        var latest = choices.floorEntry(at);
        if (latest == null) return null;
        var decision = latest.getValue();
        return decision.through().compareTo(PolicyApplicabilityEvaluator.time(at)) >= 0
                ? decision
                : null;
    }

    private static void compareAt(
            boolean retail,
            Scope scope,
            LocalDateTime start,
            LocalDateTime end,
            SortedSet<Round> before,
            SortedSet<Round> remainingOld,
            List<Policy> selected,
            List<Policy> candidates,
            List<PolicyExecutionChange> changes) {
        long id = scope.goodsId() == null ? 0 : scope.goodsId();
        var original = PolicyApplicabilityEvaluator.effectivePolicies(retail, before, id, start);
        if (candidates.isEmpty() && remainingOld.size() != before.size()) {
            // 无需在确认窗口核对此段时，纯粹因旧单提前结束而产生的变化由时间调整明细说明。
            var cutOnly =
                    PolicyApplicabilityEvaluator.effectivePolicies(retail, remainingOld, id, start);
            if (!PolicyExecutionChange.sameMembers(original, cutOnly)
                    && PolicyExecutionChange.sameMembers(cutOnly, selected)) return;
        }
        changes.add(
                PolicyExecutionChange.compare(
                        retail ? "RETAIL" : "BILLION",
                        scope,
                        start,
                        end,
                        original,
                        selected,
                        candidates));
    }

    private static List<Policy> policiesById(SortedSet<Round> after, List<String> policyIds) {
        // 本轮确认使用正式候选 ID。已经获批的延期单在历史回放时由 effectivePolicies 还原别名。
        var ids = new HashSet<>(policyIds);
        var policies = after.stream().filter(r -> ids.contains(r.id())).map(Round::policy).toList();
        if (policies.size() != ids.size()) throw uncertain();
        return policies;
    }

    private static void addGoods(SortedSet<Long> ids, Round round) {
        for (var selection : round.policy().selections())
            for (var product : selection.products()) ids.add(Long.valueOf(product.goodsId()));
    }

    private static Window decisionWindow(boolean retail, Decision decision) {
        if (decision.action() == null
                || decision.from() == null
                || decision.through() == null
                || decision.from().compareTo(decision.through()) > 0
                || decision.selectedPolicyIds() == null
                || decision.selectedPolicyIds().isEmpty()
                || decision.eligiblePolicyIds() == null
                || !decision.eligiblePolicyIds().containsAll(decision.selectedPolicyIds()))
            throw uncertain();
        var selected = new HashSet<>(decision.selectedPolicyIds());
        if (selected.size() != decision.selectedPolicyIds().size()
                || retail == (decision.action() == Action.STACK_OR_CHOOSE)) throw uncertain();
        if (decision.action() == Action.SELECT_RETAIL_POLICIES) {
            if (decision.groups() == null
                    || decision.groups().stream()
                            .anyMatch(
                                    group ->
                                            group == null
                                                    || group.policyIds() == null
                                                    || group.policyIds().stream()
                                                                    .filter(selected::contains)
                                                                    .limit(2)
                                                                    .count()
                                                            > 1)) throw uncertain();
        } else if (selected.size() != decision.eligiblePolicyIds().size()
                && !(decision.action() == Action.STACK_OR_CHOOSE && selected.size() == 1)) {
            throw uncertain();
        }
        try {
            return new Window(
                    PolicyFormValidation.time(decision.from()),
                    PolicyFormValidation.time(decision.through()));
        } catch (ApiException badDate) {
            throw uncertain();
        }
    }

    private static Boundary at(TreeMap<LocalDateTime, Boundary> events, LocalDateTime at) {
        return events.computeIfAbsent(at, ignored -> new Boundary());
    }

    static ApiException uncertain() {
        return new ApiException(
                HttpStatus.CONFLICT, "POLICY_RECONFIRM_REQUIRED", "无法还原该段的执行名单，请核对已批准快照并重新确认本轮方案。");
    }
}
