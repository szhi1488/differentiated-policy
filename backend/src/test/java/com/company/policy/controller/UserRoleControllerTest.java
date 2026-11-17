package com.company.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.mapper.UserManagementMapper;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
            "spring.datasource.url=jdbc:h2:mem:user_role;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=5000",
            "feishu.tenant-key=role-company",
            "feishu.allowed-origins=http://localhost:5173"
        })
@AutoConfigureMockMvc
class UserRoleControllerTest {
    private static final String PATH = "/api/admin/users";
    private static final String COMPANY = "role-company";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired LoginSessionService sessions;
    @MockitoSpyBean UserManagementMapper users;
    private MockHttpSession admin;

    @BeforeEach
    void setup() {
        // 仅清理本测试独立 H2 的数据；不包裹测试事务，以验证实际提交、回滚及线程并发。
        jdbc.update("DELETE FROM user_info");
        addUser(1, COMPANY, "ADMIN", 0);
        addUser(2, COMPANY, "APPLICANT", 0);
        addUser(3, COMPANY, "ADMIN", 0);
        addUser(4, COMPANY, "APPLICANT", 1);
        addUser(5, COMPANY, "ADMIN", 1);
        addUser(6, "other-company", "ADMIN", 0);
        admin = session(1, COMPANY, "ADMIN");
    }

    @Test
    void switchesBothDirectionsAndPreservesProfileAndIdentity() throws Exception {
        var before = jdbc.queryForMap("SELECT * FROM user_info WHERE id=2");
        mvc.perform(change(admin, "2", "ADMIN", "APPLICANT", "ENABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleType").value("ADMIN"))
                .andExpect(jsonPath("$.id").value("2"))
                .andExpect(jsonPath("$.openId").value("open-2"))
                .andExpect(header().string("Cache-Control", "no-store"));
        var after = jdbc.queryForMap("SELECT * FROM user_info WHERE id=2");
        assertThat(after.get("updated_at")).isNotEqualTo(before.get("updated_at"));
        before.remove("role_type");
        before.remove("updated_at");
        after.remove("role_type");
        after.remove("updated_at");
        assertThat(after).isEqualTo(before);
        mvc.perform(change(admin, "2", "APPLICANT", "ADMIN", "ENABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleType").value("APPLICANT"));
        assertThat(role(2)).isEqualTo("APPLICANT");
    }

