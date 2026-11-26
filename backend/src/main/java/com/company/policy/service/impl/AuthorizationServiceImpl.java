package com.company.policy.service.impl;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.ChannelPlatform;
import com.company.policy.dto.RoleDefinitionUpdate;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.RoleAccessMapper;
import com.company.policy.mapper.UserManagementMapper;
import com.company.policy.mapper.UserPlatformMapper;
import com.company.policy.service.AuthorizationService;
import com.company.policy.service.CatalogAccess;
import com.company.policy.support.PlatformAccess;
import com.company.policy.vo.AuthorizationRoles;
import com.company.policy.vo.AuthorizationRoles.Permission;
import com.company.policy.vo.AuthorizationRoles.Role;
import com.company.policy.vo.CurrentUserResponse;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class AuthorizationServiceImpl implements AuthorizationService {
    private static final List<Permission> CATALOG =
            List.of(
                    new Permission("catalog.import", "源商品与导入"),
                    new Permission("catalog.clean", "商品清洗及异常审核"),
                    new Permission("catalog.manage", "标准商品与字典管理"),
                    new Permission("policy.apply", "本人政策填报与改期"),
                    new Permission("policy.read.platform_submitted", "负责平台已提交政策"),
                    new Permission("policy.read.company_submitted", "全公司正式政策"),
                    new Permission("policy.purchase.decide_assigned", "本轮指派采购终审"),
                    new Permission("policy.product.decide_assigned", "本轮指派产品初审"),
                    new Permission("policy.product.read_own_decisions", "本人初审记录"),
                    new Permission("subsidy.read.company", "机型补贴查询（采购按负责品牌）"),
                    new Permission("subsidy.read.platform", "负责平台机型补贴查询"),
                    new Permission("users.manage", "用户与账号管理"),
                    new Permission("roles.manage", "岗位与权限管理"),
                    new Permission("platforms.manage", "平台团队分工"),
                    new Permission("review.manage", "维护审核分工"));
    private static final List<String> ALL =
            CATALOG.stream().map(Permission::code).sorted().toList();
    private final RoleAccessMapper roles;
    private final UserPlatformMapper platforms;
    private final UserManagementMapper users;
    private final CatalogAccess access;
    private final PlatformAccess platformAccess;

    @Transactional
    public void ensureDefaults(String tenant) {
        roles.ensureAdministration(tenant);
        roles.lockAdministration(tenant);
        builtin(
                tenant,
                "OPERATIONS",
                "运营",
                List.of("policy.apply", "policy.read.platform_submitted", "subsidy.read.platform"));
        builtin(
                tenant,
                "PURCHASE",
                "采购",
                List.of(
                        "catalog.import",
                        "catalog.clean",
                        "policy.purchase.decide_assigned",
                        "subsidy.read.company"));
        builtin(
                tenant,
                "PRODUCT_OPS",
                "产品运营",
                List.of("policy.product.decide_assigned", "policy.product.read_own_decisions"));
        var existing =
                platforms.teams(tenant).stream()
                        .map(t -> new ChannelPlatform(t.perspective(), t.platformCode()))
                        .toList();
        for (var team : ChannelPlatform.all())
            if (!existing.contains(team)) platforms.ensureTeam(tenant, team);
    }

    private void builtin(String tenant, String code, String name, List<String> permissions) {
        if (roles.roleId(tenant, code) != null) return;
        roles.insertRole(tenant, code, name, true, true);
        long id = roles.roleId(tenant, code);
        permissions.forEach(p -> roles.grantPermission(tenant, id, p));
    }

    public CurrentUserResponse current(UserInfo account) {
        var codes = roles.userRoles(account.getTenantKey(), account.getId(), false);
        var permissions =
                codes.contains("ADMIN")
                        ? ALL
                        : roles.userPermissions(account.getTenantKey(), account.getId());
        return new CurrentUserResponse(
                account.getId().toString(),
                account.getOpenId(),
                account.getTenantKey(),
                account.getName(),
                codes,
                permissions,
                account.getAvatarUrl());
    }

    @Transactional(readOnly = true)
    public AuthorizationRoles definitions(CurrentUserResponse session) {
        var actor = access.requireActive(session);
        if (!access.hasPermission(actor, "roles.manage")) access.requireUserAdmin(session);
        String tenant = actor.getTenantKey();
        return new AuthorizationRoles(
                roles.roles(tenant).stream().map(r -> view(tenant, r)).toList(), CATALOG);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Role save(CurrentUserResponse session, Long roleId, RoleDefinitionUpdate input) {
        var actor = access.requirePermission(session, "roles.manage");
        String tenant = actor.getTenantKey();
        roles.lockAdministration(tenant);
        access.requirePermission(session, "roles.manage");
        if (input == null
                || input.roleCode() == null
                || !input.roleCode().matches(RoleDefinitionUpdate.CODE_PATTERN)
                || input.roleName() == null
                || input.roleName().isBlank()
                || input.roleName().strip().codePointCount(0, input.roleName().strip().length())
                        > 64
                || input.enabled() == null
                || input.permissions() == null
                || input.permissions().stream().anyMatch(p -> p == null || !ALL.contains(p)))
            throw invalid("请核对岗位编码、名称和权限。");
        var perms = new TreeSet<>(input.permissions());
        var operator = current(actor);
        if (!operator.permissions().containsAll(perms)) throw forbidden("只能配置本人有权授予的权限。");
        String code = input.roleCode(), name = input.roleName().strip();
        if (roleId == null) {
            if (roles.roleId(tenant, code) != null) throw conflict("岗位编码已存在。");
            roles.insertRole(tenant, code, name, false, input.enabled());
            roleId = roles.roleId(tenant, code);
        } else {
            var row = roles.lock(tenant, roleId);
            if (row == null)
                throw new ApiException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "岗位不存在。");
            if ("ADMIN".equals(row.roleCode()) || !code.equals(row.roleCode()))
                throw forbidden("系统管理员岗位受保护，岗位编码不能修改。");
            if (input.expectedVersion() == null || row.version() != input.expectedVersion())
                throw conflict("岗位设置已更新，请重新读取后确认。");
            if (!operator.permissions().containsAll(roles.permissions(tenant, roleId))
                    || (!operator.roleCodes().contains("ADMIN")
                            && operator.roleCodes().contains(code))) throw forbidden("无法修改该岗位的权限。");
            if (roles.updateRole(tenant, roleId, name, input.enabled(), row.version()) != 1)
                throw conflict("岗位设置已更新。");
            roles.clearPermissions(tenant, roleId);
        }
        for (var permission : perms) roles.grantPermission(tenant, roleId, permission);
        var affected = roles.assignedUsers(tenant, roleId);
        if (!affected.isEmpty()) {
            // 多人同岗时先一次锁齐账号，再按渠道平台顺序锁团队，与终审冻结平台收件人的顺序一致。
            var actors = new TreeSet<>(affected);
            actors.add(actor.getId());
            for (long id : actors) users.lockUser(tenant, id);
            platforms.lockAssignedTeams(tenant, roleId);
            for (long id : affected) platformAccess.reconcile(tenant, id, actor.getId());
        }
        return view(tenant, roles.lock(tenant, roleId));
    }

    private Role view(String tenant, RoleAccessMapper.RoleRow row) {
        return new Role(
                Long.toString(row.id()),
                row.roleCode(),
                row.roleName(),
                row.isBuiltin(),
                row.isEnabled(),
                row.version(),
                "ADMIN".equals(row.roleCode()) ? ALL : roles.permissions(tenant, row.id()));
    }

    private static ApiException invalid(String s) {
        return new ApiException(HttpStatus.BAD_REQUEST, "ROLE_INVALID", s);
    }

    private static ApiException forbidden(String s) {
        return new ApiException(HttpStatus.FORBIDDEN, "ROLE_FORBIDDEN", s);
    }

    private static ApiException conflict(String s) {
        return new ApiException(HttpStatus.CONFLICT, "ROLE_CONFLICT", s);
    }
}
