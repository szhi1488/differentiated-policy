package com.company.policy.service;

import com.company.policy.entity.UserInfo;
import com.company.policy.vo.CurrentUserResponse;

/** 根据当前账号和公司归属校验操作权限，返回已核验的用户。 */
public interface CatalogAccess {
    /** 校验当前账号是否为本公司有效申请人，并返回该用户。 */
    UserInfo requireApplicant(CurrentUserResponse session);

    /** 校验当前账号的商品管理权限，并返回本公司有效管理员。 */
    UserInfo requireAdmin(CurrentUserResponse session);

    /** 校验当前账号的政策审核权限，并返回本公司有效管理员。 */
    UserInfo requirePolicyAdmin(CurrentUserResponse session);

    /** 校验当前账号的用户管理权限，并返回本公司有效管理员。 */
    UserInfo requireUserAdmin(CurrentUserResponse session);
}
