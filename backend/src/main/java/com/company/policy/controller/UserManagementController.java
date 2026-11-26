package com.company.policy.controller;

import com.company.policy.dto.UserListQuery;
import com.company.policy.dto.UserPlatformsUpdate;
import com.company.policy.dto.UserRoleUpdate;
import com.company.policy.dto.UserStatusUpdate;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.UserManagementService;
import com.company.policy.vo.PageResponse;
import com.company.policy.vo.UserDetailResponse;
import com.company.policy.vo.UserPlatformOwner;
import com.company.policy.vo.UserStatsResponse;
import com.company.policy.vo.UserSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "用户管理", description = "本公司有效管理员查询用户、调整角色和启停账号")
@ApiResponse(responseCode = "401", description = "LOGIN_REQUIRED：未登录或会话失效")
@ApiResponse(responseCode = "403", description = "USER_ADMIN_REQUIRED 或 USER_SCOPE_DENIED：无管理权限")
@ApiResponse(
        responseCode = "503",
        description = "USER_SCOPE_MISSING 或 USER_STORAGE_UNAVAILABLE：配置或存储不可用")
public class UserManagementController {
    private final UserManagementService users;
    private final LoginSessionService sessions;

    public UserManagementController(UserManagementService users, LoginSessionService sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    @GetMapping
    @ApiResponse(responseCode = "400", description = "USER_LIST_INVALID_REQUEST：筛选或分页参数无效")
    @Operation(
            summary = "分页查询本公司系统用户",
            description =
                    "按加入时间倒序，同秒按本地用户 ID 倒序。关键词按姓名或部门子串匹配，最多 128 字符，英文字母不区分大小写，通配符按普通字符查询。角色、状态不传或为空时不限；默认包含停用账号。重新读取当前管理员权限，不使用前端传入的公司范围。")
    public PageResponse<UserSummaryResponse> list(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页 1 至 100 条，默认 10 条") @RequestParam(defaultValue = "10")
                    int pageSize,
            @Parameter(description = "姓名或部门关键词") @RequestParam(required = false) String keyword,
            @Parameter(description = "APPLICANT 或 ADMIN") @RequestParam(required = false)
                    String roleType,
            @Parameter(description = "ENABLED 或 DISABLED") @RequestParam(required = false)
                    String status,
            HttpServletRequest request) {
        return users.list(
                sessions.requireLogin(request).user(),
                new UserListQuery(page, pageSize, keyword, roleType, status));
    }

    @GetMapping("/platform-assignments")
    @Operation(summary = "按渠道读取本公司各平台的负责人")
    public List<UserPlatformOwner> platformOwners(HttpServletRequest request) {
        return users.platformOwners(sessions.requireLogin(request).user());
    }

    @PatchMapping("/{id}/platforms")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "设置申请人的负责平台",
            description =
                    "同时传入 platforms 和 expectedPlatforms。每项包含 perspective 和 platformCode。空数组解除分配。每个渠道的平台独立分配，零售和百亿的同名平台可以由同一人或不同人负责。")
    @ApiResponse(responseCode = "409", description = "USER_PLATFORMS_CONFLICT：平台占用、账号或原分工已变化")
    public void updatePlatforms(
            @PathVariable long id,
            @RequestBody UserPlatformsUpdate body,
            HttpServletRequest request) {
        users.updatePlatforms(sessions.requireLogin(request).user(), id, body);
    }

    @GetMapping("/stats")
    @Operation(
            summary = "读取本公司用户全量统计",
            description =
                    "统计用户总数、启用申请人、启用管理员和停用人数。不接收列表筛选或分页条件，附带这些条件时忽略。实时核验本公司有效管理员，以一次聚合查询统计现有用户记录。")
    public UserStatsResponse stats(HttpServletRequest request) {
        return users.stats(sessions.requireLogin(request).user());
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "读取本公司用户详情",
            description =
                    "使用本地用户正整数 ID，允许查看启用和停用账号。返回已保存资料及应用内飞书 openId，不调用飞书、不修改账号；用户不存在或属于其他公司统一返回 404。")
    @ApiResponse(responseCode = "400", description = "USER_DETAIL_INVALID_REQUEST：用户标识不是有效的正整数")
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND：用户不存在或不在当前可见范围")
    public UserDetailResponse detail(
            @Parameter(description = "本地用户 ID，正整数，最大为 BIGINT 有符号上限") @PathVariable long id,
            HttpServletRequest request) {
        return users.detail(sessions.requireLogin(request).user(), id);
    }

    @PatchMapping("/{id}/role")
    @Operation(
            summary = "调整本公司用户角色",
            description =
                    "须携带目标 roleType、读取时的 expectedRoleType 和 expectedStatus。禁止修改自己的角色并保留至少一位有效管理员；只修改目标角色，不启用停用账号。须有可信 Origin 和 X-Policy-Request: 1。成功返回更新后的用户详情。")
    @ApiResponse(
            responseCode = "400",
            description = "INVALID_REQUEST 或 USER_ROLE_INVALID_REQUEST：请求体或用户标识无效")
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND：用户不存在或不在当前可见范围")
    @ApiResponse(
            responseCode = "409",
            description =
                    "USER_ROLE_CONFLICT、USER_ROLE_SELF_FORBIDDEN 或 USER_LAST_ADMIN_REQUIRED：数据变化或受保护操作")
    public UserDetailResponse updateRole(
            @PathVariable long id,
            @Valid @RequestBody UserRoleUpdate body,
            HttpServletRequest request) {
        return users.updateRole(sessions.requireLogin(request).user(), id, body);
    }

    @PatchMapping("/{id}/status")
    @Operation(
            summary = "启用或停用本公司用户",
            description =
                    "根据目标 status 和读取时的 expectedRoleType、expectedStatus 修改账号状态，保留原角色及业务历史。禁止停用本人，至少保留一位有效管理员。须有可信 Origin 和 X-Policy-Request: 1。成功返回当前用户详情。")
    @ApiResponse(
            responseCode = "400",
            description = "INVALID_REQUEST 或 USER_STATUS_INVALID_REQUEST：请求体或用户标识无效")
    @ApiResponse(
            responseCode = "403",
            description =
                    "USER_ADMIN_REQUIRED、USER_SCOPE_DENIED 或 REQUEST_ORIGIN_DENIED：无管理权限或请求来源无效")
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND：用户不存在或不在当前可见范围")
    @ApiResponse(
            responseCode = "409",
            description =
                    "USER_STATUS_CONFLICT、USER_STATUS_SELF_FORBIDDEN 或 USER_LAST_ADMIN_REQUIRED：数据变化或受保护操作")
    public UserDetailResponse updateStatus(
            @PathVariable long id,
            @Valid @RequestBody UserStatusUpdate body,
            HttpServletRequest request) {
        return users.updateStatus(sessions.requireLogin(request).user(), id, body);
    }
}
