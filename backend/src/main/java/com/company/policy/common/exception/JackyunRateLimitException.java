package com.company.policy.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class JackyunRateLimitException extends ApiException {
    private final int retryAfterSeconds;

    public JackyunRateLimitException(int retryAfterSeconds) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "JACKYUN_RATE_LIMITED", "吉客云限制了请求频率，请等待冷却后再试。");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
