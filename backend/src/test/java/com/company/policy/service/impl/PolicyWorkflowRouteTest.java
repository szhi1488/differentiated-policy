package com.company.policy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyDraftRequest.Card;
import com.company.policy.dto.PolicyDraftRequest.Choice;
import com.company.policy.dto.PolicyDraftRequest.GoodsForm;
import com.company.policy.dto.PolicyDraftRequest.PlatformAssignment;
import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.dto.PolicyDraftRequest.Target;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import com.company.policy.entity.PolicyConsent;
import com.company.policy.mapper.PolicyReviewMapper;
import com.company.policy.mapper.UserPlatformMapper;
import com.company.policy.support.policy.PolicyBatchExtensions;
import com.company.policy.support.policy.PolicyConfirmation;
import com.company.policy.support.policy.PolicyFormValidation;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.vo.ApplicantCatalogResponse;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyWorkflowRouteTest {
    private static final LocalDateTime SUBMISSION = LocalDateTime.of(2026, 11, 23, 12, 0);
    private static final List<String> PLATFORMS = List.of("JD", "TAOBAO");
    private static final PolicyConsent DIRECT = new PolicyConsent(2, List.of(), List.of());
    private final PolicyReviewMapper reviews = mock(PolicyReviewMapper.class);
    private final PolicySnapshotCodec snapshots = new PolicySnapshotCodec();
    private final PolicyCoverageServiceImpl coverage =
            new PolicyCoverageServiceImpl(
                    reviews,
                    mock(UserPlatformMapper.class),
                    snapshots,
                    mock(PolicyBatchExtensions.class),
                    new ObjectMapper(),
                    "");
    private final PolicyApply entry = new PolicyApply();
    private final PolicyAuditLog approved = new PolicyAuditLog();
    private final Snapshot proposed = snapshot("128", "LOCAL_SUB", true);

    @BeforeEach
    void prepare() {
        entry.setTenantKey("company");
        entry.setApplicantId(7L);
        entry.setId(128L);
        approved.setId(42L);
        approved.setPolicyId(96L);
        approved.setVersion(1);
        approved.setCreatedAt(SUBMISSION.minusDays(3));
        approved.setAuditTime(SUBMISSION.minusDays(1));
        approved.setSnapshotData(snapshots.write(snapshot("96", "NATIONAL_SUB", false)));
        when(reviews.overlappingAuditIds(
                        eq("company"),
                        eq(128L),
                        eq("RETAIL"),
                        eq(SUBMISSION),
                        anyString(),
                        anyString(),
                        anyList(),
                        eq(false),
                        anyBoolean()))
                .thenReturn(List.of(42L));
        when(reviews.coverageSnapshots("company", List.of(42L))).thenReturn(List.of(approved));
    }

    @Test
    void oneRetailPlatformStacksWhileAnotherKeepsItsApprovedPolicy() {
        var preview = coverage.applicability(entry, proposed, SUBMISSION);
        var choices =
                PolicyConfirmation.ranges(preview).stream()
                        .map(
                                range ->
                                        new Choice(
                                                range.scope(),
                                                range.through(),
                                                "SELECT",
                                                null,
                                                range.scope().platformCode().equals("JD")
                                                        ? List.of("96", "128")
                                                        : List.of("96")))
                        .toList();
        var confirmed = proposed.withConsent(PolicyConfirmation.confirm(preview, choices));
        var result = coverage.workflowRoute(entry, confirmed, SUBMISSION);
        assertThat(result.requiresProductReview()).isTrue();
        assertThat(result.triggerSegments()).isEqualTo(2);
        assertThat(result.stackSegments()).isOne();
        assertThat(result.changes()).hasSize(2);
        assertThat(result.summary())
                .contains(
                        "京东零售",
                        "Reno17 Pro",
                        "浮光白",
                        "2026-12-01 00:00:00",
                        "2026-12-05 23:59:59",
                        "ZC-000096",
                        "20 元/台",
                        "ZC-000128",
                        "到手价",
                        "5%",
                        "上限 30 元",
                        "数量 0 台",
                        "淘宝零售",
                        "沿用原单ZC-000096")
                .doesNotContain("合计");
    }

    @Test
    void unreadableApprovedSnapshotCannotProduceAnOrdinaryRoute() {
        approved.setSnapshotData("{}");
        assertThatThrownBy(() -> coverage.workflowRoute(entry, proposed, SUBMISSION))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error ->
                                assertThat(error.getCode()).isEqualTo("POLICY_RECONFIRM_REQUIRED"));
    }

    @Test
    void aDisappearingApprovedRoundRequiresReconfirmation() {
        when(reviews.coverageSnapshots("company", List.of(42L))).thenReturn(List.of());
        assertThatThrownBy(() -> coverage.workflowRoute(entry, proposed, SUBMISSION))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error ->
                                assertThat(error.getCode()).isEqualTo("POLICY_RECONFIRM_REQUIRED"));
    }

    @Test
    void missingSnapshotDatesInAdjustmentCandidatesRequireReconfirmation() {
        var row =
                new PolicyReviewMapper.AdjustmentCandidate(
                        96L,
                        42L,
                        "RETAIL",
                        SUBMISSION.minusDays(1),
                        7L,
                        "[\"JD\"]",
                        false,
                        null,
                        null,
                        null);
        when(reviews.adjustmentCandidates("company", 7L, SUBMISSION, "2026-11-23"))
                .thenReturn(List.of(row));
        assertThatThrownBy(() -> coverage.workflowRoute(entry, proposed, SUBMISSION))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error ->
                                assertThat(error.getCode()).isEqualTo("POLICY_RECONFIRM_REQUIRED"));
    }

    private static Snapshot snapshot(String id, String scene, boolean ratio) {
        var subsidy =
                ratio
                        ? new SubsidyPlan("plan", "RATIO", null, null, "5.00", "HAND_PRICE")
                        : new SubsidyPlan("plan", "FIXED", "20.00", "30.00", null, null);
        var target = new Target("81", "12GB", "256GB", "8001");
        var assignments =
                PLATFORMS.stream()
                        .map(p -> new PlatformAssignment(p, "plan", ratio ? 0 : null))
                        .toList();
        var card =
                new Card(
                        "card",
                        ratio ? "SPECIFIED" : "COMMON",
                        true,
                        List.of(target),
                        List.of(subsidy),
                        assignments);
        var form =
                new PolicyDraftRequest(
                        null,
                        "RETAIL",
                        PLATFORMS,
                        "SALES",
                        scene,
                        List.of(scene),
                        false,
                        "2026-12-01T00:00:00+08:00",
                        "2026-12-05T23:59:59+08:00",
                        null,
                        List.of(),
                        new GoodsForm(List.of(card)));
        var product =
                new ApplicantCatalogResponse(
                        "8001",
                        "1",
                        "OPPO",
                        "3",
                        "Reno",
                        "81",
                        "Reno17 Pro",
                        null,
                        "12GB",
                        "256GB",
                        "Reno17 Pro 浮光白",
                        "浮光白",
                        true);
        return new Snapshot(
                id,
                PolicyApplyServiceImpl.policyNumber(Long.parseLong(id)),
                "7",
                "申请人",
                1,
                PolicyFormValidation.validate(form, true),
                List.of(product),
                DIRECT,
                null);
    }
}
