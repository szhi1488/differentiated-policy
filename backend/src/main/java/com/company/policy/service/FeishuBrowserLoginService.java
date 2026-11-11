package com.company.policy.service;

import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.FeishuAuthorizationResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/** 浏览器网页授权与绑定当前会话的回调校验。 */
public interface FeishuBrowserLoginService {
    FeishuAuthorizationResponse authorize(String origin, HttpSession session);

    AuthSessionResponse login(String code, String state, HttpServletRequest request);
}
