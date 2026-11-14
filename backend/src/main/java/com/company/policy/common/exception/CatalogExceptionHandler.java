package com.company.policy.common.exception;

import com.company.policy.common.api.ApiError;
import com.company.policy.controller.CatalogCleaningController;
import com.company.policy.controller.CatalogDictionaryController;
import com.company.policy.controller.CatalogGoodsController;
import com.company.policy.controller.GoodsSyncController;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(0)
@RestControllerAdvice(
        assignableTypes = {
            CatalogCleaningController.class,
            CatalogDictionaryController.class,
            CatalogGoodsController.class,
            GoodsSyncController.class
        })
public class CatalogExceptionHandler {
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Cache-Control", "no-store")
                .body(new ApiError("CATALOG_STORAGE_UNAVAILABLE", "商品管理暂不可用，请稍后重试。"));
    }
}
