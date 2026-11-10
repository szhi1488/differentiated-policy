package com.company.policy.controller;

import com.company.policy.dto.FeishuLoginRequest;
import com.company.policy.service.FeishuLoginService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.LoginChallengeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(
        name = "飞书用户免登",
        description = "POST 需可信 Origin 和 X-Policy-Request: 1；通过同源 HttpOnly Cookie 保存会话。")
public class AuthController {
    private final LoginSessionService sessions;
    private final FeishuLoginService loginService;

    public AuthController(LoginSessionService sessions, FeishuLoginService loginService) {
        this.sessions = sessions;
        this.loginService = loginService;
    }

    @PostMapping("/challenge")
    @Operation(summary = "创建登录 state", description = "state 与当前 Cookie 会话绑定，3 分钟内一次有效。再次创建使上一次失效。")
    public ResponseEntity<LoginChallengeResponse> challenge(HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(sessions.createChallenge(request.getSession()));
    }

    @PostMapping("/feishu/login")
    @Operation(
            summary = "交换飞书授权码、保存用户并建立会话",
            description =
                    "以 tenant_key + open_id 幂等写入 user_info，新用户为 APPLICANT，保留已有角色与部门。落库成功后轮换 Session ID；401 授权码/state 失效，403 账号停用，502 飞书异常，503 缺少配置或用户存储不可用。失败需重新获取 state/code。")
    public ResponseEntity<AuthSessionResponse> login(
            @Valid @RequestBody FeishuLoginRequest body, HttpServletRequest request) {
        sessions.consumeChallenge(request.getSession(false), body.state());
        var login = loginService.login(body.code());
        sessions.establish(request, login);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(login);
    }

    @GetMapping("/session")
    @Operation(
            summary = "读取当前登录身份",
            description =
                    "返回本地 user_info.id、roleType 及经飞书核验的身份。无会话或会话到期返回 401；角色为登录时快照，尚未接入管理员业务接口。")
    public ResponseEntity<AuthSessionResponse> current(HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(sessions.requireLogin(request));
    }

    @PostMapping("/logout")
    @Operation(summary = "退出当前应用会话", description = "幂等；不会退出飞书客户端或撤销飞书账号授权。")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.logout(request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
