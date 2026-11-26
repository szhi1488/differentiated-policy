package com.company.policy.vo;

import java.util.List;

public record UserDetailResponse(
        String id,
        String name,
        String avatarUrl,
        String department,
        String openId,
        List<String> roleCodes,
        int authVersion,
        String status,
        String createdAt) {}
