package com.company.policy.vo;

import com.company.policy.support.policy.PolicyExecutionChange.Kind;
import com.company.policy.support.policy.PolicyWorkflowProposals.Adjustment;
import com.company.policy.vo.PolicyApplicability.Policy;
import com.company.policy.vo.PolicyApplicability.Scope;
import java.util.List;

/** 按指定轮次呈现政策与指派。最近轮次按读者权限确定，办理时使用当前并发版本。 */
public record PolicyReviewRoundResponse(
        String id,
        String policyCode,
        int submissionVersion,
        int currentSubmissionVersion,
        Integer recordVersion,
        String status,
        String reviewStage,
        String rejectedAtStage,
        String applicantName,
        String submittedAt,
        boolean requiresProductReview,
        Integer settingsVersion,
        List<String> allowedActions,
        List<Member> productReviewers,
        List<Member> purchaseReviewers,
        PolicyResponse.Snapshot snapshot,
        ChangeSummary changeSummary,
        List<Change> changes,
        List<Adjustment> adjustments,
        PolicyResponse.TimeHistory timeHistory) {

    public record Progress(
            int submissionVersion,
            String status,
            String reviewStage,
            String rejectedAtStage,
            String applicantName,
            String submittedAt,
            boolean requiresProductReview,
            List<Member> productReviewers,
            List<Member> purchaseReviewers) {}

    public record Member(
            String assignmentId,
            String userId,
            String name,
            String department,
            int assignmentNo,
            String memberStatus,
            String decision,
            String reason,
            String decidedAt) {}

    public record ChangeSummary(
            String summary,
            boolean requiresProductReview,
            int triggerSegments,
            int stackSegments,
            int replaceSegments,
            int removeStackSegments) {}

    public record Change(
            String perspective,
            Scope scope,
            String from,
            String through,
            Kind kind,
            List<Policy> before,
            List<Policy> after,
            List<Policy> added,
            List<Policy> removed,
            List<Policy> retained,
            List<Policy> candidates) {}
}
