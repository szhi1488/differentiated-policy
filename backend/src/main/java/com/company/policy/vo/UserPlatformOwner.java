package com.company.policy.vo;

/** 一个渠道平台的当前负责人。 */
public record UserPlatformOwner(
        String perspective, String platformCode, String userId, String name) {}
