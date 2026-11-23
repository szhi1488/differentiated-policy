package com.company.policy.support.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.vo.PolicyApplicability.Policy;
import com.company.policy.vo.PolicyApplicability.Product;
import com.company.policy.vo.PolicyApplicability.Scope;
import com.company.policy.vo.PolicyApplicability.Selection;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PolicyRouteSummaryTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 12, 1, 0, 0);
    private static final LocalDateTime END = START.plusDays(5).minusSeconds(1);
    private static final Scope JD = new Scope("JD", 8001L, null, null, null);
    private static final Product WHITE =
            new Product("8001", null, "浮光白", "5", "Reno17 Pro", null, "12GB", "256GB");
    private static final SubsidyPlan FIXED =
            new SubsidyPlan("f", "FIXED", "20.10", null, null, null);

    @ParameterizedTest
    @CsvSource({
        "'',B,B,'','',ORDINARY_ADDITION,'',false",
        "'',B,B,'','',ORDINARY_ADDITION,B,true",
        "A,A,'','',A,UNCHANGED,'',false",
        "A,A,'','',A,UNCHANGED,AB,true",
        "A,AB,B,'',A,STACK,AB,true",
        "A,B,B,A,'',REPLACEMENT,AB,true",
        "AB,A,'',B,A,REMOVE_STACK,ABC,true",
        "AC,BC,B,A,C,REPLACEMENT,ABC,true",
        "'',AB,AB,'','',STACK,AB,true",
        "'','','','','',UNCHANGED,'',false",
        "A,'','',A,'',REMOVAL,'',false"
    })
    void comparesTheExecutedSets(
            String before,
            String after,
            String added,
            String removed,
            String retained,
            PolicyExecutionChange.Kind kind,
            String candidates,
            boolean needsReview) {
        var change =
                PolicyExecutionChange.compare(
                        "RETAIL",
                        JD,
                        START,
                        END,
                        policies(before),
                        policies(after),
                        policies(candidates));
        assertThat(change.added())
                .extracting(Policy::policyId)
                .containsExactlyElementsOf(ids(added));
        assertThat(change.removed())
                .extracting(Policy::policyId)
                .containsExactlyElementsOf(ids(removed));
        assertThat(change.retained())
                .extracting(Policy::policyId)
                .containsExactlyElementsOf(ids(retained));
        assertThat(change.kind()).isEqualTo(kind);
        assertThat(change.requiresProductReview()).isEqualTo(needsReview);
    }

    @Test
    void sameIdOnANewRoundReplacesTheEarlierRoundAndRepeatedMembersCountOnce() {
        var first = policy("A", 1, FIXED, null);
        var second = policy("A", 2, FIXED, null);
        var change =
                PolicyExecutionChange.compare(
                        "RETAIL", JD, START, END, List.of(first, first), List.of(second, second));
        assertThat(change.added()).containsExactly(second);
        assertThat(change.removed()).containsExactly(first);
        assertThat(PolicyExecutionChange.sameMembers(List.of(first), List.of(second))).isFalse();
        assertThat(PolicyRouteSummary.from(List.of(change), List.of()).summary())
                .contains("第 2 轮", "替换", "第 1 轮");
    }

    @Test
    void retainsTheHistoricalRatioCapWhenMixingOldAndNewPlans() {
        var old =
                policy(
                        "A",
                        1,
                        new SubsidyPlan("old", "RATIO", null, "120.50", "5.25", "POLICY_PRICE"),
                        0);
        var proposed = policy("B", 1, FIXED, 0);
        var original = List.of(old, proposed);
        assertThat(PolicyExecutionChange.sameMembers(original, List.of(proposed, old, old)))
                .isTrue();
        var change =
                PolicyExecutionChange.compare(
                        "RETAIL", JD, START, END, original, List.of(proposed));
        assertThat(PolicyRouteSummary.from(List.of(change), List.of()).summary())
                .contains("取消与A", "按政策价的 5.25%", "上限 120.5 元", "数量 0 台", "保留B", "20.1 元/台")
                .doesNotContain("合计");
    }

    @Test
    void onlyChangedPlatformsFeatureInTheSummaryAndSubsidiesRemainSeparate() {
        var a = policy("ZC-000096", 1, new SubsidyPlan("f", "FIXED", "20.10", "50", null, null), 0);
        var b =
                policy(
                        "ZC-000128",
                        2,
                        new SubsidyPlan("p", "RATIO", null, null, "3.20", "HAND_PRICE"),
                        null);
        var jd =
                PolicyExecutionChange.compare(
                        "RETAIL", JD, START, END, List.of(a), List.of(b, a), List.of(a, b));
        var tb =
                PolicyExecutionChange.compare(
                        "RETAIL",
                        new Scope("TAOBAO", 8001L, null, null, null),
                        START,
                        END,
                        List.of(a),
                        List.of(a));
        var result = PolicyRouteSummary.from(List.of(tb, jd), List.of());
        assertThat(result.requiresProductReview()).isTrue();
        assertThat(result.triggerSegments()).isOne();
        assertThat(result.changes()).hasSize(2);
        assertThat(result.summary())
                .contains(
                        "京东零售",
                        "Reno17 Pro 12GB+256GB 浮光白",
                        "2026-12-01 00:00:00",
                        "2026-12-05 23:59:59",
                        "保留ZC-000096",
                        "20.1 元/台",
                        "上限 50 元",
                        "数量 0 台",
                        "新增叠加ZC-000128",
                        "按到手价的 3.2%",
                        "数量未填写",
                        "国补+地补",
                        "销售维度")
                .doesNotContain("淘宝", "合计");
    }

    @Test
    void joinsOnlyTouchingSegmentsWithTheSameTermsAndCountsAMixedSegmentOnce() {
        var a = policy("A", 1, FIXED, 0);
        var b = policy("B", 1, FIXED, 0);
        var c = policy("C", 1, FIXED, 0);
        var bOther = policy("B", 1, FIXED, 1);
        var before = List.of(a, c);
        var early =
                PolicyExecutionChange.compare(
                        "RETAIL",
                        JD,
                        START,
                        START.plusHours(1).minusSeconds(1),
                        before,
                        List.of(b, c),
                        List.of(a, b, c));
        var later =
                PolicyExecutionChange.compare(
                        "RETAIL",
                        JD,
                        START.plusHours(1),
                        END,
                        before,
                        List.of(c, b),
                        List.of(a, b, c));
        var different =
                PolicyExecutionChange.compare(
                        "RETAIL",
                        JD,
                        END.plusSeconds(1),
                        END.plusSeconds(2),
                        before,
                        List.of(bOther, c),
                        List.of(a, bOther, c));
        var gap =
                PolicyExecutionChange.compare(
                        "RETAIL",
                        JD,
                        END.plusSeconds(5),
                        END.plusSeconds(6),
                        before,
                        List.of(bOther, c),
                        List.of(a, bOther, c));
        var summary = PolicyRouteSummary.from(List.of(later, early, gap, different), List.of());
        assertThat(summary.triggerSegments()).isEqualTo(3);
        assertThat(summary.stackSegments()).isEqualTo(3);
        assertThat(summary.replaceSegments()).isEqualTo(3);
        assertThat(summary.removeStackSegments()).isEqualTo(3);
        assertThat(summary.changes().get(0).from()).isEqualTo(START);
        assertThat(summary.changes().get(0).through()).isEqualTo(END);
        assertThat(summary.summary()).contains("拟以B", "替换A", "继续与C", "固定补贴合计 40.2 元/台");
    }

    @Test
    void keptPolicyKeepsSeparateOverlapSegmentsWhenTheUnselectedCandidatesChange() {
        var kept = policies("A");
        var changes =
                List.of(
                        PolicyExecutionChange.compare(
                                "RETAIL",
                                JD,
                                START,
                                START.plusSeconds(1),
                                kept,
                                kept,
                                policies("AB")),
                        PolicyExecutionChange.compare(
                                "RETAIL",
                                JD,
                                START.plusSeconds(2),
                                START.plusSeconds(3),
                                kept,
                                kept,
                                policies("BA")),
                        PolicyExecutionChange.compare(
                                "RETAIL",
                                JD,
                                START.plusSeconds(4),
                                END,
                                kept,
                                kept,
                                policies("AC")));
        var summary = PolicyRouteSummary.from(changes, List.of());
        assertThat(summary.triggerSegments()).isEqualTo(2);
        assertThat(summary.stackSegments()).isZero();
        assertThat(summary.replaceSegments()).isZero();
        assertThat(summary.removeStackSegments()).isZero();
        assertThat(summary.summary()).contains("该段有 2 张候选政策", "沿用原单A");
        assertThat(summary.changes().get(0).through()).isEqualTo(START.plusSeconds(3));
        assertThat(summary.changes().get(0).candidates())
                .extracting(Policy::policyId)
                .containsExactly("A", "B");
        assertThat(summary.changes().get(1).candidates())
                .extracting(Policy::policyId)
                .containsExactly("A", "C");
    }

    @Test
    void bindingCapSuppressesArithmeticTotalAndUnlimitedDetailsSurviveThePreviewLimit() {
        var a = policy("A", 1, FIXED, 0);
        var b = policy("B", 1, new SubsidyPlan("c", "FIXED", "30.20", "15", null, null), 0);
        var segments =
                java.util.stream.IntStream.range(0, 4)
                        .mapToObj(
                                i ->
                                        PolicyExecutionChange.compare(
                                                "BILLION",
                                                new Scope("PDD", null, 5L, "12GB", "256GB"),
                                                START.plusDays(i),
                                                START.plusDays(i).plusSeconds(1),
                                                List.of(a),
                                                List.of(b, a)))
                        .toList();
        var result = PolicyRouteSummary.from(segments, List.of());
        assertThat(result.changes()).hasSize(4);
        assertThat(result.summary())
                .contains("整组配置", "申报浮光白", "上限 15 元", "另有 1 段")
                .doesNotContain("合计", "2026-12-04");
    }

    private static Policy policy(String id, int round, SubsidyPlan plan, Integer quantity) {
        return new Policy(
                id,
                id,
                round,
                null,
                false,
                null,
                null,
                null,
                null,
                "SALES",
                List.of("NATIONAL_SUB", "LOCAL_SUB"),
                List.of(new Selection("SPECIFIED", List.of(WHITE), plan, quantity)));
    }

    private static List<Policy> policies(String members) {
        return ids(members).stream().map(id -> policy(id, 1, FIXED, 0)).toList();
    }

    private static List<String> ids(String members) {
        return members.isEmpty() ? List.of() : Arrays.asList(members.split(""));
    }
}
