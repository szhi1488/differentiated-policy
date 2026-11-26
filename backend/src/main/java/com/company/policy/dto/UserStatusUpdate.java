package com.company.policy.dto;

import jakarta.validation.constraints.*;

public record UserStatusUpdate(
        @NotNull @Pattern(regexp = "ENABLED|DISABLED") String status,
        @NotNull @Positive Integer expectedVersion) {}
