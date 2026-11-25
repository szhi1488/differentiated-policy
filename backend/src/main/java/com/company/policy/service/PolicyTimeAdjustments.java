package com.company.policy.service;

import com.company.policy.dto.PolicyDraftRequest.Choice;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyApplicability;
import com.company.policy.vo.PolicyApplicability.AdjustmentOption;
import com.company.policy.vo.PolicyResponse;
import java.util.List;

/** 供申请人直接调整有权办理的一张或多张已通过政策。 */
public interface PolicyTimeAdjustments {
    record Preview(String confirmationToken, PolicyApplicability applicability) {}

    /** 按申请人当前负责平台及原单归属返回允许缩短或延长的政策。 */
    List<AdjustmentOption> options(CurrentUserResponse session);

    /** 核对每张原单和调整原因，计算整批延期与其他政策交叉时的候选。 */
    Preview preview(CurrentUserResponse session, List<EndAdjustment> changes);

    /** 核验本批凭证和逐段选择，原子保存已通过的调整记录并登记群通知。 */
    List<PolicyResponse> apply(
            CurrentUserResponse session,
            List<EndAdjustment> changes,
            String confirmationToken,
            List<Choice> decisions,
            String requestKey);
}
