package com.company.policy.vo;

import java.io.Serializable;
import java.time.Instant;

public record AuthSessionResponse(CurrentUserResponse user, Instant expiresAt)
        implements Serializable {}
