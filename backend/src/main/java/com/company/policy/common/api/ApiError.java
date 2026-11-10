package com.company.policy.common.api;

import io.swagger.v3.oas.annotations.media.Schema;

public record ApiError(
        @Schema(description = "稳定的错误代码", example = "INVALID_PAGE_URL") String code,
        @Schema(description = "可展示的错误提示，不含凭证或上游响应") String message) {
}
