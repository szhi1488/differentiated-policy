package com.company.policy.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException upstream() {
        return new ApiException(
                HttpStatus.BAD_GATEWAY, "FEISHU_UPSTREAM_ERROR", "飞书服务调用失败，请检查应用凭证、应用状态和网络后重试。");
    }
}
