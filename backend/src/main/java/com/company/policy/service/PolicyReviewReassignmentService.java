package com.company.policy.service;

import com.company.policy.dto.PolicyReviewReassignmentRequest;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyReviewReassignmentResult;

public interface PolicyReviewReassignmentService {
    /** 校验当前轮与岗位资格，按差集更新指派、改派轨迹和通知。 */
    PolicyReviewReassignmentResult reassign(
            CurrentUserResponse session, long id, PolicyReviewReassignmentRequest request);
}
