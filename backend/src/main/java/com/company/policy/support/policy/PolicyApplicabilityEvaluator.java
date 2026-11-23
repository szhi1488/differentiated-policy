package com.company.policy.support.policy;

import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.entity.PolicyConsent;
import com.company.policy.entity.PolicyConsent.Decision;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.entity.PolicyConsent.Target;
import com.company.policy.vo.ApplicantCatalogResponse;
import com.company.policy.vo.PolicyApplicability;
import com.company.policy.vo.PolicyApplicability.Action;
import com.company.policy.vo.PolicyApplicability.AdjustmentOption;
import com.company.policy.vo.PolicyApplicability.Group;
import com.company.policy.vo.PolicyApplicability.Policy;
import com.company.policy.vo.PolicyApplicability.Product;
import com.company.policy.vo.PolicyApplicability.Scope;
import com.company.policy.vo.PolicyApplicability.ScopeResult;
import com.company.policy.vo.PolicyApplicability.Segment;
import com.company.policy.vo.PolicyApplicability.Selection;
import com.company.policy.vo.PolicyApplicability.Status;
import com.company.policy.vo.PolicyResponse.Snapshot;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/** 提交、审核和实际生效查询共用的分段及政策选择规则。 */
public final class PolicyApplicabilityEvaluator {
    private static final LocalDateTime OPEN_END = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    record Window(LocalDateTime from, LocalDateTime through) {
        static Window of(Snapshot snapshot, LocalDateTime approvedAt) {
            var start = PolicyFormValidation.time(snapshot.form().startTime());
            return new Window(
                    start.isAfter(approvedAt) ? start : approvedAt, originalThrough(snapshot));
        }

        static LocalDateTime originalThrough(Snapshot snapshot) {
            return PolicyApplicabilityEvaluator.originalThrough(
                    snapshot.form().endTime(), snapshot.sourcePolicyCode());
        }

        boolean expired() {
            return from.isAfter(through);
        }

        Window intersect(Window other) {
            return new Window(
                    from.isAfter(other.from) ? from : other.from,
                    through.isBefore(other.through) ? through : other.through);
        }
    }

    public static LocalDateTime originalThrough(Snapshot snapshot) {
        return Window.originalThrough(snapshot);
    }

    public static LocalDateTime originalThrough(String endTime, String sourcePolicyCode) {
        return originalThrough(PolicyFormValidation.time(endTime), sourcePolicyCode);
    }

    public static LocalDateTime originalThrough(LocalDateTime end, String sourcePolicyCode) {
        return sourcePolicyCode != null ? end : end.toLocalDate().atTime(23, 59, 59);
    }

    record Round(
            Policy policy,
            Window window,
            Window original,
            Boolean estimated,
            LocalDateTime submittedAt,
            long auditOrder,
            boolean legacy,
            boolean missingConsent,
            List<Decision> decisions,
            String batchKey) {
        String id() {
            return policy.policyId();
        }

        boolean namedBy(java.util.Collection<String> ids) {
            return ids.contains(id()) || (batchKey != null && ids.contains(batchKey));
        }
    }

    private record SceneGroup(List<String> scenes, String verification) {}

    private record Boundary(List<Round> starting, List<Round> ending) {
        Boundary() {
            this(new ArrayList<>(), new ArrayList<>());
        }
    }

    static final Comparator<Round> NEWEST =
            Comparator.comparing(Round::submittedAt)
                    .thenComparingLong(Round::auditOrder)
                    .thenComparing(Round::id)
                    .reversed();

    private final PolicyEndTimes approvedRules = new PolicyEndTimes();
    private List<EndAdjustment> proposedCuts = List.of();
    private final boolean retail;
    private final LocalDateTime asOf;
    private final Window requested;
    // 每个范围第一项是本次试算轮次，同一历史轮次在这里也只追加一次。
    private final Map<Scope, List<Round>> rounds = new LinkedHashMap<>();

    public PolicyApplicabilityEvaluator(Snapshot draft, LocalDateTime asOf) {
        this(draft, asOf, asOf, Long.MAX_VALUE);
    }

    public PolicyApplicabilityEvaluator(
            Snapshot draft, LocalDateTime asOf, LocalDateTime submittedAt, long auditOrder) {
        this(List.of(draft), asOf, submittedAt, auditOrder);
    }

