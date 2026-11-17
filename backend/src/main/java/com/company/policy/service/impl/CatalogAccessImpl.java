package com.company.policy.service.impl;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.UserInfoMapper;
import com.company.policy.service.CatalogAccess;
import com.company.policy.vo.CurrentUserResponse;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CatalogAccessImpl implements CatalogAccess {
    private final UserInfoMapper users;
    private final FeishuProperties config;

    public CatalogAccessImpl(UserInfoMapper users, FeishuProperties config) {
        this.users = users;
        this.config = config;
    }

    @Override
    public UserInfo requireApplicant(CurrentUserResponse session) {
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

    @Override
    public UserInfo requireAdmin(CurrentUserResponse session) {
        return requireAdministrator(session, "CATALOG", "商品管理");
    }

    @Override
    public UserInfo requirePolicyAdmin(CurrentUserResponse session) {
        return requireAdministrator(session, "POLICY", "政策审核");
    }

    @Override
    public UserInfo requireUserAdmin(CurrentUserResponse session) {
        return requireAdministrator(session, "USER", "用户管理");
    }

    private UserInfo requireAdministrator(CurrentUserResponse session, String domain, String page) {
        var user = users.findIdentity(session.tenantKey(), session.openId());
        if (user == null
                || !Objects.equals(session.id(), user.getId().toString())
                || !"ADMIN".equals(user.getRoleType())
                || !Integer.valueOf(0).equals(user.getIsDeleted())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, domain + "_ADMIN_REQUIRED", "只有有效管理员可以访问" + page + "。");
        }
        if (!config.hasCompanyTenant()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, domain + "_SCOPE_MISSING", "请先配置公司飞书租户。");
        }
        if (!config.getTenantKey().equals(user.getTenantKey())) {
            throw new ApiException(HttpStatus.FORBIDDEN, domain + "_SCOPE_DENIED", "当前飞书账号不属于本公司。");
        }
        return user;
    }
}
