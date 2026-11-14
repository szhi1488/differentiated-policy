package com.company.policy.entity;

import java.time.LocalDateTime;

/** 标准机型及独立版本。 */
public record Spu(
        Long id,
        long brandId,
        long seriesId,
        String name,
        String edition,
        LocalDateTime createdAt) {
    public Spu(long brandId, long seriesId, String name, String edition) {
        this(null, brandId, seriesId, name, edition, null);
    }
}
