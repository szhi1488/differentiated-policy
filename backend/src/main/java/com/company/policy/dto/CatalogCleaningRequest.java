package com.company.policy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public final class CatalogCleaningRequest {
    private CatalogCleaningRequest() {}

    public record Start(
            @NotBlank String brandName,
            LocalDate createdDate,
            boolean includeReviews,
            @Positive long expectedTotal,
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{1,64}") String requestKey) {}

    public record Retry(
            @NotNull @PositiveOrZero Integer recordVersion,
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{1,64}") String requestKey) {}

    public record Review(
            @NotNull @PositiveOrZero Integer recordVersion,
            @NotNull Decision decision,
            @Size(max = 512) String reason,
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