    /** 同渠道的本次原单和所有延期段共用时段边界，同一交叉只产生一份决定。 */
    public PolicyApplicabilityEvaluator(
            List<Snapshot> proposals,
            LocalDateTime asOf,
            LocalDateTime submittedAt,
            long auditOrder) {
        this.asOf = asOf;
        var draft = proposals.get(0);
        this.retail = "RETAIL".equals(draft.form().perspective());
        proposedCuts = draft.consent() == null ? List.of() : draft.consent().endAdjustments();
        var first = Window.of(draft, asOf);
        var start = first.from;
        var end = first.through;
        for (var item : proposals) {
            var window = Window.of(item, asOf);
            if (window.from.isBefore(start)) start = window.from;
            if (window.through.isAfter(end)) end = window.through;
            if (!window.expired())
                addSelections(item, null, submittedAt, null, window, auditOrder, false);
        }
        this.requested = new Window(start, end);
    }

    /** 时点查询从空范围起算，仅纳入实际获批的轮次。 */
    public PolicyApplicabilityEvaluator(String channel, Scope scope, LocalDateTime at) {
        this(channel, List.of(scope), at);
    }

    PolicyApplicabilityEvaluator(String channel, List<Scope> scopes, LocalDateTime at) {
        this.asOf = at;
        this.retail = "RETAIL".equals(channel);
        this.requested = new Window(at, at);
        scopes.forEach(scope -> rounds.put(scope, new ArrayList<>()));
    }

    public void includeApprovedEnd(String auditId, LocalDateTime actualEnd) {
        approvedRules.cap(auditId, actualEnd);
    }

    List<Round> roundsFor(Scope scope) {
        return approvedRules.apply(rounds.get(scope));
    }

    // 切换前旧单可延续至切换前一秒。图中还需保留原单期内从未获选的记录。
    void includeTimeline(
            Snapshot snapshot,
            long auditId,
            LocalDateTime submittedAt,
            LocalDateTime approvedAt,
            LocalDateTime cutover) {
        var window = Window.of(snapshot, approvedAt);
        boolean legacy =
                snapshot.consent() == null && (cutover == null || approvedAt.isBefore(cutover));
        if (legacy && cutover != null && window.through.isBefore(cutover.minusSeconds(1))) {
            window = new Window(window.from, cutover.minusSeconds(1));
        }
        addSelections(snapshot, auditId, submittedAt, approvedAt, window, auditId, legacy);
    }

    public List<Policy> effectivePolicies(Scope scope, long queriedGoodsId) {
        var active = new TreeSet<>(NEWEST);
        roundsFor(scope).stream()
                .filter(r -> !requested.intersect(r.window).expired())
                .forEach(active::add);
        return effectivePolicies(retail, active, queriedGoodsId, asOf);
    }

    static List<Policy> effectivePolicies(
            boolean retail, SortedSet<Round> active, long queriedGoodsId, LocalDateTime at) {
        // 只有旧轮次时仍按具体商品选一，不将其他颜色的旧政策扩展到被查商品。
        if (active.stream().allMatch(Round::legacy)) {
            var goodsId = Long.toString(queriedGoodsId);
            return active.stream()
                    .filter(
                            r ->
                                    r.policy.selections().stream()
                                            .flatMap(s -> s.products().stream())
                                            .anyMatch(p -> p.goodsId().equals(goodsId)))
                    .limit(1)
                    .map(Round::policy)
                    .toList();
        }
        // 零售新轮次冻结了该段的完整执行名单，即使提交该决定的政策本身未被勾选也要应用。
        if (retail) {
            var point = time(at);
            for (var round : active) {
                for (var decision : round.decisions) {
                    if (decision.action() == Action.SELECT_RETAIL_POLICIES
                            && decision.from().compareTo(point) <= 0
                            && decision.through().compareTo(point) >= 0) {
                        return active.stream()
                                .filter(r -> r.namedBy(decision.selectedPolicyIds()))
                                .map(Round::policy)
                                .toList();
                    }
                }
            }
        }
        var baseline = historicalBaseline(active);
        var selected = retail ? legacyRetailWinners(baseline) : billionCandidates(at, baseline);
        return active.stream().filter(r -> selected.contains(r.id())).map(Round::policy).toList();
    }

    public List<Scope> scopes() {
        return List.copyOf(rounds.keySet());
    }

