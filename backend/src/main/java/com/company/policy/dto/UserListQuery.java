package com.company.policy.dto;

public record UserListQuery(
        int page, int pageSize, String keyword, String roleType, String status) {}
