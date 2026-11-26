package com.company.policy.service;

import com.company.policy.dto.ChannelPlatform;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyListQuery;
import com.company.policy.dto.PolicyTimelineQuery;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyResponse;
import com.company.policy.vo.PolicyResponse.AuditDetail;
import com.company.policy.vo.PolicyResponse.AuditSummary;
import com.company.policy.vo.PolicyResponse.Detail;
import com.company.policy.vo.PolicyResponse.Page;
import com.company.policy.vo.PolicyResponse.Summary;
import com.company.policy.vo.PolicySubsidyTimeline;
import java.util.List;

/** 供申请人保存草稿、提交政策，并查询本人的申请和审核记录。 */
public interface PolicyApplyService {
    List<ChannelPlatform> assignedPlatforms(CurrentUserResponse session, String usage);

    /** 按申请人当前负责的平台分工查询机型补贴历史和后续计划。 */
    PolicySubsidyTimeline subsidyTimeline(CurrentUserResponse session, PolicyTimelineQuery query);

    /** 为当前申请人首次保存政策草稿，可用请求标识核对重复创建。 */
    PolicyResponse create(
            CurrentUserResponse session, PolicyDraftRequest request, String requestKey);

    /** 校验记录版本后，保存本人草稿或已驳回申请的修改。 */
    PolicyResponse save(CurrentUserResponse session, long id, PolicyDraftRequest request);

    /** 核对版本后，删除本人从未正式提交过的草稿。 */
    void deleteDraft(CurrentUserResponse session, long id, Integer recordVersion);

    /** 读取本人拟提交整单在当前负责平台的区间判定和本轮确认标识。 */
    PolicyResponse.SubmissionPreview preview(
            CurrentUserResponse session, long id, Integer recordVersion);

    /** 按整张旧政策提前结束的申请重新试算交叉时段。 */
    PolicyResponse.SubmissionPreview previewAdjusted(
            CurrentUserResponse session, long id, PolicyDraftRequest.AdjustmentPreview request);

    /** 校验已保存的申请并正式提交，生成本轮提交快照和商品明细。 */
    PolicyResponse submit(
            CurrentUserResponse session, long id, PolicyDraftRequest.Submission request);

    /** 读取本人申请详情，可编辑状态返回当前表单，待审或已通过状态返回提交快照。 */
    Detail detail(CurrentUserResponse session, long id);

    /** 按筛选条件分页查询本人的政策申请。 */
    Page<Summary> mine(CurrentUserResponse session, PolicyListQuery query);

    Page<Summary> shared(CurrentUserResponse session, PolicyListQuery query);

    Detail sharedDetail(CurrentUserResponse session, long id);

    /** 分页查询本人指定申请的历轮提交和审核记录。 */
    Page<AuditSummary> auditLogs(CurrentUserResponse session, long id, int page, int pageSize);

    /** 读取本人指定申请某一轮的提交快照和审核结果。 */
    AuditDetail auditDetail(CurrentUserResponse session, long id, long logId);
}
