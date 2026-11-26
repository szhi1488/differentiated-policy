package com.company.policy.vo;

import java.io.Serializable;
import java.util.List;

/** 已核验账号及当前有效岗位权限。服务端办理业务时重新校验授权。 */
public record CurrentUserResponse(
        String id,
        String openId,
        String tenantKey,
        String name,
        List<String> roleCodes,
        List<String> permissions,
        String avatarUrl)
        implements Serializable {}
