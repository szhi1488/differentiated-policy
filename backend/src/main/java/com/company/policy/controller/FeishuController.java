package com.company.policy.controller;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.dto.JsSdkSignatureRequest;
import com.company.policy.service.JsSdkSignatureService;
import com.company.policy.vo.FeishuConfigResponse;
import com.company.policy.vo.JsSdkSignatureResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feishu")
@Tag(name = "飞书 JS-SDK", description = "本地网页应用初始化。此接口不建立用户登录态。")
public class FeishuController {
    private final FeishuProperties properties;
    private final JsSdkSignatureService signatureService;

    public FeishuController(FeishuProperties properties, JsSdkSignatureService signatureService) {
        this.properties = properties;
        this.signatureService = signatureService;
    }

    @GetMapping("/config")
    @Operation(summary = "读取公开应用配置", description = "sdkEnabled 仅表示后端配置完整；响应不包含 App Secret。")
    public ResponseEntity<FeishuConfigResponse> config() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new FeishuConfigResponse(properties.getAppId(), properties.isConfigured()));
    }

    @PostMapping("/js-sdk/signature")
    @Operation(summary = "生成当前页面的 JS-SDK 签名", description = "只接受 allowed-origins 中的页面来源；错误返回 {code,message}，包括 400 参数错误、503 未配置、502 上游不可用。")
    public ResponseEntity<JsSdkSignatureResponse> signature(@Valid @RequestBody JsSdkSignatureRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(signatureService.sign(request.url()));
    }
}
