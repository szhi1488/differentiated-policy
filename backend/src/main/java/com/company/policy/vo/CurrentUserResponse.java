package com.company.policy.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;

@Schema(description = "由飞书核验并保存至 user_info 的身份；新用户默认为 APPLICANT，不包含访问令牌。")
public record CurrentUserResponse(
        String id, String openId, String tenantKey, String name, String roleType)
        implements Serializable {}
