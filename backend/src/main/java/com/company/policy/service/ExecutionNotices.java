package com.company.policy.service;

import com.company.policy.dto.ChannelPlatform;
import com.company.policy.entity.GoodsSyncJob;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyTimeChange;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

/** 登记执行期、平台交接及商品导入暂停提醒，发送前重新核对范围与人员。 */
public interface ExecutionNotices {
    String EXPIRY_REMINDER = "EXPIRY_REMINDER";

    /** 启用调度时登记仍有效及未来的已批政策。 */
    void initialize(LocalDateTime at);

    /** 在批准事务内登记本次新申请的执行提醒。 */
    void approved(PolicyApply entry, Snapshot snapshot, LocalDateTime at);

    /** 在时间调整直接生效的事务内登记该项群公告并重算执行期提醒。 */
    void timeAdjusted(PolicyTimeChange change);

    /** 在分工事务内登记指定渠道平台的交接。 */
    void platformChanged(
            String tenant, ChannelPlatform scope, Long previousSubmitter, Long nextSubmitter);

    /** 在异常暂停的事务内登记续跑提醒。 */
    void paused(GoodsSyncJob job, String code);

    /** 投递前读取当前生效状态、截止及账号。 */
    Message message(String tenant, JsonNode payload, LocalDateTime at);

    /** 返回批准当秒实际采用的平台及其负责人。 */
    Message atApproval(String tenant, long policy);

    record Message(String text, List<Long> users) {}
}
