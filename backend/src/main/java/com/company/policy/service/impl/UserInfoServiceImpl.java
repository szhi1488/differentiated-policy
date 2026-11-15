package com.company.policy.service.impl;

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

    public UserInfoServiceImpl(UserInfoMapper mapper) {
        this.mapper = mapper;
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
            if (!Integer.valueOf(0).equals(saved.getIsDeleted())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "USER_DISABLED", "当前账号已停用，请联系管理员。");
            }
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
