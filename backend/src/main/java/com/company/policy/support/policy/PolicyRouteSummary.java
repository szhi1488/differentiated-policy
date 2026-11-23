package com.company.policy.support.policy;

import com.company.policy.support.policy.PolicyExecutionChange.Kind;
import com.company.policy.support.policy.PolicyWorkflowProposals.Adjustment;
import com.company.policy.vo.PolicyApplicability.Scope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** 同一申请整轮共用的路由结果。保留全部核对依据，短摘要只列前几项。 */
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

    public static PolicyRouteSummary from(
            List<PolicyExecutionChange> segments, List<Adjustment> adjustments) {
        var changes = merge(segments);
        var triggers =
                changes.stream().filter(PolicyExecutionChange::requiresProductReview).toList();
        int stacks = 0;
        int replacements = 0;
        int removals = 0;
        for (var change : triggers) {
            if (change.after().size() > 1 && !change.added().isEmpty()) stacks++;
            if (change.kind() == Kind.REPLACEMENT) replacements++;
            if (change.before().size() > 1 && !change.removed().isEmpty()) removals++;
        }
        long accompanying = adjustments.stream().filter(Adjustment::accompanying).count();
        boolean needsReview = !triggers.isEmpty() || accompanying > 0;
        var lines = new ArrayList<String>();
        if (!needsReview) {
            lines.add("本单由采购直审。");
            if (adjustments.isEmpty()
                    || changes.stream().anyMatch(c -> c.kind() != Kind.UNCHANGED)) {
                appendSegments(lines, changes);
            }
        } else {
            if (!triggers.isEmpty()) {
                lines.add("有 " + triggers.size() + " 段政策适用范围需产品运营初审。");
                appendSegments(lines, triggers);
            }
            if (accompanying > 0) lines.add("申请人已确认 " + accompanying + " 项随单时间调整，需产品运营初审。");
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

    private static List<PolicyExecutionChange> merge(List<PolicyExecutionChange> segments) {
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
            for (var next : track) {
                if (previous != null
                        && previous.before().equals(next.before())
                        && previous.after().equals(next.after())
                        && previous.candidates().equals(next.candidates())
                        && !next.from().isAfter(previous.through().plusSeconds(1))) {
                    if (next.through().isAfter(previous.through())) {
                        previous =
                                PolicyExecutionChange.compare(
                                        previous.perspective(),
                                        previous.scope(),
                                        previous.from(),
                                        next.through(),
                                        previous.before(),
                                        previous.after(),
                                        previous.candidates());
                    }
                } else {
                    if (previous != null) result.add(previous);
                    previous = next;
                }
            }
            if (previous != null) result.add(previous);
        }
        return List.copyOf(result);
    }
}
