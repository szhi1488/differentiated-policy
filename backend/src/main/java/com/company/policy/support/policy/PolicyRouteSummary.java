package com.company.policy.support.policy;

import com.company.policy.support.policy.PolicyApplicabilityEvaluator.Round;
import com.company.policy.support.policy.PolicyExecutionChange.Kind;
import com.company.policy.support.policy.PolicyWorkflowProposals.Adjustment;
import com.company.policy.vo.PolicyApplicability.Scope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 同一申请整轮共用的路由结果。保留全部核对依据，短摘要只列前几项。 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties("presetOwners")
public record PolicyRouteSummary(
        boolean requiresProductReview,
        int triggerSegments,
        int stackSegments,
        int replaceSegments,
        int removeStackSegments,
        List<PolicyExecutionChange> changes,
        List<Adjustment> adjustments,
        String summary) {
    private static final int PREVIEW_LIMIT = 3;

    /** 一次提交的所有渠道、平台和确认段共用，完整商品集合只在提交计算期间参与比较。 */
    public static final class Evaluation {
        private final List<PolicyExecutionChange> segments = new ArrayList<>();
        // 同一份快照在不同商品和时段共享同一个不可变集合，只需核对一次。
        private final Set<Set<String>> checked = Collections.newSetFromMap(new IdentityHashMap<>());
        private Set<String> commonGoods;
        private boolean consistent = true;

        void add(PolicyExecutionChange segment, List<Round> selected) {
            segments.add(segment);
            if (!consistent || !segment.stackedSelection()) return;
            for (var round : selected) {
                var goods = round.goodsIds();
                if (!checked.add(goods)) continue;
                if (commonGoods == null) commonGoods = goods;
                if (goods.isEmpty() || !commonGoods.equals(goods)) {
                    consistent = false;
                    return;
                }
            }
        }

        public List<PolicyExecutionChange> changes() {
            if (!consistent || commonGoods == null) return List.copyOf(segments);
            return segments.stream()
                    .map(
                            segment ->
                                    segment.stackedSelection()
                                            ? segment.withMatchingRoundGoods()
                                            : segment)
                    .toList();
        }
    }

    public static PolicyRouteSummary from(
            List<PolicyExecutionChange> segments, List<Adjustment> adjustments) {
        return summarize(merge(segments, Map.of()), adjustments);
    }

    public static PolicyRouteSummary forApplication(
            String id, List<PolicyExecutionChange> segments, List<Adjustment> adjustments) {
        return summarize(
                merge(segments, PolicyReviewContinuations.adjustedCandidates(id, adjustments)),
                adjustments);
    }

    /** 已冻结的在途轮次也可按原单阅读，始终使用本轮的依据。 */
    public PolicyRouteSummary forDisplay(String id) {
        var adjustedCandidates = PolicyReviewContinuations.adjustedCandidates(id, adjustments);
        if (adjustedCandidates.isEmpty() || changes == null) return this;
        var normalized = merge(changes, adjustedCandidates);
        return normalized.equals(changes) ? this : summarize(normalized, adjustments);
    }

    private static PolicyRouteSummary summarize(
            List<PolicyExecutionChange> changes, List<Adjustment> adjustments) {
        var triggers =
                changes.stream().filter(PolicyExecutionChange::requiresProductReview).toList();
        int stacks = 0;
        int replacements = 0;
        int removals = 0;
        for (var change : changes) {
            if (change.kind() == Kind.STACK) stacks++;
            if (change.kind() == Kind.REPLACEMENT) replacements++;
            if (change.before().size() > 1 && !change.removed().isEmpty()) removals++;
        }
        boolean needsReview = !triggers.isEmpty();
        var lines = new ArrayList<String>();
        if (!needsReview) {
            lines.add(
                    changes.stream().anyMatch(PolicyExecutionChange::stackedSelection)
                            ? "参与叠加的政策整单商品不完全一致，本单由采购直审。"
                            : "本单由采购直审。");
            if (adjustments.isEmpty()
                    || changes.stream().anyMatch(c -> c.kind() != Kind.UNCHANGED)) {
                appendSegments(lines, changes);
            }
        } else {
            lines.add("本轮有 " + triggers.size() + " 个叠加时段，所有参与政策整单所选商品完全相同，需产品运营初审。");
            appendSegments(lines, triggers);
        }
        adjustments.stream().limit(PREVIEW_LIMIT).forEach(a -> lines.add(a.summary()));
        if (adjustments.size() > PREVIEW_LIMIT)
            lines.add("另有 " + (adjustments.size() - PREVIEW_LIMIT) + " 项截止调整。");
        return new PolicyRouteSummary(
                needsReview,
                triggers.size(),
                stacks,
                replacements,
                removals,
                changes,
                List.copyOf(adjustments),
                String.join("\n", lines));
    }

    private static void appendSegments(List<String> lines, List<PolicyExecutionChange> segments) {
        segments.stream()
                .limit(PREVIEW_LIMIT)
                .forEach(c -> lines.add(PolicyChangeDescription.describe(c)));
        if (segments.size() > PREVIEW_LIMIT)
            lines.add("另有 " + (segments.size() - PREVIEW_LIMIT) + " 段，详见本单。");
    }

    private record Track(String perspective, Scope scope) {}

    private static List<PolicyExecutionChange> merge(
            List<PolicyExecutionChange> segments, Map<String, Adjustment> adjustedCandidates) {
        var byTrack = new LinkedHashMap<Track, List<PolicyExecutionChange>>();
        for (var segment : segments) {
            byTrack.computeIfAbsent(
                            new Track(segment.perspective(), segment.scope()),
                            k -> new ArrayList<>())
                    .add(segment);
        }
        var result = new ArrayList<PolicyExecutionChange>();
        for (var track : byTrack.values()) {
            track.sort(
                    Comparator.comparing(PolicyExecutionChange::from)
                            .thenComparing(PolicyExecutionChange::through));
            PolicyExecutionChange previous = null;
            PolicyExecutionChange previousRaw = null;
            var continuations =
                    adjustedCandidates.isEmpty()
                            ? null
                            : new PolicyReviewContinuations(adjustedCandidates, track);
            for (var raw : track) {
                var next = continuations == null ? raw : continuations.normalize(raw);
                if (previous != null
                        && (previousRaw.before().equals(raw.before())
                                || (continuations != null
                                        && continuations.sameBaselineAtHandoff(previousRaw, raw)))
                        && previous.after().equals(next.after())
                        && previous.candidates().equals(next.candidates())
                        && previous.matchingRoundGoods() == next.matchingRoundGoods()
                        && !next.from().isAfter(previous.through().plusSeconds(1))) {
                    if (next.through().isAfter(previous.through())) {
                        previous = previous.withThrough(next.through());
                    }
                } else {
                    if (previous != null) result.add(previous);
                    previous = next;
                }
                previousRaw = raw;
            }
            if (previous != null) result.add(previous);
        }
        return List.copyOf(result);
    }
}
