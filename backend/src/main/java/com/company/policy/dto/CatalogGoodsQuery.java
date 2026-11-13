package com.company.policy.dto;

public record CatalogGoodsQuery(
        String brandName,
        String seriesName,
        String modelName,
        String keyword,
        Integer status,
        CatalogStatus catalogStatus,
        boolean ascending,
        int page,
        int size,
        Long seriesId,
        Long spuId,
        String ram,
        String rom) {
    public enum CatalogStatus {
        DRAFT,
        ACTIVE,
        DISABLED
    }
}
