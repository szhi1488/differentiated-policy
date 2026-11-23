package com.company.policy.vo;

import com.company.policy.support.policy.PolicyExecutionChange.Kind;
import com.company.policy.support.policy.PolicyWorkflowProposals.Adjustment;
import com.company.policy.vo.PolicyApplicability.Policy;
import com.company.policy.vo.PolicyApplicability.Scope;
import java.util.List;

/** 按指定轮次呈现当时的政策与指派，操作和记录版本取本次读取时的实际值。 */
public record PolicyReviewRoundResponse(
        String id,
        String policyCode,
        int submissionVersion,
        int currentSubmissionVersion,
        int recordVersion,
        String status,
        String reviewStage,
        String rejectedAtStage,
        Integer settingsVersion,
        List<String> allowedActions,
        List<Member> productReviewers,
        List<Member> purchaseReviewers,
        PolicyResponse.Snapshot snapshot,
        ChangeSummary changeSummary,
        List<Change> changes,
        List<Adjustment> adjustments) {

    public record Item(
            String id,
            String policyCode,
            int submissionVersion,
            int recordVersion,
            String reviewStage,
            String summary,
            String submittedAt) {}

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
