package com.company.policy.controller;

import com.company.policy.dto.RoleDefinitionUpdate;
import com.company.policy.service.AuthorizationService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.AuthorizationRoles;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name = "岗位权限")
@RestController
@RequestMapping("/api/admin/roles")
@RequiredArgsConstructor
public class RoleController {
    private final AuthorizationService authorization;
    private final LoginSessionService sessions;

    @io.swagger.v3.oas.annotations.Operation(summary = "读取角色与权限目录")
    @GetMapping
    public AuthorizationRoles list(HttpServletRequest request) {
        return authorization.definitions(sessions.requireLogin(request).user());
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "新增企业岗位")
    @PostMapping
    public AuthorizationRoles.Role create(
            @RequestBody RoleDefinitionUpdate body, HttpServletRequest request) {
        return authorization.save(sessions.requireLogin(request).user(), null, body);
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "按版本修改岗位")
    @PutMapping("/{id}")
    public AuthorizationRoles.Role update(
            @PathVariable long id,
            @RequestBody RoleDefinitionUpdate body,
            HttpServletRequest request) {
        return authorization.save(sessions.requireLogin(request).user(), id, body);
    }
}
