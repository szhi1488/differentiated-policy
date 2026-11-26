package com.company.policy.service;

import com.company.policy.dto.RoleDefinitionUpdate;
import com.company.policy.entity.UserInfo;
import com.company.policy.vo.AuthorizationRoles;
import com.company.policy.vo.CurrentUserResponse;

/** 初始化公司岗位并管理角色定义和有效权限。 */
public interface AuthorizationService {
    /** 幂等建立内置角色、初始权限与渠道平台。 */
    void ensureDefaults(String tenant);

    /** 返回当前启用岗位及合并后的权限目录。 */
    CurrentUserResponse current(UserInfo account);

    /** 读取本企业的角色与可授予权限。 */
    AuthorizationRoles definitions(CurrentUserResponse session);

    /** 新建角色或按角色版本更新现有定义。 */
    AuthorizationRoles.Role save(
            CurrentUserResponse session, Long roleId, RoleDefinitionUpdate request);
}
