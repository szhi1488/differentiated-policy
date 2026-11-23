package com.company.policy.vo;

import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.entity.PolicyConsent;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record PolicyResponse(
        String id, String policyCode, String status, int recordVersion, int submissionVersion) {
    public record Page<T>(List<T> items, long total, int page, int pageSize) {}

    public record Summary(
            String id,
            String policyCode,
            String status,
            int recordVersion,
            int submissionVersion,
            String perspective,
            List<String> selectedPlatforms,
            String createdAt,
            String updatedAt,
            String endTime,
            String sourcePolicyCode,
            boolean adjustmentOnly,
            String earliestEnd,
            boolean canExtend,
            boolean canShorten) {}

    public record Detail(
            String id,
            String policyCode,
            String status,
            int recordVersion,
            int submissionVersion,
            String applicantName,
            PolicyDraftRequest form,
            List<ApplicantCatalogResponse> goods,
            String sourcePolicyCode,
            List<PolicyConsent.EndAdjustment> endAdjustments,
            ApprovedEnd approvedEnd,
            String adjustmentSourceEnd) {}

    public record ApprovedEnd(
            String requestPolicyCode,
            String targetAuditId,
            String actualEnd,
            String approvedAt,
            PolicyConsent.EndAdjustment change) {}

    public record Snapshot(
            String id,
            String policyCode,
            String applicantId,
            String applicantName,
            int submissionVersion,
            PolicyDraftRequest form,
            List<ApplicantCatalogResponse> goods,
            @JsonInclude(JsonInclude.Include.NON_NULL) PolicyConsent consent,
            @JsonInclude(JsonInclude.Include.NON_NULL) String sourcePolicyCode) {
        public Snapshot(
                String id,
                String policyCode,
                String applicantId,
                String applicantName,
                int submissionVersion,
                PolicyDraftRequest form,
                List<ApplicantCatalogResponse> goods) {
            this(
                    id,
                    policyCode,
                    applicantId,
                    applicantName,
                    submissionVersion,
                    form,
                    goods,
                    null,
                    null);
        }

        public Snapshot(
                String id,
                String policyCode,
                String applicantId,
                String applicantName,
                int submissionVersion,
                PolicyDraftRequest form,
                List<ApplicantCatalogResponse> goods,
                PolicyConsent consent) {
            this(
                    id,
                    policyCode,
                    applicantId,
                    applicantName,
                    submissionVersion,
                    form,
                    goods,
                    consent,
                    null);
        }

        public Snapshot withConsent(PolicyConsent value) {
            return new Snapshot(
                    id,
                    policyCode,
                    applicantId,
                    applicantName,
                    submissionVersion,
                    form,
                    goods,
                    value,
                    sourcePolicyCode);
        }
    }

    public record SubmissionPreview(
            Snapshot snapshot,
            PolicyReviewResponse.Coverage coverage,
            String confirmationToken,
            PolicyApplicability applicability) {}

    public record AuditSummary(
            String id,
            int submissionVersion,
            String auditStatus,
            String auditorName,
            String auditComment,
            String auditTime,
            String submittedAt) {}

    public record AuditDetail(AuditSummary log, Snapshot snapshot) {}
}
