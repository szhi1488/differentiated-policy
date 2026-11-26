package com.company.policy.service;

import com.company.policy.dto.ChannelPlatform;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyTimelineQuery;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.entity.PolicyConsent.Target;
import com.company.policy.support.policy.PolicyRouteSummary;
import com.company.policy.vo.PolicyApplicability;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.company.policy.vo.PolicyReviewResponse.Effective;
import com.company.policy.vo.PolicySubsidyTimeline;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    /** 独立调整没有在填表单，按整批原单锁定所有相关机型和延期商品。 */
    void lockAdjustmentScope(PolicyApply applicant, List<EndAdjustment> changes);

    /** 将同批延期作为拟生效政策、缩短作为历史截止变更，一起计算逐段选择。 */
    PolicyApplicability adjustmentCoverage(
            PolicyApply applicant,
            List<Snapshot> extensions,
            Map<Target, PolicyApplicability.AdjustmentOption> options,
            LocalDateTime at);

    List<PolicyApplicability.AdjustmentOption> adjustableFor(
            PolicyApply applicant, LocalDateTime asOf);

    /** 在可读品牌内查询指定商品在某一时刻实际生效的政策。 */
    Effective effective(
            String tenant,
            String channel,
            String platform,
            long goodsId,
            LocalDateTime at,
            List<String> brandNames);

    /** 一次回放多个范围，返回指定获批原单在该时点实际采用的商品或配置范围。 */
    List<PolicyApplicability.Scope> adoptedScopes(
            String tenant,
            long policyId,
            String channel,
            List<PolicyApplicability.Scope> scopes,
            LocalDateTime at);

    /** 在可读品牌和渠道平台内回放原单和逐段决定。品牌为 null 时可查询全部品牌。 */
    PolicySubsidyTimeline subsidyTimeline(
            String tenant,
            PolicyTimelineQuery query,
            List<ChannelPlatform> scopes,
            List<String> brandNames);
}
