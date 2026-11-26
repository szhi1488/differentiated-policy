package com.company.policy.vo;

public record UserStatsResponse(
        long total, long enabledUsers, long enabledAdmins, long disabledUsers) {}
