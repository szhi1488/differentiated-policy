package com.company.policy.service;

import com.company.policy.vo.AuthSessionResponse;

/** 飞书身份验证与应用登录服务。 */
public interface FeishuLoginService {
    AuthSessionResponse login(String code);
}
