package com.company.policy.vo;

import com.company.policy.entity.CatalogGoods;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record CatalogGoodsResponse(
        String id,
        String skuId,
        String productNo,
        String goodsName,
        String brandId,
        String brandName,
        String seriesId,
        String seriesName,
        String spuId,
        String modelName,
        String edition,
        String ram,
        String rom,
        String colorId,
        String colorName,
        Integer status,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updatedAt,
        String sourceSkuId,
        String sourceName,
        String editVersion) {

    public static CatalogGoodsResponse from(CatalogGoods goods) {
        return new CatalogGoodsResponse(
                Long.toString(goods.id()),
                goods.skuId(),
                goods.productNo(),
                goods.goodsName(),
                Long.toString(goods.brandId()),
                goods.brandName(),
                Long.toString(goods.seriesId()),
                goods.seriesName(),
                Long.toString(goods.spuId()),
                goods.modelName(),
                goods.edition(),
                goods.ram(),
                goods.rom(),
                Long.toString(goods.colorId()),
                goods.colorName(),
                goods.status(),
                goods.updatedAt(),
                goods.sourceSkuId(),
                goods.sourceName(),
                editVersion(goods));
    }

    private static String editVersion(CatalogGoods goods) {
        // 更新时间精确到秒，合入可编辑字段才能识别同一秒内的修改。
        var snapshot = new StringBuilder();
        for (String value :
                List.of(
                        Long.toString(goods.id()),
                        goods.goodsName(),
                        Long.toString(goods.spuId()),
                        goods.ram(),
                        goods.rom(),
                        Long.toString(goods.colorId()),
                        Objects.toString(goods.status(), ""),
                        goods.updatedAt().toString())) {
            snapshot.append(value.length()).append(':').append(value);
        }
        try {
            byte[] bytes = snapshot.toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public record Summary(
            long total,
            long active,
            long stopped,
            List<SeriesCount> series,
            List<Memory> configurations,
            List<ModelCount> models) {}

    public record SeriesCount(
            String brandName, String seriesId, String seriesName, long total, long active) {}

    public record Memory(String ram, String rom) {}

    public record ModelCount(
            String seriesId,
            String spuId,
            String modelName,
            String edition,
            long total,
            long active) {}
}
