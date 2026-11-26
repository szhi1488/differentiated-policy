package com.company.policy.service.impl;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.*;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.*;
import com.company.policy.mapper.UserManagementMapper.DetailRow;
import com.company.policy.service.*;
import com.company.policy.support.PlatformAccess;
import com.company.policy.vo.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class UserManagementServiceImpl implements UserManagementService {
    private final CatalogAccess access;
    private final UserManagementMapper users;
    private final RoleAccessMapper roles;
    private final UserPlatformMapper platforms;
    private final PlatformAccess platformAccess;
    private final ExecutionNotices notices;
    private final Clock clock;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public PageResponse<UserSummaryResponse> list(CurrentUserResponse session, UserListQuery q) {
        String tenant = access.requireUserAdmin(session).getTenantKey();
        long start = offset(q.page(), q.pageSize());
        String role = optional(q.roleCode()), status = optional(q.status());
        if (role != null && roles.roleId(tenant, role) == null
                || status != null && !Set.of("ENABLED", "DISABLED").contains(status))
            throw invalid("USER_LIST_INVALID_REQUEST", "请核对角色及状态筛选条件。");
        var filters = new UserListQuery(q.page(), q.pageSize(), pattern(q.keyword()), role, status);
        long total = users.count(tenant, filters);
        if (start >= total) return new PageResponse<>(List.of(), total);
        var rows = users.list(tenant, filters, start);
        var pageRoles =
                roles.rolesForUsers(tenant, rows.stream().map(DetailRow::id).toList()).stream()
                        .collect(
                                Collectors.groupingBy(
                                        RoleAccessMapper.UserRole::userId,
                                        Collectors.mapping(
                                                RoleAccessMapper.UserRole::roleCode,
                                                Collectors.toList())));
        var result =
                rows.stream()
                        .map(
                                user ->
                                        new UserSummaryResponse(
                                                user.id().toString(),
                                                user.name(),
                                                user.avatarUrl(),
                                                user.department(),
                                                pageRoles.getOrDefault(user.id(), List.of()),
                                                user.authVersion(),
                                                status(user),
                                                user.createdAt().format(TIME)))
                        .toList();
        return new PageResponse<>(result, total);
    }

    public PageResponse<PolicyReviewerCandidate> reviewCandidates(
            CurrentUserResponse session,
            String group,
            String name,
            String department,
            int page,
            int size) {
        String tenant = access.requirePermission(session, "review.manage").getTenantKey();
        if (!"PRODUCT".equals(group) && !"PURCHASE".equals(group))
            throw invalid("USER_LIST_INVALID_REQUEST", "审核岗位无效。");
        long start = offset(page, size);
        var query =
                new UserListQuery(
                        page,
                        size,
                        null,
                        null,
                        "ENABLED",
                        pattern(name),
                        pattern(department),
                        null);
        long total = users.countReviewCandidates(tenant, query, group);
        return new PageResponse<>(
                start >= total ? List.of() : users.reviewCandidates(tenant, query, group, start),
                total);
    }

    public PageResponse<PlatformTeam.Candidate> platformCandidates(
            CurrentUserResponse session, String keyword, int page, int size) {
        String tenant = access.requirePermission(session, "platforms.manage").getTenantKey();
        var query =
                new UserListQuery(
                        page,
                        size,
                        pattern(keyword),
                        null,
                        "ENABLED",
                        null,
                        null,
                        "PLATFORM_MEMBER");
        long start = offset(page, size);
        long total = users.count(tenant, query);
        return new PageResponse<>(
                start >= total ? List.of() : users.platformCandidates(tenant, query, start), total);
    }

    public UserStatsResponse stats(CurrentUserResponse session) {
        var s = users.stats(access.requireUserAdmin(session).getTenantKey());
        return new UserStatsResponse(
                s.total(), s.enabledUsers(), s.enabledAdmins(), s.disabledUsers());
    }

    public UserDetailResponse detail(CurrentUserResponse session, long id) {
        String tenant = access.requireUserAdmin(session).getTenantKey();
        if (id < 1) throw invalid("USER_DETAIL_INVALID_REQUEST", "用户标识须为正整数。");
        return response(tenant, users.detail(tenant, id));
    }

    private UserDetailResponse response(String tenant, DetailRow u) {
        if (u == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在或不在当前可见范围内。");
        return new UserDetailResponse(
                u.id().toString(),
                u.name(),
                u.avatarUrl(),
                u.department(),
                u.openId(),
                roles.userRoles(tenant, u.id(), true),
                u.authVersion(),
                status(u),
                u.createdAt().format(TIME));
    }

    private UserInfo managementActor(CurrentUserResponse session, String permission) {
        var actor = access.requirePermission(session, permission);
        // 同企业授权写入共用管理员角色行。取得锁后重新核验，避免沿用等待前的权限。
        roles.lockAdministration(actor.getTenantKey());
        return access.requirePermission(session, permission);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserDetailResponse updateRole(
            CurrentUserResponse session, long id, UserRoleUpdate input) {
        if (id <= 0
                || input == null
                || input.expectedVersion() == null
                || input.roleCodes() == null
                || input.roleCodes().stream()
                        .anyMatch(
                                code ->
                                        code == null
                                                || !code.matches(
                                                        RoleDefinitionUpdate.CODE_PATTERN)))
            throw invalid("USER_ROLE_INVALID_REQUEST", "请提供目标用户、岗位列表和账号版本。");
        var actor = managementActor(session, "users.manage");
        var tenant = actor.getTenantKey();
        var u = lock(tenant, actor.getId(), id, input.expectedVersion(), "USER_ROLE_CONFLICT");

        var desired = new TreeSet<>(input.roleCodes());
        var previous = new TreeSet<>(roles.userRoles(tenant, id, true));
        if (previous.equals(desired)) return response(tenant, u);
        if (previous.contains("ADMIN") && !desired.contains("ADMIN") && u.isDeleted() == 0)
            preserveAdmin(tenant);
        if (id == actor.getId()) throw conflict("USER_ROLE_SELF_FORBIDDEN", "不能调整本人角色。");
        Set<String> changed = new HashSet<>(desired);
        changed.addAll(previous);
        boolean administrator = roles.userRoles(tenant, actor.getId(), false).contains("ADMIN");
        var grantable =
                administrator ? List.<String>of() : roles.userPermissions(tenant, actor.getId());
        for (String code : changed) {
            if (desired.contains(code) == previous.contains(code)) continue;
            var rid = roles.roleId(tenant, code);
            if (rid == null || desired.contains(code) && !roles.lock(tenant, rid).isEnabled())
                throw invalid("USER_ROLE_INVALID_REQUEST", "选择的岗位不存在或已停用。");
            if (!administrator
                    && ("ADMIN".equals(code)
                            || !grantable.containsAll(roles.permissions(tenant, rid))))
                throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_FORBIDDEN", "无权授予或撤销该岗位。");
        }
        roles.removeRoles(tenant, id);
        for (String code : desired) roles.grantRole(tenant, id, roles.roleId(tenant, code));
        advance(tenant, u, u.isDeleted(), "USER_ROLE_CONFLICT");
        platformAccess.reconcile(tenant, id, actor.getId());
        return response(tenant, users.detail(tenant, id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserDetailResponse updateStatus(
            CurrentUserResponse session, long id, UserStatusUpdate input) {
        if (id <= 0
                || input == null
                || input.status() == null
                || !Set.of("ENABLED", "DISABLED").contains(input.status())
                || input.expectedVersion() == null)
            throw invalid("USER_STATUS_INVALID_REQUEST", "请提供用户、状态和账号版本。");
        var actor = managementActor(session, "users.manage");
        String tenant = actor.getTenantKey();
        var u = lock(tenant, actor.getId(), id, input.expectedVersion(), "USER_STATUS_CONFLICT");
        int deleted = "DISABLED".equals(input.status()) ? 1 : 0;

        if (u.isDeleted() == deleted) return response(tenant, u);
        if (roles.userRoles(tenant, id, false).contains("ADMIN")) {
            if (!roles.userRoles(tenant, actor.getId(), false).contains("ADMIN"))
                throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_FORBIDDEN", "仅系统管理员可以启停管理员账号。");
            if (deleted == 1) preserveAdmin(tenant);
        }
        if (id == actor.getId() && deleted == 1)
            throw conflict("USER_STATUS_SELF_FORBIDDEN", "不能停用本人。");
        advance(tenant, u, deleted, "USER_STATUS_CONFLICT");
        if (deleted == 1) platformAccess.reconcile(tenant, id, actor.getId());
        return response(tenant, users.detail(tenant, id));
    }

    private DetailRow lock(String tenant, long actorId, long id, int version, String code) {
        // 平台交接写入 updated_by 外键，操作账号和目标账号都按 ID 升序持锁。
        if (actorId < id) users.lockUser(tenant, actorId);
        var u = users.lockUser(tenant, id);
        if (actorId > id) users.lockUser(tenant, actorId);
        if (u == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在或不在当前可见范围内。");
        if (version != u.authVersion()) throw conflict(code, "账号已更新，请重新核对。");
        return u;
    }

    private void advance(String tenant, DetailRow u, int deleted, String code) {
        if (users.change(
                        tenant,
                        u.id(),
                        deleted,
                        u.authVersion(),
                        LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Shanghai"))
                                .withNano(0))
                != 1) throw conflict(code, "账号已更新，请重新核对。");
    }

    private void preserveAdmin(String tenant) {
        if (users.stats(tenant).enabledAdmins() <= 1)
            throw conflict("USER_LAST_ADMIN_REQUIRED", "须保留至少一名启用的系统管理员。");
    }

    public List<PlatformTeam> platformOwners(CurrentUserResponse session) {
        String tenant = access.requirePermission(session, "platforms.manage").getTenantKey();
        var owners =
                platforms.owners(tenant).stream()
                        .collect(
                                Collectors.groupingBy(
                                        m ->
                                                new ChannelPlatform(
                                                        m.perspective(), m.platformCode())));
        var teams = new ArrayList<PlatformTeam>();
        for (var team : platforms.teams(tenant)) {
            var scope = new ChannelPlatform(team.perspective(), team.platformCode());
            var members =
                    owners.getOrDefault(scope, List.of()).stream()
                            .map(
                                    m ->
                                            new PlatformTeam.Member(
                                                    m.userId(),
                                                    m.name(),
                                                    m.memberKind(),
                                                    m.canSubmit()))
                            .toList();
            teams.add(
                    new PlatformTeam(
                            scope.perspective(), scope.platformCode(), team.version(), members));
        }
        return teams;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void updatePlatforms(
            CurrentUserResponse session, ChannelPlatform scope, UserPlatformsUpdate input) {
        if (!scope.valid()
                || input == null
                || input.expectedVersion() == null
                || input.members() == null)
            throw invalid("USER_PLATFORMS_INVALID", "请核对平台、分工成员及版本。");
        var desired = new TreeMap<Long, String>();
        for (var m : input.members()) {
            if (m == null
                    || m.memberKind() == null
                    || !Set.of("MEMBER", "SUBMITTER").contains(m.memberKind())
                    || m.userId() == null
                    || !m.userId().matches("[1-9][0-9]{0,18}"))
                throw invalid("USER_PLATFORMS_INVALID", "请核对成员和职责。");
            try {
                if (desired.putIfAbsent(Long.parseLong(m.userId()), m.memberKind()) != null)
                    throw invalid("USER_PLATFORMS_INVALID", "同一成员不能重复。");
            } catch (NumberFormatException e) {
                throw invalid("USER_PLATFORMS_INVALID", "成员编号无效。");
            }
        }
        if (!desired.isEmpty() && Collections.frequency(desired.values(), "SUBMITTER") != 1)
            throw invalid("USER_PLATFORMS_INVALID", "每个平台需要指定一位提交人。");
        var actor = managementActor(session, "platforms.manage");
        String tenant = actor.getTenantKey();
        var previous =
                platforms.teamMembers(tenant, scope).stream()
                        .collect(
                                Collectors.toMap(
                                        UserPlatformMapper.MemberRow::userId,
                                        UserPlatformMapper.MemberRow::memberKind));
        var accounts = new TreeSet<>(previous.keySet());
        accounts.addAll(desired.keySet());
        accounts.add(actor.getId());
        for (long id : accounts) {
            var account = users.lockUser(tenant, id);
            if (!desired.containsKey(id)) continue;
            boolean submit = roles.hasPermission(tenant, id, "policy.apply") > 0;
            if (account == null
                    || account.isDeleted() != 0
                    || (!submit
                            && ("SUBMITTER".equals(desired.get(id))
                                    || !platformAccess.canRead(tenant, id))))
                throw invalid("USER_PLATFORMS_INVALID", "成员的账号或岗位不符合该项分工。");
        }
        Integer version = platforms.lockTeam(tenant, scope);
        if (version == null || !version.equals(input.expectedVersion()))
            throw conflict("USER_PLATFORMS_CONFLICT", "平台分工已更新，请重新核对。");
        if (previous.equals(desired)) return;
        platforms.clearTeam(tenant, scope);
        desired.forEach((id, kind) -> platforms.assign(tenant, id, scope, kind));
        platforms.advance(tenant, scope, actor.getId());
        Long outgoing = submitter(previous), incoming = submitter(desired);
        if (!Objects.equals(outgoing, incoming))
            notices.platformChanged(tenant, scope, outgoing, incoming);
    }

    private static Long submitter(Map<Long, String> members) {
        return members.entrySet().stream()
                .filter(e -> "SUBMITTER".equals(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private static long offset(int p, int size) {
        if (p < 1 || size < 1 || size > 100)
            throw invalid("USER_LIST_INVALID_REQUEST", "页码须为正数，每页 1 至 100 条。");
        return (p - 1L) * size;
    }

    private static String optional(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String pattern(String s) {
        s = optional(s);
        if (s == null) return null;
        if (s.codePointCount(0, s.length()) > 128)
            throw invalid("USER_LIST_INVALID_REQUEST", "关键词最多 128 个字符。");
        return "%" + s.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static String status(DetailRow u) {
        return u.isDeleted() == 0 ? "ENABLED" : "DISABLED";
    }

    private static ApiException invalid(String code, String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, msg);
    }

    private static ApiException conflict(String code, String msg) {
        return new ApiException(HttpStatus.CONFLICT, code, msg);
    }
}
