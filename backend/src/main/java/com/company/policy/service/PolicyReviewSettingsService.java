package com.company.policy.service;

import com.company.policy.dto.PolicyReviewSettingsUpdate;
import com.company.policy.entity.UserInfo;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyReviewSettings;

/** 管理公司审核人配置，并在提交事务中按当前资格核定本轮人员。 */
public interface PolicyReviewSettingsService {
    /** 管理员读取本企业现行名单及版本。 */
    PolicyReviewSettings read(CurrentUserResponse session);

    /** 管理员按期望版本保存品牌和系列的预设分工。 */
    PolicyReviewSettings save(CurrentUserResponse session, PolicyReviewSettingsUpdate update);

    /** 在提交事务取得平台或机型锁前锁定企业设置、当前品牌和系列涉及的账号及本人并返回设置版本。 */
    int lockForSubmission(String tenantKey, long applicantId, Long brandId, Long seriesId);

    /** 按同一事务内的配置，校验预设办理人当前资格及两关分离。 */
    Assignment assign(
            String tenantKey,
            long applicantId,
            Long brandId,
            Long seriesId,
            boolean needsProductReview);

    record Assignment(
            int version, UserInfo productReviewer, UserInfo purchaseReviewer, String productMode) {}
}
