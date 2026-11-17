package com.company.policy.service;

import com.company.policy.vo.AuthSessionResponse;

/** 飞书身份验证与应用登录服务。 */
public interface FeishuLoginService {
    /** 使用飞书客户端授权码核验身份，返回登录用户及有效期。 */
    AuthSessionResponse login(String code);

    /** 使用网页授权码和发起授权时的回调地址核验身份，返回登录结果。 */
    AuthSessionResponse login(String code, String redirectUri);
}
