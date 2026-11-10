package com.company.policy.service;

import static org.assertj.core.api.Assertions.*;

import com.company.policy.common.exception.ApiException;
import com.company.policy.mapper.UserInfoMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.config.import=")
@Transactional
class UserInfoServiceTest {
    @Autowired UserInfoService service;
    @Autowired UserInfoMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void
            firstLoginCreatesApplicantAndRepeatLoginUpdatesIdentityWithoutOverwritingRoleOrDepartment() {
        var first = service.saveVerifiedIdentity("tenant1", "open1", "union1", "首次姓名");
        assertThat(first.roleType()).isEqualTo("APPLICANT");
        assertThat(first.id()).isNotBlank();
        jdbc.update(
                "UPDATE user_info SET role_type='ADMIN', department='采购部' WHERE id=?", first.id());
        var second = service.saveVerifiedIdentity("tenant1", "open1", null, "更新姓名");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.roleType()).isEqualTo("ADMIN");
        assertThat(second.name()).isEqualTo("更新姓名");
        var saved = mapper.findIdentity("tenant1", "open1");
        assertThat(saved.getUnionId()).isEqualTo("union1");
        assertThat(saved.getDepartment()).isEqualTo("采购部");
        assertThat(service.saveVerifiedIdentity("tenant2", "open1", null, "另一租户").id())
                .isNotEqualTo(first.id());
    }

    @Test
    void disabledIdentityIsNotReactivatedAndInvalidIdentityIsRejected() {
        var first = service.saveVerifiedIdentity("disabled-tenant", "disabled-open", null, "原姓名");
        jdbc.update("UPDATE user_info SET is_deleted=1 WHERE id=?", first.id());
        assertThatThrownBy(
                        () ->
                                service.saveVerifiedIdentity(
                                        "disabled-tenant", "disabled-open", "new-union", "新姓名"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("USER_DISABLED"));
        assertThat(mapper.findIdentity("disabled-tenant", "disabled-open").getName())
                .isEqualTo("原姓名");
        assertThatThrownBy(
                        () -> service.saveVerifiedIdentity("tenant", "open", null, "x".repeat(65)))
                .isInstanceOf(ApiException.class);
    }
}
