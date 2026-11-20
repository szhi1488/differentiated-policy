package com.company.policy.service;

import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import com.company.policy.mapper.PolicyApplyMapper.DetailLine;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.company.policy.vo.PolicyReviewResponse.Coverage;
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

    /** 根据已提交轮次的状态，计算待审预览或已通过的生效范围。 */
    Coverage submitted(PolicyApply entry, PolicyAuditLog log, Snapshot snapshot);
}
