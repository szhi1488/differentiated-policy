package com.company.policy.service.impl;

import com.company.policy.common.exception.ApiException;
import com.company.policy.entity.CatalogCleaningJob;
import com.company.policy.entity.SourceGoodsItem;
import com.company.policy.entity.Spu;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.CatalogCleaningMapper;
import com.company.policy.mapper.CatalogCleaningMapper.Change;
import com.company.policy.mapper.CatalogGoodsMapper;
import com.company.policy.vo.CatalogCleaningResponse.Candidate;
import com.company.policy.vo.CatalogCleaningResponse.Status;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 在调用方的事务内解析标准目录映射并保存来源清洗结果。 */
@Component
class CatalogCleaningResultWriter {
    private final CatalogCleaningMapper mapper;
    private final CatalogGoodsMapper goods;
    private final ObjectMapper json;

    CatalogCleaningResultWriter(
            CatalogCleaningMapper mapper, CatalogGoodsMapper goods, ObjectMapper json) {
        this.mapper = mapper;
        this.goods = goods;
        this.json = json;
    }

    Mapping resolve(SourceGoodsItem source, Candidate candidate, String origin) {
        var memory = CatalogCleaningRule.memory(candidate.spec());
        if (memory == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "CATALOG_CLEAN_INVALID_REQUEST",
                    "请填写有效的运行内存和机身存储，例如 16GB+512GB。");
        }
        String brandName = source.getBrandName();
        String edition = Objects.requireNonNullElse(candidate.edition(), "");
        boolean protect = "AUTO".equals(origin) && source.getGoodsId() != null;
        var brand = goods.lockBrand(brandName);
        var model =
                brand == null ? null : goods.lockModel(brand.id(), candidate.modelName(), edition);
        if (model != null && !candidate.seriesName().equals(model.seriesName())) {
            throw new MappingConflict("CATALOG_SERIES_CONFLICT", "同一机型版本已归属其他系列，请核对系列。");
        }
        var color = goods.lockColor(candidate.colorName());
        var existing =
                model == null || color == null
                        ? null
                        : goods.lockGoods(model.id(), memory.ram(), memory.rom(), color.id());
        if (protect && (existing == null || !Objects.equals(source.getGoodsId(), existing.id()))) {
            throw new MappingConflict("CATALOG_MAPPING_CHANGED", "候选规格或颜色与已生效映射不同，需要人工确认。");
        }
        if (existing != null) {
            if (existing.status() == null) {
                checkWrite(goods.defaultToOnSale(existing.id()));
            }
            return new Mapping("MAPPED", origin, brand.id(), existing.id());
        }

        // 在确认目录无业务冲突后建档，避免待审结果留下部分目录记录。
        if (brand == null) {
            checkWrite(goods.insertBrand(brandName));
            brand = goods.lockBrand(brandName);
        }
        long brandId = brand.id();
        if (model == null) {
            var series = goods.lockSeries(brandId, candidate.seriesName());
            if (series == null) {
                checkWrite(goods.insertSeries(brandId, candidate.seriesName()));
                series = goods.lockSeries(brandId, candidate.seriesName());
            }
            checkWrite(
                    goods.insertModel(
                            new Spu(brandId, series.id(), candidate.modelName(), edition)));
            model = goods.lockModel(brandId, candidate.modelName(), edition);
        }
        if (color == null) {
            checkWrite(goods.insertColor(candidate.colorName()));
            color = goods.lockColor(candidate.colorName());
        }
        String sku = "G-" + UUID.randomUUID();
        String name =
                String.join(
                                " ",
                                candidate.modelName(),
                                memory.ram() + "+" + memory.rom(),
                                candidate.colorName())
                        + (edition.isEmpty() ? "" : "（" + edition + "）");
        String productNo = source.getGoodsNo();
        checkWrite(
                goods.insertGoods(
                        sku,
                        productNo == null || productNo.isBlank() ? null : productNo,
                        brandId,
                        model.seriesId(),
                        model.id(),
                        name,
                        memory.ram(),
                        memory.rom(),
                        color.id(),
                        1));
        return new Mapping(
                "MAPPED",
                origin,
                brandId,
                goods.lockGoods(model.id(), memory.ram(), memory.rom(), color.id()).id());
    }

    void persist(
            CatalogCleaningJob job,
            SourceGoodsItem source,
            Candidate candidate,
            Status status,
            Mapping mapping,
            UserInfo operator,
            String reason) {
        var change =
                new Change(
                        status.name(),
                        write(candidate),
                        status == Status.ERROR || status == Status.REVIEW_REQUIRED
                                ? candidate.code()
                                : null,
                        reason,
                        mapping == null ? null : mapping.status(),
                        mapping == null ? null : mapping.origin(),
                        mapping == null ? null : mapping.brandId(),
                        mapping == null ? null : mapping.goodsId());
        checkWrite(mapper.save(job, source, change, operator));
    }

    Candidate readCandidate(SourceGoodsItem source) {
        try {
            return source.getCleanCandidate() == null
                    ? null
                    : json.readValue(source.getCleanCandidate(), Candidate.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("来源候选读取失败", error);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("清洗结果序列化失败", error);
        }
    }

    private static void checkWrite(int rows) {
        if (rows != 1) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "CATALOG_SOURCE_CHANGED", "清洗记录已变化，请刷新后重试。");
        }
    }

    // 只有目录写入前发现的业务冲突可以转待审并提交事务。
    static final class MappingConflict extends ApiException {
        MappingConflict(String code, String reason) {
            super(HttpStatus.CONFLICT, code, reason);
        }
    }

    record Mapping(String status, String origin, Long brandId, Long goodsId) {}
}
