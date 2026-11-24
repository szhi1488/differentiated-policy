package com.company.policy.vo;

import com.company.policy.vo.PolicyApplicability.Selection;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 已授权渠道平台的机型补贴历史。各轨道的区间引用同一轨道内的审核日志 ID。 */
public record PolicySubsidyTimeline(
        String asOf,
        @Schema(description = "当前配置的标准商品总数，含下架商品。无授权查询范围时为 0") int configurationGoodsCount,
        List<Track> tracks) {
    public record Track(
            String perspective,
            String platformCode,
            @Schema(description = "轨道适用商品。百亿按整组配置判定时为空数组") List<String> goodsIds,
            List<Policy> policies,
            List<Segment> segments,
            @Schema(description = "按查询时已通过的逐段决定计算的后续计划，结束时点右开") List<Segment> plannedSegments) {}

    public record Policy(
            String policyId,
            String policyCode,
            String auditLogId,
            int submissionVersion,
            String policyFrom,
            String policyThrough,
            String approvedAt,
            String verifyType,
            List<String> sceneTypes,
            Boolean estimated,
            @Schema(description = "有效期与查询相交的原单。false 表示仅供其他原单完整期间的时点对照") boolean matchesQuery,
            List<Selection> selections) {}

    public record Segment(
            String startAt,
            @Schema(description = "右开结束时点。null 表示结果仅确知到 asOf 当秒") String endAt,
            List<String> adoptedAuditLogIds,
            List<String> excludedAuditLogIds) {}
}