    public String firstDate() {
        return requested.from.toLocalDate().toString();
    }

    public String lastDate() {
        return requested.through.toLocalDate().toString();
    }

    public void include(
            Snapshot historical,
            long auditId,
            LocalDateTime submittedAt,
            LocalDateTime approvedAt) {
        include(historical, auditId, submittedAt, approvedAt, historical.consent() == null, false);
    }

    public void include(
            Snapshot historical,
            long auditId,
            LocalDateTime submittedAt,
            LocalDateTime approvedAt,
            boolean legacy,
            boolean beforeCutover) {
        var window = Window.of(historical, approvedAt);
        if (legacy && beforeCutover) {
            window = new Window(window.from, OPEN_END);
        }
        if (!requested.intersect(window).expired()) {
            addSelections(historical, auditId, submittedAt, approvedAt, window, auditId, legacy);
        }
    }

    /** 索引已按明细命中本轮范围。若已获批原文在相交时段却无法还原任何对应商品，分流须重新核对。 */
    public void includeWorkflow(
            Snapshot historical,
            long auditId,
            LocalDateTime submittedAt,
            LocalDateTime approvedAt,
            boolean legacy) {
        var window = Window.of(historical, approvedAt);
        var actual =
                new Window(
                        window.from(),
                        approvedRules.through(Long.toString(auditId), window.through()));
        if (!requested.intersect(actual).expired()
                && !addSelections(
                        historical, auditId, submittedAt, approvedAt, window, auditId, legacy))
            throw PolicyWorkflowReplay.uncertain();
    }

    private boolean addSelections(
            Snapshot snapshot,
            Long auditId,
            LocalDateTime submittedAt,
            LocalDateTime approvedAt,
            Window window,
            long auditOrder,
            boolean legacy) {
        var catalog = new HashMap<String, ApplicantCatalogResponse>();
        if (snapshot.goods() != null) {
            snapshot.goods().forEach(g -> catalog.put(g.goodsId(), g));
        }
        var selections = new LinkedHashMap<Scope, List<Selection>>();
        boolean proposed = auditId == null;
        for (var card : snapshot.form().goodsForm().cards()) {
            var plans = new HashMap<String, SubsidyPlan>();
            card.subsidyPlans().forEach(plan -> plans.put(plan.key(), plan));
            for (var assignment : card.platformAssignments()) {
                var byScope = new LinkedHashMap<Scope, List<Product>>();
                for (var target : card.targets()) {
                    var scope = scope(assignment.platformCode(), target);
                    if (proposed || rounds.containsKey(scope)) {
                        byScope.computeIfAbsent(scope, ignored -> new ArrayList<>())
                                .add(product(target, catalog.get(target.goodsId())));
                    }
                }
                for (var group : byScope.entrySet()) {
                    var row =
                            new Selection(
                                    card.cardType(),
                                    group.getValue(),
                                    plans.get(assignment.planKey()),
                                    assignment.quantity());
                    selections
                            .computeIfAbsent(group.getKey(), ignored -> new ArrayList<>())
                            .add(row);
                }
            }
        }
        var decisionsByScope = new HashMap<Scope, List<Decision>>();
        if (!proposed && snapshot.consent() != null) {
            if (!snapshot.consent().supported()) {
                throw new IllegalStateException("无法识别该轮适用范围规则版本。");
            }
            for (var decision : snapshot.consent().decisions()) {
                if (selections.containsKey(decision.scope())) {
                    decisionsByScope
                            .computeIfAbsent(decision.scope(), unused -> new ArrayList<>())
                            .add(decision);
                }
            }
        }
        var original =
                new Window(
                        PolicyFormValidation.time(snapshot.form().startTime()),
                        Window.originalThrough(snapshot));
        for (var group : selections.entrySet()) {
            var policy =
                    new Policy(
                            snapshot.id(),
                            snapshot.policyCode(),
                            snapshot.submissionVersion(),
                            auditOrder == Long.MAX_VALUE ? null : Long.toString(auditOrder),
                            proposed,
                            time(submittedAt),
                            time(approvedAt),
                            time(window.from),
                            time(window.through),
                            snapshot.form().verifyType(),
                            snapshot.form().sceneTypes(),
                            group.getValue());
            var round =
                    new Round(
                            policy,
                            window,
                            original,
                            snapshot.form().estimated(),
                            submittedAt,
                            auditOrder,
                            legacy,
                            !proposed && !legacy && snapshot.consent() == null,
                            decisionsByScope.getOrDefault(group.getKey(), List.of()),
                            snapshot.consent() == null
                                    ? null
                                    : snapshot.consent().batchPolicyKey());
            rounds.computeIfAbsent(group.getKey(), ignored -> new ArrayList<>()).add(round);
        }
        return !selections.isEmpty();
    }

