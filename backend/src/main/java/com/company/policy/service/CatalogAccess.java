package com.company.policy.service;

import com.company.policy.entity.UserInfo;
import com.company.policy.vo.CurrentUserResponse;
import java.util.List;

/** 核验当前账号及后台各功能的有效权限。 */
public interface CatalogAccess {
    /** 按飞书企业、应用内身份和启停状态读取当前账号。 */
    UserInfo requireActive(CurrentUserResponse session);

    /** 核验有效账号拥有指定权限。 */
    UserInfo requirePermission(CurrentUserResponse session, String permission);

    /** 核对账号当前启用岗位合并后的权限。 */
    boolean hasPermission(UserInfo account, String permission);

    /** 返回当前账号在审核分工中负责采购的品牌。管理员返回 null 表示全品牌，空列表表示未分配。 */
    List<String> assignedBrandNames(UserInfo account);

    /** 查询补贴时，采购按当前品牌分工读取；管理员及其他已授权岗位沿用原查阅范围。 */
    List<String> subsidyBrandNames(UserInfo reader);

    /** 核验商品中心查询权并返回可读品牌。具备目录维护权限时返回 null。 */
    List<String> readableGoodsBrands(CurrentUserResponse session);

    /** 返回当前账号可处理的吉客云品牌，指定品牌时校验其在本人范围内。 */
    List<String> sourceBrandNames(UserInfo account, String requestedBrand);

    /** 校验本人的政策填报权限。 */
    UserInfo requireApplicant(CurrentUserResponse session);

    /** 校验本企业用户管理权限。 */
    UserInfo requireUserAdmin(CurrentUserResponse session);
}
