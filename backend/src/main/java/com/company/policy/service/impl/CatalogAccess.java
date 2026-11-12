package com.company.policy.service.impl;

import com.company.policy.common.config.JackyunProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.UserInfoMapper;
import com.company.policy.vo.CurrentUserResponse;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class CatalogAccess {
    static final List<String> BRANDS = List.of("OPPO手机", "一加手机");

    private final UserInfoMapper users;
    private final JackyunProperties config;

    CatalogAccess(UserInfoMapper users, JackyunProperties config) {
        this.users = users;
        this.config = config;
    }

    UserInfo requireAdmin(CurrentUserResponse session) {
        var user = users.findIdentity(session.tenantKey(), session.openId());
        if (user == null
                || !Objects.equals(session.id(), user.getId().toString())
                || !"ADMIN".equals(user.getRoleType())
                || !Integer.valueOf(0).equals(user.getIsDeleted())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "CATALOG_ADMIN_REQUIRED", "只有有效管理员可以访问商品管理。");
        }
        if (!config.hasImportScope()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_SCOPE_MISSING", "请先配置吉客云账套与飞书租户的绑定。");
        }
        if (!config.getTenantKey().equals(user.getTenantKey())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CATALOG_SCOPE_DENIED", "当前租户未授权访问此商品账套。");
        }
        return user;
    }
}
