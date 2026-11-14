package com.company.policy.dto;

import java.util.List;

public record CatalogGoodsQuery(
        String brandName,
        String seriesName,
        String modelName,
        String keyword,
        Integer status,
        boolean ascending,
        int page,
        int size,
        Long seriesId,
        Long spuId,
        String ram,
        String rom,
        Long brandId,
        List<Configuration> configurations) {
    public record Configuration(long spuId, String ram, String rom) {}

    public CatalogGoodsQuery(
            String brandName,
            String seriesName,
            String modelName,
            String keyword,
            Integer status,
            boolean ascending,
            int page,
            int size,
            Long seriesId,
            Long spuId,
            String ram,
            String rom) {
        this(
                brandName,
                seriesName,
                modelName,
                keyword,
                status,
                ascending,
                page,
                size,
                seriesId,
                spuId,
                ram,
                rom,
                null,
                List.of());
    }
}
