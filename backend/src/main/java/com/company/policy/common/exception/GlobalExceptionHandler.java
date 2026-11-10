package com.company.policy.common.exception;

import com.company.policy.common.api.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException error) {
        return ResponseEntity.status(error.getStatus()).header("Cache-Control", "no-store")
                .body(new ApiError(error.getCode(), error.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> handleValidation(Exception ignored) {
        return ResponseEntity.badRequest().header("Cache-Control", "no-store")
                .body(new ApiError("INVALID_REQUEST", "请求参数无效，请检查必填字段和格式。"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ignored) {
        // Never serialize exception messages: transport exceptions may contain credentials.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "服务暂时不可用，请稍后重试。"));
    }
}
