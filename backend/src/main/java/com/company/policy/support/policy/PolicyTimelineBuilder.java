package com.company.policy.support.policy;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.support.policy.PolicyApplicabilityEvaluator.Round;
import com.company.policy.support.policy.PolicyApplicabilityEvaluator.Window;
import com.company.policy.vo.PolicyApplicability.Product;
import com.company.policy.vo.PolicyApplicability.Scope;
import com.company.policy.vo.PolicyApplicability.Selection;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.company.policy.vo.PolicySubsidyTimeline.Policy;
import com.company.policy.vo.PolicySubsidyTimeline.Segment;
import com.company.policy.vo.PolicySubsidyTimeline.Track;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;

/** 按配置解开已通过快照，以政策和逐段决定的边界回放实际适用名单。 */
public final class PolicyTimelineBuilder {
    private final String channel;
    private final List<Long> goods;
    private final PolicyApplicabilityEvaluator evaluator;
    private final Window query;
    private final LocalDateTime asOf;
    private final LocalDateTime cutover;

    public PolicyTimelineBuilder(
            String channel,
            Collection<String> platforms,
            long spuId,
            String ram,
            String rom,
            List<Long> goods,
            LocalDateTime from,
            LocalDateTime through,
            LocalDateTime asOf,
            LocalDateTime cutover) {
        this.channel = channel;
        this.goods = goods;
        this.query = new Window(from, through);
        this.asOf = asOf;
        this.cutover = cutover;
        var scopes = new ArrayList<Scope>();
        for (var code : new TreeSet<>(platforms)) {
            if (channel.equals("RETAIL")) {
                for (var id : goods) scopes.add(new Scope(code, id, null, null, null));
            } else scopes.add(new Scope(code, null, spuId, ram, rom));
        }
        evaluator = new PolicyApplicabilityEvaluator(channel, scopes, asOf);
    }

    public void include(
            Snapshot snapshot, long auditId, LocalDateTime submitted, LocalDateTime approved) {
        evaluator.includeTimeline(snapshot, auditId, submitted, approved, cutover);
    }

    public void includeApprovedEnd(String auditId, LocalDateTime actualEnd) {
        evaluator.includeApprovedEnd(auditId, actualEnd);
    }

    public List<Track> tracks() {
        var groups = new LinkedHashMap<TrackKey, List<Track>>();
        for (var scope : evaluator.scopes()) {
            var rounds = evaluator.roundsFor(scope);
            if (rounds.isEmpty()) continue;
            var window = displayWindow(rounds);
            if (window == null) continue;
            var events = events(rounds, window);
            if (!window.from().isAfter(asOf) && window.through().isAfter(asOf)) {
                events.computeIfAbsent(asOf.plusSeconds(1), k -> new Event());
            }
            if (scope.goodsId() != null) {
                collect(groups, track(scope.platformCode(), rounds, events, scope.goodsId()));
            } else if (rounds.stream().anyMatch(Round::legacy)) {
                for (long id : goods)
                    collect(groups, track(scope.platformCode(), rounds, events, id));
            } else {
                collect(groups, track(scope.platformCode(), rounds, events, null));
            }
        }
        return groups.values().stream().map(PolicyTimelineBuilder::merge).toList();
    }

    private Window displayWindow(List<Round> rounds) {
        LocalDateTime start = null;
        LocalDateTime end = null;
        for (var round : rounds) {
            var span = span(round);
            if (span.intersect(query).expired()) continue;
            if (start == null || span.from().isBefore(start)) start = span.from();
            if (end == null || span.through().isAfter(end)) end = span.through();
        }
        // 原单完整展示会扩展查询窗口。扩展部分涉及旧单时也必须遵循时点接口的切换口径。
        var last = end == null || end.isBefore(query.through()) ? query.through() : end;
        if (cutover == null && !query.from().isAfter(asOf)) {
            var lastHistorical = last.isBefore(asOf) ? last : asOf.minusSeconds(1);
            if (rounds.stream()
                    .anyMatch(
                            r ->
                                    r.legacy()
                                            && !r.original()
                                                    .from()
                                                    .toLocalDate()
                                                    .isAfter(lastHistorical.toLocalDate())
                                            && !PolicyFormValidation.time(r.policy().approvedAt())
                                                    .isAfter(lastHistorical)))
                throw unknownCutover();
        }
        if (start == null) return null;
        if (query.from().isAfter(asOf) && !start.isAfter(asOf)) start = asOf.plusSeconds(1);
        if (!query.through().isAfter(asOf) && end.isAfter(asOf)) end = asOf;
        return new Window(start, end);
    }

