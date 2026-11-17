package com.company.policy.service;

import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.FeishuAuthorizationResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/** 浏览器网页授权与绑定当前会话的回调校验。 */
public interface FeishuBrowserLoginService {
    /** 为当前浏览器会话生成飞书授权地址，供扫码或网页授权使用。 */
    FeishuAuthorizationResponse authorize(String origin, HttpSession session);

    /** 校验回调状态值，并使用授权码完成浏览器登录和会话建立。 */
    AuthSessionResponse login(String code, String state, HttpServletRequest request);
}
