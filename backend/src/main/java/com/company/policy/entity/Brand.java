package com.company.policy.entity;

import java.time.LocalDateTime;

/** 标准品牌。 */
public record Brand(long id, String name, LocalDateTime createdAt) {}
