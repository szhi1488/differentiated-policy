package com.company.policy.support.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyDraftRequest.Card;
import com.company.policy.dto.PolicyDraftRequest.GoodsForm;
import com.company.policy.dto.PolicyDraftRequest.PlatformAssignment;
import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.dto.PolicyDraftRequest.Target;
import com.company.policy.entity.PolicyConsent;
import com.company.policy.entity.PolicyConsent.Decision;
import com.company.policy.vo.ApplicantCatalogResponse;
import com.company.policy.vo.PolicyApplicability.Action;
import com.company.policy.vo.PolicyApplicability.AdjustmentOption;
import com.company.policy.vo.PolicyApplicability.Group;
import com.company.policy.vo.PolicyApplicability.Policy;
import com.company.policy.vo.PolicyApplicability.Scope;
import com.company.policy.vo.PolicyApplicability.Segment;
import com.company.policy.vo.PolicyApplicability.Status;
import com.company.policy.vo.PolicyResponse.Snapshot;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PolicyApplicabilityEvaluatorTest {
    private static final LocalDateTime AS_OF = LocalDateTime.parse("2026-10-31T12:00:00");
    private static final List<String> NATIONAL = List.of("NATIONAL_SUB");
    private static final List<String> LOCAL = List.of("LOCAL_SUB");
    private static final List<String> BOTH = List.of("NATIONAL_SUB", "LOCAL_SUB");
    private static final Target BLACK = new Target("81", "12GB", "256GB", "8001");
    private static final Target WHITE = new Target("81", "12GB", "256GB", "8002");
    private static final Target OTHER_CONFIG = new Target("81", "12GB", "512GB", "8003");
    private static final Card PDD = card("COMMON", "PDD", null, BLACK);

    @Test
    void isolatesProductsAndPlatformsAndIncludesBothEndpoints() {
        var trial =
                new PolicyApplicabilityEvaluator(
                        policy(
                                "106",
                                10,
                                15,
                                "SALES",
                                NATIONAL,
                                card("COMMON", "PDD", null, BLACK, WHITE)),
                        AS_OF);
        var whiteOnly =
                new PolicyApplicabilityEvaluator(
                        policy(
                                "102",
                                10,
                                15,
                                "SALES",
                                NATIONAL,
                                card("COMMON", "PDD", null, WHITE)),
                        AS_OF);
        add(whiteOnly, policy("103", 1, 20, "SALES", NATIONAL, PDD));
        assertThat(whiteOnly.result().canDirectSubmit()).isTrue();
        add(trial, policy("101", 1, 9, "SALES", NATIONAL, PDD));
        add(trial, policy("103", 1, 20, "SALES", NATIONAL, card("COMMON", "JD", null, BLACK)));
        add(
                trial,
                policy("104", 1, 20, "SALES", NATIONAL, card("COMMON", "PDD", null, OTHER_CONFIG)));
        assertThat(trial.result().canDirectSubmit()).isTrue();
        add(trial, policy("105", 1, 10, "SALES", NATIONAL, PDD));
        var scope = trial.result().scopes().get(0);
        assertThat(trial.result().scopes()).hasSize(1);
        assertThat(scope.scope().goodsId()).isEqualTo(8001L);
        assertThat(trial.result().canDirectSubmit()).isFalse();
        var segment = scope.segments().get(0);
        assertThat(segment.from()).isEqualTo(date(10, "00:00:00"));
        assertThat(segment.through()).isEqualTo(date(10, "23:59:59"));
        assertThat(segment.action()).isEqualTo(Action.SELECT_RETAIL_POLICIES);
        assertThat(segment.eligiblePolicyIds()).containsExactly("106", "105");
        assertThat(segment.groups().get(0).policyIds()).containsExactly("106", "105");
    }

    @Test
    void fullSceneSetsAreIndependentAcrossVerificationTypes() {
        var trial =
                new PolicyApplicabilityEvaluator(
                        policy("112", 10, 15, "ACTIVATION", BOTH, PDD), AS_OF);
        add(trial, policy("111", 1, 20, "SALES", BOTH, PDD));
        var scope = trial.result().scopes().get(0);
        assertThat(scope.policies()).hasSize(2);
        var segment = scope.segments().get(0);
        assertThat(segment.action()).isEqualTo(Action.SELECT_RETAIL_POLICIES);
        assertThat(segment.groups()).hasSize(2).extracting(Group::sceneTypes).containsOnly(BOTH);
        assertThat(segment.eligiblePolicyIds()).containsExactly("112", "111");
        assertThat(scope.policies().get(1).selections().get(0).subsidy().fixedAmount())
                .isEqualTo("100.00");
    }

    @Test
    void partialSceneOverlapCreatesSeparateGroups() {
        var trial =
                new PolicyApplicabilityEvaluator(
                        policy("112", 10, 15, "SALES", NATIONAL, PDD), AS_OF);
        add(
                trial,
                policy(
                        "111",
                        1,
                        20,
                        "SALES",
                        List.of("NATIONAL_SUB", "LOCAL_SUB", "SELF_SUB"),
                        PDD));
        var segment = trial.result().scopes().get(0).segments().get(0);
        assertThat(segment.groups())
                .extracting(Group::policyIds)
                .containsExactly(List.of("112"), List.of("111"));
        assertThat(segment.eligiblePolicyIds()).containsExactly("112", "111");
        assertThat(segment.action()).isEqualTo(Action.SELECT_RETAIL_POLICIES);
    }

    @Test
    void splitsThreePoliciesIntoTheirActualIntersectionsOnly() {
        var trial =
                new PolicyApplicabilityEvaluator(
                        policy("113", 1, 25, "SALES", NATIONAL, PDD), AS_OF);
        add(trial, policy("114", 3, 8, "SALES", LOCAL, PDD));
        add(trial, policy("115", 15, 20, "SALES", List.of("SELF_SUB"), PDD));
        var segments = trial.result().scopes().get(0).segments();
        assertThat(segments)
                .extracting(Segment::from)
                .containsExactly(date(3, "00:00:00"), date(15, "00:00:00"));
        assertThat(segments)
                .extracting(Segment::through)
                .containsExactly(date(8, "23:59:59"), date(20, "23:59:59"));
        assertThat(segments)
                .extracting(Segment::eligiblePolicyIds)
                .containsExactly(List.of("113", "114"), List.of("113", "115"));
    }

    @Test
    void offersEachMatchingRoundAndLimitsSelectionsToOnePerGroup() {
        var trial =
                new PolicyApplicabilityEvaluator(policy("117", 1, 25, "SALES", LOCAL, PDD), AS_OF);
        var old = policy("116", 1, 20, "SALES", NATIONAL, PDD);
        var replacement = policy("118", 10, 15, "SALES", NATIONAL, PDD);
        // 同秒提交时，日志 ID 较大的 118 优先，116 更晚审核不改变排序。
        trial.include(old, 20, AS_OF.minusDays(5), AS_OF.minusHours(1));
        trial.include(replacement, 21, AS_OF.minusDays(5), AS_OF.minusDays(1));
        var segments = trial.result().scopes().get(0).segments();
        assertThat(segments)
                .extracting(Segment::eligiblePolicyIds)
                .containsExactly(
                        List.of("117", "116"), List.of("117", "118", "116"), List.of("117", "116"));
        assertThat(segments.get(1).action()).isEqualTo(Action.SELECT_RETAIL_POLICIES);
        var national =
                segments.get(1).groups().stream()
                        .filter(g -> g.sceneTypes().equals(NATIONAL))
                        .findFirst()
                        .orElseThrow();
        assertThat(national.policyIds()).containsExactly("118", "116");
        assertThat(segments.get(2).from()).isEqualTo(date(16, "00:00:00"));
    }

    @Test
    void mixedCommonAndSpecifiedBillionColorsShareOneConfigurationDecision() {
        var draft = policy("207", 10, 15, null, null, card("SPECIFIED", "PDD", 0, WHITE));
        var trial = new PolicyApplicabilityEvaluator(draft, AS_OF);
        add(trial, policy("206", 1, 20, null, null, PDD, card("SPECIFIED", "PDD", 3, WHITE)));
        add(trial, policy("210", 1, 20, null, null, card("COMMON", "PDD", null, OTHER_CONFIG)));
        var match = trial.result().scopes().get(0);
        assertThat(match.scope().goodsId()).isNull();
        assertThat(match.scope().spuId()).isEqualTo(81L);
        assertThat(match.segments())
                .singleElement()
                .satisfies(
                        segment -> {
                            assertThat(segment.action()).isEqualTo(Action.STACK_OR_CHOOSE);
                            assertThat(segment.eligiblePolicyIds()).containsExactly("207", "206");
                        });
        var rows = match.policies().get(1).selections();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).products().get(0).colorName()).isEqualTo("黑色");
        assertThat(rows.get(1).cardType()).isEqualTo("SPECIFIED");
        assertThat(rows.get(1).products().get(0).colorName()).isEqualTo("白色");
        assertThat(rows.get(1).quantity()).isEqualTo(3);
        assertThat(match.policies().get(0).selections().get(0).quantity()).isZero();
    }

    @Test
    void approvalAtTheLastSecondHasOneSecondLeftAndLaterHasNone() {
        var draft = policy("203", 1, 10, "SALES", NATIONAL, PDD);
        var lastSecond = LocalDateTime.parse("2026-11-10T23:59:59");
        var trial = new PolicyApplicabilityEvaluator(draft, lastSecond);
        add(trial, policy("201", 1, 10, "SALES", NATIONAL, PDD));
        var segment = trial.result().scopes().get(0).segments().get(0);
        assertThat(segment.from()).isEqualTo(segment.through()).isEqualTo(date(10, "23:59:59"));
        var expired = new PolicyApplicabilityEvaluator(draft, lastSecond.plusSeconds(1)).result();
        assertThat(expired.status()).isEqualTo(Status.NO_EFFECTIVE_PERIOD);
        assertThat(expired.canDirectSubmit()).isFalse();
        assertThat(expired.scopes()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"00:00:00", "14:26:53"})
    void submittedCutoffIsInclusiveAndDoesNotExtendPastTheNextSecond(String endClock) {
        var start = "2026-12-07T00:00:00+08:00";
        var end = "2026-12-08T" + endClock + "+08:00";
        var through = PolicyFormValidation.time(end);
        var approved = LocalDateTime.parse("2026-11-24T19:43:08");
        var snapshot =
                policy("13", start, end, "SALES", BOTH, card("SPECIFIED", "KUAISHOU", null, BLACK));
        var scope = new Scope("KUAISHOU", 8001L, null, null, null);
        var before = new PolicyApplicabilityEvaluator("RETAIL", scope, through);
        before.include(snapshot, 13L, approved.minusHours(1), approved);
        assertThat(before.effectivePolicies(scope))
                .extracting(Policy::policyId)
                .containsExactly("13");
        var after = new PolicyApplicabilityEvaluator("RETAIL", scope, through.plusSeconds(1));
        after.include(snapshot, 13L, approved.minusHours(1), approved);
        assertThat(after.effectivePolicies(scope)).isEmpty();

        var next =
                policy(
                        "14",
                        through.plusSeconds(1).format(PolicyFormValidation.TIME),
                        "2026-12-09T00:00:00+08:00",
                        "SALES",
                        BOTH,
                        card("SPECIFIED", "KUAISHOU", null, BLACK));
        var preview = new PolicyApplicabilityEvaluator(next, approved);
        preview.include(snapshot, 13L, approved.minusHours(1), approved);
        assertThat(preview.result().canDirectSubmit()).isTrue();

        for (var asOf : List.of(approved, through.plusDays(1))) {
            var timeline =
                    new PolicyTimelineBuilder(
                            "RETAIL",
                            List.of("KUAISHOU"),
                            81,
                            "12GB",
                            "256GB",
                            List.of(8001L),
                            PolicyFormValidation.time(start),
                            through.plusDays(1),
                            asOf);
            timeline.include(snapshot, 13L, approved.minusHours(1), approved);
            var track = timeline.tracks().get(0);
            assertThat(track.policies().get(0).policyFrom()).isEqualTo(start);
            assertThat(track.policies().get(0).policyThrough()).isEqualTo(end);
            var segments = asOf.isBefore(through) ? track.plannedSegments() : track.segments();
            assertThat(segments)
                    .singleElement()
                    .satisfies(
                            segment -> {
                                assertThat(segment.startAt()).isEqualTo(start);
                                assertThat(PolicyFormValidation.time(segment.endAt()))
                                        .isEqualTo(through.plusSeconds(1));
                                assertThat(segment.adoptedAuditLogIds()).containsExactly("13");
                            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETAIL", "BILLION"})
    void olderDecisionStopsExcludingRemainingPolicyAfterChosenPolicyExpires(String channel) {
        boolean retail = channel.equals("RETAIL");
        var scope =
                retail
                        ? new Scope("PDD", 8001L, null, null, null)
                        : new Scope("PDD", null, 81L, "12GB", "256GB");
        var verification = retail ? "SALES" : null;
        var scenes = retail ? NATIONAL : null;
        var cutoff = date(10, "00:00:00");
        var chosen = policy("301", date(1, "00:00:00"), cutoff, verification, scenes, PDD);
        var oldDecision =
                new Decision(
                        scope,
                        date(1, "00:00:00"),
                        date(10, "23:59:59"),
                        retail ? Action.SELECT_RETAIL_POLICIES : Action.STACK_OR_CHOOSE,
                        List.of(),
                        List.of("301", "302"),
                        List.of("301"));
        var remaining =
                policy("302", 1, 12, verification, scenes, PDD)
                        .withConsent(new PolicyConsent(2, List.of(), List.of(oldDecision)));
        var atCutoff =
                new PolicyApplicabilityEvaluator(channel, scope, PolicyFormValidation.time(cutoff));
        var nextSecond =
                new PolicyApplicabilityEvaluator(
                        channel, scope, PolicyFormValidation.time(cutoff).plusSeconds(1));
        for (var evaluation : List.of(atCutoff, nextSecond)) {
            add(evaluation, chosen);
            add(evaluation, remaining);
        }
        assertThat(atCutoff.effectivePolicies(scope))
                .extracting(Policy::policyId)
                .containsExactly("301");
        assertThat(nextSecond.effectivePolicies(scope))
                .extracting(Policy::policyId)
                .containsExactly("302");
    }

    @Test
    void approvalChecksOnlyRemainingOutcomesAndUsesTheActualSubmissionOrder() {
        var draft = policy("112", 1, 25, "SALES", LOCAL, PDD);
        var previous = policy("111", 1, 20, "SALES", NATIONAL, PDD);
        var submitted = new PolicyApplicabilityEvaluator(draft, AS_OF);
        add(submitted, previous);
        var preview = submitted.result();
        var scope = preview.scopes().get(0);
        var choice =
                new PolicyDraftRequest.Choice(
                        scope.scope(),
                        scope.segments().get(0).through(),
                        "SELECT",
                        null,
                        List.of("111"));
        var saved = PolicyConfirmation.confirm(preview, List.of(choice));
        var review = new PolicyApplicabilityEvaluator(draft, AS_OF.plusDays(5), AS_OF, 112);
        add(review, previous);
        assertThat(PolicyConfirmation.requiresResubmission(saved, review.result())).isFalse();
        // 新获批轮次在 8~15 日给同一组增加了可选政策，也需要申请人重新确认。
        review.include(
                policy("109", 8, 15, "SALES", NATIONAL, PDD),
                109,
                AS_OF.minusDays(5),
                AS_OF.plusDays(4));
        assertThat(PolicyConfirmation.requiresResubmission(saved, review.result())).isTrue();
        review.include(policy("113", 8, 15, "SALES", LOCAL, PDD), 113, AS_OF, AS_OF.plusDays(4));
        assertThat(PolicyConfirmation.requiresResubmission(saved, review.result())).isTrue();
        var expired = new PolicyApplicabilityEvaluator(draft, AS_OF.plusMonths(1), AS_OF, 112);
        assertThat(PolicyConfirmation.requiresResubmission(saved, expired.result())).isTrue();
    }

    @Test
    void selectsASubsetWithoutTheDraftAndRejectsInvalidSets() {
        var draft = policy("7", 23, 25, "SALES", BOTH, PDD);
        var olderCombined = policy("5", 23, 25, "SALES", List.of("LOCAL_SUB", "NATIONAL_SUB"), PDD);
        var national = policy("6", 23, 24, "SALES", NATIONAL, PDD);
        var local = policy("3", 23, 25, "SALES", LOCAL, PDD);
        var trial = new PolicyApplicabilityEvaluator(draft, AS_OF);
        for (var old : List.of(olderCombined, national, local)) add(trial, old);
        var preview = trial.result();
        var scope = preview.scopes().get(0).scope();
        var ranges = preview.scopes().get(0).segments();
        assertThat(ranges.get(0).groups()).extracting(Group::policyIds).contains(List.of("7", "5"));
        var second =
                new PolicyDraftRequest.Choice(
                        scope, ranges.get(1).through(), "SELECT", null, List.of("7"));
        for (var invalid :
                List.of(List.<String>of(), List.of("7", "5"), List.of("6", "6"), List.of("999"))) {
            var first =
                    new PolicyDraftRequest.Choice(
                            scope, ranges.get(0).through(), "SELECT", null, invalid);
            assertThatThrownBy(() -> PolicyConfirmation.confirm(preview, List.of(first, second)))
                    .isInstanceOf(ApiException.class);
        }
        var first =
                new PolicyDraftRequest.Choice(
                        scope, ranges.get(0).through(), "SELECT", null, List.of("6", "3"));
        var saved = draft.withConsent(PolicyConfirmation.confirm(preview, List.of(first, second)));
        var timeline =
                new PolicyTimelineBuilder(
                        "RETAIL",
                        List.of("PDD"),
                        81,
                        "12GB",
                        "256GB",
                        List.of(8001L),
                        PolicyFormValidation.time(date(24, "00:00:00")),
                        PolicyFormValidation.time(date(24, "23:59:59")),
                        PolicyFormValidation.time(date(26, "12:00:00")));
        for (var old : List.of(olderCombined, national, local))
            timeline.include(old, Long.parseLong(old.id()), AS_OF.minusDays(3), AS_OF.minusDays(2));
        timeline.include(saved, 7, AS_OF, AS_OF.plusDays(1));
        var segments = timeline.tracks().get(0).segments();
        assertThat(segments.get(0).adoptedAuditLogIds()).containsExactly("6", "3");
        assertThat(segments.get(0).excludedAuditLogIds()).contains("7");
        assertThat(segments.get(1).adoptedAuditLogIds()).containsExactly("7");
        assertThat(segments.get(1).startAt()).isEqualTo(date(25, "00:00:00"));
        for (var day : List.of(24, 25)) {
            var query =
                    new PolicyApplicabilityEvaluator(
                            "RETAIL", scope, LocalDateTime.of(2026, 11, day, 23, 59, 59));
            for (var old : List.of(olderCombined, national, local)) add(query, old);
            query.include(saved, 7, AS_OF, AS_OF.plusDays(1));
            assertThat(query.effectivePolicies(scope).stream().map(p -> p.policyId()))
                    .containsExactlyInAnyOrderElementsOf(
                            day == 24 ? List.of("6", "3") : List.of("7"));
        }
    }

    @Test
    void timelineKeepsExcludedBillionRoundAndReplaysEveryChangeLikePointQueries() {
        var scope =
                new com.company.policy.vo.PolicyApplicability.Scope(
                        "PDD", null, 81L, "12GB", "256GB");
        var keepOld =
                new Decision(
                        scope,
                        date(10, "00:00:00"),
                        date(15, "23:59:59"),
                        Action.STACK_OR_CHOOSE,
                        List.of(),
                        List.of("201", "202"),
                        List.of("201"));
        var stack =
                new Decision(
                        scope,
                        date(16, "00:00:00"),
                        date(17, "23:59:59"),
                        Action.STACK_OR_CHOOSE,
                        List.of(),
                        List.of("201", "202"),
                        List.of("201", "202"));
        var base = policy("201", 1, 20, null, List.of(), PDD);
        var other =
                policy("202", 10, 17, null, List.of(), card("SPECIFIED", "PDD", 0, WHITE))
                        .withConsent(new PolicyConsent(2, List.of(), List.of(keepOld, stack)));
        var clock = LocalDateTime.parse("2026-11-23T12:00:00");
        var day = LocalDateTime.parse("2026-11-12T00:00:00");
        var timeline =
                new PolicyTimelineBuilder(
                        "BILLION",
                        List.of("PDD"),
                        81,
                        "12GB",
                        "256GB",
                        List.of(8001L, 8002L),
                        day,
                        day.plusHours(23),
                        clock);
        timeline.include(base, 81, AS_OF.minusDays(2), AS_OF);
        timeline.include(other, 82, AS_OF.minusDays(1), AS_OF);
        var track = timeline.tracks().get(0);
        assertThat(track.goodsIds()).isEmpty();
        assertThat(track.policies()).hasSize(2);
        assertThat(track.policies().get(1).selections().get(0).quantity()).isZero();
        assertThat(track.policies().get(1).policyThrough()).isEqualTo(date(17, "23:59:59"));
        assertThat(track.segments()).hasSize(4);
        assertThat(track.segments().get(1).excludedAuditLogIds()).containsExactly("82");
        assertThat(track.segments().get(1).endAt()).isEqualTo(date(16, "00:00:00"));
        assertThat(track.segments().get(2).adoptedAuditLogIds()).containsExactly("82", "81");
        var future =
                new PolicyTimelineBuilder(
                        "BILLION",
                        List.of("PDD"),
                        81,
                        "12GB",
                        "256GB",
                        List.of(8001L, 8002L),
                        day,
                        day.plusHours(23),
                        AS_OF);
        future.include(base, 81, AS_OF.minusDays(2), AS_OF);
        future.include(other, 82, AS_OF.minusDays(1), AS_OF);
        var projected = future.tracks().get(0);
        assertThat(projected.segments()).isEmpty();
        assertThat(projected.plannedSegments()).isEqualTo(track.segments());
        for (var part : track.segments()) {
            for (var at :
                    List.of(
                            PolicyFormValidation.time(part.startAt()),
                            PolicyFormValidation.time(part.endAt()).minusSeconds(1))) {
                var point = new PolicyApplicabilityEvaluator("BILLION", scope, at);
                point.include(base, 81, AS_OF.minusDays(2), AS_OF);
                point.include(other, 82, AS_OF.minusDays(1), AS_OF);
                assertThat(point.effectivePolicies(scope))
                        .extracting(p -> p.auditLogId())
                        .containsExactlyElementsOf(part.adoptedAuditLogIds());
            }
        }
        var onlyOld =
                other.withConsent(
                        new PolicyConsent(
                                2,
                                List.of(),
                                List.of(
                                        new Decision(
                                                scope,
                                                date(10, "00:00:00"),
                                                date(17, "23:59:59"),
                                                Action.STACK_OR_CHOOSE,
                                                List.of(),
                                                List.of("201", "202"),
                                                List.of("201")))));
        var single =
                new PolicyTimelineBuilder(
                        "BILLION",
                        List.of("PDD"),
                        81,
                        "12GB",
                        "256GB",
                        List.of(8001L, 8002L),
                        day,
                        day,
                        clock);
        single.include(base, 81, AS_OF.minusDays(2), AS_OF);
        single.include(onlyOld, 82, AS_OF.minusDays(1), AS_OF);
        assertThat(single.tracks().get(0).segments())
                .noneMatch(s -> s.adoptedAuditLogIds().contains("82"));
        assertThat(single.tracks().get(0).policies()).hasSize(2);
    }

    @Test
    void verifiedGroupKeepsSubmittedCountAfterSnapshotRoundTripAndRetailSplitting() {
        var codec = new PolicySnapshotCodec();
        var clock = LocalDateTime.parse("2026-11-12T12:00:00");
        for (var channel : List.of("RETAIL", "BILLION")) {
            boolean retail = channel.equals("RETAIL");
            var original =
                    policy(
                            "10",
                            1,
                            25,
                            retail ? "SALES" : null,
                            retail ? NATIONAL : null,
                            card("COMMON", "PDD", null, BLACK, WHITE));
            var verified =
                    new Snapshot(
                                    original.id(),
                                    original.policyCode(),
                                    original.applicantId(),
                                    original.applicantName(),
                                    original.submissionVersion(),
                                    original.form(),
                                    original.goods(),
                                    null,
                                    null,
                                    true)
                            .withConsent(original.consent());
            var frozen = codec.read(codec.write(verified));
            // 零售此处仅计算黑色轨道，仍应拿到原卡两件的整组基数。
            var builder =
                    new PolicyTimelineBuilder(
                            channel,
                            List.of("PDD"),
                            81,
                            "12GB",
                            "256GB",
                            List.of(8001L),
                            clock.toLocalDate().atStartOfDay(),
                            clock,
                            clock);
            builder.include(frozen, 10, AS_OF, AS_OF);
            var selection = builder.tracks().get(0).policies().get(0).selections().get(0);
            assertThat(selection.wholeConfigurationGoodsCount()).isEqualTo(2);
            assertThat(selection.products()).hasSize(retail ? 1 : 2);
        }
    }

    @Test
    void retailTimelineMergesIdenticalColorsButSplitsDifferentSelectionsAndStopsAtAsOf() {
        var shared =
                policy("10", 1, 25, "SALES", NATIONAL, card("COMMON", "PDD", null, BLACK, WHITE));
        var clock = LocalDateTime.parse("2026-11-12T12:00:00");
        var timeline =
                new PolicyTimelineBuilder(
                        "RETAIL",
                        List.of("PDD"),
                        81,
                        "12GB",
                        "256GB",
                        List.of(8001L, 8002L),
                        clock.toLocalDate().atStartOfDay(),
                        clock,
                        clock);
        timeline.include(shared, 10, AS_OF, AS_OF);
        var together = timeline.tracks().get(0);
        assertThat(together.goodsIds()).containsExactly("8001", "8002");
        assertThat(together.policies().get(0).selections().get(0).products()).hasSize(2);
        assertThat(together.policies().get(0).selections().get(0).wholeConfigurationGoodsCount())
                .isNull();
        assertThat(together.segments().get(0).endAt()).isNull();
        var late = selectRetail(policy("11", 10, 18, "SALES", NATIONAL, PDD), shared);
        var approved = LocalDateTime.parse("2026-11-11T10:30:00");
        timeline.include(late, 11, AS_OF.plusDays(1), approved);
        var tracks = timeline.tracks();
        assertThat(tracks).hasSize(2);
        assertThat(tracks.get(0).goodsIds()).containsExactly("8001");
        var periods = tracks.get(0).segments();
        assertThat(periods.get(1).excludedAuditLogIds()).containsExactly("11");
        assertThat(periods.get(1).endAt()).isEqualTo(date(11, "10:30:00"));
        assertThat(periods.get(2).adoptedAuditLogIds()).containsExactly("11");
        var scope =
                new com.company.policy.vo.PolicyApplicability.Scope("PDD", 8001L, null, null, null);
        for (var part : periods) {
            var point =
                    new PolicyApplicabilityEvaluator(
                            "RETAIL", scope, PolicyFormValidation.time(part.startAt()));
            point.include(shared, 10, AS_OF, AS_OF);
            point.include(late, 11, AS_OF.plusDays(1), approved);
            assertThat(point.effectivePolicies(scope))
                    .extracting(p -> p.auditLogId())
                    .containsExactlyElementsOf(part.adoptedAuditLogIds());
        }
    }

    @Test
    void timelineReturnsApprovedContextOutsideTheFilter() {
        var query = PolicyFormValidation.time(date(3, "10:00:00"));
        var clock = PolicyFormValidation.time(date(23, "12:00:00"));
        var track =
                new PolicyTimelineBuilder(
                        "RETAIL",
                        List.of("PDD"),
                        81,
                        "12GB",
                        "256GB",
                        List.of(8001L),
                        query,
                        query,
                        clock);
        var base = policy("1", 1, 20, "SALES", NATIONAL, PDD);
        var late = selectRetail(policy("2", 10, 12, "SALES", NATIONAL, PDD), base);
        track.include(base, 1, AS_OF, AS_OF);
        track.include(late, 2, AS_OF.plusHours(1), AS_OF.plusHours(1));
        var result = track.tracks().get(0);
        assertThat(result.policies())
                .extracting(p -> p.matchesQuery())
                .containsExactly(true, false);
        assertThat(result.segments().get(1).adoptedAuditLogIds()).containsExactly("2");
        assertThat(result.segments().get(2).adoptedAuditLogIds()).containsExactly("1");
    }

    @Test
    void confirmationAndWorkflowSplitAtDifferentApprovedRetailChoices() {
        var scope = new Scope("PDD", 8001L, null, null, null);
        var oldDecisions =
                List.of(
                        selection(scope, 10, 10, "302"),
                        selection(scope, 11, 12, "301"),
                        selection(scope, 13, 14, "302"));
        var trial =
                new PolicyApplicabilityEvaluator(
                        policy(
                                "303",
                                10,
                                14,
                                "SALES",
                                BOTH,
                                card("COMMON", "PDD", null, BLACK, WHITE)),
                        AS_OF);
        add(trial, policy("301", 10, 14, "SALES", NATIONAL, PDD));
        add(
                trial,
                policy("302", 10, 14, "SALES", NATIONAL, PDD)
                        .withConsent(new PolicyConsent(2, List.of(), oldDecisions)));

        var preview = trial.result();
        var ranges = PolicyConfirmation.ranges(preview);
        assertThat(ranges).hasSize(3);
        var confirmed =
                PolicyConfirmation.confirm(
                        preview,
                        ranges.stream()
                                .map(
                                        range ->
                                                new PolicyDraftRequest.Choice(
                                                        scope,
                                                        range.through(),
                                                        "SELECT",
                                                        null,
                                                        List.of("302")))
                                .toList());
        var changes = trial.workflowSegments(confirmed, Map.of());
        assertThat(changes).noneMatch(PolicyExecutionChange::requiresProductReview);
        assertThat(changes)
                .filteredOn(c -> !c.candidates().isEmpty())
                .hasSize(3)
                .allSatisfy(
                        c ->
                                assertThat(c.candidates())
                                        .extracting(Policy::policyId)
                                        .containsExactly("301", "302", "303"));
        assertThat(changes)
                .filteredOn(c -> c.kind() == PolicyExecutionChange.Kind.REPLACEMENT)
                .singleElement()
                .satisfies(
                        change -> {
                            assertThat(change.scope().goodsId()).isEqualTo(8001L);
                            assertThat(change.from()).isEqualTo("2026-11-11T00:00:00");
                            assertThat(change.through()).isEqualTo("2026-11-12T23:59:59");
                            assertThat(change.removed())
                                    .extracting(Policy::policyId)
                                    .containsExactly("301");
                            assertThat(change.added())
                                    .extracting(Policy::policyId)
                                    .containsExactly("302");
                        });
        assertThat(changes)
                .filteredOn(c -> c.scope().goodsId().equals(8002L))
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.kind())
                                    .isEqualTo(PolicyExecutionChange.Kind.ORDINARY_ADDITION);
                            assertThat(c.requiresProductReview()).isFalse();
                        });
    }

    private static Decision selection(Scope scope, int from, int end, String selected) {
        return new Decision(
                scope,
                date(from, "00:00:00"),
                date(end, "23:59:59"),
                Action.SELECT_RETAIL_POLICIES,
                List.of(),
                List.of("301", "302"),
                List.of(selected));
    }

    @Test
    void billionExecutionStacksAcrossColorsButDistinctGoodsIdsSkipProductReview() {
        var scope = new Scope("PDD", null, 81L, "12GB", "256GB");
        var oldDecision =
                new Decision(
                        scope,
                        date(1, "00:00:00"),
                        date(20, "23:59:59"),
                        Action.STACK_OR_CHOOSE,
                        List.of(),
                        List.of("401", "402"),
                        List.of("401"));
        var trial =
                new PolicyApplicabilityEvaluator(
                        policy("403", 10, 15, null, null, card("SPECIFIED", "PDD", 0, WHITE)),
                        AS_OF);
        add(trial, policy("401", 1, 20, null, null, PDD));
        add(
                trial,
                policy("402", 1, 20, null, null, PDD)
                        .withConsent(new PolicyConsent(2, List.of(), List.of(oldDecision))));
        var preview = trial.result();
        var confirmed =
                PolicyConfirmation.confirm(
                        preview,
                        List.of(
                                new PolicyDraftRequest.Choice(
                                        scope, date(15, "23:59:59"), "STACK", null, null)));
        assertThat(trial.workflowSegments(confirmed, Map.of()))
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.scope()).isEqualTo(scope);
                            assertThat(c.before())
                                    .extracting(Policy::policyId)
                                    .containsExactly("401");
                            assertThat(c.after())
                                    .extracting(Policy::policyId)
                                    .containsExactly("401", "403");
                            assertThat(c.candidates())
                                    .extracting(Policy::policyId)
                                    .containsExactly("401", "403");
                            assertThat(c.added().get(0).selections().get(0).quantity()).isZero();
                            assertThat(c.requiresProductReview()).isFalse();
                        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wholePolicyGoods")
    void comparesAllSelectedGoodsIdsAcrossBothWholePolicies(
            String scenario, List<Card> previous, List<Card> proposed, boolean needsReview) {
        var earlier = policy("401", 1, 20, null, null, previous.toArray(Card[]::new));
        var draft = policy("402", 10, 15, null, null, proposed.toArray(Card[]::new));
        PolicyFormValidation.validate(earlier.form(), true);
        PolicyFormValidation.validate(draft.form(), true);
        var trial = new PolicyApplicabilityEvaluator(draft, AS_OF);
        add(trial, earlier);
        var preview = trial.result();
        var choices =
                PolicyConfirmation.ranges(preview).stream()
                        .map(
                                range ->
                                        new PolicyDraftRequest.Choice(
                                                range.scope(),
                                                range.through(),
                                                "STACK",
                                                null,
                                                null))
                        .toList();
        assertThat(choices).isNotEmpty();
        var changes =
                trial.workflowSegments(PolicyConfirmation.confirm(preview, choices), Map.of());
        assertThat(changes)
                .filteredOn(change -> change.after().size() == 2)
                .isNotEmpty()
                .allSatisfy(
                        change ->
                                assertThat(change.requiresProductReview()).isEqualTo(needsReview));
        assertThat(PolicyRouteSummary.from(changes, List.of()).requiresProductReview())
                .isEqualTo(needsReview);
    }

    private static Stream<Arguments> wholePolicyGoods() {
        var sameColorAndConfiguration = new Target("81", "12GB", "256GB", "8010");
        var black = card("SPECIFIED", "PDD", 3, BLACK);
        var other = card("COMMON", "PDD", null, OTHER_CONFIG);
        var both = List.of(black, other);
        var grouped = List.of(card("COMMON", "PDD", null, BLACK, OTHER_CONFIG));
        return Stream.of(
                Arguments.of("同一批商品在普通卡和指定卡间调整，顺序及数量变化", grouped, List.of(other, black), true),
                Arguments.of(
                        "同一商品在另一平台也选中，整单按 ID 去重",
                        grouped,
                        List.of(
                                card("COMMON", "PDD", null, OTHER_CONFIG, BLACK),
                                card("SPECIFIED", "TAOBAO", 1, BLACK)),
                        true),
                Arguments.of("旧单只选其子集", List.of(black), both, false),
                Arguments.of("新单只选其子集", both, List.of(black), false),
                Arguments.of(
                        "同机型配置和颜色，商品 ID 不同",
                        both,
                        List.of(card("SPECIFIED", "PDD", 1, sameColorAndConfiguration), other),
                        false),
                Arguments.of(
                        "同机型配置，不同颜色",
                        both,
                        List.of(card("SPECIFIED", "PDD", 1, WHITE), other),
                        false),
                Arguments.of(
                        "同配置另加一个颜色商品",
                        both,
                        List.of(card("COMMON", "PDD", null, OTHER_CONFIG, WHITE, BLACK)),
                        false),
                Arguments.of(
                        "旧单其他平台多选同配置商品",
                        List.of(black, card("SPECIFIED", "TAOBAO", 1, WHITE)),
                        List.of(black),
                        false),
                Arguments.of(
                        "新单其他平台多选同配置商品",
                        List.of(black),
                        List.of(black, card("SPECIFIED", "TAOBAO", 1, WHITE)),
                        false));
    }

    @Test
    void kuaishouRoundsWithSixThreeAndFourSelectedGoodsRemainWithProcurement() {
        var black512 = new Target("81", "12GB", "512GB", "8101");
        var white512 = new Target("81", "12GB", "512GB", "8102");
        var blue512 = new Target("81", "12GB", "512GB", "8103");
        var blue256 = new Target("81", "12GB", "256GB", "8004");
        var three256 = card("COMMON", "KUAISHOU", null, BLACK, WHITE, blue256);
        var three512 = card("COMMON", "KUAISHOU", null, black512, white512, blue512);
        var start = "2026-12-10T00:00:00+08:00";
        var end = "2026-12-11T00:00:00+08:00";
        var both = policy("15", start, end, "SALES", BOTH, three256, three512);
        var national = policy("14", start, end, "SALES", NATIONAL, three256);
        var earlier = new PolicyApplicabilityEvaluator(national, AS_OF);
        add(earlier, both);
        var accepted = selectRetail(earlier, scope -> List.of("15", "14"));
        var local =
                policy(
                        "16",
                        start,
                        end,
                        "SALES",
                        LOCAL,
                        three256,
                        card("SPECIFIED", "KUAISHOU", 3, white512));
        for (var snapshot : List.of(both, national, local))
            PolicyFormValidation.validate(snapshot.form(), true);
        var trial = new PolicyApplicabilityEvaluator(local, AS_OF);
        add(trial, both);
        add(trial, national.withConsent(accepted));
        var decided =
                selectRetail(
                        trial,
                        scope ->
                                scope.goodsId().equals(8102L)
                                        ? List.of("15", "16")
                                        : List.of("14", "16"));
        var route = PolicyRouteSummary.from(trial.workflowSegments(decided, Map.of()), List.of());
        assertThat(route.changes())
                .hasSize(4)
                .allSatisfy(
                        change -> {
                            assertThat(change.scope().platformCode()).isEqualTo("KUAISHOU");
                            assertThat(change.after())
                                    .extracting(Policy::policyId)
                                    .containsExactlyInAnyOrderElementsOf(
                                            change.scope().goodsId().equals(8102L)
                                                    ? List.of("15", "16")
                                                    : List.of("14", "16"));
                        });
        assertThat(route.requiresProductReview()).isFalse();
        assertThat(route.triggerSegments()).isZero();
    }

    @Test
    void disjointSelectedStacksWithInternallyIdenticalGoodsStillCompareAcrossTheWholeRound() {
        var x = card("SPECIFIED", "PDD", 3, BLACK);
        var y = card("SPECIFIED", "PDD", 2, WHITE);
        var baseX = policy("10", 1, 20, "SALES", NATIONAL, x);
        var baseY = policy("30", 1, 20, "SALES", NATIONAL, y);
        var secondX = policy("20", 1, 20, "SALES", LOCAL, x);
        var secondY = policy("40", 1, 20, "SALES", LOCAL, y);
        var pairX = new PolicyApplicabilityEvaluator(secondX, AS_OF);
        add(pairX, baseX);
        var pairY = new PolicyApplicabilityEvaluator(secondY, AS_OF);
        add(pairY, baseY);
        var idsX = List.of("10", "20");
        var idsY = List.of("30", "40");
        var current =
                policy("50", 10, 15, "SALES", BOTH, card("COMMON", "PDD", null, BLACK, WHITE));
        var trial = new PolicyApplicabilityEvaluator(current, AS_OF);
        add(trial, baseX);
        add(trial, secondX.withConsent(selectRetail(pairX, s -> idsX)));
        add(trial, baseY);
        add(trial, secondY.withConsent(selectRetail(pairY, s -> idsY)));
        var confirmation =
                selectRetail(trial, scope -> scope.goodsId().equals(8001L) ? idsX : idsY);
        var changes = trial.workflowSegments(confirmation, Map.of());
        assertThat(changes)
                .hasSize(2)
                .allSatisfy(
                        change -> {
                            assertThat(change.candidates()).hasSize(3);
                            assertThat(change.after())
                                    .extracting(Policy::policyId)
                                    .containsExactlyInAnyOrderElementsOf(
                                            change.scope().goodsId().equals(8001L) ? idsX : idsY);
                            assertThat(change.requiresProductReview()).isFalse();
                        });
        assertThat(PolicyRouteSummary.from(changes, List.of()).triggerSegments()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mergedChannelsMustShareTheSameGoodsAcrossAllSelectedStacks(boolean sameGoods) {
        var evaluation = new PolicyRouteSummary.Evaluation();
        var retail =
                new PolicyApplicabilityEvaluator(
                        policy("12", 10, 15, "SALES", LOCAL, card("SPECIFIED", "PDD", 0, BLACK)),
                        AS_OF);
        add(retail, policy("11", 1, 20, "SALES", NATIONAL, card("SPECIFIED", "PDD", 7, BLACK)));
        retail.workflowSegments(
                selectRetail(retail, scope -> List.of("11", "12")), Map.of(), evaluation);

        var item = sameGoods ? BLACK : WHITE;
        var selected = card("SPECIFIED", "TAOBAO", 4, item);
        var billion =
                new PolicyApplicabilityEvaluator(policy("22", 10, 15, null, null, selected), AS_OF);
        add(billion, policy("21", 1, 20, null, null, selected));
        var preview = billion.result();
        var range = PolicyConfirmation.ranges(preview).get(0);
        var choice =
                new PolicyDraftRequest.Choice(range.scope(), range.through(), "STACK", null, null);
        billion.workflowSegments(
                PolicyConfirmation.confirm(preview, List.of(choice)), Map.of(), evaluation);

        var route = PolicyRouteSummary.from(evaluation.changes(), List.of());
        assertThat(route.changes()).hasSize(2).allMatch(change -> change.after().size() == 2);
        assertThat(route.requiresProductReview()).isEqualTo(sameGoods);
        assertThat(route.triggerSegments()).isEqualTo(sameGoods ? 2 : 0);
    }

    private static PolicyConsent selectRetail(
            PolicyApplicabilityEvaluator evaluator, Function<Scope, List<String>> selected) {
        var preview = evaluator.result();
        return PolicyConfirmation.confirm(
                preview,
                PolicyConfirmation.ranges(preview).stream()
                        .map(
                                range ->
                                        new PolicyDraftRequest.Choice(
                                                range.scope(),
                                                range.through(),
                                                "SELECT",
                                                null,
                                                selected.apply(range.scope())))
                        .toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void comparesOnlyTheChosenPoliciesAndRequiresAllOfThemToMatch(boolean chooseThird) {
        var retailScope = new Scope("PDD", 8001L, null, null, null);
        var approved =
                new Decision(
                        retailScope,
                        date(1, "00:00:00"),
                        date(20, "23:59:59"),
                        Action.SELECT_RETAIL_POLICIES,
                        List.of(),
                        List.of("401", "402"),
                        List.of("401", "402"));
        var trial =
                new PolicyApplicabilityEvaluator(
                        policy(
                                "403",
                                10,
                                15,
                                "SALES",
                                BOTH,
                                card("COMMON", "PDD", null, BLACK, OTHER_CONFIG)),
                        AS_OF);
        add(trial, policy("401", 1, 20, "SALES", LOCAL, PDD));
        add(
                trial,
                policy("402", 1, 20, "SALES", NATIONAL, PDD)
                        .withConsent(new PolicyConsent(2, List.of(), List.of(approved))));
        var preview = trial.result();
        var chosen = chooseThird ? List.of("401", "402", "403") : List.of("401", "402");
        var choice =
                new PolicyDraftRequest.Choice(
                        retailScope, date(15, "23:59:59"), "SELECT", null, chosen);
        assertThat(
                        trial.workflowSegments(
                                PolicyConfirmation.confirm(preview, List.of(choice)), Map.of()))
                .filteredOn(change -> change.scope().equals(retailScope))
                .singleElement()
                .satisfies(
                        change -> {
                            assertThat(change.candidates()).hasSize(3);
                            assertThat(change.after())
                                    .extracting(Policy::policyId)
                                    .containsExactlyInAnyOrderElementsOf(chosen);
                            assertThat(change.requiresProductReview()).isEqualTo(!chooseThird);
                        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void oneEligibleBillionCandidateSkipsProductReviewAfterConfirmation(boolean alreadyShortened) {
        var scope = new Scope("PDD", null, 81L, "12GB", "256GB");
        var target = new PolicyConsent.Target("401", "401", date(20, "23:59:59"));
        var cut = new PolicyConsent.EndAdjustment(target, date(9, "23:59:59"), "提前结束 A");
        var options =
                Map.of(
                        target,
                        new AdjustmentOption(
                                target,
                                "ZC-000401",
                                date(1, "00:00:00"),
                                date(20, "23:59:58"),
                                "BILLION"));
        var draft = policy("403", 10, 15, null, null, PDD);
        var trial =
                new PolicyApplicabilityEvaluator(
                        alreadyShortened
                                ? draft
                                : draft.withConsent(
                                        new PolicyConsent(3, List.of(), List.of(), List.of(cut))),
                        AS_OF);
        if (alreadyShortened) {
            var through = PolicyFormValidation.time(cut.endTime());
            trial.includeTimeChange(
                    401,
                    new PolicyEffectivePeriods.Change(
                            1, false, through.plusSeconds(1), through, AS_OF, List.of()));
        }
        add(trial, policy("401", 1, 20, null, null, PDD));
        var acceptedA =
                new Decision(
                        scope,
                        date(1, "00:00:00"),
                        date(20, "23:59:59"),
                        Action.STACK_OR_CHOOSE,
                        List.of(),
                        List.of("401", "402"),
                        List.of("401"));
        add(
                trial,
                policy("402", 1, 20, null, null, PDD)
                        .withConsent(new PolicyConsent(2, List.of(), List.of(acceptedA))));

        var preview = trial.result(options);
        assertThat(preview.scopes().get(0).segments().get(0).eligiblePolicyIds())
                .containsExactly("403");
        var confirmed =
                PolicyConfirmation.confirm(
                        preview,
                        List.of(
                                new PolicyDraftRequest.Choice(
                                        scope, date(15, "23:59:59"), "CHOOSE_ONE", "403", null)));
        assertThat(trial.workflowSegments(confirmed, options))
                .singleElement()
                .satisfies(
                        change -> {
                            assertThat(change.candidates())
                                    .extracting(Policy::policyId)
                                    .containsExactly("403");
                            assertThat(change.kind())
                                    .isEqualTo(
                                            alreadyShortened
                                                    ? PolicyExecutionChange.Kind.ORDINARY_ADDITION
                                                    : PolicyExecutionChange.Kind.REPLACEMENT);
                            assertThat(change.requiresProductReview()).isFalse();
                        });
    }

    @Test
    void confirmedKeptStackNeedsReviewWhileKeepingOnlyOneAfterTheCutSkipsReview() {
        var scope = new Scope("PDD", 8001L, null, null, null);
        var target = new PolicyConsent.Target("302", "302", date(14, "23:59:59"));
        var end = date(10, "18:00:00");
        var options =
                Map.of(
                        target,
                        new AdjustmentOption(
                                target,
                                "ZC-000302",
                                date(1, "00:00:00"),
                                date(14, "23:59:58"),
                                "RETAIL"));
        var cut = new PolicyConsent.EndAdjustment(target, end, "原单提前退出");
        var trial =
                new PolicyApplicabilityEvaluator(
                        policy("303", 10, 14, "SALES", LOCAL, PDD)
                                .withConsent(
                                        new PolicyConsent(3, List.of(), List.of(), List.of(cut))),
                        AS_OF);
        add(trial, policy("301", 10, 14, "SALES", NATIONAL, PDD));
        var stacked =
                new Decision(
                        scope,
                        date(10, "00:00:00"),
                        date(14, "23:59:59"),
                        Action.SELECT_RETAIL_POLICIES,
                        List.of(),
                        List.of("301", "302"),
                        List.of("301", "302"));
        add(
                trial,
                policy("302", 10, 14, "SALES", LOCAL, PDD)
                        .withConsent(new PolicyConsent(2, List.of(), List.of(stacked))));
        var preview = trial.result(options);
        var keepBoth =
                new PolicyDraftRequest.Choice(scope, end, "SELECT", null, List.of("301", "302"));
        var keepOne =
                new PolicyDraftRequest.Choice(
                        scope, date(14, "23:59:59"), "SELECT", null, List.of("301"));
        assertThat(
                        trial.workflowSegments(
                                PolicyConfirmation.confirm(preview, List.of(keepBoth, keepOne)),
                                options))
                .extracting(PolicyExecutionChange::requiresProductReview)
                .containsExactly(true, false);
        var replace =
                new PolicyDraftRequest.Choice(
                        scope, date(14, "23:59:59"), "SELECT", null, List.of("301", "303"));
        assertThat(
                        trial.workflowSegments(
                                PolicyConfirmation.confirm(preview, List.of(keepBoth, replace)),
                                options))
                .filteredOn(c -> c.kind() == PolicyExecutionChange.Kind.REPLACEMENT)
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.requiresProductReview()).isTrue();
                            assertThat(c.from()).isEqualTo("2026-11-10T18:00:01");
                            assertThat(c.added())
                                    .extracting(Policy::policyId)
                                    .containsExactly("303");
                            assertThat(c.removed())
                                    .extracting(Policy::policyId)
                                    .containsExactly("302");
                        });
    }

    @Test
    void workflowRejectsMissingModernHistoryAndUnconfirmedOverlap() {
        var trial =
                new PolicyApplicabilityEvaluator(policy("303", 10, 14, "SALES", BOTH, PDD), AS_OF);
        add(trial, policy("301", 10, 14, "SALES", NATIONAL, PDD));
        assertThatThrownBy(
                        () ->
                                trial.workflowSegments(
                                        new PolicyConsent(2, List.of(), List.of()), Map.of()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("POLICY_RECONFIRM_REQUIRED"));
        assertThatThrownBy(
                        () ->
                                trial.include(
                                        policy("302", 10, 14, "SALES", NATIONAL, PDD)
                                                .withConsent(null),
                                        302L,
                                        AS_OF,
                                        AS_OF))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String date(int day, String time) {
        return "2026-11-%02dT%s+08:00".formatted(day, time);
    }

    @Test
    void workflowRejectsAnActiveHistoryIndexWhoseSnapshotDoesNotContainTheMatchedScope() {
        var trial =
                new PolicyApplicabilityEvaluator(policy("303", 10, 14, "SALES", BOTH, PDD), AS_OF);
        var wrongColor =
                policy("301", 10, 14, "SALES", NATIONAL, card("COMMON", "PDD", null, WHITE));
        assertThatThrownBy(() -> trial.includeWorkflow(wrongColor, 301, AS_OF, AS_OF))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("POLICY_RECONFIRM_REQUIRED"));
        var shortened = PolicyFormValidation.time(date(9, "23:59:59"));
        trial.includeTimeChange(
                301,
                new PolicyEffectivePeriods.Change(
                        1, false, shortened.plusSeconds(1), shortened, AS_OF, List.of()));
        trial.includeWorkflow(wrongColor, 301, AS_OF, AS_OF);
        assertThat(trial.result().canDirectSubmit()).isTrue();
    }

    @Test
    void workflowRejectsAStoredRetailDecisionSelectingTwoCandidatesInOneGroup() {
        var trial =
                new PolicyApplicabilityEvaluator(
                        policy("303", 10, 14, "SALES", NATIONAL, PDD), AS_OF);
        add(trial, policy("301", 10, 14, "SALES", NATIONAL, PDD));
        var range = PolicyConfirmation.ranges(trial.result()).get(0);
        var broken =
                new Decision(
                        range.scope(),
                        range.from(),
                        range.through(),
                        range.action(),
                        range.groups(),
                        range.eligiblePolicyIds(),
                        List.of("301", "303"));
        var confirmed =
                new PolicyConsent(
                        2, PolicyConfirmation.comparedRounds(trial.result()), List.of(broken));
        assertThatThrownBy(() -> trial.workflowSegments(confirmed, Map.of()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("POLICY_RECONFIRM_REQUIRED"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void samePolicyNumberOnlyCreatesAnotherConfirmationWhenAdoptionChanges(
            boolean changeSelection) {
        var scope = new Scope("PDD", 8001L, null, null, null);
        var ids = List.of("24", "25");
        var groups =
                List.of(
                        new Group(NATIONAL, "SALES", List.of("24")),
                        new Group(LOCAL, "SALES", List.of("25")));
        var first =
                new Decision(
                        scope,
                        date(1, "00:00:00"),
                        date(10, "23:59:59"),
                        Action.SELECT_RETAIL_POLICIES,
                        groups,
                        ids,
                        ids);
        var next =
                new Decision(
                        scope,
                        date(11, "00:00:00"),
                        date(12, "23:59:59"),
                        Action.SELECT_RETAIL_POLICIES,
                        groups,
                        ids,
                        changeSelection ? List.of("25") : ids);
        var extension =
                new PolicyEffectivePeriods.Change(
                        1,
                        true,
                        PolicyFormValidation.time(next.from()),
                        PolicyFormValidation.time(next.through()),
                        AS_OF,
                        List.of(next));
        var source = policy("24", 1, 10, "SALES", NATIONAL, PDD);
        var other =
                policy("25", 1, 12, "SALES", LOCAL, PDD)
                        .withConsent(new PolicyConsent(2, List.of(), List.of(first)));
        var preview =
                new PolicyApplicabilityEvaluator(
                        policy("27", 1, 12, "ACTIVATION", LOCAL, PDD), AS_OF);
        preview.includeTimeChange(24, extension);
        add(preview, source);
        add(preview, other);
        var result = preview.result().scopes().get(0);
        assertThat(result.policies())
                .extracting(Policy::policyId)
                .containsExactlyInAnyOrder("24", "25", "27");
        assertThat(result.segments()).hasSize(changeSelection ? 2 : 1);
        assertThat(result.segments().get(0).through())
                .isEqualTo(changeSelection ? first.through() : next.through());
        var point =
                new PolicyApplicabilityEvaluator(
                        "RETAIL", scope, PolicyFormValidation.time(next.from()));
        point.includeTimeChange(24, extension);
        add(point, source);
        add(point, other);
        assertThat(point.effectivePolicies(scope))
                .extracting(Policy::policyId)
                .containsExactlyInAnyOrderElementsOf(next.selectedPolicyIds());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void proposedExtensionKeepsAnIndependentChoiceWhenOtherAdoptedPoliciesChange(
            boolean switchOtherPolicy) {
        var scope = new Scope("PDD", 8001L, null, null, null);
        var first =
                new Decision(
                        scope,
                        date(1, "00:00:00"),
                        date(10, "23:59:59"),
                        Action.SELECT_RETAIL_POLICIES,
                        List.of(),
                        List.of("24", "25", "26"),
                        List.of("24", "25"));
        var next =
                new Decision(
                        scope,
                        date(11, "00:00:00"),
                        date(14, "23:59:59"),
                        Action.SELECT_RETAIL_POLICIES,
                        List.of(),
                        List.of("25", "26"),
                        List.of(switchOtherPolicy ? "26" : "25"));
        var old = policy("24", 1, 10, "SALES", NATIONAL, PDD);
        var proposal = policy("24", 11, 14, "SALES", NATIONAL, PDD);
        var extension =
                new Snapshot(
                        "extension:30:24",
                        old.policyCode() + "（延期）",
                        old.applicantId(),
                        old.applicantName(),
                        1,
                        proposal.form(),
                        proposal.goods(),
                        proposal.consent());
        var parent = policy("30", 1, 13, "ACTIVATION", LOCAL, PDD);
        var preview =
                new PolicyApplicabilityEvaluator(
                        List.of(parent, extension), AS_OF, AS_OF, Long.MAX_VALUE);
        add(preview, old);
        add(preview, policy("25", 1, 14, "SALES", LOCAL, PDD));
        add(
                preview,
                policy("26", 1, 14, "SALES", LOCAL, PDD)
                        .withConsent(new PolicyConsent(2, List.of(), List.of(first, next))));
        var segments = preview.result().scopes().get(0).segments();
        assertThat(segments)
                .extracting(Segment::continuesPrevious)
                .containsExactly(false, !switchOtherPolicy, false);
    }

    @Test
    void expiredRenewalKeepsItsGapAndOneSecondExtensionUsesTheOriginalRound() {
        var source = policy("24", 1, 10, "SALES", NATIONAL, PDD);
        var cutoff = PolicyFormValidation.time(date(10, "23:59:59"));
        var resumed = cutoff.plusDays(2);
        var renewal =
                new PolicyEffectivePeriods.Change(1, true, resumed, resumed, resumed, List.of());
        var timeline =
                new PolicyTimelineBuilder(
                        "RETAIL",
                        List.of("PDD"),
                        81,
                        "12GB",
                        "256GB",
                        List.of(8001L),
                        cutoff.minusDays(2),
                        resumed.plusDays(1),
                        resumed.plusDays(2));
        timeline.includeTimeChange(24, renewal);
        timeline.include(source, 24, AS_OF.minusDays(1), AS_OF);
        var track = timeline.tracks().get(0);
        assertThat(track.policies())
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.policyId()).isEqualTo("24");
                            assertThat(p.policyThrough()).isEqualTo(date(10, "23:59:59"));
                            assertThat(p.effectiveThrough())
                                    .isEqualTo(PolicyApplicabilityEvaluator.time(resumed));
                        });
        assertThat(track.segments()).hasSize(2);
        assertThat(track.segments().get(0).endAt())
                .isEqualTo(PolicyApplicabilityEvaluator.time(cutoff.plusSeconds(1)));
        assertThat(track.segments().get(1).startAt())
                .isEqualTo(PolicyApplicabilityEvaluator.time(resumed));
        assertThat(track.segments().get(1).endAt())
                .isEqualTo(PolicyApplicabilityEvaluator.time(resumed.plusSeconds(1)));
        var scope = new Scope("PDD", 8001L, null, null, null);
        for (var second :
                List.of(
                        cutoff,
                        cutoff.plusSeconds(1),
                        resumed.minusSeconds(1),
                        resumed,
                        resumed.plusSeconds(1))) {
            var point = new PolicyApplicabilityEvaluator("RETAIL", scope, second);
            point.includeTimeChange(24, renewal);
            add(point, source);
            assertThat(point.effectivePolicies(scope))
                    .hasSize(second.equals(cutoff) || second.equals(resumed) ? 1 : 0);
        }
    }

    @Test
    void billionRenewalCanSelectOriginalEvenIfAPriorDecisionExcludedItsPreviousPeriod() {
        var scope = new Scope("PDD", null, 81L, "12GB", "256GB");
        var old =
                new Decision(
                        scope,
                        date(1, "00:00:00"),
                        date(20, "23:59:59"),
                        Action.STACK_OR_CHOOSE,
                        List.of(),
                        List.of("24", "25"),
                        List.of("25"));
        var selected =
                new Decision(
                        scope,
                        date(11, "00:00:00"),
                        date(18, "23:59:59"),
                        Action.STACK_OR_CHOOSE,
                        List.of(),
                        List.of("24", "25"),
                        List.of("24"));
        var point =
                new PolicyApplicabilityEvaluator(
                        "BILLION", scope, PolicyFormValidation.time(selected.from()));
        point.includeTimeChange(
                24,
                new PolicyEffectivePeriods.Change(
                        1,
                        false,
                        PolicyFormValidation.time(selected.from()),
                        PolicyFormValidation.time(selected.from()).minusSeconds(1),
                        AS_OF.minusDays(1),
                        List.of()));
        point.includeTimeChange(
                24,
                new PolicyEffectivePeriods.Change(
                        2,
                        true,
                        PolicyFormValidation.time(selected.from()),
                        PolicyFormValidation.time(selected.through()),
                        AS_OF,
                        List.of(selected)));
        add(point, policy("24", 1, 20, null, List.of(), PDD));
        add(
                point,
                policy("25", 1, 20, null, List.of(), PDD)
                        .withConsent(new PolicyConsent(2, List.of(), List.of(old))));
        assertThat(point.effectivePolicies(scope))
                .extracting(Policy::policyId)
                .containsExactly("24");
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETAIL", "BILLION"})
    void sameSecondUsesWhichApprovalTheAdjustmentSawInsteadOfComparingIdsAcrossTables(
            String channel) {
        var retail = "RETAIL".equals(channel);
        var scope =
                retail
                        ? new Scope("PDD", 8001L, null, null, null)
                        : new Scope("PDD", null, 81L, "12GB", "256GB");
        var action = retail ? Action.SELECT_RETAIL_POLICIES : Action.STACK_OR_CHOOSE;
        var verify = retail ? "SALES" : null;
        var scenes = retail ? NATIONAL : null;
        var start = date(11, "00:00:00");
        var end = date(12, "23:59:59");
        for (boolean ordinaryAlreadyApproved : List.of(true, false)) {
            var earlier = ordinaryAlreadyApproved ? List.of("24", "25", "90") : List.of("24", "25");
            var extensionChoice =
                    new Decision(scope, start, end, action, List.of(), earlier, List.of("24"));
            var ordinaryChoice =
                    new Decision(
                            scope,
                            start,
                            end,
                            action,
                            List.of(),
                            List.of("24", "25", "90"),
                            List.of("90"));
            // 故意让两个不同表的 ID 大小与业务顺序相反。
            var changeId = ordinaryAlreadyApproved ? 1 : 1000;
            var auditId = ordinaryAlreadyApproved ? 1000 : 1;
            var point =
                    new PolicyApplicabilityEvaluator(
                            channel, scope, PolicyFormValidation.time(start));
            point.includeTimeChange(
                    24,
                    new PolicyEffectivePeriods.Change(
                            changeId,
                            true,
                            PolicyFormValidation.time(start),
                            PolicyFormValidation.time(end),
                            AS_OF,
                            List.of(extensionChoice),
                            java.util.Set.copyOf(earlier)));
            add(point, policy("24", 1, 10, verify, scenes, PDD));
            add(point, policy("25", 1, 15, verify, scenes, PDD));
            point.include(
                    policy("90", 1, 15, verify, scenes, PDD)
                            .withConsent(new PolicyConsent(2, List.of(), List.of(ordinaryChoice))),
                    auditId,
                    AS_OF,
                    AS_OF);
            assertThat(point.effectivePolicies(scope))
                    .extracting(Policy::policyId)
                    .containsExactly(ordinaryAlreadyApproved ? "24" : "90");
        }
    }

    @Test
    void successiveAdjustmentsWithinOneSecondReplayTheirInsertionOrder() {
        var scope = new Scope("PDD", 8001L, null, null, null);
        var at = PolicyFormValidation.time(date(10, "12:00:00"));
        var firstEnd = date(10, "23:59:59");
        var shorterEnd = date(9, "23:59:59");
        var end = date(12, "23:59:59");
        var priorChoice =
                new Decision(
                        scope,
                        date(9, "00:00:00"),
                        firstEnd,
                        Action.SELECT_RETAIL_POLICIES,
                        List.of(),
                        List.of("24", "25"),
                        List.of("25"));
        var newChoice =
                new Decision(
                        scope,
                        date(10, "00:00:00"),
                        end,
                        Action.SELECT_RETAIL_POLICIES,
                        List.of(),
                        List.of("24", "25"),
                        List.of("24"));
        var point = new PolicyApplicabilityEvaluator("RETAIL", scope, at);
        point.includeTimeChange(
                24,
                new PolicyEffectivePeriods.Change(
                        1,
                        true,
                        PolicyFormValidation.time(priorChoice.from()),
                        PolicyFormValidation.time(firstEnd),
                        AS_OF,
                        List.of(priorChoice)));
        point.includeTimeChange(
                24,
                new PolicyEffectivePeriods.Change(
                        2,
                        false,
                        PolicyFormValidation.time(newChoice.from()),
                        PolicyFormValidation.time(shorterEnd),
                        AS_OF,
                        List.of()));
        point.includeTimeChange(
                24,
                new PolicyEffectivePeriods.Change(
                        3,
                        true,
                        PolicyFormValidation.time(newChoice.from()),
                        PolicyFormValidation.time(end),
                        AS_OF,
                        List.of(newChoice)));
        add(point, policy("24", 1, 8, "SALES", NATIONAL, PDD));
        add(point, policy("25", 1, 15, "SALES", NATIONAL, PDD));
        assertThat(point.effectivePolicies(scope))
                .extracting(Policy::policyId)
                .containsExactly("24");
    }

    private static Snapshot selectRetail(Snapshot proposal, Snapshot previous) {
        var evaluator = new PolicyApplicabilityEvaluator(proposal, AS_OF);
        add(evaluator, previous);
        var preview = evaluator.result();
        var choices =
                PolicyConfirmation.ranges(preview).stream()
                        .map(
                                range ->
                                        new PolicyDraftRequest.Choice(
                                                range.scope(),
                                                range.through(),
                                                "SELECT",
                                                null,
                                                List.of(proposal.id())))
                        .toList();
        return proposal.withConsent(PolicyConfirmation.confirm(preview, choices));
    }

    private static void add(PolicyApplicabilityEvaluator trial, Snapshot prior) {
        trial.include(prior, Long.parseLong(prior.id()), AS_OF.minusDays(3), AS_OF.minusDays(2));
    }

    private static Card card(String type, String platform, Integer quantity, Target... targets) {
        return new Card(
                type + targets[0].goodsId(),
                type,
                false,
                List.of(targets),
                List.of(new SubsidyPlan("plan", "FIXED", "100.00", null, null, null)),
                List.of(new PlatformAssignment(platform, "plan", quantity)));
    }

    private static Snapshot policy(
            String id, int from, int through, String verify, List<String> scenes, Card... cards) {
        return policy(id, date(from, "00:00:00"), date(through, "23:59:59"), verify, scenes, cards);
    }

    private static Snapshot policy(
            String id,
            String from,
            String through,
            String verify,
            List<String> scenes,
            Card... cards) {
        var form =
                new PolicyDraftRequest(
                        null,
                        verify == null ? "BILLION" : "RETAIL",
                        List.of(cards).stream()
                                .flatMap(card -> card.platformAssignments().stream())
                                .map(PlatformAssignment::platformCode)
                                .distinct()
                                .toList(),
                        verify,
                        scenes,
                        verify == null ? null : false,
                        from,
                        through,
                        null,
                        List.of(),
                        new GoodsForm(List.of(cards)));
        var goods =
                List.of(cards).stream()
                        .flatMap(c -> c.targets().stream())
                        .distinct()
                        .map(
                                t ->
                                        new ApplicantCatalogResponse(
                                                t.goodsId(),
                                                "1",
                                                "OPPO",
                                                "2",
                                                "Find",
                                                t.spuId(),
                                                "Find X8",
                                                "标准版",
                                                t.ram(),
                                                t.rom(),
                                                "Find X8",
                                                switch (t.goodsId()) {
                                                    case "8002" -> "白色";
                                                    case "8102" -> "初雪白";
                                                    case "8004", "8103" -> "蓝色";
                                                    default -> "黑色";
                                                },
                                                true))
                        .toList();
        return new Snapshot(
                id,
                "ZC-%06d".formatted(Long.parseLong(id)),
                "9",
                "申请人",
                1,
                form,
                goods,
                new PolicyConsent(2, List.of(), List.of()));
    }
}
