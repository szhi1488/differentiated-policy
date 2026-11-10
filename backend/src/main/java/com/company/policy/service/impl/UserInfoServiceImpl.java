package com.company.policy.service.impl;

import com.company.policy.common.exception.ApiException;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.UserInfoMapper;
import com.company.policy.service.UserInfoService;
import com.company.policy.vo.CurrentUserResponse;
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
            String tenantKey, String openId, String unionId, String name) {
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
                    saved.getRoleType());
        } catch (DataAccessException ignored) {
            throw databaseUnavailable();
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
