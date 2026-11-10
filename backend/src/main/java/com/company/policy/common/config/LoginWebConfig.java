package com.company.policy.common.config;

import com.company.policy.common.exception.ApiException;
import com.company.policy.service.JsSdkSignatureService;
import com.company.policy.service.LoginSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class LoginWebConfig implements WebMvcConfigurer {
    private final LoginSessionService sessions;
    private final JsSdkSignatureService signatures;
    private static final Set<String> PUBLIC = Set.of("GET /api/feishu/config", "POST /api/feishu/js-sdk/signature",
            "POST /api/auth/challenge", "POST /api/auth/feishu/login", "POST /api/auth/logout");

    public LoginWebConfig(LoginSessionService sessions, JsSdkSignatureService signatures) {
        this.sessions = sessions;
        this.signatures = signatures;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("Cache-Control", "no-store");
                String method = request.getMethod();
                if (!Set.of("GET", "HEAD", "OPTIONS").contains(method)) validateMutation(request);
                if (!PUBLIC.contains(method + " " + request.getRequestURI())) sessions.requireLogin(request);
                return true;
            }
        }).addPathPatterns("/api/**");
    }

    private void validateMutation(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        try {
            if (!"1".equals(request.getHeader("X-Policy-Request")) || origin == null || origin.equals("null")) throw new IllegalArgumentException();
            var uri = URI.create(origin);
            if (uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) throw new IllegalArgumentException();
            signatures.validateAndStripFragment(origin);
        } catch (RuntimeException ignored) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REQUEST_ORIGIN_DENIED", "请求来源校验失败，请从应用页面重试。");
        }
    }
}
