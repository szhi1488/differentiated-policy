package com.company.policy.service.impl;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.CatalogDictionaryRequest.Type;
import com.company.policy.dto.CatalogGoodsCreate;
import com.company.policy.dto.CatalogGoodsQuery;
import com.company.policy.mapper.CatalogDictionaryMapper;
import com.company.policy.mapper.CatalogGoodsMapper;
import com.company.policy.service.CatalogGoodsService;
import com.company.policy.vo.CatalogCleaningResponse.Page;
import com.company.policy.vo.CatalogGoodsResponse;
import com.company.policy.vo.CatalogGoodsResponse.Memory;
import com.company.policy.vo.CatalogGoodsResponse.ModelCount;
import com.company.policy.vo.CatalogGoodsResponse.SeriesCount;
import com.company.policy.vo.CatalogGoodsResponse.Summary;
import com.company.policy.vo.CurrentUserResponse;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogGoodsServiceImpl implements CatalogGoodsService {
    private static final Pattern MEMORY = Pattern.compile("[1-9][0-9]{0,7}GB");
    private static final Pattern CAPACITY =
            Pattern.compile("([1-9][0-9]{0,7})\\s*(GB?|TB?)?", Pattern.CASE_INSENSITIVE);
    private final CatalogAccess access;
    private final CatalogGoodsMapper mapper;
    private final CatalogDictionaryMapper dictionary;

    CatalogGoodsServiceImpl(
            CatalogAccess access, CatalogGoodsMapper mapper, CatalogDictionaryMapper dictionary) {
        this.access = access;
        this.mapper = mapper;
        this.dictionary = dictionary;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CatalogGoodsResponse create(CurrentUserResponse session, CatalogGoodsCreate request) {
        access.requireAdmin(session);
        String modelName = CatalogCleaningRule.normalize(request.modelName());
        String edition = CatalogCleaningRule.normalize(request.edition());
        if (modelName.isEmpty() || modelName.length() > 100 || edition.length() > 64) {
            throw invalidGoods("请核对机型名称和版本。");
        }
        String ram = capacity(request.ram(), false);
        String rom = capacity(request.rom(), true);
        long brand = request.brandId();
        long series = request.seriesId();
        long color = request.colorId();
        if (dictionary.lockName(Type.brand, brand) == null) {
            throw invalidGoods("所选品牌已不存在，请重新选择。");
        }
        var model = mapper.lockModel(brand, modelName, edition);
        if (mapper.lockSeriesBinding(brand, series) == null) {
            throw invalidGoods("所选系列不属于当前品牌或已不存在。");
        }
        if (model != null && model.seriesId() != series) {
            throw conflict("CATALOG_SERIES_CONFLICT", "同一机型版本已归属其他系列，请核对系列。");
        }
        if (dictionary.lockName(Type.color, color) == null) {
            throw invalidGoods("所选颜色已不存在，请重新选择。");
        }
        try {
            if (model == null) {
                checkWrite(mapper.insertModel(brand, series, modelName, edition));
                model = mapper.lockModel(brand, modelName, edition);
            }
            long spu = model.id();
            var existing = mapper.lockGoods(spu, ram, rom, color);
            if (existing != null && "DISABLED".equals(existing.catalogStatus())) {
                throw conflict("CATALOG_GOODS_DISABLED", "相同配置和颜色的商品已停用，仍占用该规格。");
            }
            if (existing != null) {
                throw duplicate();
            }
            checkWrite(
                    mapper.insertGoods(
                            "G-" + UUID.randomUUID(),
                            null,
                            brand,
                            series,
                            spu,
                            request.name().strip(),
                            ram,
                            rom,
                            color,
                            request.status()));
            var saved = mapper.lockGoods(spu, ram, rom, color);
            return CatalogGoodsResponse.from(mapper.find(saved.id()));
        } catch (DuplicateKeyException error) {
            throw duplicate();
        }
    }

    @Override
    @Transactional
    public void disable(CurrentUserResponse session, long id) {
        access.requireAdmin(session);
        if (id < 1) {
            throw invalidGoods("商品 ID 无效。");
        }
        String status = mapper.lockCatalogStatus(id);
        if (status == null) {
            throw notFound();
        }
        if (!"DISABLED".equals(status)) {
            checkWrite(mapper.disable(id));
        }
    }

    private static String capacity(String value, boolean storage) {
        var match = CAPACITY.matcher(CatalogCleaningRule.normalize(value));
        String message = storage ? "请填写有效的 ROM，例如 256GB 或 1T。" : "请填写有效的 RAM，例如 12GB。";
        if (!match.matches()) {
            throw invalidGoods(message);
        }
        long gb = Long.parseLong(match.group(1));
        String unit = match.group(2);
        if (unit != null && Character.toUpperCase(unit.charAt(0)) == 'T') {
            if (!storage || gb * 1024 > 99_999_999) {
                throw invalidGoods(message);
            }
            gb *= 1024;
        }
        return gb + "GB";
    }

    private static void checkWrite(int rows) {
        if (rows != 1) {
            throw conflict("CATALOG_GOODS_CHANGED", "商品目录正在更新，请稍后重新保存。");
        }
    }

    private static ApiException duplicate() {
        return conflict("CATALOG_GOODS_DUPLICATE", "该机型版本的相同配置和颜色已存在。");
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException invalidGoods(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "CATALOG_GOODS_INVALID", message);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CatalogGoodsResponse> list(CurrentUserResponse session, CatalogGoodsQuery query) {
        access.requireAdmin(session);
        if (query.page() < 1
                || query.size() < 1
                || query.size() > 100
                || (query.seriesId() != null && query.seriesId() < 1)
                || (query.spuId() != null && query.spuId() < 1)
                || (query.ram() == null) != (query.rom() == null)
                || (query.ram() != null
                        && (!MEMORY.matcher(query.ram()).matches()
                                || !MEMORY.matcher(query.rom()).matches()))
                || (query.status() != null && (query.status() < 1 || query.status() > 4))) {
            throw invalid();
        }
        var filters =
                new CatalogGoodsQuery(
                        search(query.brandName(), 50),
                        search(query.seriesName(), 64),
                        search(query.modelName(), 100),
                        search(query.keyword(), 128),
                        query.status(),
                        query.catalogStatus(),
                        query.ascending(),
                        query.page(),
                        query.size(),
                        query.seriesId(),
                        query.spuId(),
                        query.ram(),
                        query.rom());
        long offset = (filters.page() - 1L) * filters.size();
        var items =
                mapper.page(filters, filters.size(), offset).stream()
                        .map(CatalogGoodsResponse::from)
                        .toList();
        return new Page<>(items, mapper.count(filters));
    }

    @Override
    @Transactional(readOnly = true)
    public Summary summary(CurrentUserResponse session) {
        access.requireAdmin(session);
        var counts = mapper.seriesCounts();
        return new Summary(
                counts.stream().mapToLong(CatalogGoodsMapper.SeriesCount::total).sum(),
                counts.stream().mapToLong(CatalogGoodsMapper.SeriesCount::active).sum(),
                counts.stream().mapToLong(CatalogGoodsMapper.SeriesCount::stopped).sum(),
                counts.stream()
                        .map(
                                row ->
                                        new SeriesCount(
                                                row.brandName(),
                                                Long.toString(row.seriesId()),
                                                row.seriesName(),
                                                row.total(),
                                                row.active()))
                        .toList(),
                mapper.configurations().stream()
                        .map(row -> new Memory(row.ram(), row.rom()))
                        .toList(),
                mapper.modelCounts().stream()
                        .map(
                                row ->
                                        new ModelCount(
                                                Long.toString(row.seriesId()),
                                                Long.toString(row.spuId()),
                                                row.modelName(),
                                                row.edition(),
                                                row.total(),
                                                row.active()))
                        .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogGoodsResponse detail(CurrentUserResponse session, long id) {
        access.requireAdmin(session);
        var item = mapper.find(id);
        if (item == null) {
            throw notFound();
        }
        return CatalogGoodsResponse.from(item);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "CATALOG_GOODS_NOT_FOUND", "未找到该标准商品。");
    }

    private static String search(String value, int limit) {
        if (value != null && value.length() > limit) {
            throw invalid();
        }
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "CATALOG_INVALID_FILTER", "商品查询参数无效。");
    }
}
