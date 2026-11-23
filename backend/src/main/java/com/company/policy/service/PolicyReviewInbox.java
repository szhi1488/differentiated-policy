package com.company.policy.service;

import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyResponse.Page;
import com.company.policy.vo.PolicyReviewRoundResponse;
import com.company.policy.vo.PolicyReviewRoundResponse.Item;

/** 个人当前待审节点及按轮授权的审核详情。 */
public interface PolicyReviewInbox {
    /** 返回本人当前有办理权的指派，按提交时间倒序分页。 */
    Page<Item> mine(CurrentUserResponse session, int page, int pageSize);

    /** 读取指定轮次的冻结内容、成员处理结果与当前操作权限。 */
    PolicyReviewRoundResponse detail(CurrentUserResponse session, long id, int version);
}
