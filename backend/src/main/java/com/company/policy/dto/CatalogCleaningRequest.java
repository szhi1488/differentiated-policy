package com.company.policy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class CatalogCleaningRequest {
    private CatalogCleaningRequest() {}

    public record Control(@NotNull @PositiveOrZero Integer recordVersion) {}

    public record Review(
            @NotNull @PositiveOrZero Integer recordVersion,
            @NotNull Decision decision,
            @NotBlank @Size(max = 512) String reason,
            @Valid Target target) {}

    public enum Decision {
        APPROVE,
        EXCLUDE
    }

    public record Target(
            @NotBlank @Size(max = 64) String seriesName,
            @NotBlank @Size(max = 64) String modelName,
            @NotBlank @Size(max = 64) String spec,
            @NotBlank @Size(max = 64) String colorName) {}
}
