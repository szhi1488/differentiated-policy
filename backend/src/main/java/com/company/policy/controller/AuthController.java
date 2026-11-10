package com.company.policy.controller;

import com.company.policy.dto.FeishuLoginRequest;
import com.company.policy.service.FeishuLoginService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.AuthSessionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "飞书用户免登", description = "POST 需可信 Origin 和 X-Policy-Request: 1；通过同源 HttpOnly Cookie 保存会话。")
public class AuthController {
    private final LoginSessionService sessions;
    private final FeishuLoginService loginService;

    public AuthController(LoginSessionService sessions, FeishuLoginService loginService) {
        this.sessions = sessions;
        this.loginService = loginService;
    }

    @PostMapping("/challenge")
    @Operation(summary = "创建登录 state", description = "state 与当前 Cookie 会话绑定，3 分钟内一次有效。再次创建使上一次失效。")
    public ResponseEntity<LoginSessionService.Challenge> challenge(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.createChallenge(request.getSession()));
    }

    @PostMapping("/feishu/login")
    @Operation(summary = "交换飞书一次性授权码并建立应用会话", description = "成功轮换 Session ID；401 授权码/state 失效，403 用户不可用，502 飞书服务异常，503 缺少配置。失败必须重新获取 state 和 code。")
    public ResponseEntity<AuthSessionResponse> login(@Valid @RequestBody FeishuLoginRequest body, HttpServletRequest request) {
        sessions.consumeChallenge(request.getSession(false), body.state());
        var login = loginService.login(body.code());
        sessions.establish(request, login);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(login);
    }

    @GetMapping("/session")
    @Operation(summary = "读取当前登录身份", description = "无会话或会话到期返回 401。身份依据 tenantKey + openId，不授予管理员权限。")
    public ResponseEntity<AuthSessionResponse> current(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions.requireLogin(request));
    }

    @PostMapping("/logout")
    @Operation(summary = "退出当前应用会话", description = "幂等；不会退出飞书客户端或撤销飞书账号授权。")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
