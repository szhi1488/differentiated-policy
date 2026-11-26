package com.company.policy.vo;

import com.company.policy.entity.PolicyConsent;
import com.company.policy.vo.PolicyResponse.AuditSummary;
import com.company.policy.vo.PolicyResponse.Snapshot;
import java.util.List;
import java.util.Map;

public final class PolicyReviewResponse {
    private PolicyReviewResponse() {}

    public record Summary(
            String id,
            String policyCode,
            String applicant,
            String perspective,
            List<String> platforms,
            String verifyType,
            List<String> sceneTypes,
            String submittedAt,
            String status,
            Integer recordVersion,
            int submissionVersion,
            boolean reviewable,
            String reviewStage,
            String productReviewerName,
            String productReviewedAt) {}

    public record Page(
            List<Summary> items, long total, int page, int pageSize, Map<String, Long> counts) {}

    public record Detail(
            Summary policy,
            Snapshot snapshot,
            AuditSummary audit,
            PolicyResponse.TimeHistory timeHistory) {}

    public record Review(
            String id,
            String status,
            int recordVersion,
            int submissionVersion,
            String reviewTime,
            String reviewStage) {}

    public record Winner(
            String id,
            String policyCode,
            String auditLogId,
            int submissionVersion,
            String submittedAt,
            String effectiveFrom) {}

    public record Applied(
            Winner winner, Snapshot snapshot, List<PolicyApplicability.Selection> selections) {}

    public record Effective(String at, List<Applied> policies) {}

    public record OverridePreview(
            PolicyApplicability applicability,
            PolicyConsent consent,
            boolean requiresResubmission,
            String nextAction) {}
}
