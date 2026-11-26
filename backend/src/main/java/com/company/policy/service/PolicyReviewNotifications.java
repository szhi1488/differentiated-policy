package com.company.policy.service;

import java.time.LocalDateTime;

/** 在审核业务事务内为群事件及每次成员指派登记待投递动作。 */
public interface PolicyReviewNotifications {
    /** 重提成功后，按原申请人的旧轮指派结办修改待办。 */
    void revisionSubmitted(
            String tenant,
            long id,
            int previousVersion,
            long memberId,
            long applicantId,
            LocalDateTime at);

    /** 按提交时冻结的首节点审核人登记初审卡或采购终审提醒及待办。 */
    void submitted(
            String tenant,
            long id,
            int version,
            boolean productReview,
            String summary,
            LocalDateTime at);

    /** 结办当前节点，向下一位办理人登记提醒及待办，终审通过时登记群公告。 */
    void decided(
            String tenant,
            long id,
            int version,
            String group,
            long actorId,
            String decision,
            String reason,
            LocalDateTime at);
}
