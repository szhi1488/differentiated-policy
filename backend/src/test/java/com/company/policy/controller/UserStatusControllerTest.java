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
    @Autowired ObjectMapper json;
    @Autowired LoginSessionService sessions;
    @Autowired UserInfoService identities;
    @MockitoSpyBean UserManagementMapper users;
    private MockHttpSession admin;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM policy_audit_log");
        jdbc.update("DELETE FROM policy_apply");
        jdbc.update("DELETE FROM user_info");
        jdbc.update(
                """
            INSERT INTO user_info (id, tenant_key, open_id, name, role_type, updated_at) VALUES
            (1, 'status-company', 'open-1', '管理员甲', 'ADMIN', '2020-01-01'),
            (2, 'status-company', 'open-2', '申请人', 'APPLICANT', '2020-01-01'),
            (3, 'status-company', 'open-3', '管理员乙', 'ADMIN', '2020-01-01'),
            (4, 'other-company', 'open-4', '其他公司用户', 'ADMIN', '2020-01-01')
            """);
        admin = session(1, "ADMIN");
    }

    @Test
    void disableAndEnablePreserveApplicantAndSubmittedHistory() throws Exception {
        jdbc.update(
                """
            INSERT INTO policy_apply (id,policy_no,tenant_key,applicant_id,applicant_name,channel_type,status,current_version)
            VALUES (10,'status-history','status-company',2,'申请人','RETAIL','APPROVED',1)
            """);
        jdbc.update(
                """
            INSERT INTO policy_audit_log (policy_id,tenant_key,version,snapshot_data,audit_status,auditor_id,auditor_name,audit_time)
            VALUES (10,'status-company',1,'{"applicantName":"申请人"}','APPROVED',3,'管理员乙',CURRENT_TIMESTAMP)
            """);
        var before = jdbc.queryForMap("SELECT * FROM user_info WHERE id=2");
        var policy = jdbc.queryForMap("SELECT * FROM policy_apply WHERE id=10");
        var audit = jdbc.queryForMap("SELECT * FROM policy_audit_log WHERE policy_id=10");
        var applicant = session(2, "APPLICANT");
        mvc.perform(change("2", "DISABLED", "APPLICANT", "ENABLED").session(admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value("2"))
                .andExpect(jsonPath("$.roleType").value("APPLICANT"))
                .andExpect(jsonPath("$.status").value("DISABLED"));
        var disabled = jdbc.queryForMap("SELECT * FROM user_info WHERE id=2");
        assertThat(disabled.remove("updated_at")).isNotEqualTo(before.remove("updated_at"));
        assertThat(((Number) disabled.remove("is_deleted")).intValue()).isEqualTo(1);
        before.remove("is_deleted");
        assertThat(disabled).isEqualTo(before);
        mvc.perform(get(USERS).session(admin).param("status", "DISABLED"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value("2"));
        mvc.perform(get(USERS + "/stats").session(admin))
                .andExpect(jsonPath("$.disabledUsers").value(1))
                .andExpect(jsonPath("$.enabledApplicants").value(0));
        mvc.perform(get("/api/policies/mine").session(applicant)).andExpect(status().isForbidden());
        assertThatThrownBy(
                        () -> identities.saveVerifiedIdentity(COMPANY, "open-2", null, "申请人", null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("USER_DISABLED"));

        mvc.perform(change("2", "ENABLED", "APPLICANT", "DISABLED").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleType").value("APPLICANT"))
                .andExpect(jsonPath("$.status").value("ENABLED"));
        var restored = identities.saveVerifiedIdentity(COMPANY, "open-2", null, "申请人", null);
        assertThat(restored.id()).isEqualTo("2");
        assertThat(restored.roleType()).isEqualTo("APPLICANT");
        mvc.perform(get("/api/policies/mine").session(applicant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        assertThat(jdbc.queryForMap("SELECT * FROM policy_apply WHERE id=10")).isEqualTo(policy);
        assertThat(jdbc.queryForMap("SELECT * FROM policy_audit_log WHERE policy_id=10"))
                .isEqualTo(audit);
    }

    @Test
    void protectsSelfAndLastAdminAndRejectsDisabledAdministratorSession() throws Exception {
        mvc.perform(change("1", "DISABLED", "ADMIN", "ENABLED").session(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_STATUS_SELF_FORBIDDEN"));
        var oldAdmin = session(3, "ADMIN");
        mvc.perform(change("3", "DISABLED", "ADMIN", "ENABLED").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleType").value("ADMIN"));
        mvc.perform(change("2", "DISABLED", "APPLICANT", "ENABLED").session(oldAdmin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_ADMIN_REQUIRED"));
        mvc.perform(change("1", "DISABLED", "ADMIN", "ENABLED").session(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_LAST_ADMIN_REQUIRED"));
        mvc.perform(change("3", "ENABLED", "ADMIN", "DISABLED").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleType").value("ADMIN"));
        mvc.perform(get(USERS + "/stats").session(oldAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledAdmins").value(2));
    }

    @Test
    void matchingNoOpDoesNotWriteAndStaleRoleOrStatusConflicts() throws Exception {
        var before = jdbc.queryForMap("SELECT * FROM user_info WHERE id=2");
        mvc.perform(change("2", "ENABLED", "APPLICANT", "ENABLED").session(admin))
                .andExpect(status().isOk());
        mvc.perform(change("2", "DISABLED", "ADMIN", "ENABLED").session(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_STATUS_CONFLICT"));
        assertThat(jdbc.queryForMap("SELECT * FROM user_info WHERE id=2")).isEqualTo(before);
        mvc.perform(change("2", "DISABLED", "APPLICANT", "ENABLED").session(admin))
                .andExpect(status().isOk());
        mvc.perform(change("2", "DISABLED", "APPLICANT", "ENABLED").session(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_STATUS_CONFLICT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"4", "99"})
    void missingAndForeignTargetsAreHidden(String id) throws Exception {
        mvc.perform(
                        change(id, "DISABLED", "ADMIN", "ENABLED")
                                .session(admin)
                                .param("tenantKey", "other-company"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void rejectsUnauthorizedRequests() throws Exception {
        mvc.perform(change("2", "DISABLED", "APPLICANT", "ENABLED"))
                .andExpect(status().isUnauthorized());
        mvc.perform(change("3", "DISABLED", "ADMIN", "ENABLED").session(session(2, "ADMIN")))
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
        mvc.perform(change(id, "DISABLED", "APPLICANT", "ENABLED").session(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_STATUS_INVALID_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"status\":\"DISABLED\",\"expectedRoleType\":\"ADMIN\"}",
                "{\"status\":\"DISABLED\",\"expectedStatus\":\"ENABLED\"}",
                "{\"status\":\"UNKNOWN\",\"expectedRoleType\":\"ADMIN\",\"expectedStatus\":\"ENABLED\"}",
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
        mvc.perform(change("2", "DISABLED", "APPLICANT", "ENABLED").session(admin))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("USER_STORAGE_UNAVAILABLE"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_deleted FROM user_info WHERE id=2", Integer.class))
                .isZero();
    }

    @Test
    void lockFailureUsesStatusConflictCode() throws Exception {
        doThrow(new CannotAcquireLockException("测试锁等待")).when(users).lockUser(COMPANY, 2);
        mvc.perform(change("2", "DISABLED", "APPLICANT", "ENABLED").session(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_STATUS_CONFLICT"));
    }

    private MockHttpServletRequestBuilder change(
            String id, String status, String role, String expectedStatus) throws Exception {
        return request(id)
                .content(
                        json.writeValueAsBytes(
                                Map.of(
                                        "status",
                                        status,
                                        "expectedRoleType",
                                        role,
                                        "expectedStatus",
                                        expectedStatus)));
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
                                Long.toString(id), "open-" + id, COMPANY, "会话用户", role, null),
                        Instant.now().plusSeconds(3600)));
        return (MockHttpSession) request.getSession(false);
    }
}
