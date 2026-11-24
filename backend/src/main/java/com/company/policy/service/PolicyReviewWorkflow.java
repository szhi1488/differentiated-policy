package com.company.policy.service;

import com.company.policy.entity.PolicyApply;
import com.company.policy.vo.PolicyResponse.Snapshot;
import java.time.LocalDateTime;

/** 在正式提交事务内冻结新流程的本轮分流、审核成员并登记待投递动作。 */
public interface PolicyReviewWorkflow {
    /** 锁定本次提交所需的公司审核名单。 */
    void lockForSubmission(PolicyApply entry);

    /** 重提时结办上轮修改任务，冻结本轮名单和首节点，返回当前审核阶段。 */
    String submitted(PolicyApply entry, Snapshot confirmed, LocalDateTime at);
}
