package com.company.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.GlobalExceptionHandler;
import com.company.policy.mapper.UserManagementMapper;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.UserManagementService;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
        properties = {
            "spring.config.import=",
            "spring.datasource.url=jdbc:h2:mem:user_management;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "feishu.tenant-key=user-company"
        })
@AutoConfigureMockMvc
@Transactional
class UserManagementControllerTest {
    private static final String PATH = "/api/admin/users";
    private static final String COMPANY = "user-company";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoginSessionService sessions;
    @Autowired FeishuProperties config;
    @Autowired UserManagementMapper userMapper;
    @Autowired SqlSessionTemplate sqlSession;
    private MockHttpSession admin;

    @BeforeEach
    void setup() {
        addUser(1, COMPANY, "列表管理员", "采购部", "ADMIN", 0, "2026-11-16 08:00:00");
        addUser(2, COMPANY, "张三", "零售业务部", "APPLICANT", 0, "2026-11-17 09:00:00");
        addUser(3, COMPANY, "Alice采购", "采购部", "ADMIN", 0, "2026-11-17 09:00:00");
        addUser(4, COMPANY, "李四", null, "APPLICANT", 1, "2026-11-17 10:00:00");
        addUser(5, COMPANY, "王五", "采购部", "ADMIN", 1, "2026-11-17 11:00:00");
        addUser(6, "other-company", "Alice采购", "采购部", "ADMIN", 0, "2026-11-17 12:00:00");
        admin = session(1, COMPANY, "ADMIN");
    }

    @Test
    void detailReturnsSavedProfileAndApplicationOpenIdWithoutExtraIdentityData() throws Exception {
        var result = read(get(PATH + "/3").session(admin));
        assertThat(result.size()).isEqualTo(8);
        assertThat(result.path("id").asText()).isEqualTo("3");
        assertThat(result.path("name").asText()).isEqualTo("Alice采购");
        assertThat(result.path("avatarUrl").asText()).isEqualTo("https://example.test/avatar.png");
        assertThat(result.path("department").asText()).isEqualTo("采购部");
        assertThat(result.path("openId").asText()).isEqualTo("open-3");
        assertThat(result.path("roleType").asText()).isEqualTo("ADMIN");
        assertThat(result.path("status").asText()).isEqualTo("ENABLED");
        assertThat(result.path("createdAt").asText()).isEqualTo("2026-11-17 09:00:00");
        assertThat(result.has("tenantKey")).isFalse();
        assertThat(result.has("unionId")).isFalse();
        assertThat(result.has("isDeleted")).isFalse();
        assertThat(read(get(PATH + "/1").session(admin)).path("name").asText()).isEqualTo("列表管理员");
    }

