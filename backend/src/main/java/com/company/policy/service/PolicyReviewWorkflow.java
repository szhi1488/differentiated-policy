package com.company.policy.service;

import com.company.policy.entity.PolicyApply;
import com.company.policy.vo.PolicyResponse.Snapshot;
import java.time.LocalDateTime;

/** 在正式提交事务内冻结新流程的本轮分流、审核成员并登记待投递动作。 */
public interface PolicyReviewWorkflow {
    /** 新流程开启时锁定公司名单及相关用户；返回本轮须写入审核日志的流程版本。 */
    int lockForSubmission(PolicyApply entry);

    /** 重提时结办上轮修改任务；新流程另冻结首节点及任务，返回其阶段。旧流程返回空。 */
    String submitted(PolicyApply entry, Snapshot confirmed, LocalDateTime at);
}
