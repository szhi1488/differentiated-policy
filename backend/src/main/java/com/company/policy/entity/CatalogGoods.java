package com.company.policy.entity;

import java.time.LocalDateTime;

public record CatalogGoods(
        long id,
        String skuId,
        String productNo,
        String goodsName,
        long brandId,
        String brandName,
        long seriesId,
        String seriesName,
        long spuId,
        String modelName,
        String edition,
        String ram,
        String rom,
        long colorId,
        String colorName,
        Integer status,
        LocalDateTime updatedAt,
        String sourceSkuId,
        String sourceName) {}
