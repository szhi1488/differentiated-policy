package com.company.policy.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public record PolicyReviewSettingsUpdate(
        @NotNull @PositiveOrZero @JsonDeserialize(using = PolicyDraftRequest.IntOnly.class)
                Integer expectedVersion,
        @NotNull @JsonDeserialize(contentUsing = PolicyDraftRequest.TextOnly.class)
                List<String> productReviewerUserIds,
        @NotNull @JsonDeserialize(contentUsing = PolicyDraftRequest.TextOnly.class)
                List<String> purchaseReviewerUserIds) {}
