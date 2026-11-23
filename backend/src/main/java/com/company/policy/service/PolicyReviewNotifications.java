package com.company.policy.service;

import java.time.LocalDateTime;
import java.util.List;

/** 在审核业务事务内为群事件及每次成员指派登记待投递动作。 */
public interface PolicyReviewNotifications {
    record Assignee(long memberId, long userId) {}

    /** 改派群事件只点名变更人员。当前节点按新旧指派登记建办、结办。 */
    void reassigned(
            String tenant,
            long id,
            int version,
            int recordVersion,
            String currentStage,
            String targetStage,
            long actorId,
            String reason,
            List<Assignee> added,
            List<Assignee> removed,
            LocalDateTime at);

    /** 重提成功后，按原申请人的旧轮指派结办修改待办。 */
    void revisionSubmitted(
            String tenant,
            long id,
            int previousVersion,
            long memberId,
            long applicantId,
            LocalDateTime at);

    /** 根据提交时冻结的两组成员登记一条群通知，为首节点每个成员分别建待办。 */
    void submitted(
            String tenant,
            long id,
            int version,
            boolean productReview,
            String summary,
            LocalDateTime at);

    /** 登记节点决定、同组逐人结办、下一节点或原申请人的待办及相应群提醒。 */
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