    private Scope scope(String platform, PolicyDraftRequest.Target target) {
        return retail
                ? new Scope(platform, Long.valueOf(target.goodsId()), null, null, null)
                : new Scope(
                        platform, null, Long.valueOf(target.spuId()), target.ram(), target.rom());
    }

    private static Product product(
            PolicyDraftRequest.Target target, ApplicantCatalogResponse good) {
        if (good != null
                && !(target.spuId().equals(good.spuId())
                        && target.ram().equals(good.ram())
                        && target.rom().equals(good.rom()))) {
            good = null;
        }
        return new Product(
                target.goodsId(),
                good == null ? null : good.goodsName(),
                good == null ? null : good.colorName(),
                target.spuId(),
                good == null ? null : good.modelName(),
                good == null ? null : good.edition(),
                target.ram(),
                target.rom());
    }

    public PolicyApplicability result() {
        return result(Map.of());
    }

    /** 将已批准的实际、未来安排与本轮已确认的逐段选择对齐，供整单审核分流使用。 */
    public List<PolicyExecutionChange> workflowSegments(
            PolicyConsent confirmed, Map<Target, AdjustmentOption> options) {
        var adjusted = approvedRules.withProposals(proposedCuts, options);
        var byScope = new HashMap<Scope, List<Decision>>();
        for (var decision : confirmed.decisions()) {
            byScope.computeIfAbsent(decision.scope(), unused -> new ArrayList<>()).add(decision);
        }
        var changes = new ArrayList<PolicyExecutionChange>();
        for (var item : rounds.entrySet()) {
            var scope = item.getKey();
            PolicyWorkflowReplay.compare(
                    retail,
                    scope,
                    requested,
                    approvedRules.apply(item.getValue()).stream()
                            .filter(round -> !round.policy().proposed())
                            .toList(),
                    adjusted.apply(item.getValue()),
                    byScope.getOrDefault(scope, List.of()),
                    changes);
        }
        return List.copyOf(changes);
    }

    public PolicyApplicability result(Map<Target, AdjustmentOption> options) {
        var rules = approvedRules.withProposals(proposedCuts, options);
        var matches = new ArrayList<ScopeResult>();
        for (var item : rounds.entrySet()) {
            var scope = item.getKey();
            var roundsInScope = rules.apply(item.getValue());
            var proposals = roundsInScope.stream().filter(r -> r.policy.proposed()).toList();
            var policies =
                    roundsInScope.stream()
                            .filter(
                                    r ->
                                            proposals.stream()
                                                    .anyMatch(
                                                            p ->
                                                                    !p.window
                                                                            .intersect(r.window)
                                                                            .expired()))
                            .toList();
            if (policies.size() > 1) {
                var overlap = segments(policies);
                if (overlap.isEmpty()) continue;
                matches.add(
                        new ScopeResult(
                                scope,
                                policies.stream().sorted(NEWEST).map(Round::policy).toList(),
                                overlap));
            }
        }
        var status =
                requested.expired()
                        ? Status.NO_EFFECTIVE_PERIOD
                        : matches.isEmpty() ? Status.DIRECT : Status.NEEDS_CONFIRMATION;
        return new PolicyApplicability(
                "SNAPSHOT_DATES",
                time(asOf),
                time(requested.from),
                time(requested.through),
                status,
                status == Status.DIRECT,
                matches,
                List.copyOf(options.values()));
    }

