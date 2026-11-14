package com.company.policy.dto;

public record PolicyListQuery(
        int page, int pageSize, String status, String perspective, String platformCode) {}
