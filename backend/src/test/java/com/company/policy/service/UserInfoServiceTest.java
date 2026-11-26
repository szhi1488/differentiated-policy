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
        var first = service.saveVerifiedIdentity("tenant1", "open1", "union1", "首次姓名", null);
        assertThat(first.roleCodes()).containsExactly("OPERATIONS");
        assertThat(first.id()).isNotBlank();
        com.company.policy.RbacFixture.setRole(jdbc, Long.parseLong(first.id()), "ADMIN");
        jdbc.update("UPDATE user_info SET department='采购部' WHERE id=?", first.id());
        var second = service.saveVerifiedIdentity("tenant1", "open1", null, "更新姓名", null);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.roleCodes()).containsExactly("ADMIN");
        assertThat(second.name()).isEqualTo("更新姓名");
        var saved = mapper.findIdentity("tenant1", "open1");
        assertThat(saved.getUnionId()).isEqualTo("union1");
        assertThat(saved.getDepartment()).isEqualTo("采购部");
        assertThat(service.saveVerifiedIdentity("tenant2", "open1", null, "另一租户", null).id())
                .isNotEqualTo(first.id());
    }

    @Test
    void avatarIsUpdatedAndOptionalInvalidValuesPreserveExistingIdentity() {
        var first =
                service.saveVerifiedIdentity(
                        "avatar-tenant",
                        "avatar-open",
                        null,
                        "头像用户",
                        "https://example.test/first.png");
        assertThat(first.avatarUrl()).isEqualTo("https://example.test/first.png");
        com.company.policy.RbacFixture.setRole(jdbc, Long.parseLong(first.id()), "ADMIN");
        jdbc.update("UPDATE user_info SET department='采购部' WHERE id=?", first.id());
        var updated =
                service.saveVerifiedIdentity(
                        "avatar-tenant",
                        "avatar-open",
                        null,
                        "新姓名",
                        "https://example.test/second.png");
        assertThat(updated.id()).isEqualTo(first.id());
        assertThat(updated.avatarUrl()).isEqualTo("https://example.test/second.png");
        assertThat(updated.roleCodes()).containsExactly("ADMIN");
        for (String avatar :
                new String[] {
                    null,
                    "",
                    "http://example.test/plain.png",
                    "javascript:alert(1)",
                    "https://",
                    "https://example.test/" + "x".repeat(2048)
                }) {
            var retained =
                    service.saveVerifiedIdentity(
                            "avatar-tenant", "avatar-open", null, "新姓名", avatar);
            assertThat(retained.avatarUrl()).isEqualTo(updated.avatarUrl());
        }
        assertThat(mapper.findIdentity("avatar-tenant", "avatar-open").getDepartment())
                .isEqualTo("采购部");
        jdbc.update("UPDATE user_info SET is_deleted=1 WHERE id=?", first.id());
        assertThatThrownBy(
                        () ->
                                service.saveVerifiedIdentity(
                                        "avatar-tenant",
                                        "avatar-open",
                                        null,
                                        "停用后",
                                        "https://example.test/disabled.png"))
                .isInstanceOf(ApiException.class);
        assertThat(mapper.findIdentity("avatar-tenant", "avatar-open").getAvatarUrl())
                .isEqualTo(updated.avatarUrl());
        assertThat(
                        service.saveVerifiedIdentity(
                                        "avatar-tenant", "without-avatar", null, "无头像", null)
                                .avatarUrl())
                .isNull();
    }

    @Test
    void disabledIdentityIsNotReactivatedAndInvalidIdentityIsRejected() {
        var first =
                service.saveVerifiedIdentity("disabled-tenant", "disabled-open", null, "原姓名", null);
        jdbc.update("UPDATE user_info SET is_deleted=1 WHERE id=?", first.id());
        assertThatThrownBy(
                        () ->
                                service.saveVerifiedIdentity(
                                        "disabled-tenant",
                                        "disabled-open",
                                        "new-union",
                                        "新姓名",
                                        null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getCode()).isEqualTo("USER_DISABLED"));
        assertThat(mapper.findIdentity("disabled-tenant", "disabled-open").getName())
                .isEqualTo("原姓名");
        assertThatThrownBy(
                        () ->
                                service.saveVerifiedIdentity(
                                        "tenant", "open", null, "x".repeat(65), null))
                .isInstanceOf(ApiException.class);
    }
}
