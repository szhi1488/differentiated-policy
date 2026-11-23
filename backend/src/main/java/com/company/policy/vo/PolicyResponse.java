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
            String updatedAt) {}

    public record Detail(
            String id,
            String policyCode,
            String status,
            int recordVersion,
            int submissionVersion,
            String applicantName,
            PolicyDraftRequest form,
            List<ApplicantCatalogResponse> goods) {}

    public record Snapshot(
            String id,
            String policyCode,
            String applicantId,
            String applicantName,
            int submissionVersion,
            PolicyDraftRequest form,
            List<ApplicantCatalogResponse> goods,
            @JsonInclude(JsonInclude.Include.NON_NULL) PolicyConsent consent) {
        public Snapshot(
                String id,
                String policyCode,
                String applicantId,
                String applicantName,
                int submissionVersion,
                PolicyDraftRequest form,
                List<ApplicantCatalogResponse> goods) {
            this(id, policyCode, applicantId, applicantName, submissionVersion, form, goods, null);
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
                    value);
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
