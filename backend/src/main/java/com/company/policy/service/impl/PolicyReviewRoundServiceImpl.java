package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.formatTime;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.policyNumber;
import static com.company.policy.support.policy.PolicyFormValidation.invalid;

import com.company.policy.common.exception.ApiException;
import com.company.policy.mapper.PolicyPurchaseReviewMapper;
import com.company.policy.mapper.PolicyReviewRoundMapper;
import com.company.policy.mapper.PolicyReviewRoundMapper.ProgressRow;
import com.company.policy.mapper.PolicyReviewRoundMapper.Round;
import com.company.policy.service.CatalogAccess;
import com.company.policy.service.PolicyReviewRoundService;
import com.company.policy.support.policy.PolicyRouteSummary;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyReviewRoundResponse;
import com.company.policy.vo.PolicyReviewRoundResponse.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class PolicyReviewRoundServiceImpl implements PolicyReviewRoundService {
    private final CatalogAccess access;
    private final PolicyReviewRoundMapper rounds;
    private final PolicyPurchaseReviewMapper purchases;
    private final PolicySnapshotCodec snapshots;
    private final ObjectMapper json;
    private final PolicyTimeHistory history;
    private final PolicyReviewProgressReader progress;

    @Override
    public com.company.policy.vo.PageResponse<SignedDecision> signed(
            CurrentUserResponse session, int page, int pageSize) {
        var user = access.requirePermission(session, "policy.product.read_own_decisions");
        long offset = PolicyApplyServiceImpl.offset(page, pageSize);
        var count = rounds.signedCount(user.getTenantKey(), user.getId());
        var items =
                offset >= count
                        ? List.<SignedDecision>of()
                        : rounds
                                .signed(user.getTenantKey(), user.getId(), pageSize, offset)
                                .stream()
                                .map(
                                        m ->
                                                new SignedDecision(
                                                        Long.toString(m.policyId()),
                                                        policyNumber(m.policyId()),
                                                        m.submissionVersion(),
                                                        m.decision(),
                                                        m.reason(),
                                                        formatTime(m.decidedAt())))
                                .toList();
        return new com.company.policy.vo.PageResponse<>(items, count);
    }

    @Override
    public PolicyReviewRoundResponse detail(CurrentUserResponse session, long id, int version) {
        var user = access.requireActive(session);
        if (version < 1) throw invalid("提交轮次须为正整数。");
        String tenant = user.getTenantKey();
        var row = rounds.visible(tenant, user.getId(), id, version);
        if (row == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "POLICY_AUDIT_NOT_FOUND", "未找到可查看的提交轮次。");
        var snapshot = snapshots.read(row.snapshotData());
        if (row.stage() == null)
            throw new ApiException(
                    HttpStatus.CONFLICT, "POLICY_REVIEW_ROUND_MISSING", "本轮缺少审核阶段，请联系管理员核对。");
        var frozenRoute = readRoute(row);
        var route = frozenRoute == null ? null : frozenRoute.forDisplay(Long.toString(id));
        var flow =
                progress.read(
                        tenant,
                        id,
                        version,
                        new ProgressRow(
                                row.stage(),
                                row.auditStatus(),
                                snapshot.applicantName(),
                                row.submittedAt(),
                                frozenRoute != null && frozenRoute.requiresProductReview()));
        String stage = flow.reviewStage();
        boolean current = stage != null && version == row.currentVersion();
        var summary =
                route == null
                        ? null
                        : new ChangeSummary(
                                route.summary(),
                                route.requiresProductReview(),
                                route.triggerSegments(),
                                route.stackSegments(),
                                route.replaceSegments(),
                                route.removeStackSegments());
        var changes =
                route == null || route.changes() == null
                        ? List.<Change>of()
                        : route.changes().stream()
                                .map(
                                        c ->
                                                new Change(
                                                        c.perspective(),
                                                        c.scope(),
                                                        formatTime(c.from()),
                                                        formatTime(c.through()),
                                                        c.kind(),
                                                        c.before(),
                                                        c.after(),
                                                        c.added(),
                                                        c.removed(),
                                                        c.retained(),
                                                        c.candidates()))
                                .toList();
        return new PolicyReviewRoundResponse(
                Long.toString(id),
                policyNumber(id),
                version,
                version == row.currentVersion()
                        ? version
                        : rounds.latestVisibleVersion(tenant, user.getId(), id),
                current || access.hasPermission(user, "policy.read.company_submitted")
                        ? row.recordVersion()
                        : null,
                flow.status(),
                stage,
                flow.rejectedAtStage(),
                flow.applicantName(),
                flow.submittedAt(),
                flow.requiresProductReview(),
                row.settingsVersion(),
                current && canDecide(tenant, user.getId(), id, version, stage)
                        ? List.of("APPROVE", "REJECT")
                        : List.of(),
                flow.productReviewers(),
                flow.purchaseReviewers(),
                snapshot,
                summary,
                changes,
                route == null || route.adjustments() == null ? List.of() : route.adjustments(),
                "APPROVED".equals(row.auditStatus())
                        ? history.read(tenant, id, version, snapshot.form().endTime())
                        : null);
    }

    private boolean canDecide(String tenant, long userId, long id, int version, String stage) {
        if ("PRODUCT_REVIEW".equals(stage))
            return rounds.productPermission(tenant, userId, id, version) != null;
        return "PURCHASE_REVIEW".equals(stage)
                && !purchases.permissions(tenant, userId, List.of(id)).isEmpty();
    }

    private PolicyRouteSummary readRoute(Round row) {
        try {
            return json.readValue(row.routeJson(), PolicyRouteSummary.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法还原本轮冻结的分流依据。", e);
        }
    }
}
