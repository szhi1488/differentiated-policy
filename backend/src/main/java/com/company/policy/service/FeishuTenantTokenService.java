package com.company.policy.service;

/** 企业自建应用的服务端凭证，与 JS-SDK 票据服务共用租户令牌缓存。 */
public interface FeishuTenantTokenService {
    /** 返回仍有效的应用身份令牌。 */
    String getTenantAccessToken();

    /** 平台拒绝旧凭证时仅失效对应令牌，以免并发请求清掉刚取得的新凭证。 */
    void invalidateTenantAccessToken(String rejectedToken);
}
