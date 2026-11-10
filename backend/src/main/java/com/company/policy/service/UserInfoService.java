package com.company.policy.service;

import com.company.policy.vo.CurrentUserResponse;

/** 已核验飞书身份的本地用户维护服务。 */
public interface UserInfoService {
    CurrentUserResponse saveVerifiedIdentity(
            String tenantKey, String openId, String unionId, String name);
}
