package com.company.policy.controller;

import com.company.policy.dto.FeishuLoginRequest;
import com.company.policy.service.FeishuBrowserLoginService;
import com.company.policy.service.FeishuLoginService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.FeishuAuthorizationResponse;
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
    private final FeishuBrowserLoginService browserLogin;

    public AuthController(
            LoginSessionService sessions,
            FeishuLoginService loginService,
            FeishuBrowserLoginService browserLogin) {
        this.sessions = sessions;
        this.loginService = loginService;
        this.browserLogin = browserLogin;
    }

    @PostMapping("/feishu/browser/authorize")
    @Operation(
            summary = "创建浏览器飞书授权链接",
            description =
                    "需可信 Origin 和 X-Policy-Request: 1。回调固定为当前 Origin 的根路径 /，须加入飞书重定向 URL；state 与 Cookie 绑定，3 分钟有效。503 缺少配置，403 来源不可信。")
    public ResponseEntity<FeishuAuthorizationResponse> browserAuthorize(
            HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(browserLogin.authorize(request.getHeader("Origin"), request.getSession()));
    }

    @PostMapping("/feishu/browser/login")
    @Operation(
            summary = "交换浏览器网页授权码并建立会话",
            description =
                    "code/state 来自飞书回调，回调地址从服务端会话读取。401 state/code 无效，403 账号停用，502 上游异常，503 用户存储失败；失败须重新授权，成功轮换 Session ID。")
    public ResponseEntity<AuthSessionResponse> browserLogin(
            @Valid @RequestBody FeishuLoginRequest body, HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(browserLogin.login(body.code(), body.state(), request));
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