    public static ApiException unknownCutover() {
        return new ApiException(
                HttpStatus.CONFLICT, "POLICY_CUTOVER_REQUIRED", "该历史时点涉及旧政策，请管理员配置实际规则上线时间后重查。");
    }

    private static Window span(Round round) {
        return new Window(
                round.original().from(),
                round.window().through().isAfter(round.original().through())
                        ? round.window().through()
                        : round.original().through());
    }

    private static final class Event {
        final List<Round> show = new ArrayList<>();
        final List<Round> hide = new ArrayList<>();
        final List<Round> enter = new ArrayList<>();
        final List<Round> leave = new ArrayList<>();
    }

    private static TreeMap<LocalDateTime, Event> events(List<Round> rounds, Window window) {
        var events = new TreeMap<LocalDateTime, Event>();
        for (var round : rounds) {
            var visible = round.original().intersect(window);
            if (!visible.expired()) {
                events.computeIfAbsent(visible.from(), k -> new Event()).show.add(round);
                events.computeIfAbsent(visible.through().plusSeconds(1), k -> new Event())
                        .hide
                        .add(round);
            }
            var active = round.window().intersect(window);
            if (active.expired()) continue;
            events.computeIfAbsent(active.from(), k -> new Event()).enter.add(round);
            events.computeIfAbsent(active.through().plusSeconds(1), k -> new Event())
                    .leave
                    .add(round);
            for (var decision : round.decisions()) {
                var interval =
                        new Window(
                                        PolicyFormValidation.time(decision.from()),
                                        PolicyFormValidation.time(decision.through()))
                                .intersect(active);
                if (!interval.expired()) {
                    events.computeIfAbsent(interval.from(), k -> new Event());
                    events.computeIfAbsent(interval.through().plusSeconds(1), k -> new Event());
                }
            }
        }
        return events;
    }

    private Track track(
            String platform,
            List<Round> rounds,
            TreeMap<LocalDateTime, Event> events,
            Long goodsId) {
        var segments = new ArrayList<Segment>();
        var plannedSegments = new ArrayList<Segment>();
        var visible = new TreeSet<>(PolicyApplicabilityEvaluator.NEWEST);
        var active = new TreeSet<>(PolicyApplicabilityEvaluator.NEWEST);
        var seen = new LinkedHashSet<String>();
        var adoptedInQuery = new LinkedHashSet<String>();
        for (var item : events.entrySet()) {
            var event = item.getValue();
            visible.removeAll(event.hide);
            visible.addAll(event.show);
            active.removeAll(event.leave);
            active.addAll(event.enter);
            if (visible.isEmpty() && active.isEmpty()) continue;
            var at = item.getKey();
            var next = events.higherKey(at);
            var adopted =
                    PolicyApplicabilityEvaluator.effectivePolicies(
                                    channel.equals("RETAIL"),
                                    active,
                                    goodsId == null ? 0 : goodsId,
                                    at)
                            .stream()
                            .map(p -> p.auditLogId())
                            .toList();
            boolean perProduct = goodsId != null && active.stream().allMatch(Round::legacy);
            // 尚无可采用轮次时，以本段可见原单判断；其他日期的旧单只影响轨道划分。
            boolean configurationDecision =
                    channel.equals("BILLION")
                            && (!perProduct
                                    || (active.isEmpty()
                                            && visible.stream().noneMatch(Round::legacy)));
            var excluded =
                    visible.stream()
                            .filter(r -> !perProduct || !r.legacy() || containsGoods(r, goodsId))
                            .map(r -> r.policy().auditLogId())
                            .filter(id -> !adopted.contains(id))
                            .toList();
            if (adopted.isEmpty() && excluded.isEmpty()) continue;
            if (!at.isAfter(query.through()) && next.isAfter(query.from()))
                adoptedInQuery.addAll(adopted);
            seen.addAll(adopted);
            seen.addAll(excluded);
            // 若当前秒之后仍有原单可参与，后续实际结果只确知到 asOf。
            String end =
                    !at.isAfter(asOf)
                                    && next.isAfter(asOf)
                                    && (visible.stream()
                                                    .anyMatch(
                                                            r ->
                                                                    r.original()
                                                                            .through()
                                                                            .isAfter(asOf))
                                            || active.stream()
                                                    .anyMatch(
                                                            r ->
                                                                    r.window()
                                                                            .through()
                                                                            .isAfter(asOf)))
                            ? null
                            : time(next);
            append(
                    at.isAfter(asOf) ? plannedSegments : segments,
                    new Segment(time(at), end, adopted, excluded, configurationDecision));
        }
        var policies =
                rounds.stream()
                        .filter(
                                r ->
                                        seen.contains(r.policy().auditLogId())
                                                || (r.original().through().isAfter(asOf)
                                                        && !r.original().intersect(query).expired()
                                                        && (goodsId == null
                                                                || !r.legacy()
                                                                || containsGoods(r, goodsId))))
                        .map(
                                r -> {
                                    var p = r.policy();
                                    return new Policy(
                                            p.policyId(),
                                            p.policyCode(),
                                            p.auditLogId(),
                                            p.submissionVersion(),
                                            time(r.original().from()),
                                            time(r.original().through()),
                                            p.approvedAt(),
                                            p.verifyType(),
                                            p.sceneTypes(),
                                            r.estimated(),
                                            !r.original().intersect(query).expired()
                                                    || adoptedInQuery.contains(p.auditLogId()),
                                            p.selections());
                                })
                        .toList();
        return new Track(
                channel,
                platform,
                goodsId == null ? List.of() : List.of(goodsId.toString()),
                policies,
                segments,
                plannedSegments);
    }

