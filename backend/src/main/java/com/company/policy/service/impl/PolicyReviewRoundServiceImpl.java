package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.formatTime;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.policyNumber;
import static com.company.policy.support.policy.PolicyFormValidation.invalid;

import com.company.policy.common.exception.ApiException;
import com.company.policy.mapper.PolicyPurchaseReviewMapper;
import com.company.policy.mapper.PolicyReviewRoundMapper;
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
        boolean pending = "SUBMITTED".equals(row.auditStatus());
        String stage = pending ? row.stage() : null;
        boolean current = pending && version == row.currentVersion();
        var members = rounds.members(tenant, id, version);
        String rejected = null;
        if ("REJECTED".equals(row.auditStatus())) {
            rejected =
                    members.stream()
                            .filter(m -> "REJECT".equals(m.decision()))
                            .map(
                                    m ->
                                            "PRODUCT".equals(m.groupCode())
                                                    ? "PRODUCT_REVIEW"
                                                    : "PURCHASE_REVIEW")
                            .findFirst()
                            .orElse(null);
        }
        var route = readRoute(row, id);
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
                row.currentVersion(),
                row.recordVersion(),
                pending ? "PENDING" : row.auditStatus(),
                stage,
                rejected,
                row.settingsVersion(),
                current && canDecide(tenant, user.getId(), id, version, stage)
                        ? List.of("APPROVE", "REJECT")
                        : List.of(),
                members(members, "PRODUCT"),
                members(members, "PURCHASE"),
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

    private PolicyRouteSummary readRoute(Round row, long id) {
        try {
            var route = json.readValue(row.routeJson(), PolicyRouteSummary.class);
            return route == null ? null : route.forDisplay(Long.toString(id));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法还原本轮冻结的分流依据。", e);
        }
    }

    private static List<Member> members(List<PolicyReviewRoundMapper.Member> all, String group) {
        return all.stream()
                .filter(m -> group.equals(m.groupCode()))
                .map(
                        m ->
                                new Member(
                                        Long.toString(m.id()),
                                        Long.toString(m.userId()),
                                        m.displayName(),
                                        m.department(),
                                        m.assignmentNo(),
                                        m.memberStatus(),
                                        m.decision(),
                                        m.reason(),
                                        formatTime(m.decidedAt())))
                .toList();
    }
}
