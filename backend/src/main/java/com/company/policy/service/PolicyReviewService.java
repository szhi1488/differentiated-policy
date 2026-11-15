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

public interface PolicyReviewService {
    Page list(CurrentUserResponse session, PolicyReviewQuery query);

    Detail detail(CurrentUserResponse session, long id);

    Review review(CurrentUserResponse session, long id, PolicyReviewRequest request);

    Coverage preview(
            CurrentUserResponse session, long id, int recordVersion, int submissionVersion);

    Effective effective(
            CurrentUserResponse session,
            String perspective,
            String platformCode,
            long goodsId,
            String at);

    com.company.policy.vo.PolicyResponse.Page<AuditSummary> auditLogs(
            CurrentUserResponse session, long id, int page, int pageSize);

    AuditDetail auditDetail(CurrentUserResponse session, long id, long logId);
}
