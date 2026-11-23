package com.company.policy.service;

import com.company.policy.dto.PolicyReviewSettingsUpdate;
import com.company.policy.entity.UserInfo;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyReviewSettings;
import java.util.List;

/** 管理公司审核人配置，并在提交事务中按当前资格核定本轮人员。 */
public interface PolicyReviewSettingsService {
    /** 管理员读取本企业现行名单及版本。 */
    PolicyReviewSettings read(CurrentUserResponse session);

    /** 管理员按期望版本保存两组本企业真实人员。 */
    PolicyReviewSettings save(CurrentUserResponse session, PolicyReviewSettingsUpdate update);

    /** 在提交事务取得平台或机型锁前锁定企业设置、全部候选账号及本人并返回设置版本。 */
    int lockForSubmission(String tenantKey, long applicantId);

    /** 按同一事务内的配置，去掉失效和自审人员并核定能够分别签署本轮的两组人员。 */
    Assignment assign(String tenantKey, long applicantId, boolean needsProductReview);

    record Assignment(
            int version, List<UserInfo> productReviewers, List<UserInfo> purchaseReviewers) {}
}
