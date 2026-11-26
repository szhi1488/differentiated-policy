package com.company.policy.support.policy;

import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.entity.PolicyConsent.Target;
import com.company.policy.support.policy.PolicyWorkflowProposals.Adjustment;
import com.company.policy.vo.PolicyApplicability.Policy;
import com.company.policy.vo.PolicyApplicability.Product;
import com.company.policy.vo.PolicyApplicability.Scope;
import com.company.policy.vo.PolicyApplicability.Selection;
import java.time.LocalDateTime;
import java.util.List;

/** 卡片回调与传输测试使用同一份已冻结的叠加时段和随单缩短依据。 */
public final class ProductReviewCardFixture {
    private ProductReviewCardFixture() {}

    public static PolicyRouteSummary route(long policyId, int version) {
        var original = policy(77, 1, false, "LOCAL_SUB", "25.00");
        var current = policy(policyId, version, true, "SELF_SUB", "30.00");
        var selected = List.of(original, current);
        var segment =
                PolicyExecutionChange.compare(
                        "RETAIL",
                        new Scope("JD", 8L, null, null, null),
                        LocalDateTime.of(2026, 12, 1, 0, 0),
                        LocalDateTime.of(2026, 12, 4, 23, 59, 59),
                        List.of(original),
                        selected,
                        selected,
                        true);
        var change =
                new Adjustment(
                        new Target("41", "11", "2026-12-22T00:00:00+08:00", 3),
                        "ZC-000041",
                        "2026-12-20T00:00:00+08:00",
                        false,
                        true);
        return PolicyRouteSummary.forApplication(
                Long.toString(policyId), List.of(segment), List.of(change));
    }

    private static Policy policy(
            long id, int version, boolean proposed, String scene, String amount) {
        var product = new Product("8", "Reno", "黑", "2", "Reno", null, "8GB", "128GB");
        var subsidy = new SubsidyPlan("a", "FIXED", amount, null, null, null);
        return new Policy(
                Long.toString(id),
                "ZC-%06d".formatted(id),
                version,
                proposed ? null : "71",
                proposed,
                null,
                null,
                null,
                null,
                "SALES",
                List.of(scene),
                List.of(new Selection("COMMON", List.of(product), subsidy, null, 1)));
    }
}
