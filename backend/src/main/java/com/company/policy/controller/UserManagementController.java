package com.company.policy.controller;

import com.company.policy.dto.*;
import com.company.policy.service.*;
import com.company.policy.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name = "用户与平台分工")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class UserManagementController {
    private final UserManagementService users;
    private final LoginSessionService sessions;

    private CurrentUserResponse actor(HttpServletRequest request) {
        return sessions.requireLogin(request).user();
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "分页查询公司用户")
    @GetMapping
    public PageResponse<UserSummaryResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        return users.list(
                actor(request), new UserListQuery(page, pageSize, keyword, roleCode, status));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "读取平台成员、提交人及版本")
    @GetMapping("/platform-assignments")
    public List<PlatformTeam> platformOwners(HttpServletRequest request) {
        return users.platformOwners(actor(request));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "分页查找平台成员候选")
    @GetMapping("/platform-candidates")
    public PageResponse<PlatformTeam.Candidate> platformCandidates(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        return users.platformCandidates(actor(request), keyword, page, pageSize);
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "保存整个平台的成员分工")
    @PutMapping("/platform-assignments/{perspective}/{platformCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updatePlatforms(
            @PathVariable String perspective,
            @PathVariable String platformCode,
            @RequestBody UserPlatformsUpdate body,
            HttpServletRequest request) {
        users.updatePlatforms(actor(request), new ChannelPlatform(perspective, platformCode), body);
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "统计公司账号及有效管理员")
    @GetMapping("/stats")
    public UserStatsResponse stats(HttpServletRequest request) {
        return users.stats(actor(request));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "查询账号详情")
    @GetMapping("/{id}")
    public UserDetailResponse detail(@PathVariable long id, HttpServletRequest request) {
        return users.detail(actor(request), id);
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "按版本替换账号角色")
    @PatchMapping("/{id}/roles")
    public UserDetailResponse updateRole(
            @PathVariable long id,
            @jakarta.validation.Valid @RequestBody UserRoleUpdate body,
            HttpServletRequest request) {
        return users.updateRole(actor(request), id, body);
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "按版本启停账号")
    @PatchMapping("/{id}/status")
    public UserDetailResponse updateStatus(
            @PathVariable long id,
            @jakarta.validation.Valid @RequestBody UserStatusUpdate body,
            HttpServletRequest request) {
        return users.updateStatus(actor(request), id, body);
    }
}
