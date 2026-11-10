package com.company.policy.service;

import com.company.policy.common.exception.ApiException;
import com.company.policy.vo.AuthSessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LoginSessionService {
    private static final String LOGIN = LoginSessionService.class.getName() + ".login";
    private static final String CHALLENGE = LoginSessionService.class.getName() + ".challenge";
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public LoginSessionService(Clock clock) { this.clock = clock; }

    public Challenge createChallenge(HttpSession session) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        var challenge = new Challenge(HexFormat.of().formatHex(bytes), clock.instant().plusSeconds(180));
        synchronized (session) { session.setAttribute(CHALLENGE, challenge); }
        return challenge;
    }

    public void consumeChallenge(HttpSession session, String state) {
        if (session == null) throw invalidState();
        synchronized (session) {
            var saved = session.getAttribute(CHALLENGE);
            session.removeAttribute(CHALLENGE);
            if (!(saved instanceof Challenge challenge) || !clock.instant().isBefore(challenge.expiresAt())
                    || !challenge.state().equals(state)) throw invalidState();
        }
    }

    public void establish(HttpServletRequest request, AuthSessionResponse login) {
        var session = request.getSession();
        synchronized (session) {
            request.changeSessionId();
            session.setAttribute(LOGIN, login);
        }
    }

    public AuthSessionResponse requireLogin(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            synchronized (session) {
                var saved = session.getAttribute(LOGIN);
                if (saved instanceof AuthSessionResponse login && clock.instant().isBefore(login.expiresAt())) return login;
                session.removeAttribute(LOGIN);
            }
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "登录已过期或尚未登录，请重新通过飞书授权。");
    }

    private ApiException invalidState() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_STATE_INVALID", "登录请求已失效，请重新通过飞书授权。");
    }

    public record Challenge(String state, Instant expiresAt) {}
}
