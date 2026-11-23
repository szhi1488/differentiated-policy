package com.company.policy.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;

public record PolicyReviewRequest(
        @JsonDeserialize(using = PolicyDraftRequest.IntOnly.class) Integer recordVersion,
        @JsonDeserialize(using = PolicyDraftRequest.IntOnly.class) Integer submissionVersion,
        @JsonDeserialize(using = PolicyDraftRequest.TextOnly.class)
                @Schema(allowableValues = {"APPROVE", "REJECT"})
                String decision,
        @JsonDeserialize(using = PolicyDraftRequest.TextOnly.class) String reviewRemark) {}
