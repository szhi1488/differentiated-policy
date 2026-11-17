package com.company.policy.service;

import com.company.policy.vo.CurrentUserResponse;

/** 已核验飞书身份的本地用户维护服务。 */
public interface UserInfoService {
    /** 根据已核验的飞书身份创建或更新本地用户资料，并拒绝停用账号登录。 */
    CurrentUserResponse saveVerifiedIdentity(
            String tenantKey, String openId, String unionId, String name, String avatarUrl);

    /** 核对会话对应的公司和本地账号，返回账号当前角色。 */
    CurrentUserResponse restoreSessionUser(CurrentUserResponse identity);
}
