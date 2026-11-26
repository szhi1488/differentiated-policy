package com.company.policy.vo;

/** 平台分工当前的启用成员。 */
public record UserPlatformOwner(
        String perspective,
        String platformCode,
        String userId,
        String name,
        String memberKind,
        boolean canSubmit) {}