    @Test
    void unchangedRoleDoesNotWriteUpdatedTime() throws Exception {
        var before = jdbc.queryForMap("SELECT * FROM user_info WHERE id=2");
        mvc.perform(change(admin, "2", "APPLICANT", "APPLICANT", "ENABLED"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT * FROM user_info WHERE id=2")).isEqualTo(before);
    }

    @Test
    void disabledTargetKeepsDisabledFlagAndIsNotCountedAsEnabledAdmin() throws Exception {
        mvc.perform(change(admin, "4", "ADMIN", "APPLICANT", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.roleType").value("ADMIN"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_deleted FROM user_info WHERE id=4", Integer.class))
                .isEqualTo(1);
        mvc.perform(get(PATH + "/stats").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledAdmins").value(2));
    }

    @Test
    void rejectsSelfChangeAndPreservesLastEnabledAdministrator() throws Exception {
        mvc.perform(change(admin, "1", "APPLICANT", "ADMIN", "ENABLED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ROLE_SELF_FORBIDDEN"));
        jdbc.update("UPDATE user_info SET role_type='APPLICANT' WHERE id=3");
        mvc.perform(change(admin, "1", "APPLICANT", "ADMIN", "ENABLED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_LAST_ADMIN_REQUIRED"));
        assertThat(role(1)).isEqualTo("ADMIN");
        assertThat(enabledAdmins()).isEqualTo(1);
        // 停用管理员的降级不会减少有效管理员。
        mvc.perform(change(admin, "5", "APPLICANT", "ADMIN", "DISABLED"))
                .andExpect(status().isOk());
        assertThat(enabledAdmins()).isEqualTo(1);
    }

    @Test
    void staleRoleStatusAndRepeatedOldRequestAreConflicts() throws Exception {
        mvc.perform(change(admin, "2", "ADMIN", "ADMIN", "ENABLED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ROLE_CONFLICT"));
        mvc.perform(change(admin, "2", "ADMIN", "APPLICANT", "DISABLED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ROLE_CONFLICT"));
        assertThat(role(2)).isEqualTo("APPLICANT");
        mvc.perform(change(admin, "2", "ADMIN", "APPLICANT", "ENABLED")).andExpect(status().isOk());
        mvc.perform(change(admin, "2", "ADMIN", "APPLICANT", "ENABLED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ROLE_CONFLICT"));
        assertThat(role(2)).isEqualTo("ADMIN");
    }

    @Test
    void missingAndForeignTargetsUseSameNotFoundResponse() throws Exception {
        var missing =
                mvc.perform(change(admin, "99", "ADMIN", "APPLICANT", "ENABLED"))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var foreign =
                mvc.perform(
                                change(admin, "6", "APPLICANT", "ADMIN", "ENABLED")
                                        .param("tenantKey", "other-company"))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(foreign).isEqualTo(missing);
        assertThat(role(6)).isEqualTo("ADMIN");
    }

    @Test
    void requiresTrustedOriginCustomHeaderAndCurrentCompanyAdministrator() throws Exception {
        String body = body("ADMIN", "APPLICANT", "ENABLED");
        mvc.perform(
                        patch(PATH + "/2/role")
                                .session(admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REQUEST_ORIGIN_DENIED"));
        mvc.perform(
                        patch(PATH + "/2/role")
                                .session(admin)
                                .header("Origin", "https://untrusted.example")
                                .header("X-Policy-Request", "1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(
                        patch(PATH + "/2/role")
                                .session(admin)
                                .header("Origin", "http://localhost:5173")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(
                        patch(PATH + "/2/role")
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isUnauthorized());
        for (MockHttpSession invalid :
                List.of(
                        session(2, COMPANY, "ADMIN"),
                        session(5, COMPANY, "ADMIN"),
                        session(99, COMPANY, "ADMIN"))) {
            mvc.perform(change(invalid, "2", "ADMIN", "APPLICANT", "ENABLED"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("USER_ADMIN_REQUIRED"));
        }
        mvc.perform(
                        change(
                                session(6, "other-company", "ADMIN"),
                                "2",
                                "ADMIN",
                                "APPLICANT",
                                "ENABLED"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_SCOPE_DENIED"));
        assertThat(role(2)).isEqualTo("APPLICANT");
    }

    @Test
    void demotionBlocksOldAdministratorSessionOnNextBusinessRequest() throws Exception {
        var oldAdmin = session(3, COMPANY, "ADMIN");
        mvc.perform(change(admin, "3", "APPLICANT", "ADMIN", "ENABLED")).andExpect(status().isOk());
        mvc.perform(get(PATH + "/stats").session(oldAdmin)).andExpect(status().isForbidden());
        mvc.perform(change(oldAdmin, "2", "ADMIN", "APPLICANT", "ENABLED"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_ADMIN_REQUIRED"));
        assertThat(role(2)).isEqualTo("APPLICANT");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"roleType\":\"ROOT\",\"expectedRoleType\":\"APPLICANT\",\"expectedStatus\":\"ENABLED\"}",
                "{\"roleType\":\"ADMIN\",\"expectedStatus\":\"ENABLED\"}",
                "{\"roleType\":\"ADMIN\",\"expectedRoleType\":\"APPLICANT\"}",
                "{\"roleType\":\"ADMIN\",\"expectedRoleType\":\"ROOT\",\"expectedStatus\":\"ENABLED\"}",
                "{\"roleType\":\"ADMIN\",\"expectedRoleType\":\"APPLICANT\",\"expectedStatus\":\"ACTIVE\"}",
                "null",
                "{"
            })
    void rejectsInvalidOrIncompleteBodies(String body) throws Exception {
        mvc.perform(
                        patch(PATH + "/2/role")
                                .session(admin)
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(role(2)).isEqualTo("APPLICANT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
    void rejectsInvalidIds(String id) throws Exception {
        mvc.perform(change(admin, id, "ADMIN", "APPLICANT", "ENABLED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_ROLE_INVALID_REQUEST"));
    }

    @Test
    void supportsLongIdsWithoutChangingIdentity() throws Exception {
        addUser(9007199254740993L, COMPANY, "APPLICANT", 0);
        mvc.perform(change(admin, "9007199254740993", "ADMIN", "APPLICANT", "ENABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("9007199254740993"))
                .andExpect(jsonPath("$.openId").value("open-9007199254740993"));
    }

    @Test
    void responseReadFailureRollsBackRoleUpdate() throws Exception {
        doThrow(new DataAccessResourceFailureException("private-database-details"))
                .when(users)
                .detail(COMPANY, 2L);
        var result =
                mvc.perform(change(admin, "2", "ADMIN", "APPLICANT", "ENABLED"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("USER_STORAGE_UNAVAILABLE"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(result).doesNotContain("private-database-details");
        assertThat(role(2)).isEqualTo("APPLICANT");
    }

    @Test
    void concurrentUpdatesToSameTargetOnlyOneSucceeds() throws Exception {
        var other = session(3, COMPANY, "ADMIN");
        var results =
                concurrent(
                        change(admin, "2", "ADMIN", "APPLICANT", "ENABLED"),
                        change(other, "2", "ADMIN", "APPLICANT", "ENABLED"));
        assertThat(results).containsExactlyInAnyOrder(200, 409);
        assertThat(role(2)).isEqualTo("ADMIN");
    }

    @Test
    void concurrentMutualDemotionCannotRemoveAllAdministrators() throws Exception {
        var other = session(3, COMPANY, "ADMIN");
        var results =
                concurrent(
                        change(admin, "3", "APPLICANT", "ADMIN", "ENABLED"),
                        change(other, "1", "APPLICANT", "ADMIN", "ENABLED"));
        assertThat(results.stream().filter(code -> code == 200).count()).isEqualTo(1);
        assertThat(results).allMatch(code -> code == 200 || code == 403 || code == 409);
        assertThat(enabledAdmins()).isEqualTo(1);
    }

    @Test
    void disablingAndDemotingEachOtherLeavesOneEnabledAdministrator() throws Exception {
        var results =
                concurrent(
                        disable("3", "ADMIN"),
                        change(session(3, COMPANY, "ADMIN"), "1", "APPLICANT", "ADMIN", "ENABLED"));
        assertThat(results).containsExactlyInAnyOrder(200, 403);
        assertThat(enabledAdmins()).isEqualTo(1);
    }

    @Test
    void sameTargetCannotBePromotedAndDisabledFromOneSnapshot() throws Exception {
        var results =
                concurrent(
                        disable("2", "APPLICANT"),
                        change(session(3, COMPANY, "ADMIN"), "2", "ADMIN", "APPLICANT", "ENABLED"));
        assertThat(results).containsExactlyInAnyOrder(200, 409);
        var target = users.detail(COMPANY, 2L);
        assertThat(target.isDeleted()).isEqualTo("ADMIN".equals(target.roleType()) ? 0 : 1);
    }

    private MockHttpServletRequestBuilder disable(String id, String expectedRole) throws Exception {
        return patch(PATH + "/{id}/status", id)
                .session(admin)
                .header("Origin", "http://localhost:5173")
                .header("X-Policy-Request", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        json.writeValueAsBytes(
                                Map.of(
                                        "status",
                                        "DISABLED",
                                        "expectedStatus",
                                        "ENABLED",
                                        "expectedRoleType",
                                        expectedRole)));
    }

    private List<Integer> concurrent(
            MockHttpServletRequestBuilder first, MockHttpServletRequestBuilder second)
            throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var futures =
                    List.of(first, second).stream()
                            .map(
                                    request ->
                                            pool.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        if (!start.await(5, TimeUnit.SECONDS)) {
                                                            throw new IllegalStateException(
                                                                    "并发测试启动超时");
                                                        }
                                                        return mvc.perform(request)
                                                                .andReturn()
                                                                .getResponse()
                                                                .getStatus();
                                                    }))
                            .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private MockHttpServletRequestBuilder change(
            MockHttpSession session,
            String id,
            String role,
            String expectedRole,
            String expectedStatus)
            throws Exception {
        return patch(PATH + "/" + id + "/role")
                .session(session)
                .header("Origin", "http://localhost:5173")
                .header("X-Policy-Request", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(role, expectedRole, expectedStatus));
    }

    private String body(String role, String expectedRole, String expectedStatus) throws Exception {
        return json.writeValueAsString(
                Map.of(
                        "roleType",
                        role,
                        "expectedRoleType",
                        expectedRole,
                        "expectedStatus",
                        expectedStatus));
    }

    private String role(long id) {
        return jdbc.queryForObject("SELECT role_type FROM user_info WHERE id=?", String.class, id);
    }

    private long enabledAdmins() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_info WHERE tenant_key=? AND role_type='ADMIN' AND is_deleted=0",
                Long.class,
                COMPANY);
    }

    private MockHttpSession session(long id, String tenant, String role) {
        var request = new MockHttpServletRequest();
        sessions.establish(
                request,
                new AuthSessionResponse(
                        new CurrentUserResponse(
                                String.valueOf(id), "open-" + id, tenant, "会话姓名", role, null),
                        Instant.now().plusSeconds(3600)));
        return (MockHttpSession) request.getSession(false);
    }

    private void addUser(long id, String tenant, String role, int disabled) {
        jdbc.update(
                """
            INSERT INTO user_info (id, tenant_key, open_id, union_id, name, avatar_url, department, role_type, is_deleted, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '2026-11-01 09:00:00', '2026-11-01 09:00:00')
            """,
                id,
                tenant,
                "open-" + id,
                "union-" + id,
                "测试用户" + id,
                "https://example.test/avatar.png",
                "采购部",
                role,
                disabled);
    }
}
