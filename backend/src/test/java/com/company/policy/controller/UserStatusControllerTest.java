package com.company.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.common.exception.ApiException;
import com.company.policy.mapper.UserManagementMapper;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.UserInfoService;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
        properties = {
            "spring.config.import=",
            "spring.datasource.url=jdbc:h2:mem:user_status;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.sql.init.schema-locations=classpath:user-info-schema.sql,classpath:catalog-import-schema.sql",
            "feishu.tenant-key=status-company",
            "feishu.allowed-origins=http://localhost:5173"
        })
@AutoConfigureMockMvc
class UserStatusControllerTest {
    private static final String USERS = "/api/admin/users";
    private static final String COMPANY = "status-company";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.company.policy.service.AuthorizationService authorization;
    @Autowired ObjectMapper json;
    @Autowired LoginSessionService sessions;
    @Autowired UserInfoService identities;
    @MockitoSpyBean UserManagementMapper users;
    private MockHttpSession admin;

    @BeforeEach
    void setup() {
        authorization.ensureDefaults(COMPANY);
        authorization.ensureDefaults("other-company");
        jdbc.update("DELETE FROM policy_time_change");
        jdbc.update("DELETE FROM policy_audit_log");
        jdbc.update("DELETE FROM policy_apply");
        jdbc.update("DELETE FROM series");
        jdbc.update("DELETE FROM brand");
        jdbc.update("DELETE FROM sys_user_role");
        jdbc.update("DELETE FROM user_info");
        jdbc.update(
                """
            INSERT INTO user_info (id, tenant_key, open_id, name, updated_at) VALUES
            (1, 'status-company', 'open-1', '管理员甲', '2020-01-01'),
            (2, 'status-company', 'open-2', '申请人', '2020-01-01'),
            (3, 'status-company', 'open-3', '管理员乙', '2020-01-01'),
            (4, 'other-company', 'open-4', '其他公司用户', '2020-01-01')
            """);
        for (long id : new long[] {1, 2, 3, 4})
            com.company.policy.RbacFixture.setRole(jdbc, id, id == 2 ? "OPERATIONS" : "ADMIN");
        admin = session(1, "ADMIN");
    }

