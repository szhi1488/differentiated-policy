package com.company.policy.service.impl;

import com.company.policy.common.config.FeishuProperties;
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
    private final FeishuProperties config;

    CatalogAccess(UserInfoMapper users, FeishuProperties config) {
        this.users = users;
        this.config = config;
    }

    UserInfo requireApplicant(CurrentUserResponse session) {
        var user = users.findIdentity(session.tenantKey(), session.openId());
        if (!config.hasCompanyTenant()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "POLICY_SCOPE_MISSING", "请先配置公司飞书租户。");
        }
        if (user == null
                || !Objects.equals(session.id(), user.getId().toString())
                || !"APPLICANT".equals(user.getRoleType())
                || !Integer.valueOf(0).equals(user.getIsDeleted())
                || !config.getTenantKey().equals(user.getTenantKey())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "POLICY_ACCESS_DENIED", "只有本公司有效申请人可使用政策申请。");
        }
        return user;
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
        if (!config.hasCompanyTenant()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_SCOPE_MISSING", "请先配置公司飞书租户。");
        }
        if (!config.getTenantKey().equals(user.getTenantKey())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CATALOG_SCOPE_DENIED", "当前飞书账号不属于本公司。");
        }
        return user;
    }
}
