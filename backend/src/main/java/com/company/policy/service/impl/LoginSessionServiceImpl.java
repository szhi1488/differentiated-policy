package com.company.policy.service.impl;

import com.company.policy.common.exception.ApiException;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.LoginChallengeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LoginSessionServiceImpl implements LoginSessionService {
    private static final int STATE_BYTES = 32;
    private static final int CHALLENGE_TTL_SECONDS = 180;
    private static final String LOGIN = LoginSessionService.class.getName() + ".login";
    private static final String CHALLENGE = LoginSessionService.class.getName() + ".challenge";
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public LoginSessionServiceImpl(Clock clock) {
        this.clock = clock;
    }

    @Override
    public LoginChallengeResponse createChallenge(HttpSession session) {
        byte[] bytes = new byte[STATE_BYTES];
        random.nextBytes(bytes);
        var challenge =
                new LoginChallengeResponse(
                        HexFormat.of().formatHex(bytes),
                        clock.instant().plusSeconds(CHALLENGE_TTL_SECONDS));
        synchronized (session) {
            session.setAttribute(CHALLENGE, challenge);
        }
        return challenge;
    }

    @Override
    public void consumeChallenge(HttpSession session, String state) {
        if (session == null) {
            throw invalidState();
        }
        synchronized (session) {
            var saved = session.getAttribute(CHALLENGE);
            session.removeAttribute(CHALLENGE);
            if (!(saved instanceof LoginChallengeResponse challenge)
                    || !clock.instant().isBefore(challenge.expiresAt())
                    || !challenge.state().equals(state)) {
                throw invalidState();
            }
        }
    }

    @Override
    public void establish(HttpServletRequest request, AuthSessionResponse login) {
        var session = request.getSession();
        synchronized (session) {
            request.changeSessionId();
            session.setAttribute(LOGIN, login);
        }
    }

    @Override
    public AuthSessionResponse requireLogin(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            synchronized (session) {
                var saved = session.getAttribute(LOGIN);
                if (saved instanceof AuthSessionResponse login
                        && clock.instant().isBefore(login.expiresAt())) {
                    return login;
                }
                session.removeAttribute(LOGIN);
            }
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "登录已过期或尚未登录，请重新通过飞书授权。");
    }

    @Override
    public void logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    private ApiException invalidState() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED, "LOGIN_STATE_INVALID", "登录请求已失效，请重新通过飞书授权。");
    }
}
