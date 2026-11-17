package com.company.policy.service.impl;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.UserInfoMapper;
import com.company.policy.service.UserInfoService;
import com.company.policy.vo.CurrentUserResponse;
import java.net.URI;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserInfoServiceImpl implements UserInfoService {
    private final UserInfoMapper mapper;
    private final FeishuProperties config;

    public UserInfoServiceImpl(UserInfoMapper mapper, FeishuProperties config) {
        this.mapper = mapper;
        this.config = config;
    }

    @Transactional
    @Override
    public CurrentUserResponse saveVerifiedIdentity(
            String tenantKey, String openId, String unionId, String name, String avatarUrl) {
        validate(tenantKey, 64);
        validate(openId, 64);
        validate(name, 64);
        if (unionId != null) {
            validate(unionId, 64);
        }
        var identity = new UserInfo();
        identity.setTenantKey(tenantKey);
        identity.setOpenId(openId);
        identity.setUnionId(unionId);
        identity.setName(name);
        identity.setAvatarUrl(normalizeAvatarUrl(avatarUrl));
        try {
            mapper.upsertVerifiedIdentity(identity);
            var saved = mapper.findIdentity(tenantKey, openId);
            if (saved == null) {
                throw databaseUnavailable();
            }
            requireEnabled(saved);
            return new CurrentUserResponse(
                    saved.getId().toString(),
                    saved.getOpenId(),
                    saved.getTenantKey(),
                    saved.getName(),
                    saved.getRoleType(),
                    saved.getAvatarUrl());
        } catch (DataAccessException ignored) {
            throw databaseUnavailable();
        }
    }

    @Override
    public CurrentUserResponse restoreSessionUser(CurrentUserResponse identity) {
        if (!config.hasCompanyTenant()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "FEISHU_TENANT_NOT_CONFIGURED", "请先配置公司飞书租户。");
        }
        if (!config.getTenantKey().equals(identity.tenantKey())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FEISHU_TENANT_DENIED", "当前飞书账号不属于本公司。");
        }
        UserInfo saved;
        try {
            saved = mapper.findIdentity(identity.tenantKey(), identity.openId());
        } catch (DataAccessException ignored) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "USER_STORAGE_UNAVAILABLE",
                    "暂时无法核对账号角色，请稍后重试。");
        }
        if (saved == null || !identity.id().equals(saved.getId().toString())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "登录身份已变化，请重新通过飞书授权。");
        }
        requireEnabled(saved);
        return new CurrentUserResponse(
                identity.id(),
                identity.openId(),
                identity.tenantKey(),
                identity.name(),
                saved.getRoleType(),
                identity.avatarUrl());
    }

    private static void requireEnabled(UserInfo saved) {
        if (!Integer.valueOf(0).equals(saved.getIsDeleted())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_DISABLED", "当前账号已停用，请联系管理员。");
        }
    }

    // 头像为可选展示资料，异常地址不阻断身份核验，也不保存为可执行或非 HTTPS 地址。
    private static String normalizeAvatarUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 2048) {
            return null;
        }
        try {
            var uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                            && uri.getHost() != null
                            && uri.getUserInfo() == null
                    ? value
                    : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void validate(String value, int maxLength) {
        if (value == null
                || value.isBlank()
                || value.codePointCount(0, value.length()) > maxLength) {
            throw ApiException.upstream();
        }
    }

    private static ApiException databaseUnavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "USER_STORAGE_UNAVAILABLE",
                "用户信息保存失败，请检查数据库连接和 user_info 表后重新登录。");
    }
}
