package com.company.policy.service.impl;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.CatalogDictionaryRequest.Create;
import com.company.policy.dto.CatalogDictionaryRequest.Rename;
import com.company.policy.dto.CatalogDictionaryRequest.Type;
import com.company.policy.mapper.CatalogDictionaryMapper;
import com.company.policy.mapper.CatalogGoodsMapper;
import com.company.policy.service.CatalogDictionaryService;
import com.company.policy.vo.CatalogDictionaryEntry;
import com.company.policy.vo.CurrentUserResponse;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogDictionaryServiceImpl implements CatalogDictionaryService {
    private final CatalogAccess access;
    private final CatalogDictionaryMapper dictionary;
    private final CatalogGoodsMapper goods;

    CatalogDictionaryServiceImpl(
            CatalogAccess access, CatalogDictionaryMapper dictionary, CatalogGoodsMapper goods) {
        this.access = access;
        this.dictionary = dictionary;
        this.goods = goods;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CatalogDictionaryEntry> list(CurrentUserResponse session) {
        access.requireAdmin(session);
        return dictionary.entries().stream()
                .map(
                        row ->
                                new CatalogDictionaryEntry(
                                        row.type(),
                                        Long.toString(row.id()),
                                        row.parentId() == null ? null : row.parentId().toString(),
                                        row.name(),
                                        row.inUse()))
                .toList();
    }

    @Override
    @Transactional
    public void create(CurrentUserResponse session, Type type, Create request) {
        access.requireAdmin(session);
        String name = normalizedName(type, request.name());
        Long parent = request.parentId();
        if ((type == Type.series) != (parent != null)) {
            throw invalid("系列须选择所属品牌。");
        }
        if (parent != null && dictionary.lockName(Type.brand, parent) == null) {
            throw invalid("所选品牌已不存在，请重新选择。");
        }
        try {
            int rows =
                    switch (type) {
                        case brand -> goods.insertBrand(name);
                        case series -> goods.insertSeries(parent, name);
                        case color -> goods.insertColor(name);
                    };
            checkWrite(rows);
        } catch (DuplicateKeyException error) {
            throw duplicate();
        }
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void rename(CurrentUserResponse session, Type type, long id, Rename request) {
        access.requireAdmin(session);
        String name = normalizedName(type, request.name());
        if (lockCurrent(type, id, request.expectedName()).equals(name)) {
            return;
        }
        requireUnused(type, id);
        try {
            checkWrite(dictionary.rename(type, id, name));
        } catch (DuplicateKeyException error) {
            throw duplicate();
        }
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(CurrentUserResponse session, Type type, long id, String expectedName) {
        access.requireAdmin(session);
        lockCurrent(type, id, expectedName);
        requireUnused(type, id);
        try {
            if (type == Type.series) {
                dictionary.deleteUnusedModels(id);
            }
            checkWrite(dictionary.delete(type, id));
        } catch (DataIntegrityViolationException error) {
            throw inUse(type);
        }
    }

    private String lockCurrent(Type type, long id, String expectedName) {
        if (id < 1 || expectedName.isBlank() || expectedName.length() > 64) {
            throw invalid("字典项参数无效。");
        }
        String current = dictionary.lockName(type, id);
        if (current == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "CATALOG_DICTIONARY_NOT_FOUND", "该字典项已不存在。");
        }
        if (!current.equals(expectedName)) {
            throw changed();
        }
        return current;
    }

    private void requireUnused(Type type, long id) {
        if (type == Type.series) {
            // 锁定已有机型，避免校验之后有商品或政策通过外键新增引用。
            dictionary.lockModels(id);
        }
        boolean used =
                switch (type) {
                    case brand ->
                            dictionary.linkedSeries(id) != null
                                    || dictionary.linkedSource(id) != null;
                    case series -> dictionary.hasReferencedModel(id);
                    case color -> dictionary.linkedGoods(id) != null;
                };
        if (used) {
            throw inUse(type);
        }
    }

    private static ApiException inUse(Type type) {
        String message = type == Type.series ? "该系列仍有关联商品或政策引用。" : "该字典项已被引用。";
        return new ApiException(HttpStatus.CONFLICT, "CATALOG_DICTIONARY_IN_USE", message);
    }

    private static String normalizedName(Type type, String value) {
        String name = value.strip();
        int maxLength = type == Type.brand ? 50 : 64;
        if (name.isEmpty() || name.length() > maxLength) {
            throw invalid("名称须为 1 至 " + maxLength + " 个字符。");
        }
        return name;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "CATALOG_DICTIONARY_INVALID", message);
    }

    private static ApiException duplicate() {
        return new ApiException(HttpStatus.CONFLICT, "CATALOG_DICTIONARY_DUPLICATE", "已存在同名的字典项。");
    }

    private static void checkWrite(int rows) {
        if (rows != 1) {
            throw changed();
        }
    }

    private static ApiException changed() {
        return new ApiException(
                HttpStatus.CONFLICT, "CATALOG_DICTIONARY_CHANGED", "字典项写入冲突，请刷新后操作。");
    }
}
