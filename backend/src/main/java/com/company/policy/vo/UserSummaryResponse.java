package com.company.policy.vo;

import java.util.List;

public record UserSummaryResponse(
        String id,
        String name,
        String avatarUrl,
        String department,
        List<String> roleCodes,
        int authVersion,
        String status,
        String createdAt) {}
