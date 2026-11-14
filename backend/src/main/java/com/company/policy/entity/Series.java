package com.company.policy.entity;

import java.time.LocalDateTime;

/** 品牌下的标准系列。 */
public record Series(long id, long brandId, String name, LocalDateTime createdAt) {}
