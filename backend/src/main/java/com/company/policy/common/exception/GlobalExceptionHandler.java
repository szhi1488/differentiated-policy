package com.company.policy.common.exception;

import com.company.policy.common.api.ApiError;
import com.company.policy.controller.AttachmentController;
import com.company.policy.controller.AuthController;
import com.company.policy.controller.CatalogCleaningController;
import com.company.policy.controller.CatalogDictionaryController;
import com.company.policy.controller.PolicyApplyController;
import com.company.policy.controller.PolicyAttachmentController;
import com.company.policy.controller.PolicyReviewController;
import com.company.policy.controller.UserManagementController;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int MAX_EXCEPTION_DEPTH = 5;
    private static final int MAX_FRAMES_PER_EXCEPTION = 4;

    private static ResponseEntity.BodyBuilder errorResponse(HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore());
    }

    private static HandlerMethod matchedHandler(HttpServletRequest request) {
        Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        return matched instanceof HandlerMethod handler ? handler : null;
    }

    private static boolean isPolicyController(Class<?> type) {
        return type == PolicyApplyController.class || type == PolicyReviewController.class;
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> handlePersistence(
            NestedRuntimeException error, HttpServletRequest request) {
        HandlerMethod handler = matchedHandler(request);
        Class<?> type = handler == null ? null : handler.getBeanType();
        if (error.contains(PessimisticLockingFailureException.class)) {
            return errorResponse(HttpStatus.CONFLICT).body(lockConflict(type, handler));
        }

        ApiError body;
        if (isPolicyController(type)) {
            body = new ApiError("POLICY_STORAGE_UNAVAILABLE", "政策存储暂不可用，请稍后重试。");
        } else if (type == UserManagementController.class) {
            body = UserManagementRequestErrors.storageUnavailable();
        } else if (CatalogRequestErrors.supports(type)) {
            body = CatalogRequestErrors.storageUnavailable();
        } else if (error instanceof CannotCreateTransactionException
                || error instanceof DataAccessResourceFailureException
                || error instanceof TransientDataAccessResourceException
                || error instanceof QueryTimeoutException
                || error instanceof TransactionTimedOutException) {
            if (type == AuthController.class) {
                body = new ApiError("USER_STORAGE_UNAVAILABLE", "用户信息保存失败，请检查数据库连接后重新登录。");
            } else if (type == AttachmentController.class
                    || type == PolicyAttachmentController.class) {
                body = new ApiError("ATTACHMENT_STORAGE_UNAVAILABLE", "附件存储暂时不可用，请稍后重试或联系管理员。");
            } else {
                body = new ApiError("STORAGE_UNAVAILABLE", "数据存储暂不可用，请稍后重试。");
            }
        } else {
            // 其余接口没有领域存储错误约定，未知的持久化故障仍保留 500 诊断。
            return handleUnexpected(error);
        }
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private static ApiError lockConflict(Class<?> type, HandlerMethod handler) {
        if (isPolicyController(type)) {
            return new ApiError("POLICY_VERSION_CONFLICT", "申请或商品正在更新，请保留输入并稍后重试。");
        }
        if (type == UserManagementController.class) {
            return UserManagementRequestErrors.lockConflict(handler);
        }
        if (CatalogRequestErrors.supports(type)) {
            return CatalogRequestErrors.lockConflict(type);
        }
        return new ApiError("RESOURCE_CONFLICT", "数据正在更新，请稍后重试。");
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException error) {
        return errorResponse(error.getStatus())
                .body(new ApiError(error.getCode(), error.getMessage()));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiError> handleValidation(Exception error, HttpServletRequest request) {
        HandlerMethod handler = matchedHandler(request);
        Class<?> type = handler == null ? null : handler.getBeanType();
        ApiError body = new ApiError("INVALID_REQUEST", "请求参数无效，请检查必填字段和格式。");
        if (error instanceof MethodArgumentTypeMismatchException mismatch) {
            if (isPolicyController(type)) {
                body = new ApiError("POLICY_VALIDATION_FAILED", "申请、审核记录 ID、筛选日期或分页参数格式无效。");
            } else if (type == UserManagementController.class) {
                body = UserManagementRequestErrors.invalidParameter(handler, mismatch.getName());
            } else if (CatalogRequestErrors.supports(type)) {
                body = CatalogRequestErrors.invalidParameter(type);
            }
        } else if (error instanceof MissingServletRequestParameterException
                && (type == CatalogCleaningController.class
                        || type == CatalogDictionaryController.class)) {
            body = CatalogRequestErrors.invalidParameter(type);
        }
        return errorResponse(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingFile(MissingServletRequestPartException ignored) {
        return errorResponse(HttpStatus.BAD_REQUEST)
                .body(new ApiError("ATTACHMENT_INVALID", "请选择需要上传的文件。"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException ignored) {
        return errorResponse(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("ATTACHMENT_TOO_LARGE", "单个附件不能超过 20MB。"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException error) {
        var supported = error.getSupportedHttpMethods();
        return errorResponse(HttpStatus.METHOD_NOT_ALLOWED)
                .allow(supported == null ? new HttpMethod[0] : supported.toArray(HttpMethod[]::new))
                .body(new ApiError("METHOD_NOT_ALLOWED", "当前接口不支持此请求方法。"));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(Exception ignored) {
        return errorResponse(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", "请求的接口或资源不存在。"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(Exception ignored) {
        return errorResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiError("UNSUPPORTED_MEDIA_TYPE", "请求内容类型不受支持，请使用接口要求的格式。"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception error) {
        String requestId = UUID.randomUUID().toString();
        log.error("接口发生未处理异常，requestId={}，堆栈摘要={}", requestId, stackSummary(error));
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("X-Request-Id", requestId)
                .body(new ApiError("INTERNAL_ERROR", "服务暂时不可用，请稍后重试。"));
    }

    private static String stackSummary(Throwable error) {
        // 异常消息可能含凭证、SQL 或上游报文，只从异常类型和调用位置构造日志。
        StringJoiner chain = new StringJoiner(" <- ");
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cause = error;
        while (cause != null && visited.size() < MAX_EXCEPTION_DEPTH && visited.add(cause)) {
            StringBuilder summary = new StringBuilder(cause.getClass().getName());
            int frames = 0;
            for (StackTraceElement frame : cause.getStackTrace()) {
                // 保留实际抛出位置，即使它位于第三方库，再沿调用栈回溯到业务代码。
                if (frames == 0 || frame.getClassName().startsWith("com.company.policy.")) {
                    summary.append(" at ")
                            .append(frame.getClassName())
                            .append('.')
                            .append(frame.getMethodName());
                    if (frame.getLineNumber() >= 0) {
                        summary.append(':').append(frame.getLineNumber());
                    }
                    if (++frames == MAX_FRAMES_PER_EXCEPTION) {
                        break;
                    }
                }
            }
            chain.add(summary);
            cause = cause.getCause();
        }
        if (cause != null) {
            chain.add("…");
        }
        return chain.toString();
    }
}
