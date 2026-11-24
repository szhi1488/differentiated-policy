package com.company.policy.service;

import com.company.policy.dto.ChannelPlatform;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyTimelineQuery;
import com.company.policy.entity.PolicyApply;
import com.company.policy.support.policy.PolicyRouteSummary;
import com.company.policy.vo.PolicyApplicability;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.company.policy.vo.PolicyReviewResponse.Effective;
import com.company.policy.vo.PolicySubsidyTimeline;
import java.time.LocalDateTime;
import java.util.List;

/** 按已鉴权申请的商品和平台计算政策覆盖范围。 */
public interface PolicyCoverageService {
    /** 只读区间试算，涵盖当前负责范围内同公司其他申请人的已通过轮次。 */
    PolicyApplicability applicability(PolicyApply entry, Snapshot snapshot, LocalDateTime asOf);

    /** 按已提交的时间和轮次顺序复核申请人的决定。 */
    PolicyApplicability applicability(
            PolicyApply entry,
            Snapshot snapshot,
            LocalDateTime asOf,
            LocalDateTime submittedAt,
            long auditId);

    /** 按已鉴权申请、本次提交时点及服务端已确认的整轮快照计算分流理由和摘要。 */
    PolicyRouteSummary workflowRoute(PolicyApply entry, Snapshot confirmed, LocalDateTime asOf);

    /** 在写事务中按相同顺序锁定选中机型，覆盖同配置不同颜色及多实例并发。 */
    void lockScope(
            PolicyApply entry,
            PolicyDraftRequest form,
            List<com.company.policy.entity.PolicyConsent.EndAdjustment> changes);

    List<PolicyApplicability.AdjustmentOption> adjustableFor(
            PolicyApply applicant,
            LocalDateTime asOf,
            java.util.Map<String, com.company.policy.vo.PolicyResponse.ApprovedEnd> ends);

    /** 按目标政策 ID 返回截至指定时点已通过的整单提前结束决定。 */
    java.util.Map<String, com.company.policy.vo.PolicyResponse.ApprovedEnd> approvedEnds(
            String tenant, LocalDateTime asOf);

    /** 只取目标原单当前获批轮次的实际截止。若传入审核日志 ID，必须与当前获批轮次相符。 */
    com.company.policy.vo.PolicyResponse.ApprovedEnd approvedEnd(
            String tenant, long targetId, Long targetAuditId, LocalDateTime asOf);

    /** 查询某一时刻已通过并按确认决定生效的全部政策。 */
    Effective effective(
            String tenant, String channel, String platform, long goodsId, LocalDateTime at);

    /** 一次回放多个范围，返回指定获批原单在该时点实际采用的商品或配置范围。 */
    List<PolicyApplicability.Scope> adoptedScopes(
            String tenant,
            long policyId,
            String channel,
            List<PolicyApplicability.Scope> scopes,
            LocalDateTime at);

    /** 仅在调用方已授权的渠道平台内回放原单和逐段决定，空范围返回空轨道。 */
    PolicySubsidyTimeline subsidyTimeline(
            String tenant, PolicyTimelineQuery query, List<ChannelPlatform> scopes);
}
