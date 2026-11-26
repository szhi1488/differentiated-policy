package com.company.policy.service.impl;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.JackyunGoodsQuery;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.RoleAccessMapper;
import com.company.policy.mapper.UserInfoMapper;
import com.company.policy.service.CatalogAccess;
import com.company.policy.vo.CurrentUserResponse;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CatalogAccessImpl implements CatalogAccess {
    private final UserInfoMapper users;
    private final FeishuProperties config;
    private final RoleAccessMapper roles;

    public UserInfo requireActive(CurrentUserResponse session) {
        return active(session, "POLICY");
    }

    private UserInfo active(CurrentUserResponse session, String domain) {
        if (!config.hasCompanyTenant())
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, domain + "_SCOPE_MISSING", "请先配置公司飞书租户。");
        if (session == null || !Objects.equals(config.getTenantKey(), session.tenantKey()))
            throw new ApiException(HttpStatus.FORBIDDEN, domain + "_SCOPE_DENIED", "当前账号不属于本公司。");
        var account = users.findIdentity(session.tenantKey(), session.openId());
        if (account == null
                || !Objects.equals(session.id(), account.getId().toString())
                || !Integer.valueOf(0).equals(account.getIsDeleted()))
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "USER".equals(domain) ? "USER_ADMIN_REQUIRED" : domain + "_ACCESS_DENIED",
                    "当前账号已停用或身份已变更。");
        return account;
    }

    public boolean hasPermission(UserInfo account, String permission) {
        return account != null
                && roles.hasPermission(account.getTenantKey(), account.getId(), permission) > 0;
    }

    public List<String> assignedBrandNames(UserInfo account) {
        String tenant = account.getTenantKey();
        if (roles.userRoles(tenant, account.getId(), false).contains("ADMIN")) return null;
        return purchaseBrandNames(account);
    }

    public List<String> subsidyBrandNames(UserInfo reader) {
        var roleCodes = roles.userRoles(reader.getTenantKey(), reader.getId(), false);
        if (roleCodes.contains("ADMIN")) return null;
        if (roleCodes.contains("PURCHASE")
                || hasPermission(reader, "policy.purchase.decide_assigned"))
            return purchaseBrandNames(reader);
        return null;
    }

    private List<String> purchaseBrandNames(UserInfo account) {
        var brands = roles.purchaseBrandNames(account.getTenantKey(), account.getId());
        if (brands.size() > 1)
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CATALOG_BRAND_ASSIGNMENT_CONFLICT",
                    "当前账号被分配了多个采购品牌，请联系管理员在政策审核设置中更正。");
        return brands;
    }

    public List<String> readableGoodsBrands(CurrentUserResponse session) {
        var reader = active(session, "CATALOG");
        if (hasPermission(reader, "catalog.manage")) return null;
        if (!hasPermission(reader, "catalog.import"))
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "当前账号没有商品中心查看权限。");
        return assignedBrandNames(reader);
    }

    public UserInfo requirePermission(CurrentUserResponse session, String permission) {
        if ("users.manage".equals(permission)) return requireUserAdmin(session);
        return permitted(
                session,
                permission,
                permission.startsWith("catalog.") ? "CATALOG" : "POLICY",
                "PERMISSION_DENIED");
    }

    private UserInfo permitted(
            CurrentUserResponse session, String permission, String domain, String code) {
        var account = active(session, domain);
        if (!hasPermission(account, permission))
            throw new ApiException(HttpStatus.FORBIDDEN, code, "当前账号没有该项操作权限。");
        return account;
    }

    @Override
    public List<String> sourceBrandNames(UserInfo account, String requestedBrand) {
        var assigned = assignedBrandNames(account);
        var brands =
                assigned == null
                        ? JackyunGoodsQuery.SUPPORTED_BRANDS
                        : JackyunGoodsQuery.SUPPORTED_BRANDS.stream()
                                .filter(assigned::contains)
                                .toList();
        if (requestedBrand == null) return brands;
        if (!brands.contains(requestedBrand))
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "CATALOG_BRAND_FORBIDDEN", "当前账号未负责该商品品牌。");
        return List.of(requestedBrand);
    }

    public UserInfo requireApplicant(CurrentUserResponse session) {
        return permitted(session, "policy.apply", "POLICY", "POLICY_ACCESS_DENIED");
    }

    public UserInfo requireUserAdmin(CurrentUserResponse session) {
        return permitted(session, "users.manage", "USER", "USER_ADMIN_REQUIRED");
    }
}
