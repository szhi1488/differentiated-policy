package com.company.policy.entity;

import com.company.policy.dto.PolicyDraftRequest.Link;
import com.company.policy.vo.PolicyApplicability.Action;
import com.company.policy.vo.PolicyApplicability.Group;
import com.company.policy.vo.PolicyApplicability.Scope;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 本轮提交时由服务端核验并冻结的范围和选择。直提轮次也保存版本和空分段。 */
public record PolicyConsent(
        int ruleVersion,
        List<ComparedRound> comparedRounds,
        List<Decision> decisions,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<EndAdjustment> endAdjustments) {
    public boolean supported() {
        return ruleVersion >= 2 && ruleVersion <= 4;
    }

    public PolicyConsent {
        endAdjustments = endAdjustments == null ? List.of() : List.copyOf(endAdjustments);
    }

    public PolicyConsent(
            int ruleVersion, List<ComparedRound> comparedRounds, List<Decision> decisions) {
        this(ruleVersion, comparedRounds, decisions, List.of());
    }

    /** 原政策、获批轮次和当前记录版本共同构成本次调整的核对依据。 */
    public record Target(
            String policyId, String auditLogId, String previousEnd, Integer recordVersion) {
        public Target(String policyId, String auditLogId, String previousEnd) {
            this(policyId, auditLogId, previousEnd, null);
        }
    }

    public record EndAdjustment(
            Target target,
            String endTime,
            String reason,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Link> attachmentInfo) {
        public EndAdjustment {
            attachmentInfo = attachmentInfo == null ? List.of() : List.copyOf(attachmentInfo);
        }

        public EndAdjustment(Target target, String endTime, String reason) {
            this(target, endTime, reason, List.of());
        }
    }

    public record ComparedRound(String policyId, int submissionVersion, String auditLogId) {}

    public record Decision(
            Scope scope,
            String from,
            String through,
            Action action,
            List<Group> groups,
            List<String> eligiblePolicyIds,
            List<String> selectedPolicyIds) {}
}
