package com.company.policy.service;

import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.LoginChallengeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/** 应用登录会话与一次性登录校验服务。 */
public interface LoginSessionService {
    LoginChallengeResponse createChallenge(HttpSession session);

    void consumeChallenge(HttpSession session, String state);

    void establish(HttpServletRequest request, AuthSessionResponse login);

    AuthSessionResponse requireLogin(HttpServletRequest request);

    void logout(HttpServletRequest request);
}
