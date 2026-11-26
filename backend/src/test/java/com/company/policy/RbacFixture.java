package com.company.policy;

import org.springframework.jdbc.core.JdbcTemplate;

public final class RbacFixture {
    private RbacFixture() {}

    public static void setRole(JdbcTemplate jdbc, long id, String role) {
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?", id);
        jdbc.update(
                """
            INSERT INTO sys_user_role(tenant_key,user_id,role_id) SELECT u.tenant_key,u.id,r.id
            FROM user_info u JOIN sys_role r ON r.tenant_key=u.tenant_key AND r.role_code=? WHERE u.id=?
            """,
                role,
                id);
    }
}
