package com.company.policy.common.interceptor;

import com.company.policy.common.exception.ApiException;
import com.company.policy.service.JsSdkSignatureService;
import com.company.policy.service.LoginSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 统一校验接口登录态及写请求来源。 */
@Component
public class LoginInterceptor implements HandlerInterceptor {
    private static final Set<String> PUBLIC_ENDPOINTS =
            Set.of(
                    "GET /api/feishu/config",
                    "POST /api/feishu/js-sdk/signature",
                    "POST /api/auth/challenge",
                    "POST /api/auth/feishu/login",
                    "POST /api/auth/logout");
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final LoginSessionService sessions;
    private final JsSdkSignatureService signatures;

    public LoginInterceptor(LoginSessionService sessions, JsSdkSignatureService signatures) {
        this.sessions = sessions;
        this.signatures = signatures;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("Cache-Control", "no-store");
        String method = request.getMethod();
        if (!READ_METHODS.contains(method)) {
            validateMutation(request);
        }
        if (!PUBLIC_ENDPOINTS.contains(method + " " + request.getRequestURI())) {
            sessions.requireLogin(request);
        }
        return true;
    }

    private void validateMutation(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        try {
            if (!"1".equals(request.getHeader("X-Policy-Request"))
                    || origin == null
                    || origin.equals("null")) {
                throw new IllegalArgumentException();
            }
            var uri = URI.create(origin);
            if (uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                throw new IllegalArgumentException();
            }
            signatures.validateAndStripFragment(origin);
        } catch (RuntimeException ignored) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "REQUEST_ORIGIN_DENIED", "请求来源校验失败，请从应用页面重试。");
        }
    }
}
