package com.company.policy.controller;

import com.company.policy.common.exception.ApiException;
import com.company.policy.service.PolicyCardCallback;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PolicyCardCallbackController {
    private static final int MAX_CALLBACK_BYTES = 64 * 1024;
    private final PolicyCardCallback callbacks;

    @Operation(summary = "接收飞书私聊初审卡的加密地址验证和按钮回调")
    @PostMapping(
            value = "/callbacks/feishu/policy-review",
            consumes = "application/json",
            produces = "application/json")
    public Map<String, ?> receive(
            InputStream input,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature)
            throws IOException {
        byte[] body = input.readNBytes(MAX_CALLBACK_BYTES + 1);
        if (body.length > MAX_CALLBACK_BYTES)
            throw new ApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "FEISHU_CALLBACK_TOO_LARGE", "飞书回调超过接收大小限制。");
        return callbacks.receive(body, timestamp, nonce, signature);
    }
}
