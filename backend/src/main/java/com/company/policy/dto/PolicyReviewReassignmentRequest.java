package com.company.policy.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record PolicyReviewReassignmentRequest(
        @NotBlank @Schema(allowableValues = {"PRODUCT_REVIEW", "PURCHASE_REVIEW"})
                String expectedStage,
        @NotBlank @Schema(allowableValues = {"PRODUCT_REVIEW", "PURCHASE_REVIEW"})
                String targetStage,
        @NotNull @Positive @JsonDeserialize(using = PolicyDraftRequest.IntOnly.class)
                Integer recordVersion,
        @NotNull @Positive @JsonDeserialize(using = PolicyDraftRequest.IntOnly.class)
                Integer submissionVersion,
        @NotEmpty @JsonDeserialize(contentUsing = PolicyDraftRequest.TextOnly.class)
                List<String> assigneeUserIds,
        @NotBlank
                @JsonDeserialize(using = PolicyDraftRequest.TextOnly.class)
                @Schema(description = "改派原因，最多 512 字")
                String reason) {}
