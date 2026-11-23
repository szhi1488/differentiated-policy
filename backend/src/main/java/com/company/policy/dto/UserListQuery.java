package com.company.policy.dto;

public record UserListQuery(
        int page,
        int pageSize,
        String keyword,
        String roleType,
        String status,
        String name,
        String department) {
    public UserListQuery(int page, int pageSize, String keyword, String roleType, String status) {
        this(page, pageSize, keyword, roleType, status, null, null);
    }
}
