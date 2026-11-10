package com.company.policy.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;

@Schema(description = "由飞书服务端核验的用户身份；不包含访问令牌，不代表管理员授权。")
public record CurrentUserResponse(String openId, String tenantKey, String name) implements Serializable {}
