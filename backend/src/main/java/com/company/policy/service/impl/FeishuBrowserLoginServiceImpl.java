package com.company.policy.service.impl;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.service.FeishuBrowserLoginService;
import com.company.policy.service.FeishuLoginService;
import com.company.policy.service.JsSdkSignatureService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.FeishuAuthorizationResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class FeishuBrowserLoginServiceImpl implements FeishuBrowserLoginService {
    private static final String PENDING = FeishuBrowserLoginService.class.getName() + ".pending";
    private final FeishuProperties properties;
    private final JsSdkSignatureService signatures;
    private final LoginSessionService sessions;
    private final FeishuLoginService loginService;

    public FeishuBrowserLoginServiceImpl(
            FeishuProperties properties,
            JsSdkSignatureService signatures,
            LoginSessionService sessions,
            FeishuLoginService loginService) {
        this.properties = properties;
        this.signatures = signatures;
        this.sessions = sessions;
        this.loginService = loginService;
    }

    @Override
    public FeishuAuthorizationResponse authorize(String origin, HttpSession session) {
        if (!properties.isConfigured()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "FEISHU_NOT_CONFIGURED", "请先配置后端飞书应用凭证。");
        }
        // 仅使用经来源校验的站点首页，不接受任意回跳地址或转发 Host。
        String redirectUri = signatures.validateAndStripFragment(origin + "/");
        synchronized (session) {
            var challenge = sessions.createChallenge(session);
            session.setAttribute(PENDING, new Pending(challenge.state(), redirectUri));
            String url =
                    UriComponentsBuilder.fromUriString(
                                    "https://accounts.feishu.cn/open-apis/authen/v1/authorize")
                            .queryParam("client_id", properties.getAppId())
                            .queryParam("response_type", "code")
                            .queryParam("redirect_uri", "{redirectUri}")
                            .queryParam("state", challenge.state())
                            .encode()
                            .buildAndExpand(redirectUri)
                            .toUriString();
            return new FeishuAuthorizationResponse(url);
        }
    }

    @Override
    public AuthSessionResponse login(String code, String state, HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) {
            throw invalidState();
        }
        String redirectUri;
        synchronized (session) {
            var saved = session.getAttribute(PENDING);
            session.removeAttribute(PENDING);
            if (!(saved instanceof Pending pending) || !pending.state().equals(state)) {
                throw invalidState();
            }
            sessions.consumeChallenge(session, state);
            redirectUri = pending.redirectUri();
        }
        // 与发起时使用完全相同的地址；失败不重放授权码。
        var login = loginService.login(code, redirectUri);
        sessions.establish(request, login);
        return login;
    }

    private ApiException invalidState() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED, "LOGIN_STATE_INVALID", "网页登录请求已失效，请重新通过飞书授权。");
    }

    private record Pending(String state, String redirectUri) {}
}
