package com.company.policy.vo;

import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
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
            String sceneType,
            String submittedAt,
            String status,
            int recordVersion,
            int submissionVersion,
            boolean reviewable) {}

    public record Page(
            List<Summary> items, long total, int page, int pageSize, Map<String, Long> counts) {}

    public record Detail(
            Summary policy, Snapshot snapshot, AuditSummary audit, Coverage coverage) {}

    public record Review(
            String id,
            String status,
            int recordVersion,
            int submissionVersion,
            String reviewTime) {}

    public record Coverage(String at, String effectiveFrom, List<CoverageItem> items) {}

    public record CoverageItem(
            String platformCode,
            String goodsId,
            String outcome,
            Winner winner,
            SubsidyPlan existingSubsidy) {}

    public record Winner(
            String id,
            String policyCode,
            String auditLogId,
            int submissionVersion,
            String submittedAt,
            String effectiveFrom) {}

    public record Effective(String at, Winner winner, Snapshot snapshot) {}
}
