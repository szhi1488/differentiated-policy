package com.company.policy.vo;

import java.time.Instant;

public record AuthSessionResponse(CurrentUserResponse user, Instant expiresAt) {}
