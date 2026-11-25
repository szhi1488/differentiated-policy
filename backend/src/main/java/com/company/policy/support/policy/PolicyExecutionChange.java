package com.company.policy.support.policy;

import com.company.policy.vo.PolicyApplicability.Policy;
import com.company.policy.vo.PolicyApplicability.Scope;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 一个实际平台及审核范围内，闭区间两端均有效的原、拟执行名单。 */
public record PolicyExecutionChange(
        String perspective,
        Scope scope,
        LocalDateTime from,
        LocalDateTime through,
        List<Policy> before,
        List<Policy> after,
        List<Policy> added,
        List<Policy> removed,
        List<Policy> retained,
        Kind kind,
        List<Policy> candidates,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean matchingConfigurations,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean matchingRoundGoods) {
    public PolicyExecutionChange {
        // 按已冻结的内容展示，未记录候选时不推导当时的可选政策。
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public enum Kind {
        UNCHANGED,
        ORDINARY_ADDITION,
        STACK,
        REPLACEMENT,
        REMOVE_STACK,
        REMOVAL
    }

    private record RoundId(String policyId, int submissionVersion) {
        RoundId(Policy policy) {
            this(policy.policyId(), policy.submissionVersion());
        }
    }

    /** 名单来自执行规则回放；单号、审核日志 ID、展示字段及输入顺序均不参与身份比较。 */
    public static PolicyExecutionChange compare(
            String perspective,
            Scope scope,
            LocalDateTime from,
            LocalDateTime through,
            List<Policy> before,
            List<Policy> after) {
        return compare(perspective, scope, from, through, before, after, List.of());
    }

    public static PolicyExecutionChange compare(
            String perspective,
            Scope scope,
            LocalDateTime from,
            LocalDateTime through,
            List<Policy> before,
            List<Policy> after,
            List<Policy> candidates) {
        return compare(perspective, scope, from, through, before, after, candidates, false);
    }

    public static PolicyExecutionChange compare(
            String perspective,
            Scope scope,
            LocalDateTime from,
            LocalDateTime through,
            List<Policy> before,
            List<Policy> after,
            List<Policy> candidates,
            boolean matchingRoundGoods) {
        if (from.isAfter(through)) throw new IllegalArgumentException("执行段的开始晚于截止。");
        var original = byRound(before);
        var proposed = byRound(after);
        var added = difference(proposed, original);
        var removed = difference(original, proposed);
        var retained =
                proposed.entrySet().stream()
                        .filter(entry -> original.containsKey(entry.getKey()))
                        .map(Map.Entry::getValue)
                        .toList();
        return new PolicyExecutionChange(
                perspective,
                scope,
                from,
                through,
                List.copyOf(original.values()),
                List.copyOf(proposed.values()),
                added,
                removed,
                retained,
                classify(added, removed, retained),
                List.copyOf(byRound(candidates).values()),
                null,
                matchingRoundGoods);
    }

    public boolean stackedSelection() {
        return !candidates.isEmpty() && after.size() >= 2;
    }

    public boolean requiresProductReview() {
        if (matchingRoundGoods != null) return matchingRoundGoods && stackedSelection();
        // 历史在途卡按当轮冻结的机型配置或更早的交叉确认依据展示。
        if (matchingConfigurations == null) return !candidates.isEmpty();
        return matchingConfigurations && stackedSelection();
    }

    PolicyExecutionChange withThrough(LocalDateTime end) {
        return copy(end, matchingRoundGoods);
    }

    PolicyExecutionChange withProposedPolicies(List<Policy> selected, List<Policy> eligible) {
        var change = compare(perspective, scope, from, through, before, selected, eligible);
        return new PolicyExecutionChange(
                perspective,
                scope,
                from,
                through,
                change.before,
                change.after,
                change.added,
                change.removed,
                change.retained,
                change.kind,
                change.candidates,
                matchingConfigurations,
                matchingRoundGoods);
    }

    PolicyExecutionChange withMatchingRoundGoods() {
        return copy(through, true);
    }

    private PolicyExecutionChange copy(LocalDateTime end, Boolean goodsMatch) {
        return new PolicyExecutionChange(
                perspective,
                scope,
                from,
                end,
                before,
                after,
                added,
                removed,
                retained,
                kind,
                candidates,
                matchingConfigurations,
                goodsMatch);
    }

    static boolean sameMembers(List<Policy> left, List<Policy> right) {
        return byRound(left).keySet().equals(byRound(right).keySet());
    }

    private static Map<RoundId, Policy> byRound(Collection<Policy> policies) {
        var rounds =
                new TreeMap<RoundId, Policy>(
                        Comparator.comparing(RoundId::policyId)
                                .thenComparingInt(RoundId::submissionVersion));
        for (var policy : policies) rounds.putIfAbsent(new RoundId(policy), policy);
        return rounds;
    }

    private static List<Policy> difference(Map<RoundId, Policy> left, Map<RoundId, Policy> right) {
        return left.entrySet().stream()
                .filter(entry -> !right.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    private static Kind classify(List<Policy> added, List<Policy> removed, List<Policy> retained) {
        if (!added.isEmpty() && !removed.isEmpty()) return Kind.REPLACEMENT;
        if (!added.isEmpty()) {
            // 同批两份拟执行的延期可以从空基线形成叠加。
            return retained.isEmpty() && added.size() == 1 ? Kind.ORDINARY_ADDITION : Kind.STACK;
        }
        if (!removed.isEmpty()) return retained.isEmpty() ? Kind.REMOVAL : Kind.REMOVE_STACK;
        return Kind.UNCHANGED;
    }
}
