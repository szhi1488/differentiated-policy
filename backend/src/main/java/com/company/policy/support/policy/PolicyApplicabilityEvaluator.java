package com.company.policy.support.policy;

import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.entity.PolicyConsent;
import com.company.policy.entity.PolicyConsent.Decision;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.entity.PolicyConsent.Target;
import com.company.policy.vo.ApplicantCatalogResponse;
import com.company.policy.vo.ApplicantCatalogResponse.Configuration;
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
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** 提交、审核和实际生效查询共用的分段及政策选择规则。 */
public final class PolicyApplicabilityEvaluator {
    record Window(LocalDateTime from, LocalDateTime through) {
        static Window of(Snapshot snapshot, LocalDateTime approvedAt) {
            var start = PolicyFormValidation.time(snapshot.form().startTime());
            return new Window(
                    start.isAfter(approvedAt) ? start : approvedAt, originalThrough(snapshot));
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
        return PolicyFormValidation.time(snapshot.form().endTime());
    }

    record Round(
            Policy policy,
            Window window,
            Window original,
            LocalDateTime visibleFrom,
            Boolean estimated,
            LocalDateTime submittedAt,
            long auditOrder,
            PolicyEffectivePeriods.Change timeChange,
            List<Decision> decisions,
            Set<String> goodsIds) {
        String id() {
            return policy.policyId();
        }

        Window visibleWindow() {
            return new Window(visibleFrom, window.through());
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

    private final Map<Long, List<PolicyEffectivePeriods.Change>> timeChanges = new HashMap<>();
    private final Map<String, LocalDateTime> filedEnds = new HashMap<>();
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
            if (!window.expired()) addSelections(item, null, submittedAt, null, window, auditOrder);
        }
        this.requested = new Window(start, end);
    }

    /** 时点查询从空范围起算，仅纳入实际获批的轮次。 */
    public PolicyApplicabilityEvaluator(String channel, Scope scope, LocalDateTime at) {
        this(channel, List.of(scope), at);
    }

    public PolicyApplicabilityEvaluator(String channel, List<Scope> scopes, LocalDateTime at) {
        this.asOf = at;
        this.retail = "RETAIL".equals(channel);
        this.requested = new Window(at, at);
        scopes.forEach(scope -> rounds.put(scope, new ArrayList<>()));
    }

    public void includeTimeChange(long auditId, PolicyEffectivePeriods.Change change) {
        timeChanges.computeIfAbsent(auditId, ignored -> new ArrayList<>()).add(change);
    }

    private List<PolicyEffectivePeriods.Period> periods(
            Snapshot snapshot, Long auditId, Window window) {
        var changes = timeChanges.getOrDefault(auditId, List.of());
        return PolicyEffectivePeriods.resolve(
                window, PolicyFormValidation.time(snapshot.form().startTime()), changes);
    }

    List<Round> roundsFor(Scope scope) {
        return rounds.get(scope);
    }

    void includeTimeline(
            Snapshot snapshot, long auditId, LocalDateTime submittedAt, LocalDateTime approvedAt) {
        addSelections(
                snapshot,
                auditId,
                submittedAt,
                approvedAt,
                approvedWindow(snapshot, auditId, approvedAt),
                auditId);
    }

    public List<Policy> effectivePolicies(Scope scope) {
        var active = new TreeSet<>(NEWEST);
        roundsFor(scope).stream()
                .filter(r -> !requested.intersect(r.window).expired())
                .forEach(active::add);
        return effectivePolicies(active, asOf);
    }

    List<Policy> effectivePolicies(SortedSet<Round> active, LocalDateTime at) {
        if (retail) {
            var selected = retailSelectionAt(active, at);
            if (selected != null) {
                return active.stream()
                        .filter(r -> selected.contains(r.id()))
                        .map(Round::policy)
                        .toList();
            }
            if (active.size() > 1) throw PolicyWorkflowReplay.uncertain();
            return active.stream().map(Round::policy).toList();
        }
        var selected = billionCandidates(at, active);
        return active.stream().filter(r -> selected.contains(r.id())).map(Round::policy).toList();
    }

    private List<String> retailSelectionAt(SortedSet<Round> active, LocalDateTime at) {
        var applicable = orderedDecisions(active, at, Action.SELECT_RETAIL_POLICIES);
        return applicable.isEmpty() ? null : applicable.get(0).selectedPolicyIds();
    }

    private record RoundDecision(Round round, Decision decision) {}

