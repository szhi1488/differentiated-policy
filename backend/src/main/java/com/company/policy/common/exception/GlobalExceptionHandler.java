package com.company.policy.common.exception;

import com.company.policy.common.api.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CannotCreateTransactionException.class)
    public ResponseEntity<ApiError> handleStorageUnavailable(Exception ignored) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Cache-Control", "no-store")
                .body(new ApiError("USER_STORAGE_UNAVAILABLE", "用户信息保存失败，请检查数据库连接后重新登录。"));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException error) {
        return ResponseEntity.status(error.getStatus())
                .header("Cache-Control", "no-store")
                .body(new ApiError(error.getCode(), error.getMessage()));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiError> handleValidation(Exception ignored) {
        return ResponseEntity.badRequest()
                .header("Cache-Control", "no-store")
                .body(new ApiError("INVALID_REQUEST", "请求参数无效，请检查必填字段和格式。"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException error) {
        var supported = error.getSupportedHttpMethods();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .allow(supported == null ? new HttpMethod[0] : supported.toArray(HttpMethod[]::new))
                .header("Cache-Control", "no-store")
                .body(new ApiError("METHOD_NOT_ALLOWED", "当前接口不支持此请求方法。"));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(Exception ignored) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("Cache-Control", "no-store")
                .body(new ApiError("NOT_FOUND", "请求的接口或资源不存在。"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(Exception ignored) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .header("Cache-Control", "no-store")
                .body(new ApiError("UNSUPPORTED_MEDIA_TYPE", "请求内容类型不受支持，请使用接口要求的格式。"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception error) {
        // 禁止将异常消息序列化返回，网络请求异常可能包含凭证。
        // 仅记录异常类型用于排查，不记录异常消息、请求参数或原始堆栈。
        log.error("接口发生未处理异常，异常类型：{}", error.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("Cache-Control", "no-store")
                .body(new ApiError("INTERNAL_ERROR", "服务暂时不可用，请稍后重试。"));
    }
}
