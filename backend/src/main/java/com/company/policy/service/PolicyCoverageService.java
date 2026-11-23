package com.company.policy.service;

import com.company.policy.dto.ChannelPlatform;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyTimelineQuery;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import com.company.policy.mapper.PolicyApplyMapper.DetailLine;
import com.company.policy.support.policy.PolicyRouteSummary;
import com.company.policy.vo.PolicyApplicability;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.company.policy.vo.PolicyReviewResponse.Coverage;
import com.company.policy.vo.PolicyReviewResponse.Effective;
import com.company.policy.vo.PolicySubsidyTimeline;
import java.time.LocalDateTime;
import java.util.List;

/** 按已鉴权申请的商品和平台计算政策覆盖范围。 */
public interface PolicyCoverageService {
    /** 按草稿实际商品与平台，对照同一申请人、同一渠道在预计生效时点已通过且满足开始时间的政策。 */
    Coverage previewDraft(
            PolicyApply entry,
            Snapshot snapshot,
            List<DetailLine> lines,
            LocalDateTime submittedAt);

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

    /** 查询某一时刻已通过并按确认决定生效的全部政策。 */
    Effective effective(
            String tenant, String channel, String platform, long goodsId, LocalDateTime at);

    /** 仅在调用方已授权的渠道平台内回放原单和逐段决定，空范围返回空轨道。 */
    PolicySubsidyTimeline subsidyTimeline(
            String tenant, PolicyTimelineQuery query, List<ChannelPlatform> scopes);

    /** 根据已提交轮次的状态，计算待审预览或已通过的生效范围。 */
    Coverage submitted(PolicyApply entry, PolicyAuditLog log, Snapshot snapshot);
}
