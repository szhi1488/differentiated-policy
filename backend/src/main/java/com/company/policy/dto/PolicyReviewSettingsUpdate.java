package com.company.policy.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public record PolicyReviewSettingsUpdate(
        @NotNull @PositiveOrZero @JsonDeserialize(using = PolicyDraftRequest.IntOnly.class)
                Integer expectedVersion,
        @NotNull List<@NotNull @Valid Brand> brands) {
    public record Brand(
            String brandId,
            String productMode,
            String purchaseUserId,
            String brandProductUserId,
            @NotNull List<@NotNull @Valid Series> series) {}

    public record Series(String seriesId, String productUserId) {}
}
