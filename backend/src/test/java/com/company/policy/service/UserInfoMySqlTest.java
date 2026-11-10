package com.company.policy.service;

import static org.assertj.core.api.Assertions.*;

import com.company.policy.mapper.UserInfoMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 仅在显式启用时验证本地 MySQL，所有临时用户数据变更均随事务回滚。 */
@EnabledIfSystemProperty(named = "policy.mysql.verify", matches = "true")
@SpringBootTest(
        properties = {
            "spring.datasource.url=${DB_URL}", "spring.datasource.username=${DB_USERNAME}",
            "spring.datasource.password=${DB_PASSWORD}",
                    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "spring.sql.init.mode=never"
        })
@Transactional
class UserInfoMySqlTest {
    @Autowired UserInfoService users;
    @Autowired UserInfoMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void existingMySqlSchemaSupportsLoginWithoutChangingBusinessData() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("differentiated policy");
        var columns =
                jdbc.queryForList(
                        """
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_info'
                """,
                        String.class);
        assertThat(columns)
                .contains(
                        "id",
                        "tenant_key",
                        "open_id",
                        "union_id",
                        "name",
                        "department",
                        "role_type",
                        "is_deleted",
                        "created_at",
                        "updated_at");
        String tenant = "verify_" + UUID.randomUUID();
        var first = users.saveVerifiedIdentity(tenant, "temporary_open", "temporary_union", "临时验证");
        assertThat(first.roleType()).isEqualTo("APPLICANT");
        jdbc.update(
                "UPDATE user_info SET role_type='ADMIN', department='临时验证部门' WHERE id=?",
                first.id());
        var repeat = users.saveVerifiedIdentity(tenant, "temporary_open", null, "更新验证");
        assertThat(repeat.id()).isEqualTo(first.id());
        assertThat(repeat.roleType()).isEqualTo("ADMIN");
        assertThat(mapper.findIdentity(tenant, "temporary_open").getUnionId())
                .isEqualTo("temporary_union");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM user_info WHERE tenant_key=?",
                                Long.class,
                                tenant))
                .isEqualTo(1);
    }
}
