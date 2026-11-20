package com.company.policy.service;

import com.company.policy.dto.PolicyReviewQuery;
import com.company.policy.dto.PolicyReviewRequest;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyResponse.AuditDetail;
import com.company.policy.vo.PolicyResponse.AuditSummary;
import com.company.policy.vo.PolicyReviewResponse.Coverage;
import com.company.policy.vo.PolicyReviewResponse.Detail;
import com.company.policy.vo.PolicyReviewResponse.Effective;
import com.company.policy.vo.PolicyReviewResponse.Page;
import com.company.policy.vo.PolicyReviewResponse.Review;

/** 供管理员审核政策申请、查看历轮记录、预览覆盖结果并查询有效政策。 */
public interface PolicyReviewService {
    /** 按最近正式提交的内容筛选并分页查询本公司的政策申请。 */
    Page list(CurrentUserResponse session, PolicyReviewQuery query);

    /** 读取本公司申请的最近提交详情，按状态返回待审覆盖预览或已通过政策的生效范围。 */
    Detail detail(CurrentUserResponse session, long id, boolean includeCoverage);

    /** 核对记录版本和提交轮次后，通过或驳回待审申请。 */
    Review review(CurrentUserResponse session, long id, PolicyReviewRequest request);

    /** 预览当前待审轮次在此刻获批时对同渠道、同平台商品政策的覆盖结果。 */
    Coverage preview(
            CurrentUserResponse session, long id, int recordVersion, int submissionVersion);

    /** 查询指定时点、渠道、平台和商品的有效政策，省略时点时使用当前时间。 */
    Effective effective(
            CurrentUserResponse session,
            String perspective,
            String platformCode,
            long goodsId,
            String at);

    /** 分页查询本公司指定申请的历轮提交和审核记录。 */
    com.company.policy.vo.PolicyResponse.Page<AuditSummary> auditLogs(
            CurrentUserResponse session, long id, int page, int pageSize);

    /** 读取本公司指定申请某一轮的提交快照和审核结果。 */
    AuditDetail auditDetail(CurrentUserResponse session, long id, long logId);
}
