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
import com.company.policy.vo.PolicyApplicability.Group;
import com.company.policy.vo.PolicyApplicability.Segment;
import com.company.policy.vo.PolicyApplicability.Status;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

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
                        .filter(g -> g.sceneType().equals("NATIONAL_SUB"))
                        .findFirst()
                        .orElseThrow();
        assertThat(national.winnerPolicyId()).isNull();
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
        for (var day : List.of(24, 25)) {
            var query =
                    new PolicyApplicabilityEvaluator(
                            "RETAIL", scope, LocalDateTime.of(2026, 11, day, 23, 59, 59));
            for (var old : List.of(olderCombined, national, local)) add(query, old);
            query.include(saved, 7, AS_OF, AS_OF.plusDays(1));
            assertThat(query.effectivePolicies(scope, 8001).stream().map(p -> p.policyId()))
                    .containsExactlyInAnyOrderElementsOf(
                            day == 24 ? List.of("6", "3") : List.of("7"));
        }
    }

    @Test
    void readsFrozenVersionOneGroupsAndRestoresTheirResultAfterANewDecisionEnds() throws Exception {
        var combined = policy("3", 1, 30, "SALES", BOTH, PDD);
        var codec = new PolicySnapshotCodec();
        var json = new ObjectMapper();
        var stored =
                (ObjectNode) json.readTree(codec.write(policy("6", 1, 30, "SALES", NATIONAL, PDD)));
        var oldConsent =
                json.readTree(
                        """
                {"ruleVersion":1,"comparedRounds":[{"policyId":"3","submissionVersion":1,"auditLogId":"3"}],
                 "decisions":[{"scope":{"platformCode":"PDD","goodsId":"8001"},
                 "from":"2026-11-01T00:00:00+08:00","through":"2026-11-30T23:59:59+08:00",
                 "action":"CONFIRM_STACK","groups":[
                 {"sceneType":"NATIONAL_SUB","verifyType":"SALES","policyIds":["6","3"],"winnerPolicyId":"6"},
                 {"sceneType":"LOCAL_SUB","verifyType":"SALES","policyIds":["3"],"winnerPolicyId":"3"}],
                 "eligiblePolicyIds":["6","3"],"selectedPolicyIds":["6","3"]}]}
                """);
        stored.set("consent", oldConsent);
        var old = codec.read(stored.toString());
        var draft = policy("7", 23, 25, "SALES", BOTH, PDD);
        var trial = new PolicyApplicabilityEvaluator(draft, AS_OF);
        add(trial, combined);
        add(trial, old);
        var scope = trial.result().scopes().get(0).scope();
        var choice =
                new PolicyDraftRequest.Choice(
                        scope, date(25, "23:59:59"), "SELECT", null, List.of("3"));
        var saved = draft.withConsent(PolicyConfirmation.confirm(trial.result(), List.of(choice)));
        for (var day : List.of(22, 23, 25, 26)) {
            var query =
                    new PolicyApplicabilityEvaluator(
                            "RETAIL", scope, LocalDateTime.of(2026, 11, day, 23, 59, 59));
            add(query, combined);
            add(query, old);
            query.include(saved, 7, AS_OF, AS_OF.plusDays(1));
            assertThat(query.effectivePolicies(scope, 8001).stream().map(p -> p.policyId()))
                    .containsExactlyElementsOf(
                            day == 22 || day == 26 ? List.of("6", "3") : List.of("3"));
        }
    }

    @Test
    void pendingVersionOneConsentCanKeepItsConfirmedOutcomeWhenTheGroupsStillMatch() {
        var draft = policy("7", 23, 25, "SALES", NATIONAL, PDD);
        var trial = new PolicyApplicabilityEvaluator(draft, AS_OF);
        var old = policy("3", 1, 30, "SALES", NATIONAL, PDD);
        add(trial, old);
        var preview = trial.result();
        var range = PolicyConfirmation.ranges(preview).get(0);
        var saved =
                new PolicyConsent(
                        1,
                        PolicyConfirmation.comparedRounds(preview),
                        List.of(
                                new Decision(
                                        range.scope(),
                                        range.from(),
                                        range.through(),
                                        Action.ACKNOWLEDGE_EXCLUSIVE,
                                        List.of(
                                                new Group(
                                                        "NATIONAL_SUB",
                                                        "SALES",
                                                        List.of("7", "3"),
                                                        "7",
                                                        null)),
                                        List.of("7"),
                                        List.of("7"))));
        assertThat(PolicyConfirmation.requiresResubmission(saved, preview)).isFalse();
        var changed =
                new PolicyApplicabilityEvaluator(policy("7", 23, 25, "SALES", BOTH, PDD), AS_OF);
        add(changed, old);
        assertThat(PolicyConfirmation.requiresResubmission(saved, changed.result())).isTrue();
    }

    private static String date(int day, String time) {
        return "2026-11-%02dT%s+08:00".formatted(day, time);
    }

    private static void add(PolicyApplicabilityEvaluator trial, Snapshot prior) {
        trial.include(prior, Long.parseLong(prior.id()), AS_OF.minusDays(3), AS_OF.minusDays(2));
    }

    private static Card card(String type, String platform, Integer quantity, Target... targets) {
        return new Card(
                type + targets[0].goodsId(),
                type,
                true,
                List.of(targets),
                List.of(new SubsidyPlan("plan", "FIXED", "100.00", null, null, null)),
                List.of(new PlatformAssignment(platform, "plan", quantity)));
    }

    private static Snapshot policy(
            String id, int from, int through, String verify, List<String> scenes, Card... cards) {
        var form =
                new PolicyDraftRequest(
                        null,
                        verify == null ? "BILLION" : "RETAIL",
                        List.of("PDD"),
                        verify,
                        PolicyScenes.single(scenes),
                        scenes,
                        verify == null ? null : false,
                        date(from, "00:00:00"),
                        date(through, "09:00:00"),
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
                                                t.equals(WHITE) ? "白色" : "黑色",
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
                new PolicyConsent(1, List.of(), List.of()));
    }
}
