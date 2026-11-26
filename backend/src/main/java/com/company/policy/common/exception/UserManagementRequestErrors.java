package com.company.policy.common.exception;

import com.company.policy.common.api.ApiError;
import org.springframework.web.method.HandlerMethod;

final class UserManagementRequestErrors {
    private UserManagementRequestErrors() {}

    static ApiError invalidParameter(HandlerMethod handler, String parameterName) {
        if (!"id".equals(parameterName))
            return new ApiError("USER_LIST_INVALID_REQUEST", "分页参数须为有效整数。");
        String code =
                switch (handler.getMethod().getName()) {
                    case "updateRole" -> "USER_ROLE_INVALID_REQUEST";
                    case "updateStatus" -> "USER_STATUS_INVALID_REQUEST";
                    case "updatePlatforms" -> "USER_PLATFORMS_INVALID";
                    default -> "USER_DETAIL_INVALID_REQUEST";
                };
        return new ApiError(code, "用户标识须为有效的正整数。");
    }

    static ApiError lockConflict(HandlerMethod handler) {
        String message = "用户正在被其他操作修改，请重新读取详情后重试。";
        return switch (handler.getMethod().getName()) {
            case "updateRole" -> new ApiError("USER_ROLE_CONFLICT", message);
            case "updateStatus" -> new ApiError("USER_STATUS_CONFLICT", message);
            case "updatePlatforms" -> new ApiError("USER_PLATFORMS_CONFLICT", message);
            default -> new ApiError("USER_CONFLICT", "用户信息正在更新，请稍后重试。");
        };
    }

    static ApiError storageUnavailable() {
        return new ApiError("USER_STORAGE_UNAVAILABLE", "用户信息暂不可用，请稍后重试。");
    }
}
