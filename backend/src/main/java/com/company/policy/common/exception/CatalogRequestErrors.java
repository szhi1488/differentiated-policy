package com.company.policy.common.exception;

import com.company.policy.common.api.ApiError;
import com.company.policy.controller.ApplicantCatalogController;
import com.company.policy.controller.CatalogCleaningController;
import com.company.policy.controller.CatalogDictionaryController;
import com.company.policy.controller.CatalogGoodsController;
import com.company.policy.controller.GoodsSyncController;
import java.util.Map;

final class CatalogRequestErrors {
    private static final Map<Class<?>, ApiError> INVALID_PARAMETERS =
            Map.of(
                    ApplicantCatalogController.class,
                    new ApiError("CATALOG_INVALID_FILTER", "商品查询参数无效。"),
                    CatalogCleaningController.class,
                    new ApiError("CATALOG_CLEAN_INVALID_REQUEST", "请检查品牌、ID、日期、状态和分页参数。"),
                    CatalogDictionaryController.class,
                    new ApiError("CATALOG_DICTIONARY_INVALID", "字典类型或参数无效。"),
                    CatalogGoodsController.class,
                    new ApiError("CATALOG_INVALID_FILTER", "商品 ID、状态或分页参数无效。"),
                    GoodsSyncController.class,
                    new ApiError("INVALID_REQUEST", "任务 ID、建档日或分页参数无效。"));

    private CatalogRequestErrors() {}

    static boolean supports(Class<?> controller) {
        return controller != null && INVALID_PARAMETERS.containsKey(controller);
    }

    static ApiError invalidParameter(Class<?> controller) {
        return INVALID_PARAMETERS.get(controller);
    }

    static ApiError lockConflict(Class<?> controller) {
        if (controller == CatalogGoodsController.class)
            return new ApiError("CATALOG_GOODS_CHANGED", "商品目录正在更新，请稍后重试。");
        if (controller == CatalogDictionaryController.class)
            return new ApiError("CATALOG_DICTIONARY_CONFLICT", "字典项正在更新，请刷新后操作。");
        return new ApiError("CATALOG_CONFLICT", "商品数据正在被其他操作修改，请稍后重试。");
    }

    static ApiError storageUnavailable() {
        return new ApiError("CATALOG_STORAGE_UNAVAILABLE", "商品目录暂不可用，请稍后重试。");
    }
}
