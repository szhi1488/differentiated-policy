package com.company.policy.service;

import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.LoginChallengeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/** 应用登录会话与一次性登录校验服务。 */
public interface LoginSessionService {
    /** 生成有有效期的一次性登录状态值，并将其保存在当前会话中。 */
    LoginChallengeResponse createChallenge(HttpSession session);

    /** 取出并校验一次性登录状态值，使其无法被再次使用。 */
    void consumeChallenge(HttpSession session, String state);

    /** 更新会话标识并保存已核验的登录信息。 */
    void establish(HttpServletRequest request, AuthSessionResponse login);

    /** 读取当前会话中仍有效的登录信息，未登录或已过期时拒绝访问。 */
    AuthSessionResponse requireLogin(HttpServletRequest request);

    /** 使当前登录会话失效。 */
    void logout(HttpServletRequest request);
}
