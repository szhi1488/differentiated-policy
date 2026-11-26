package com.company.policy.dto;

public record UserListQuery(
        int page,
        int pageSize,
        String keyword,
        String roleCode,
        String status,
        String name,
        String department,
        String permission) {
    public UserListQuery(int page, int pageSize, String keyword, String roleCode, String status) {
        this(page, pageSize, keyword, roleCode, status, null, null, null);
    }
}
