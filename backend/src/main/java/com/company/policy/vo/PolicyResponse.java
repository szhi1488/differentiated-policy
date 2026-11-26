package com.company.policy.vo;

import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.entity.PolicyConsent;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record PolicyResponse(
        String id,
        String policyCode,
        String status,
        int recordVersion,
        int submissionVersion,
        @JsonInclude(JsonInclude.Include.NON_NULL) String reviewStage) {
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
            String reviewStage) {}

    public record Detail(
            String id,
            String policyCode,
            String status,
            int recordVersion,
            int submissionVersion,
            String applicantName,
            PolicyDraftRequest form,
            List<ApplicantCatalogResponse> goods,
            List<PolicyConsent.EndAdjustment> endAdjustments,
            TimeHistory timeHistory,
            String reviewStage,
            PolicyReviewRoundResponse.Progress reviewProgress) {}

    public record TimeHistory(String initialEnd, String currentEnd, List<TimeChange> changes) {}

    public record TimeChange(
            String id,
            String direction,
            String previousEnd,
            String requestedEnd,
            String actualEnd,
            String effectiveAt,
            String changedAt,
            String actorName,
            String reason,
            String sourcePolicyCode,
            List<PolicyDraftRequest.Link> attachmentInfo) {}

    public record Snapshot(
            String id,
            String policyCode,
            String applicantId,
            String applicantName,
            int submissionVersion,
            PolicyDraftRequest form,
            List<ApplicantCatalogResponse> goods,
            @JsonInclude(JsonInclude.Include.NON_NULL) PolicyConsent consent,
            @JsonInclude(JsonInclude.Include.NON_NULL) String sourcePolicyCode,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean wholeConfigurationSelection) {
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
                    null,
                    false);
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
                    null,
                    false);
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
                    sourcePolicyCode,
                    wholeConfigurationSelection);
        }
    }

    public record SubmissionPreview(
            Snapshot snapshot, String confirmationToken, PolicyApplicability applicability) {}

    public record AuditSummary(
            String id,
            int submissionVersion,
            String auditStatus,
            String auditorName,
            String auditComment,
            String auditTime,
            String submittedAt,
            String rejectedAtStage) {}

    public record AuditDetail(
            AuditSummary log,
            Snapshot snapshot,
            PolicyReviewRoundResponse.Progress reviewProgress) {}
}