    private List<Segment> segments(List<Round> policies) {
        var boundaries = new TreeMap<LocalDateTime, Boundary>();
        for (var prior : policies) {
            var overlap = requested.intersect(prior.window);
            boundaries.computeIfAbsent(overlap.from, ignored -> new Boundary()).starting.add(prior);
            // 较早百亿轮次的二选一可能只在交集中的几天有效。
            for (var decision : prior.decisions) {
                if (decision.action() != Action.STACK_OR_CHOOSE) continue;
                var interval =
                        overlap.intersect(
                                new Window(
                                        PolicyFormValidation.time(decision.from()),
                                        PolicyFormValidation.time(decision.through())));
                if (!interval.expired()) {
                    boundaries.computeIfAbsent(interval.from, ignored -> new Boundary());
                    boundaries.computeIfAbsent(
                            interval.through.plusSeconds(1), ignored -> new Boundary());
                }
            }
            // 闭区间的最后一秒仍有效，下一秒才移出。
            boundaries
                    .computeIfAbsent(overlap.through.plusSeconds(1), ignored -> new Boundary())
                    .ending
                    .add(prior);
        }
        var active = new TreeSet<>(NEWEST);
        var segments = new ArrayList<Segment>();
        LocalDateTime previous = null;
        for (var entry : boundaries.entrySet()) {
            var boundary = entry.getKey();
            if (previous != null
                    && active.size() > 1
                    && active.stream().anyMatch(r -> r.policy.proposed())) {
                var end = boundary.minusSeconds(1);
                segments.add(
                        retail
                                ? retailOptions(previous, end, active)
                                : new Segment(
                                        time(previous),
                                        time(end),
                                        Action.STACK_OR_CHOOSE,
                                        List.of(),
                                        billionCandidates(previous, historicalBaseline(active))));
            }
            active.removeAll(entry.getValue().ending);
            active.addAll(entry.getValue().starting);
            previous = boundary;
        }
        return segments;
    }

    private Segment retailOptions(LocalDateTime start, LocalDateTime end, SortedSet<Round> active) {
        var candidates = new LinkedHashMap<SceneGroup, List<String>>();
        for (var round : active) {
            var scenes =
                    PolicyScenes.CODES.stream()
                            .filter(round.policy.sceneTypes()::contains)
                            .toList();
            var group = new SceneGroup(scenes, round.policy.verifyType());
            candidates.computeIfAbsent(group, ignored -> new ArrayList<>()).add(round.id());
        }
        var groups =
                candidates.entrySet().stream()
                        .map(
                                e ->
                                        new Group(
                                                PolicyScenes.single(e.getKey().scenes),
                                                e.getKey().verification,
                                                e.getValue(),
                                                null,
                                                e.getKey().scenes))
                        .toList();
        return new Segment(
                time(start),
                time(end),
                Action.SELECT_RETAIL_POLICIES,
                groups,
                active.stream().map(Round::id).toList());
    }

    private static SortedSet<Round> historicalBaseline(SortedSet<Round> active) {
        var baseline = new TreeSet<>(NEWEST);
        var legacyGoods = new LinkedHashSet<String>();
        for (var round : active) {
            // 旧单先取旧规则下各具体商品的胜出轮次，已被覆盖的旧补贴不能因新规则恢复。
            if (!round.legacy) {
                baseline.add(round);
                continue;
            }
            boolean contributes = false;
            for (var row : round.policy.selections()) {
                for (var product : row.products()) {
                    if (legacyGoods.add(product.goodsId())) contributes = true;
                }
            }
            if (contributes) baseline.add(round);
        }
        return baseline;
    }

    private static List<String> billionCandidates(LocalDateTime start, SortedSet<Round> active) {
        var excluded = new LinkedHashSet<String>();
        var point = time(start);
        for (var round : active) {
            for (var decision : round.decisions) {
                if (decision.from().compareTo(point) <= 0
                        && decision.through().compareTo(point) >= 0) {
                    for (var id : decision.eligiblePolicyIds()) {
                        if (!decision.selectedPolicyIds().contains(id)) excluded.add(id);
                    }
                }
            }
        }
        return active.stream().filter(r -> !r.namedBy(excluded)).map(Round::id).toList();
    }

    // 历史零售轮次没有新版区间决定时，按当时的逐场景规则回查。
    private static List<String> legacyRetailWinners(SortedSet<Round> active) {
        var winners = new LinkedHashMap<SceneGroup, String>();
        for (var round : active) {
            for (var scene : round.policy.sceneTypes()) {
                winners.putIfAbsent(
                        new SceneGroup(List.of(scene), round.policy.verifyType()), round.id());
            }
        }
        return winners.values().stream().distinct().toList();
    }

    static String time(LocalDateTime value) {
        return value == null ? null : value.format(PolicyFormValidation.TIME);
    }
}