    private List<Decision> orderedDecisions(
            SortedSet<Round> active, LocalDateTime at, Action action) {
        var point = time(at);
        // 遍历顺序已按提交秒倒序。同秒中，改期流水与审批日志的自增 ID 不能跨表比较。
        var byTime = new LinkedHashMap<LocalDateTime, List<RoundDecision>>();
        for (var round : active) {
            for (var decision : round.decisions()) {
                if (decision.action() == action
                        && decision.from().compareTo(point) <= 0
                        && decision.through().compareTo(point) >= 0
                        && !allFiledPeriodsEnded(decision, at)) {
                    byTime.computeIfAbsent(round.submittedAt(), ignored -> new ArrayList<>())
                            .add(new RoundDecision(round, decision));
                }
            }
        }
        var ordered = new ArrayList<Decision>();
        for (var sameSecond : byTime.values()) {
            var regular = new ArrayList<RoundDecision>();
            for (var item : sameSecond) if (item.round().timeChange() == null) regular.add(item);
            for (var item : sameSecond) {
                var change = item.round().timeChange();
                if (change == null) continue;
                // 该次改期已核对的获批轮次一定先于改期；同秒才进入候选的普通单排在改期之前回放。
                // 多次改期本身仍按时间及同表流水 ID 倒序。百亿只处理各次决定里实际参与的候选。
                var it = regular.iterator();
                while (it.hasNext()) {
                    var ordinary = it.next();
                    var id = ordinary.round().id();
                    if (!change.comparedPolicyIds().contains(id)
                            && !item.decision().eligiblePolicyIds().contains(id)) {
                        ordered.add(ordinary.decision());
                        it.remove();
                    }
                }
                ordered.add(item.decision());
            }
            for (var item : regular) ordered.add(item.decision());
        }
        return ordered;
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

    private Window approvedWindow(Snapshot snapshot, long auditId, LocalDateTime approvedAt) {
        var window = Window.of(snapshot, approvedAt);
        var latestFiledEnd =
                timeChanges.getOrDefault(auditId, List.of()).stream()
                        .filter(PolicyEffectivePeriods.Change::extension)
                        .map(PolicyEffectivePeriods.Change::actualEnd)
                        .reduce(window.through(), (a, b) -> a.isAfter(b) ? a : b);
        filedEnds.put(snapshot.id(), latestFiledEnd);
        return window;
    }

    // 旧选单决定可能覆盖到日末。只在已选单全部过了原填截止后退出该决定，显式缩短仍按原审核决定回放。
    private boolean allFiledPeriodsEnded(Decision decision, LocalDateTime at) {
        return decision.selectedPolicyIds().stream()
                .allMatch(id -> filedEnds.containsKey(id) && filedEnds.get(id).isBefore(at));
    }

    public void include(
            Snapshot historical,
            long auditId,
            LocalDateTime submittedAt,
            LocalDateTime approvedAt) {
        var window = approvedWindow(historical, auditId, approvedAt);
        if (periods(historical, auditId, window).stream()
                .anyMatch(p -> !requested.intersect(p.window()).expired())) {
            addSelections(historical, auditId, submittedAt, approvedAt, window, auditId);
        }
    }

    /** 索引已按明细命中本轮范围。若已获批原文在相交时段却无法还原任何对应商品，分流须重新核对。 */
    public void includeWorkflow(
            Snapshot historical,
            long auditId,
            LocalDateTime submittedAt,
            LocalDateTime approvedAt) {
        var window = approvedWindow(historical, auditId, approvedAt);
        boolean overlaps =
                periods(historical, auditId, window).stream()
                        .anyMatch(p -> !requested.intersect(p.window()).expired());
        if (overlaps
                && !addSelections(historical, auditId, submittedAt, approvedAt, window, auditId))
            throw PolicyWorkflowReplay.uncertain();
    }

    private boolean addSelections(
            Snapshot snapshot,
            Long auditId,
            LocalDateTime submittedAt,
            LocalDateTime approvedAt,
            Window window,
            long auditOrder) {
        var catalog = new HashMap<String, ApplicantCatalogResponse>();
        if (snapshot.goods() != null) {
            snapshot.goods().forEach(g -> catalog.put(g.goodsId(), g));
        }
        var selections = new LinkedHashMap<Scope, List<Selection>>();
        boolean proposed = auditId == null;
        for (var card : snapshot.form().goodsForm().cards()) {
            var plans = new HashMap<String, SubsidyPlan>();
            card.subsidyPlans().forEach(plan -> plans.put(plan.key(), plan));
            // 零售按单件裁剪后仍需保留提交时整组的件数，供时间轴判断本段能否合并。
            var wholeCounts = new HashMap<Configuration, Integer>();
            if (snapshot.wholeConfigurationSelection() && "COMMON".equals(card.cardType())) {
                for (var target : card.targets()) {
                    wholeCounts.merge(
                            new Configuration(target.spuId(), target.ram(), target.rom()),
                            1,
                            Integer::sum);
                }
            }
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
                    var product = group.getValue().get(0);
                    var row =
                            new Selection(
                                    card.cardType(),
                                    group.getValue(),
                                    plans.get(assignment.planKey()),
                                    assignment.quantity(),
                                    wholeCounts.get(
                                            new Configuration(
                                                    product.spuId(),
                                                    product.ram(),
                                                    product.rom())));
                    selections
                            .computeIfAbsent(group.getKey(), ignored -> new ArrayList<>())
                            .add(row);
                }
            }
        }
        var decisionsByScope = new HashMap<Scope, List<Decision>>();
        if (!proposed) {
            if (snapshot.consent() == null || !snapshot.consent().supported()) {
                throw new IllegalStateException("无法识别该轮适用范围规则版本。");
            }
            for (var saved : snapshot.consent().decisions()) {
                var decision = actualIds(saved);
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
                        originalThrough(snapshot));
        // 整单的普通卡和指定卡共享一份商品集合，各审核范围只引用它。
        var goodsIds =
                snapshot.form().goodsForm().cards().stream()
                        .flatMap(card -> card.targets().stream())
                        .map(PolicyDraftRequest.Target::goodsId)
                        .collect(Collectors.toUnmodifiableSet());
        var periods = periods(snapshot, auditId, window);
        var changes = timeChanges.getOrDefault(auditId, List.of());
        var currentEnd =
                changes.isEmpty() ? window.through() : changes.get(changes.size() - 1).actualEnd();
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
                            time(currentEnd),
                            snapshot.form().verifyType(),
                            snapshot.form().sceneTypes(),
                            group.getValue());
            var scopeRounds = rounds.computeIfAbsent(group.getKey(), ignored -> new ArrayList<>());
            for (var period : periods) {
                var change = period.change();
                var decisions =
                        change == null
                                ? decisionsByScope.getOrDefault(group.getKey(), List.of())
                                : change.decisions().stream()
                                        .filter(d -> group.getKey().equals(d.scope()))
                                        .toList();
                scopeRounds.add(
                        new Round(
                                policy,
                                period.window(),
                                original,
                                period.visibleFrom(),
                                snapshot.form().estimated(),
                                change == null ? submittedAt : change.changedAt(),
                                change == null ? auditOrder : change.id(),
                                change,
                                decisions,
                                goodsIds));
            }
        }
        return !selections.isEmpty();
    }

    private static Decision actualIds(Decision saved) {
        if (saved.eligiblePolicyIds().stream().noneMatch(id -> id.startsWith("extension:")))
            return saved;
        return new Decision(
                saved.scope(),
                saved.from(),
                saved.through(),
                saved.action(),
                saved.groups().stream()
                        .map(
                                group ->
                                        new Group(
                                                group.sceneTypes(),
                                                group.verifyType(),
                                                actualIds(group.policyIds())))
                        .toList(),
                actualIds(saved.eligiblePolicyIds()),
                actualIds(saved.selectedPolicyIds()));
    }

    private static List<String> actualIds(List<String> ids) {
        return ids.stream().map(PolicyApplicabilityEvaluator::originalId).distinct().toList();
    }

    private static String originalId(String id) {
        return id.startsWith("extension:") ? id.substring(id.lastIndexOf(':') + 1) : id;
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
        var evaluation = new PolicyRouteSummary.Evaluation();
        workflowSegments(confirmed, options, evaluation);
        return evaluation.changes();
    }

    public void workflowSegments(
            PolicyConsent confirmed,
            Map<Target, AdjustmentOption> options,
            PolicyRouteSummary.Evaluation evaluation) {
        var adjusted = PolicyEndTimes.withProposals(proposedCuts, options);
        var byScope = new HashMap<Scope, List<Decision>>();
        for (var decision : confirmed.decisions()) {
            byScope.computeIfAbsent(decision.scope(), unused -> new ArrayList<>()).add(decision);
        }
        for (var item : rounds.entrySet()) {
            var scope = item.getKey();
            PolicyWorkflowReplay.compare(
                    this,
                    retail,
                    scope,
                    requested,
                    item.getValue().stream().filter(round -> !round.policy().proposed()).toList(),
                    adjusted.apply(item.getValue()),
                    byScope.getOrDefault(scope, List.of()),
                    evaluation);
        }
    }

    public PolicyApplicability result(Map<Target, AdjustmentOption> options) {
        var rules = PolicyEndTimes.withProposals(proposedCuts, options);
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
                                policies.stream()
                                        .sorted(NEWEST)
                                        .map(Round::policy)
                                        .distinct()
                                        .toList(),
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
            if (overlap.expired()) continue;
            boundaries.computeIfAbsent(overlap.from, ignored -> new Boundary()).starting.add(prior);
            // 冻结的执行选择可以在同一组候选的有效期内发生变化。
            for (var decision : prior.decisions) {
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
        Set<String> previousAdopted = null;
        for (var entry : boundaries.entrySet()) {
            var boundary = entry.getKey();
            if (previous != null
                    && active.size() > 1
                    && active.stream().anyMatch(r -> r.policy.proposed())) {
                var end = boundary.minusSeconds(1);
                var next =
                        retail
                                ? retailOptions(previous, end, active)
                                : new Segment(
                                        time(previous),
                                        time(end),
                                        Action.STACK_OR_CHOOSE,
                                        List.of(),
                                        billionCandidates(previous, active),
                                        false);
                var approved = new TreeSet<>(NEWEST);
                active.stream().filter(r -> !r.policy().proposed()).forEach(approved::add);
                var adopted = adoptedIds(approved, previous);
                var last = segments.isEmpty() ? null : segments.get(segments.size() - 1);
                boolean adjacent =
                        last != null && time(previous.minusSeconds(1)).equals(last.through());
                if (adjacent
                        && adopted.equals(previousAdopted)
                        && Set.copyOf(last.eligiblePolicyIds())
                                .equals(Set.copyOf(next.eligiblePolicyIds()))) {
                    segments.set(
                            segments.size() - 1,
                            new Segment(
                                    last.from(),
                                    next.through(),
                                    last.action(),
                                    last.groups(),
                                    last.eligiblePolicyIds(),
                                    last.continuesPrevious()));
                } else if (adjacent && continuesExtension(last, next, previousAdopted, adopted)) {
                    segments.add(
                            new Segment(
                                    next.from(),
                                    next.through(),
                                    next.action(),
                                    next.groups(),
                                    next.eligiblePolicyIds(),
                                    true));
                } else {
                    segments.add(next);
                }
                previousAdopted = adopted;
            }
            active.removeAll(entry.getValue().ending);
            active.addAll(entry.getValue().starting);
            previous = boundary;
        }
        return segments;
    }

    // 拟延期取自同一获批快照，沿用原商品、补贴和分组。只消去本次到期接续的原单身份，
    // 如有其他单据加入、退出或实际采用名单变化，申请人仍须独立核对。
    private static boolean continuesExtension(
            Segment last, Segment next, Set<String> beforeAdopted, Set<String> afterAdopted) {
        var before = Set.copyOf(last.eligiblePolicyIds());
        var expected = new LinkedHashSet<>(before);
        var remainingAdopted = new LinkedHashSet<>(beforeAdopted);
        boolean continued = false;
        for (var id : next.eligiblePolicyIds()) {
            var original = originalId(id);
            if (!id.equals(original) && before.contains(original)) {
                expected.remove(original);
                expected.add(id);
                remainingAdopted.remove(original);
                continued = true;
            }
        }
        return continued
                && expected.equals(Set.copyOf(next.eligiblePolicyIds()))
                && remainingAdopted.equals(afterAdopted);
    }

    private Set<String> adoptedIds(SortedSet<Round> active, LocalDateTime at) {
        if (!retail) return Set.copyOf(billionCandidates(at, active));
        var selected = retailSelectionAt(active, at);
        return active.stream()
                .filter(r -> selected == null || selected.contains(r.id()))
                .map(Round::id)
                .collect(Collectors.toSet());
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
                                                e.getKey().scenes,
                                                e.getKey().verification,
                                                e.getValue()))
                        .toList();
        return new Segment(
                time(start),
                time(end),
                Action.SELECT_RETAIL_POLICIES,
                groups,
                active.stream().map(Round::id).toList(),
                false);
    }

    private List<String> billionCandidates(LocalDateTime start, SortedSet<Round> active) {
        var excluded = new LinkedHashSet<String>();
        var decided = new LinkedHashSet<String>();
        for (var decision : orderedDecisions(active, start, Action.STACK_OR_CHOOSE)) {
            for (var id : decision.eligiblePolicyIds()) {
                // 一张原单缩短后再延长时，新增时段里重新确认的选择优先。
                if (decided.add(id) && !decision.selectedPolicyIds().contains(id)) excluded.add(id);
            }
        }
        return active.stream().filter(r -> !excluded.contains(r.id())).map(Round::id).toList();
    }

    static String time(LocalDateTime value) {
        return value == null ? null : value.format(PolicyFormValidation.TIME);
    }
}