    @Test
    void disableAndEnablePreserveApplicantAndSubmittedHistory() throws Exception {
        jdbc.update("INSERT INTO brand (id,name) VALUES(91,\u0027OPPO手机\u0027)");
        jdbc.update("INSERT INTO series (id,brand_id,name) VALUES(92,91,\u0027Find\u0027)");
        jdbc.update(
                """
            INSERT INTO policy_apply (id,policy_no,tenant_key,applicant_id,applicant_name,brand_id,series_id,channel_type,status,current_version)
            VALUES (10,'status-history','status-company',2,'申请人',91,92,'RETAIL','APPROVED',1)
            """);
        jdbc.update(
                """
            INSERT INTO policy_audit_log (policy_id,tenant_key,version,brand_id,series_id,perspective,snapshot_data,audit_status,auditor_id,auditor_name,audit_time)
            VALUES (10,'status-company',1,91,92,'RETAIL','{"applicantName":"申请人"}','APPROVED',3,'管理员乙',CURRENT_TIMESTAMP)
            """);
        var before = jdbc.queryForMap("SELECT * FROM user_info WHERE id=2");
        var policy = jdbc.queryForMap("SELECT * FROM policy_apply WHERE id=10");
        var audit = jdbc.queryForMap("SELECT * FROM policy_audit_log WHERE policy_id=10");
        var applicant = session(2, "OPERATIONS");
        mvc.perform(change("2", "DISABLED", 1).session(admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value("2"))
                .andExpect(jsonPath("$.roleCodes[0]").value("OPERATIONS"))
                .andExpect(jsonPath("$.status").value("DISABLED"));
        var disabled = jdbc.queryForMap("SELECT * FROM user_info WHERE id=2");
        assertThat(disabled.remove("updated_at")).isNotEqualTo(before.remove("updated_at"));
        assertThat(((Number) disabled.remove("is_deleted")).intValue()).isEqualTo(1);
        before.remove("is_deleted");
        before.remove("auth_version");
        disabled.remove("auth_version");
        assertThat(disabled).isEqualTo(before);
        mvc.perform(get(USERS).session(admin).param("status", "DISABLED"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value("2"));
        mvc.perform(get(USERS + "/stats").session(admin))
                .andExpect(jsonPath("$.disabledUsers").value(1))
                .andExpect(jsonPath("$.enabledUsers").value(2));
        mvc.perform(get("/api/policies/mine").session(applicant)).andExpect(status().isForbidden());
        assertThatThrownBy(
                        () -> identities.saveVerifiedIdentity(COMPANY, "open-2", null, "申请人", null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("USER_DISABLED"));

        mvc.perform(change("2", "ENABLED", 2).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleCodes[0]").value("OPERATIONS"))
                .andExpect(jsonPath("$.status").value("ENABLED"));
        var restored = identities.saveVerifiedIdentity(COMPANY, "open-2", null, "申请人", null);
        assertThat(restored.id()).isEqualTo("2");
        assertThat(restored.roleCodes()).containsExactly("OPERATIONS");
        mvc.perform(get("/api/policies/mine").session(applicant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        assertThat(jdbc.queryForMap("SELECT * FROM policy_apply WHERE id=10")).isEqualTo(policy);
        assertThat(jdbc.queryForMap("SELECT * FROM policy_audit_log WHERE policy_id=10"))
                .isEqualTo(audit);
    }

    @Test
    void protectsSelfAndLastAdminAndRejectsDisabledAdministratorSession() throws Exception {
        mvc.perform(change("1", "DISABLED", 1).session(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_STATUS_SELF_FORBIDDEN"));
        var oldAdmin = session(3, "ADMIN");
        mvc.perform(change("3", "DISABLED", 1).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleCodes[0]").value("ADMIN"));
        mvc.perform(change("2", "DISABLED", 1).session(oldAdmin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_ADMIN_REQUIRED"));
        mvc.perform(change("1", "DISABLED", 1).session(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_LAST_ADMIN_REQUIRED"));
        mvc.perform(change("3", "ENABLED", 2).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleCodes[0]").value("ADMIN"));
        mvc.perform(get(USERS + "/stats").session(oldAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledAdmins").value(2));
    }

    @Test
    void matchingNoOpDoesNotWriteAndStaleRoleOrStatusConflicts() throws Exception {
        var before = jdbc.queryForMap("SELECT * FROM user_info WHERE id=2");
        mvc.perform(change("2", "ENABLED", 1).session(admin)).andExpect(status().isOk());
        mvc.perform(change("2", "DISABLED", 9).session(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_STATUS_CONFLICT"));
        assertThat(jdbc.queryForMap("SELECT * FROM user_info WHERE id=2")).isEqualTo(before);
        mvc.perform(change("2", "DISABLED", 1).session(admin)).andExpect(status().isOk());
        mvc.perform(change("2", "DISABLED", 1).session(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_STATUS_CONFLICT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"4", "99"})
    void missingAndForeignTargetsAreHidden(String id) throws Exception {
        mvc.perform(change(id, "DISABLED", 1).session(admin).param("tenantKey", "other-company"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void rejectsUnauthorizedRequests() throws Exception {
        mvc.perform(change("2", "DISABLED", 1)).andExpect(status().isUnauthorized());
        mvc.perform(change("3", "DISABLED", 1).session(session(2, "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_ADMIN_REQUIRED"));
        mvc.perform(
                        patch(USERS + "/2/status")
                                .session(admin)
                                .header("Origin", "http://localhost:5173"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REQUEST_ORIGIN_DENIED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void rejectsInvalidId(String id) throws Exception {
        mvc.perform(change(id, "DISABLED", 1).session(admin))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("USER_STATUS_INVALID_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"status\":\"DISABLED\"}",
                "{\"expectedVersion\":1}",
                "{\"status\":\"UNKNOWN\",\"expectedVersion\":1}",
                "{\"status\":\"ENABLED\",\"expectedVersion\":0}",
                "null"
            })
    void rejectsIncompleteOrInvalidBody(String body) throws Exception {
        mvc.perform(request("2").content(body).session(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void writeIsRolledBackWhenResponseCannotBeRead() throws Exception {
        doThrow(new DataAccessResourceFailureException("测试异常")).when(users).detail(COMPANY, 2);
        mvc.perform(change("2", "DISABLED", 1).session(admin))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("USER_STORAGE_UNAVAILABLE"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_deleted FROM user_info WHERE id=2", Integer.class))
                .isZero();
    }

    @Test
    void lockFailureUsesStatusConflictCode() throws Exception {
        doThrow(new CannotAcquireLockException("测试锁等待")).when(users).lockUser(COMPANY, 2);
        mvc.perform(change("2", "DISABLED", 1).session(admin))
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("USER_STATUS_CONFLICT"));
    }

    private MockHttpServletRequestBuilder change(String id, String status, int expectedVersion)
            throws Exception {
        return request(id)
                .content(
                        json.writeValueAsBytes(
                                Map.of("status", status, "expectedVersion", expectedVersion)));
    }

    private MockHttpServletRequestBuilder request(String id) {
        return patch(USERS + "/{id}/status", id)
                .header("Origin", "http://localhost:5173")
                .header("X-Policy-Request", "1")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpSession session(long id, String role) {
        var request = new MockHttpServletRequest();
        sessions.establish(
                request,
                new AuthSessionResponse(
                        new CurrentUserResponse(
                                Long.toString(id),
                                "open-" + id,
                                COMPANY,
                                "会话用户",
                                java.util.List.of(role),
                                java.util.List.of(),
                                null),
                        Instant.now().plusSeconds(3600)));
        return (MockHttpSession) request.getSession(false);
    }
}
