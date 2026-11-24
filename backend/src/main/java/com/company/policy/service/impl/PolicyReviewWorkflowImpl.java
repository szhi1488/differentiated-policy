package com.company.policy.service.impl;

import com.company.policy.common.exception.ApiException;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.PolicyReviewWorkflowMapper;
import com.company.policy.service.PolicyCoverageService;
import com.company.policy.service.PolicyReviewNotifications;
import com.company.policy.service.PolicyReviewSettingsService;
import com.company.policy.service.PolicyReviewWorkflow;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class PolicyReviewWorkflowImpl implements PolicyReviewWorkflow {
    private final PolicyCoverageService coverage;
    private final PolicyReviewSettingsService settings;
    private final PolicyReviewWorkflowMapper workflow;
    private final PolicyReviewNotifications notifications;
    private final ObjectMapper json;

    @Override
    public void lockForSubmission(PolicyApply entry) {
        if (settings.lockForSubmission(entry.getTenantKey(), entry.getApplicantId()) == 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "POLICY_REVIEW_SETTINGS_REQUIRED",
                    "请管理员先配置产品运营和采购审核人，再提交本单。");
        }
    }

    @Override
    public String submitted(PolicyApply entry, Snapshot confirmed, LocalDateTime at) {
        if (entry.getCurrentVersion() > 0) closePreviousRevision(entry, at);
        var route = coverage.workflowRoute(entry, confirmed, at);
        var tenant = entry.getTenantKey();
        var assigned =
                settings.assign(tenant, entry.getApplicantId(), route.requiresProductReview());
        var stage = route.requiresProductReview() ? "PRODUCT_REVIEW" : "PURCHASE_REVIEW";
        long id = entry.getId();
        int version = confirmed.submissionVersion();
        try {
            if (workflow.insertRound(
                            tenant,
                            id,
                            version,
                            assigned.version(),
                            stage,
                            json.writeValueAsString(route),
                            at)
                    != 1) throw new IllegalStateException("无法冻结本轮审核阶段。");
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法冻结本轮分流依据。", error);
        }
        insertMembers(tenant, id, version, "PRODUCT", assigned.productReviewers());
        insertMembers(tenant, id, version, "PURCHASE", assigned.purchaseReviewers());
        notifications.submitted(
                tenant, id, version, route.requiresProductReview(), route.summary(), at);
        return stage;
    }

    private void closePreviousRevision(PolicyApply entry, LocalDateTime at) {
        String tenant = entry.getTenantKey();
        long id = entry.getId();
        int version = entry.getCurrentVersion();
        long owner = entry.getApplicantId();
        var memberId = workflow.activeRevision(tenant, id, version, owner);
        if (memberId == null) return;
        if (workflow.finishRevision(tenant, id, version, memberId, owner) != 1)
            throw new IllegalStateException("上轮修改指派已变化，请重新读取申请。");
        notifications.revisionSubmitted(tenant, id, version, memberId, owner, at);
    }

    private void insertMembers(
            String tenant, long id, int version, String group, List<UserInfo> members) {
        for (int i = 0; i < members.size(); i += 500) {
            var page = members.subList(i, Math.min(i + 500, members.size()));
            if (workflow.insertMembers(tenant, id, version, group, page) != page.size())
                throw new IllegalStateException("无法冻结本轮审核成员。");
        }
    }
}