    private static boolean containsGoods(Round round, long id) {
        var key = Long.toString(id);
        return round.policy().selections().stream()
                .flatMap(s -> s.products().stream())
                .anyMatch(p -> key.equals(p.goodsId()));
    }

    private static void append(List<Segment> segments, Segment next) {
        if (!segments.isEmpty()) {
            int last = segments.size() - 1;
            var old = segments.get(last);
            if (next.startAt().equals(old.endAt())
                    && old.configurationDecision() == next.configurationDecision()
                    && old.adoptedAuditLogIds().equals(next.adoptedAuditLogIds())
                    && old.excludedAuditLogIds().equals(next.excludedAuditLogIds())) {
                segments.set(
                        last,
                        new Segment(
                                old.startAt(),
                                next.endAt(),
                                old.adoptedAuditLogIds(),
                                old.excludedAuditLogIds(),
                                old.configurationDecision()));
                return;
            }
        }
        segments.add(next);
    }

    // 只有完整的时段、轮次与补贴分配一致才合并。独立商品保留自身标识。
    private record Assignment(
            String cardType, SubsidyPlan subsidy, Integer quantity, List<Product> independent) {}

    private record PolicyKey(String auditId, List<Assignment> allocations) {}

    private record TrackKey(
            String platform,
            List<PolicyKey> policies,
            List<Segment> segments,
            List<Segment> plannedSegments) {}

    private static void collect(Map<TrackKey, List<Track>> groups, Track track) {
        if (track.policies().stream().noneMatch(Policy::matchesQuery)) return;
        var policies =
                track.policies().stream()
                        .map(
                                p ->
                                        new PolicyKey(
                                                p.auditLogId(),
                                                p.selections().stream()
                                                        .map(
                                                                s ->
                                                                        new Assignment(
                                                                                s.cardType(),
                                                                                s.subsidy(),
                                                                                s.quantity(),
                                                                                "SPECIFIED"
                                                                                                .equals(
                                                                                                        s
                                                                                                                .cardType())
                                                                                        ? s
                                                                                                .products()
                                                                                        : List
                                                                                                .of()))
                                                        .toList()))
                        .toList();
        var key =
                new TrackKey(
                        track.platformCode(), policies, track.segments(), track.plannedSegments());
        groups.computeIfAbsent(key, k -> new ArrayList<>()).add(track);
    }

    private static Track merge(List<Track> tracks) {
        var first = tracks.get(0);
        if (tracks.size() == 1) return first;
        var merged = new ArrayList<Policy>();
        for (int i = 0; i < first.policies().size(); i++) {
            var p = first.policies().get(i);
            var selections = new ArrayList<Selection>();
            for (int j = 0; j < p.selections().size(); j++) {
                var s = p.selections().get(j);
                var products = new LinkedHashSet<Product>();
                for (var track : tracks)
                    products.addAll(track.policies().get(i).selections().get(j).products());
                selections.add(
                        new Selection(
                                s.cardType(), List.copyOf(products), s.subsidy(), s.quantity()));
            }
            merged.add(
                    new Policy(
                            p.policyId(),
                            p.policyCode(),
                            p.auditLogId(),
                            p.submissionVersion(),
                            p.policyFrom(),
                            p.policyThrough(),
                            p.approvedAt(),
                            p.verifyType(),
                            p.sceneTypes(),
                            p.estimated(),
                            p.matchesQuery(),
                            selections));
        }
        return new Track(
                first.perspective(),
                first.platformCode(),
                tracks.stream().flatMap(t -> t.goodsIds().stream()).toList(),
                merged,
                first.segments(),
                first.plannedSegments());
    }

    private static String time(LocalDateTime time) {
        return time.format(PolicyFormValidation.TIME);
    }
}
