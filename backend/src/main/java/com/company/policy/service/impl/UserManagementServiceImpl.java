package com.company.policy.service.impl;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.UserListQuery;
import com.company.policy.dto.UserRoleUpdate;
import com.company.policy.dto.UserStatusUpdate;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.UserManagementMapper;
import com.company.policy.mapper.UserManagementMapper.DetailRow;
import com.company.policy.service.UserManagementService;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PageResponse;
import com.company.policy.vo.UserDetailResponse;
import com.company.policy.vo.UserStatsResponse;
import com.company.policy.vo.UserSummaryResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class UserManagementServiceImpl implements UserManagementService {
    private static final Set<String> ROLES = Set.of("APPLICANT", "ADMIN");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final CatalogAccess access;
    private final UserManagementMapper users;
    private final Clock clock;

    public UserManagementServiceImpl(
            CatalogAccess access, UserManagementMapper users, Clock clock) {
        this.access = access;
        this.users = users;
        this.clock = clock;
    }

    @Override
    public PageResponse<UserSummaryResponse> list(
            CurrentUserResponse session, UserListQuery query) {
        var admin = access.requireUserAdmin(session);
        if (query.page() < 1 || query.pageSize() < 1 || query.pageSize() > 100) {
            throw invalid("页码须从 1 开始，每页条数须在 1 至 100 之间。");
        }
        String keyword = optional(query.keyword());
        if (keyword != null && keyword.codePointCount(0, keyword.length()) > 128) {
            throw invalid("关键词最多 128 个字符。");
        }
        String role = filter(query.roleType(), ROLES, "系统角色");
        String status = filter(query.status(), STATUSES, "账号状态");
        var filters =
                new UserListQuery(query.page(), query.pageSize(), like(keyword), role, status);
        long offset = (query.page() - 1L) * query.pageSize();
        long total = users.count(admin.getTenantKey(), filters);
        var items =
                offset >= total
                        ? List.<UserSummaryResponse>of()
                        : users.list(admin.getTenantKey(), filters, offset).stream()
                                .map(
                                        user ->
                                                new UserSummaryResponse(
                                                        user.id().toString(),
                                                        user.name(),
                                                        user.avatarUrl(),
                                                        user.department(),
                                                        user.roleType(),
                                                        statusOf(user.isDeleted()),
                                                        user.createdAt().format(TIME)))
                                .toList();
        return new PageResponse<>(items, total);
    }

    @Override
    public UserStatsResponse stats(CurrentUserResponse session) {
        var admin = access.requireUserAdmin(session);
        var counts = users.stats(admin.getTenantKey());
        return new UserStatsResponse(
                counts.total(),
                counts.enabledApplicants(),
                counts.enabledAdmins(),
                counts.disabledUsers());
    }

    @Override
    public UserDetailResponse detail(CurrentUserResponse session, long id) {
        var admin = access.requireUserAdmin(session);
        if (id < 1) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "USER_DETAIL_INVALID_REQUEST", "用户标识须为正整数。");
        }
        var user = users.detail(admin.getTenantKey(), id);
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在或不在当前可见范围内。");
        }
        return detailResponse(user);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserDetailResponse updateRole(
            CurrentUserResponse session, long id, UserRoleUpdate request) {
        var admin = access.requireUserAdmin(session);
        if (id < 1
                || request == null
                || request.roleType() == null
                || !ROLES.contains(request.roleType())
                || !validExpectedValues(request.expectedRoleType(), request.expectedStatus())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "USER_ROLE_INVALID_REQUEST", "请核对用户标识、目标角色及读取时的角色和状态。");
        }
        var target = lockTarget(admin, session, id);
        if (admin.getId() == id) {
            if ("APPLICANT".equals(request.roleType())) {
                requireAnotherAdmin(admin.getTenantKey());
            }
            throw conflict("USER_ROLE_SELF_FORBIDDEN", "不能调整自己的角色，请由其他管理员操作。");
        }
        requireExpectedValues(
                target, request.expectedRoleType(), request.expectedStatus(), "USER_ROLE_CONFLICT");
        if (target.roleType().equals(request.roleType())) {
            return detailResponse(target);
        }
        if (users.updateRole(
                        admin.getTenantKey(),
                        id,
                        request.roleType(),
                        target.roleType(),
                        target.isDeleted(),
                        now())
                != 1) {
            throw stale("USER_ROLE_CONFLICT");
        }
        return detailResponse(users.detail(admin.getTenantKey(), id));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserDetailResponse updateStatus(
            CurrentUserResponse session, long id, UserStatusUpdate request) {
        var admin = access.requireUserAdmin(session);
        if (id < 1
                || request == null
                || request.status() == null
                || !STATUSES.contains(request.status())
                || !validExpectedValues(request.expectedRoleType(), request.expectedStatus())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "USER_STATUS_INVALID_REQUEST",
                    "请核对用户标识、目标状态及读取时的角色和状态。");
        }
        var target = lockTarget(admin, session, id);
        int deleted = "DISABLED".equals(request.status()) ? 1 : 0;
        if (admin.getId() == id && deleted == 1) {
            requireAnotherAdmin(admin.getTenantKey());
            throw conflict("USER_STATUS_SELF_FORBIDDEN", "不能停用自己的账号，请由其他管理员操作。");
        }
        requireExpectedValues(
                target,
                request.expectedRoleType(),
                request.expectedStatus(),
                "USER_STATUS_CONFLICT");
        if (target.isDeleted() == deleted) {
            return detailResponse(target);
        }
        if (users.updateStatus(
                        admin.getTenantKey(),
                        id,
                        deleted,
                        target.roleType(),
                        target.isDeleted(),
                        now())
                != 1) {
            throw stale("USER_STATUS_CONFLICT");
        }
        return detailResponse(users.detail(admin.getTenantKey(), id));
    }

    private DetailRow lockTarget(UserInfo admin, CurrentUserResponse session, long id) {
        long actorId = admin.getId();
        long firstId = Math.min(actorId, id);
        long secondId = Math.max(actorId, id);
        var first = users.lockUser(admin.getTenantKey(), firstId);
        var second = firstId == secondId ? first : users.lockUser(admin.getTenantKey(), secondId);
        var actor = actorId == firstId ? first : second;
        var target = id == firstId ? first : second;
        // 角色和启停共用锁顺序。等待期间操作人可能已被降级或停用，须按锁内身份授权。
        if (actor == null
                || !actor.openId().equals(session.openId())
                || !"ADMIN".equals(actor.roleType())
                || actor.isDeleted() != 0) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_ADMIN_REQUIRED", "只有有效管理员可以管理用户。");
        }
        if (target == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在或不在当前可见范围内。");
        }
        return target;
    }

    // 操作他人时，已锁定的操作人本身就是另一位有效管理员。
    private void requireAnotherAdmin(String tenantKey) {
        if (users.stats(tenantKey).enabledAdmins() <= 1) {
            throw conflict("USER_LAST_ADMIN_REQUIRED", "须保留至少一位有效管理员。");
        }
    }

    private static boolean validExpectedValues(String role, String status) {
        return role != null && ROLES.contains(role) && status != null && STATUSES.contains(status);
    }

    private static void requireExpectedValues(
            DetailRow target, String role, String status, String code) {
        if (!target.roleType().equals(role) || !statusOf(target.isDeleted()).equals(status)) {
            throw stale(code);
        }
    }

    private static ApiException stale(String code) {
        return conflict(code, "用户角色或状态已变化，请重新读取详情后确认。");
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Shanghai")).withNano(0);
    }

    private static String statusOf(int deleted) {
        return deleted == 0 ? "ENABLED" : "DISABLED";
    }

    private static UserDetailResponse detailResponse(DetailRow user) {
        return new UserDetailResponse(
                user.id().toString(),
                user.name(),
                user.avatarUrl(),
                user.department(),
                user.openId(),
                user.roleType(),
                statusOf(user.isDeleted()),
                user.createdAt().format(TIME));
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String filter(String value, Set<String> allowed, String label) {
        String normalized = optional(value);
        if (normalized != null && !allowed.contains(normalized)) {
            throw invalid(label + "无效。");
        }
        return normalized;
    }

    private static String like(String keyword) {
        return keyword == null
                ? null
                : "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "USER_LIST_INVALID_REQUEST", message);
    }
}
