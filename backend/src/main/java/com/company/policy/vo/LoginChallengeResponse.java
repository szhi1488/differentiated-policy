package com.company.policy.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.time.Instant;

@Schema(description = "绑定当前浏览器会话的一次性登录校验信息。")
public record LoginChallengeResponse(String state, Instant expiresAt) implements Serializable {}
