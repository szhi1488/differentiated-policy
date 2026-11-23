package com.company.policy.dto;

import java.time.LocalDateTime;

public record PolicyReviewQuery(
        int page,
        int pageSize,
        String status,
        String reviewStage,
        String perspective,
        String platformCode,
        String verifyType,
        String sceneType,
        String applicant,
        LocalDateTime submittedFrom,
        LocalDateTime submittedBefore,
        String keyword) {}
