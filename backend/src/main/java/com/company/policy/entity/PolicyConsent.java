package com.company.policy.entity;

import com.company.policy.vo.PolicyApplicability.Action;
import com.company.policy.vo.PolicyApplicability.Group;
import com.company.policy.vo.PolicyApplicability.Scope;
import java.util.List;

/** 本轮提交时由服务端核验并冻结的范围和选择。直提轮次也保存版本和空分段。 */
public record PolicyConsent(
        int ruleVersion, List<ComparedRound> comparedRounds, List<Decision> decisions) {
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