    @Test
    void detailAllowsDisabledTargetAndPreservesMissingProfileFields() throws Exception {
        jdbc.update("UPDATE user_info SET avatar_url=NULL WHERE id=4");
        var result = read(get(PATH + "/4").session(admin));
        assertThat(result.path("name").asText()).isEqualTo("李四");
        assertThat(result.path("status").asText()).isEqualTo("DISABLED");
        assertThat(result.path("roleType").asText()).isEqualTo("APPLICANT");
        assertThat(result.path("department").isNull()).isTrue();
        assertThat(result.path("avatarUrl").isNull()).isTrue();
        assertThat(result.path("openId").asText()).isEqualTo("open-4");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_deleted FROM user_info WHERE id=4", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void detailUsesCompanyScopeAndHidesWhetherForeignTargetExists() throws Exception {
        String missing =
                mvc.perform(get(PATH + "/999").session(admin))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String foreign =
                mvc.perform(get(PATH + "/6").session(admin).param("tenantKey", "other-company"))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(foreign).isEqualTo(missing);
        assertThat(
                        read(get(PATH + "/3")
                                        .session(admin)
                                        .param("tenantKey", "other-company")
                                        .param("status", "DISABLED")
                                        .param("keyword", "不存在"))
                                .path("id")
                                .asText())
                .isEqualTo("3");
        mvc.perform(get(PATH + "/9223372036854775807").session(admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void detailKeepsBigintIdAsString() throws Exception {
        long id = 9007199254740993L;
        addUser(id, COMPANY, "长 ID 用户", null, "APPLICANT", 0, "2026-11-17 13:00:00");
        var result = read(get(PATH + "/" + id).session(admin));
        assertThat(result.path("id").isTextual()).isTrue();
        assertThat(result.path("id").asText()).isEqualTo("9007199254740993");
        assertThat(result.path("openId").asText()).isEqualTo("open-9007199254740993");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
    void detailRejectsInvalidIds(String id) throws Exception {
        mvc.perform(get(PATH + "/" + id).session(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_DETAIL_INVALID_REQUEST"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void statsCountCompanyRolesAndStatusesIncludingCurrentAdministrator() throws Exception {
        var result = read(get(PATH + "/stats").session(admin));
        assertThat(result.size()).isEqualTo(4);
        assertThat(result.path("total").asLong()).isEqualTo(5);
        assertThat(result.path("enabledApplicants").asLong()).isEqualTo(1);
        assertThat(result.path("enabledAdmins").asLong()).isEqualTo(2);
        assertThat(result.path("disabledUsers").asLong()).isEqualTo(2);
        assertThat(result.path("total").asLong())
                .isEqualTo(
                        result.path("enabledApplicants").asLong()
                                + result.path("enabledAdmins").asLong()
                                + result.path("disabledUsers").asLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_info", Long.class)).isEqualTo(6);
    }

    @Test
    void statsIgnoreListFiltersPaginationAndRequestedCompanyButReadCurrentData() throws Exception {
        assertThat(read(list().param("keyword", "不存在")).path("total").asLong()).isZero();
        var result =
                read(
                        get(PATH + "/stats")
                                .session(admin)
                                .param("keyword", "不存在")
                                .param("roleType", "ROOT")
                                .param("status", "DISABLED")
                                .param("page", "-1")
                                .param("pageSize", "1")
                                .param("tenantKey", "other-company"));
        assertThat(result.path("total").asLong()).isEqualTo(5);
        assertThat(result.path("enabledApplicants").asLong()).isEqualTo(1);
        assertThat(result.path("enabledAdmins").asLong()).isEqualTo(2);
        assertThat(result.path("disabledUsers").asLong()).isEqualTo(2);
        jdbc.update("UPDATE user_info SET role_type='ADMIN', is_deleted=0 WHERE id=4");
        // 测试事务跨请求复用 SqlSession；JDBC 变更后清理一级缓存，模拟独立 HTTP 请求。
        sqlSession.clearCache();
        var updated = read(get(PATH + "/stats").session(admin));
        assertThat(updated.path("total").asLong()).isEqualTo(5);
        assertThat(updated.path("enabledAdmins").asLong()).isEqualTo(3);
        assertThat(updated.path("disabledUsers").asLong()).isEqualTo(1);
    }

    @Test
    void statsReturnZeroForEmptyAggregationRatherThanNull() {
        var result = userMapper.stats("empty-company");
        assertThat(result.total()).isZero();
        assertThat(result.enabledApplicants()).isZero();
        assertThat(result.enabledAdmins()).isZero();
        assertThat(result.disabledUsers()).isZero();
    }

    @Test
    void statsSupportNoEnabledApplicantsAndOnlyCurrentAdministrator() throws Exception {
        jdbc.update("UPDATE user_info SET is_deleted=1 WHERE tenant_key=? AND id<>1", COMPANY);
        var result = read(get(PATH + "/stats").session(admin));
        assertThat(result.path("total").asLong()).isEqualTo(5);
        assertThat(result.path("enabledApplicants").asLong()).isZero();
        assertThat(result.path("enabledAdmins").asLong()).isEqualTo(1);
        assertThat(result.path("disabledUsers").asLong()).isEqualTo(4);
    }

    @Test
    void defaultListIncludesDisabledCompanyUsersAndOnlyReturnsListFields() throws Exception {
        var result = read(list());
        assertThat(result.path("total").asLong()).isEqualTo(5);
        assertThat(result.path("items").size()).isEqualTo(5);
        assertThat(ids(result)).containsExactly("5", "4", "3", "2", "1");
        assertThat(result.at("/items/0/status").asText()).isEqualTo("DISABLED");
        assertThat(result.at("/items/1/department").isNull()).isTrue();
        assertThat(result.at("/items/2/createdAt").asText()).isEqualTo("2026-11-17 09:00:00");
        assertThat(result.at("/items/2/avatarUrl").asText())
                .isEqualTo("https://example.test/avatar.png");
        for (var user : result.path("items")) {
            assertThat(user.path("id").isTextual()).isTrue();
            assertThat(user.size()).isEqualTo(7);
            assertThat(user.has("tenantKey")).isFalse();
            assertThat(user.has("openId")).isFalse();
            assertThat(user.has("unionId")).isFalse();
            assertThat(user.has("isDeleted")).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_info", Long.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT SUM(is_deleted) FROM user_info", Long.class))
                .isEqualTo(2);
    }

    @Test
    void keywordRoleAndStatusFiltersCombineAndBlankFiltersAreUnrestricted() throws Exception {
        var applicant =
                read(
                        list().param("keyword", " 张 ")
                                .param("roleType", "APPLICANT")
                                .param("status", "ENABLED"));
        assertThat(ids(applicant)).containsExactly("2");
        var disabled =
                read(
                        list().param("keyword", "采购")
                                .param("roleType", " ADMIN ")
                                .param("status", "DISABLED"));
        assertThat(disabled.path("total").asLong()).isEqualTo(1);
        assertThat(ids(disabled)).containsExactly("5");
        assertThat(ids(read(list().param("keyword", "ALICE")))).containsExactly("3");
        assertThat(
                        read(list().param("keyword", "  ")
                                        .param("roleType", "")
                                        .param("status", " "))
                                .path("total")
                                .asLong())
                .isEqualTo(5);
        assertThat(ids(read(list().param("status", "ENABLED")))).containsExactly("3", "2", "1");
        assertThat(ids(read(list().param("status", "DISABLED")))).containsExactly("5", "4");
        assertThat(read(list().param("keyword", "不存在")).path("total").asLong()).isZero();
        assertThat(read(list().param("keyword", "不存在")).path("items").isEmpty()).isTrue();
    }

    @Test
    void paginationUsesStableOrderAndOutOfRangePagesKeepTotal() throws Exception {
        assertThat(ids(read(list().param("pageSize", "2")))).containsExactly("5", "4");
        assertThat(ids(read(list().param("page", "2").param("pageSize", "2"))))
                .containsExactly("3", "2");
        assertThat(ids(read(list().param("page", "3").param("pageSize", "2"))))
                .containsExactly("1");
        var empty = read(list().param("page", "2147483647").param("pageSize", "100"));
        assertThat(empty.path("total").asLong()).isEqualTo(5);
        assertThat(empty.path("items").isEmpty()).isTrue();
        assertThat(read(list().param("pageSize", "1")).path("items").size()).isEqualTo(1);
    }

    @Test
    void keywordTreatsWildcardsAndSqlTextAsLiteralAndIdsStayPrecise() throws Exception {
        long largeId = 9007199254740993L;
        addUser(largeId, COMPANY, "特殊字符用户", "质检%_!\\部", "APPLICANT", 0, "2026-11-17 13:00:00");
        for (String keyword : List.of("%", "_", "!", "\\")) {
            var result = read(list().param("keyword", keyword));
            assertThat(result.path("total").asLong()).isEqualTo(1);
            assertThat(ids(result)).containsExactly("9007199254740993");
        }
        assertThat(read(list().param("keyword", "' OR 1=1 --")).path("total").asLong()).isZero();
        assertThat(read(list().param("keyword", "😀".repeat(128))).path("total").asLong()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "page,0",
        "page,-1",
        "page,abc",
        "page,2147483648",
        "pageSize,0",
        "pageSize,-1",
        "pageSize,101",
        "pageSize,1.5",
        "roleType,ROOT",
        "status,ACTIVE"
    })
    void rejectsInvalidFiltersAndPagination(String key, String value) throws Exception {
        mvc.perform(list().param(key, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_LIST_INVALID_REQUEST"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void rejectsKeywordsOver128Characters() throws Exception {
        for (String keyword : List.of("字".repeat(129), "😀".repeat(129))) {
            mvc.perform(list().param("keyword", keyword))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("USER_LIST_INVALID_REQUEST"));
        }
    }

    @Test
    void defaultPageSizeIsTenAndFiltersCannotSelectAnotherCompany() throws Exception {
        for (long id = 10; id < 21; id++) {
            addUser(id, COMPANY, "分页用户" + id, "运营部", "APPLICANT", 0, "2026-11-17 14:00:00");
        }
        var result = read(list().param("tenantKey", "other-company"));
        assertThat(result.path("total").asLong()).isEqualTo(16);
        assertThat(result.path("items").size()).isEqualTo(10);
        assertThat(result.at("/items/0/id").asText()).isEqualTo("20");
    }

    @Test
    void unauthenticatedApplicantDisabledForeignAndMismatchedIdentitiesAreDenied()
            throws Exception {
        for (String path : List.of(PATH, PATH + "/stats", PATH + "/2")) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        }
        for (MockHttpSession invalid :
                List.of(
                        session(2, COMPANY, "ADMIN"),
                        session(5, COMPANY, "ADMIN"),
                        session(999, COMPANY, "ADMIN"),
                        established(
                                new CurrentUserResponse(
                                        "999", "open-1", COMPANY, "伪造本地主键", "ADMIN", null)))) {
            mvc.perform(get(PATH).session(invalid).param("roleType", "ADMIN"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("USER_ADMIN_REQUIRED"));
            mvc.perform(get(PATH + "/stats").session(invalid))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("USER_ADMIN_REQUIRED"));
            mvc.perform(get(PATH + "/2").session(invalid))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("USER_ADMIN_REQUIRED"));
        }
        mvc.perform(get(PATH).session(session(6, "other-company", "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_SCOPE_DENIED"));
        mvc.perform(get(PATH + "/stats").session(session(6, "other-company", "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_SCOPE_DENIED"));
        mvc.perform(get(PATH + "/2").session(session(6, "other-company", "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_SCOPE_DENIED"));
    }

    @Test
    void rechecksDatabaseRoleAndDisabledFlagInsteadOfTrustingOldSession() throws Exception {
        jdbc.update("UPDATE user_info SET role_type = 'APPLICANT' WHERE id = 1");
        mvc.perform(list())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_ADMIN_REQUIRED"));
        mvc.perform(get(PATH + "/stats").session(admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_ADMIN_REQUIRED"));
        mvc.perform(get(PATH + "/2").session(admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_ADMIN_REQUIRED"));
        jdbc.update("UPDATE user_info SET role_type = 'ADMIN', is_deleted = 1 WHERE id = 1");
        mvc.perform(list())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_ADMIN_REQUIRED"));
        mvc.perform(get(PATH + "/stats").session(admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_ADMIN_REQUIRED"));
        mvc.perform(get(PATH + "/2").session(admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_ADMIN_REQUIRED"));
    }

    @Test
    void missingCompanyConfigurationIsUnavailable() throws Exception {
        String tenant = config.getTenantKey();
        config.setTenantKey("");
        try {
            mvc.perform(list())
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("USER_SCOPE_MISSING"));
            mvc.perform(get(PATH + "/stats").session(admin))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("USER_SCOPE_MISSING"));
            mvc.perform(get(PATH + "/2").session(admin))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("USER_SCOPE_MISSING"));
        } finally {
            config.setTenantKey(tenant);
        }
    }

    @Test
    void storageAndTransactionFailuresReturnSanitizedUnavailableErrors() throws Exception {
        var service = mock(UserManagementService.class);
        var login = mock(LoginSessionService.class);
        when(login.requireLogin(any()))
                .thenReturn(
                        new AuthSessionResponse(
                                new CurrentUserResponse(
                                        "1", "open-1", COMPANY, "管理员", "ADMIN", null),
                                Instant.now().plusSeconds(3600)));
        var isolated =
                MockMvcBuilders.standaloneSetup(new UserManagementController(service, login))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        for (RuntimeException failure :
                List.of(
                        new DataAccessResourceFailureException("private-database-details"),
                        new CannotCreateTransactionException("private-database-details"))) {
            doThrow(failure).when(service).list(any(), any());
            doThrow(failure).when(service).stats(any());
            doThrow(failure).when(service).detail(any(), anyLong());
            var result =
                    isolated.perform(get(PATH))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.code").value("USER_STORAGE_UNAVAILABLE"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(result).doesNotContain("private-database-details");
            var statsResult =
                    isolated.perform(get(PATH + "/stats"))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.code").value("USER_STORAGE_UNAVAILABLE"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(statsResult).doesNotContain("private-database-details");
            var detailResult =
                    isolated.perform(get(PATH + "/2"))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.code").value("USER_STORAGE_UNAVAILABLE"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(detailResult).doesNotContain("private-database-details");
        }
    }

    private MockHttpServletRequestBuilder list() {
        return get(PATH).session(admin);
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(
                mvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray());
    }

    private List<String> ids(JsonNode result) {
        return result.path("items").findValuesAsText("id");
    }

    private MockHttpSession session(long id, String tenant, String role) {
        return established(
                new CurrentUserResponse(
                        String.valueOf(id), "open-" + id, tenant, "会话姓名", role, null));
    }

    private MockHttpSession established(CurrentUserResponse user) {
        var request = new MockHttpServletRequest();
        sessions.establish(request, new AuthSessionResponse(user, Instant.now().plusSeconds(3600)));
        return (MockHttpSession) request.getSession(false);
    }

    private void addUser(
            long id,
            String tenant,
            String name,
            String department,
            String role,
            int disabled,
            String createdAt) {
        jdbc.update(
                """
            INSERT INTO user_info (id, tenant_key, open_id, name, avatar_url, department, role_type, is_deleted, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                id,
                tenant,
                "open-" + id,
                name,
                "https://example.test/avatar.png",
                department,
                role,
                disabled,
                createdAt);
    }
}
