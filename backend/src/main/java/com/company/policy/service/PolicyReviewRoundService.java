package com.company.policy.service;

import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyReviewRoundResponse;

/** 按轮授权的政策审核详情。 */
public interface PolicyReviewRoundService {
    /** 读取指定轮次的冻结内容、成员处理结果与当前操作权限。 */
    PolicyReviewRoundResponse detail(CurrentUserResponse session, long id, int version);
}
