package com.company.policy.vo;

import com.company.policy.entity.CatalogGoods;
import com.company.policy.mapper.CatalogGoodsMapper.OnSaleConfiguration;
import java.util.List;
import java.util.Objects;

public record ApplicantCatalogResponse(
        String goodsId,
        String brandId,
        String brandName,
        String seriesId,
        String seriesName,
        String spuId,
        String modelName,
        String edition,
        String ram,
        String rom,
        String goodsName,
        String colorName,
        boolean onSale) {

    public static ApplicantCatalogResponse from(CatalogGoods goods) {
        return new ApplicantCatalogResponse(
                Long.toString(goods.id()),
                Long.toString(goods.brandId()),
                goods.brandName(),
                Long.toString(goods.seriesId()),
                goods.seriesName(),
                Long.toString(goods.spuId()),
                goods.modelName(),
                goods.edition(),
                goods.ram(),
                goods.rom(),
                goods.goodsName(),
                goods.colorName(),
                Objects.equals(goods.status(), 1));
    }

    public record Options(
            List<Option> brands,
            List<SeriesOption> series,
            List<ModelOption> models,
            List<Configuration> configurations) {
        public static Options from(
                List<OnSaleConfiguration> rows, Long brandId, Long seriesId, Long spuId) {
            var byBrand =
                    rows.stream()
                            .filter(row -> brandId == null || brandId == row.brandId())
                            .toList();
            var bySeries =
                    byBrand.stream()
                            .filter(row -> seriesId == null || seriesId == row.seriesId())
                            .toList();
            return new Options(
                    rows.stream()
                            .map(row -> new Option(Long.toString(row.brandId()), row.brandName()))
                            .distinct()
                            .toList(),
                    byBrand.stream()
                            .map(
                                    row ->
                                            new SeriesOption(
                                                    Long.toString(row.seriesId()),
                                                    Long.toString(row.brandId()),
                                                    row.seriesName()))
                            .distinct()
                            .toList(),
                    bySeries.stream()
                            .map(
                                    row ->
                                            new ModelOption(
                                                    Long.toString(row.spuId()),
                                                    Long.toString(row.seriesId()),
                                                    row.modelName(),
                                                    row.edition()))
                            .distinct()
                            .toList(),
                    bySeries.stream()
                            .filter(row -> spuId == null || spuId == row.spuId())
                            .map(
                                    row ->
                                            new Configuration(
                                                    Long.toString(row.spuId()),
                                                    row.ram(),
                                                    row.rom()))
                            .distinct()
                            .toList());
        }
    }

    public record Option(String id, String name) {}

    public record SeriesOption(String id, String brandId, String name) {}

    public record ModelOption(String id, String seriesId, String name, String edition) {}

    public record Configuration(String spuId, String ram, String rom) {}
}
