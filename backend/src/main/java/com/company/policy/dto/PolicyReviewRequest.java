package com.company.policy.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PolicyReviewRequest(
        Integer recordVersion,
        Integer submissionVersion,
        @Schema(allowableValues = {"APPROVE", "REJECT"}) String decision,
        String reviewRemark) {}
