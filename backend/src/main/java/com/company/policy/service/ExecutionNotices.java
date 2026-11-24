package com.company.policy.service;

import com.company.policy.dto.ChannelPlatform;
import com.company.policy.entity.CatalogCleaningJob;
import com.company.policy.entity.GoodsSyncJob;
import com.company.policy.entity.PolicyApply;
import com.company.policy.mapper.CatalogCleaningMapper.JobCount;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

/** 登记执行期、平台交接及商品异常提醒，发送前重新核对范围与人员。 */
public interface ExecutionNotices {
    /** 启用调度时登记仍有效及未来的已批政策。 */
    void initialize(LocalDateTime at);

    /** 在批准事务内更新本单、受影响原单及延期子单的提醒。 */
    void approved(PolicyApply entry, Snapshot snapshot, LocalDateTime at);

    /** 在分工事务内登记指定渠道平台的交接。 */
    void platformChanged(String tenant, ChannelPlatform scope, long user, boolean assigned);

    /** 在异常暂停的事务内登记续跑提醒。 */
    void paused(GoodsSyncJob job, String code);

    /** 清洗批次结束时汇总待审及技术错误。 */
    void cleaned(CatalogCleaningJob job, List<JobCount> counts);

    /** 投递前读取当前生效状态、截止及账号。 */
    Message message(String tenant, JsonNode payload, LocalDateTime at);

    /** 返回批准当秒实际采用的平台及其负责人。 */
    Message atApproval(String tenant, long policy);

    record Message(String text, List<Long> users) {}
}
