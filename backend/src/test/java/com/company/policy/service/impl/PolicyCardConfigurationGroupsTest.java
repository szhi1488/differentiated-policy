package com.company.policy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.service.impl.PolicyCardConfigurationGroups.Group;
import com.company.policy.support.policy.PolicyChangeDescription;
import com.company.policy.support.policy.PolicyExecutionChange;
import com.company.policy.vo.PolicyApplicability.Policy;
import com.company.policy.vo.PolicyApplicability.Product;
import com.company.policy.vo.PolicyApplicability.Scope;
import com.company.policy.vo.PolicyApplicability.Selection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyCardConfigurationGroupsTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 12, 1, 12, 0);
    private static final Product WHITE = product("101", "白");
    private static final Product BLACK = product("102", "黑");
    private static final Product OTHER_BLACK = product("103", "黑");
    private static final Scope CONFIGURATION = new Scope("JD", null, 8L, "12GB", "256GB");

    @Test
    void retailColorsAppearOnceAsAWholeWithEvidenceFromOneAdoptedPolicy() {
        var white = retail("JD", WHITE, 0, 30, "B", "12.00", 2);
        var black = retail("JD", BLACK, 0, 30, "B", "12.00", 2);
        assertThat(PolicyCardConfigurationGroups.group(List.of(white, black)))
                .singleElement()
                .satisfies(
                        group -> {
                            assertThat(group.wholeConfiguration()).isTrue();
                            assertThat(group.from()).isEqualTo(START);
                            assertThat(group.through()).isEqualTo(START.plusSeconds(30));
                        });
    }

    @Test
    void singleSecondIntersectionLeavesBothPartialPeriodsIntact() {
        var white = retail("JD", WHITE, 0, 5, "B", "12.00", 2);
        var black = retail("JD", BLACK, 5, 10, "B", "12.00", 2);
        var groups = PolicyCardConfigurationGroups.group(List.of(black, white));
        assertThat(groups)
                .extracting(Group::wholeConfiguration, Group::from, Group::through)
                .containsExactly(
                        tuple(false, START, START.plusSeconds(4)),
                        tuple(true, START.plusSeconds(5), START.plusSeconds(5)),
                        tuple(false, START.plusSeconds(6), START.plusSeconds(10)));
        assertThat(groups.get(0).change()).isSameAs(white);
        assertThat(groups.get(2).change()).isSameAs(black);
    }

    @Test
    void retailRequiresAllGoodsOnOnePlatformWithTheSameSelectedPoliciesAndSubsidies() {
        var white = retail("JD", WHITE, 0, 10, "B", "12.00", 2);
        assertThat(PolicyCardConfigurationGroups.group(List.of(white)))
                .extracting(Group::wholeConfiguration)
                .containsExactly(false);
        var differences =
                List.of(
                        retail("JD", BLACK, 0, 10, "B", "15.00", 2),
                        retail("JD", BLACK, 0, 10, "C", "12.00", 2),
                        retail("PDD", BLACK, 0, 10, "B", "12.00", 2),
                        retail("JD", BLACK, 0, 10, "B", "12.00", null));
        for (var black : differences) {
            assertThat(PolicyCardConfigurationGroups.group(List.of(white, black)))
                    .extracting(Group::wholeConfiguration)
                    .containsExactly(false, false);
        }
    }

    @Test
    void aSingleGoodsConfigurationAlsoRequiresPositiveFrozenEvidence() {
        var single = retail("JD", WHITE, 0, 10, "B", "12.00", 1);
        var unknown = retail("JD", WHITE, 0, 10, "B", "12.00", null);
        assertThat(PolicyCardConfigurationGroups.group(List.of(single)))
                .extracting(Group::wholeConfiguration)
                .containsExactly(true);
        assertThat(PolicyCardConfigurationGroups.group(List.of(unknown)))
                .extracting(Group::wholeConfiguration)
                .containsExactly(false);
    }

    @Test
    void billionRequiresIdenticalActualGoodsAndAWholeConfigurationCount() {
        var whole = common("A", "10.00", 2, WHITE, BLACK);
        var unmarked = common("B", "12.00", null, BLACK, WHITE);
        var full = segment(CONFIGURATION, 0, 10, whole, unmarked);
        assertThat(PolicyCardConfigurationGroups.group(List.of(full)))
                .extracting(Group::wholeConfiguration)
                .containsExactly(true);

        var partial =
                segment(
                        CONFIGURATION,
                        0,
                        10,
                        common("A", "10.00", 2, WHITE),
                        common("B", "12.00", null, WHITE));
        var differentId =
                segment(CONFIGURATION, 0, 10, whole, common("B", "12.00", 2, WHITE, OTHER_BLACK));
        var inconsistentCount =
                segment(CONFIGURATION, 0, 10, whole, common("B", "12.00", 3, WHITE, BLACK));
        assertThat(
                        PolicyCardConfigurationGroups.group(
                                List.of(partial, differentId, inconsistentCount)))
                .extracting(Group::wholeConfiguration)
                .containsExactly(false, false, false);
    }

    @Test
    void billionCanGroupIndividuallyDeclaredGoodsWhenEveryItemHasTheSameTerms() {
        var whole = common("A", "10.00", 2, WHITE, BLACK);
        var white = specified(WHITE, "12.00", 3);
        var black = specified(BLACK, "12.00", 3);
        var byItem = policy("B", white, black);
        var complete = segment(CONFIGURATION, 0, 10, whole, byItem);
        assertThat(PolicyCardConfigurationGroups.group(List.of(complete)))
                .extracting(Group::wholeConfiguration)
                .containsExactly(true);
        assertThat(PolicyChangeDescription.cardTerms(byItem, false, true, Set.of()))
                .containsOnlyOnce("固定金额 12 元/台")
                .containsOnlyOnce("每件申报数量 3 台");

        var differentAmount =
                segment(
                        CONFIGURATION,
                        0,
                        10,
                        whole,
                        policy("B", white, specified(BLACK, "15.00", 3)));
        var differentQuantity =
                segment(
                        CONFIGURATION,
                        0,
                        10,
                        whole,
                        policy("B", white, specified(BLACK, "12.00", 4)));
        assertThat(PolicyCardConfigurationGroups.group(List.of(differentAmount, differentQuantity)))
                .extracting(Group::wholeConfiguration)
                .containsExactly(false, false);
    }

    @Test
    void retailCanGroupSeparateItemDeclarationsAcrossColorsOnlyWithMatchingAmountAndQuantity() {
        var white = retailWithSpecified(WHITE, "12.00", 0);
        var black = retailWithSpecified(BLACK, "12.00", 0);
        assertThat(PolicyCardConfigurationGroups.group(List.of(white, black)))
                .extracting(Group::wholeConfiguration)
                .containsExactly(true);
        for (var different :
                List.of(
                        retailWithSpecified(BLACK, "12.00", null),
                        retailWithSpecified(BLACK, "15.00", 0))) {
            assertThat(PolicyCardConfigurationGroups.group(List.of(white, different)))
                    .extracting(Group::wholeConfiguration)
                    .containsExactly(false, false);
        }
    }

    private static PolicyExecutionChange retailWithSpecified(
            Product item, String amount, Integer quantity) {
        var scope = new Scope("JD", Long.valueOf(item.goodsId()), null, null, null);
        return segment(
                scope,
                0,
                10,
                common("A", "10.00", 2, item),
                policy("B", specified(item, amount, quantity)));
    }

    private static Selection specified(Product item, String amount, Integer quantity) {
        var plan = new SubsidyPlan("plan-" + item.goodsId(), "FIXED", amount, null, null, null);
        return new Selection("SPECIFIED", List.of(item), plan, quantity, null);
    }

    private static PolicyExecutionChange retail(
            String platform,
            Product item,
            int from,
            int through,
            String secondPolicyId,
            String secondAmount,
            Integer count) {
        var scope = new Scope(platform, Long.valueOf(item.goodsId()), null, null, null);
        // A 的旧快照未记录整组标记，B 的冻结件数可供本段核实。
        return segment(
                scope,
                from,
                through,
                common("A", "10.00", null, item),
                common(secondPolicyId, secondAmount, count, item));
    }

    private static PolicyExecutionChange segment(
            Scope scope, int from, int through, Policy first, Policy second) {
        var selected = List.of(first, second);
        return PolicyExecutionChange.compare(
                scope.goodsId() == null ? "BILLION" : "RETAIL",
                scope,
                START.plusSeconds(from),
                START.plusSeconds(through),
                List.of(first),
                selected,
                selected,
                true);
    }

    private static Policy common(String id, String fixed, Integer count, Product... goods) {
        var plan = new SubsidyPlan("p", "FIXED", fixed, null, null, null);
        var row = new Selection("COMMON", List.of(goods), plan, null, count);
        return policy(id, row);
    }

    private static Policy policy(String id, Selection... rows) {
        return new Policy(
                id, id, 1, null, false, null, null, null, null, null, null, List.of(rows));
    }

    private static Product product(String id, String color) {
        return new Product(id, null, color, "8", "Reno", null, "12GB", "256GB");
    }
}
