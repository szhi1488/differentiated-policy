package com.company.policy;

import com.company.policy.service.AuthorizationService;
import org.springframework.jdbc.core.JdbcTemplate;

/** 使用应用内置岗位为审核集成测试准备账号。 */
public class ReviewAccounts {
    public static void create(
            JdbcTemplate jdbc,
            AuthorizationService roles,
            long id,
            String tenant,
            String open,
            String name,
            String... roleCodes) {
        roles.ensureDefaults(tenant);
        jdbc.update(
                "INSERT INTO user_info(id,tenant_key,open_id,name) VALUES(?,?,?,?)",
                id,
                tenant,
                open,
                name);
        for (String role : roleCodes) grant(jdbc, id, tenant, role);
    }

    public static void grant(JdbcTemplate jdbc, long id, String tenant, String role) {
        jdbc.update(
                "INSERT INTO sys_user_role(tenant_key,user_id,role_id) SELECT tenant_key,?,id FROM sys_role WHERE tenant_key=? AND role_code=?",
                id,
                tenant,
                role);
    }
}
