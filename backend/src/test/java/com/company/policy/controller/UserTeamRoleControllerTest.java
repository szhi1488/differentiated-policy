package com.company.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.RbacFixture;
import com.company.policy.dto.ChannelPlatform;
import com.company.policy.service.AuthorizationService;
import com.company.policy.service.ExecutionNotices;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.UserInfoService;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
        properties = {
            "spring.config.import=",
            "feishu.tenant-key=rbac-team",
            "spring.datasource.url=jdbc:h2:mem:rbac-team;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
        })
@AutoConfigureMockMvc
@Transactional
class UserTeamRoleControllerTest {
    private static final String TENANT = "rbac-team";
    private static final String TEAM = "/api/admin/users/platform-assignments/RETAIL/JD";
    private static final ChannelPlatform JD = new ChannelPlatform("RETAIL", "JD");
    @Autowired AuthorizationService roles;
    @Autowired com.company.policy.mapper.PolicyDeliveryAudienceMapper audience;
    @Autowired UserInfoService identities;
    @Autowired LoginSessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean ExecutionNotices notices;
    private MockHttpSession admin;

    @BeforeEach
    void setup() {
        roles.ensureDefaults(TENANT);
        for (int id = 1; id <= 4; id++) {
            jdbc.update(
                    "INSERT INTO user_info(id,tenant_key,open_id,name) VALUES(?,?,?,?)",
                    id,
                    TENANT,
                    "open-" + id,
                    "同事" + id);
            RbacFixture.setRole(
                    jdbc,
                    id,
                    switch (id) {
                        case 1 -> "ADMIN";
                        case 4 -> "PURCHASE";
                        default -> "OPERATIONS";
                    });
        }
        admin = session(1);
    }

    private MockHttpSession session(int id) {
        var request = new MockHttpServletRequest();
        sessions.establish(
                request,
                new AuthSessionResponse(
                        new CurrentUserResponse(
                                "" + id, "open-" + id, TENANT, "同事", List.of(), List.of(), null),
                        Instant.now().plusSeconds(3600)));
        return (MockHttpSession) request.getSession(false);
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder, Object body)
            throws Exception {
        return builder.session(admin)
                .header("Origin", "http://localhost:5173")
                .header("X-Policy-Request", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body));
    }

    private MockHttpServletRequestBuilder team(int version, String... kinds) throws Exception {
        var members = new java.util.ArrayList<Map<String, String>>();
        for (int i = 0; i < kinds.length; i++)
            members.add(Map.of("userId", String.valueOf(2 + i), "memberKind", kinds[i]));
        return write(put(TEAM), Map.of("expectedVersion", version, "members", members));
    }

    @Test
    void teamRequiresOneQualifiedSubmitterAndAStaleVersionCannotReplaceIt() throws Exception {
        mvc.perform(team(0, "MEMBER", "MEMBER")).andExpect(status().isBadRequest());
        mvc.perform(team(0, "SUBMITTER", "SUBMITTER")).andExpect(status().isBadRequest());
        mvc.perform(team(0, "SUBMITTER", "MEMBER", "MEMBER")).andExpect(status().isBadRequest());
        mvc.perform(team(0, "SUBMITTER", "MEMBER")).andExpect(status().isNoContent());
        mvc.perform(team(0, "MEMBER", "SUBMITTER")).andExpect(status().isConflict());
        mvc.perform(team(1, "MEMBER", "SUBMITTER")).andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/users/platform-assignments").session(admin))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                        "$[?(@.perspective == 'RETAIL' && @.platformCode == 'JD')].version")
                                .value(org.hamcrest.Matchers.contains(2)));
        assertThat(
                        jdbc.queryForList(
                                "SELECT user_id FROM user_platform_assignment WHERE member_kind='SUBMITTER'",
                                Long.class))
                .containsExactly(3L);
        verify(notices).platformChanged(TENANT, JD, null, 2L);
        verify(notices).platformChanged(TENANT, JD, 2L, 3L);
        mvc.perform(
                        write(
                                patch("/api/admin/users/3/status"),
                                Map.of("expectedVersion", 1, "status", "DISABLED")))
                .andExpect(status().isOk());
        verify(notices).platformChanged(TENANT, JD, 3L, null);
        assertThat(jdbc.queryForList("SELECT user_id FROM user_platform_assignment", Long.class))
                .containsExactly(2L);
    }

    @Test
    void customRolesUnionRefreshesExistingSessionAndPermissionRemovalDemotesSubmitter()
            throws Exception {
        var template =
                new java.util.HashMap<String, Object>(
                        Map.of(
                                "roleCode",
                                "CHANNEL",
                                "roleName",
                                "渠道运营",
                                "enabled",
                                true,
                                "permissions",
                                List.of("policy.apply", "policy.read.platform_submitted")));
        var res =
                mvc.perform(write(post("/api/admin/roles"), template))
                        .andExpect(status().isOk())
                        .andReturn();
        var id = json.readTree(res.getResponse().getContentAsByteArray()).get("id").asText();
        mvc.perform(
                        write(
                                patch("/api/admin/users/2/roles"),
                                Map.of(
                                        "expectedVersion",
                                        1,
                                        "roleCodes",
                                        List.of("CHANNEL", "PRODUCT_OPS"))))
                .andExpect(status().isOk());
        var employee = session(2);
        mvc.perform(get("/api/auth/session").session(employee))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.user.permissions",
                                org.hamcrest.Matchers.hasItems(
                                        "policy.apply",
                                        "policy.product.decide_assigned",
                                        "policy.product.read_own_decisions")));
        mvc.perform(team(0, "SUBMITTER", "MEMBER")).andExpect(status().isNoContent());
        template.put("permissions", List.of("policy.read.platform_submitted"));
        template.put("expectedVersion", 1);
        mvc.perform(write(put("/api/admin/roles/" + id), template)).andExpect(status().isOk());
        mvc.perform(write(put("/api/admin/roles/" + id), template))
                .andExpect(status().isConflict());
        assertThat(
                        jdbc.queryForList(
                                "SELECT member_kind FROM user_platform_assignment ORDER BY user_id",
                                String.class))
                .containsExactly("MEMBER", "MEMBER");
        mvc.perform(get("/api/auth/session").session(employee))
                .andExpect(
                        jsonPath(
                                "$.user.permissions",
                                org.hamcrest.Matchers.not(
                                        org.hamcrest.Matchers.hasItem("policy.apply"))));
        mvc.perform(
                        get("/api/admin/users/platform-candidates")
                                .session(admin)
                                .param("keyword", "同事"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3));
        mvc.perform(
                        write(
                                patch("/api/admin/users/2/roles"),
                                Map.of("expectedVersion", 2, "roleCodes", List.of())))
                .andExpect(status().isOk());
        assertThat(identities.saveVerifiedIdentity(TENANT, "open-2", null, "新姓名", null).roleCodes())
                .isEmpty();
        assertThat(jdbc.queryForList("SELECT user_id FROM user_platform_assignment", Long.class))
                .containsExactly(3L);
    }

    @Test
    void subsidyOnlyMemberCanBeAssignedToReadSubsidiesAndCatalogNoticesCheckTheSpecificAction()
            throws Exception {
        var custom =
                Map.of(
                        "roleCode",
                        "REGIONAL",
                        "roleName",
                        "平台补贴查询与商品清洗",
                        "enabled",
                        true,
                        "permissions",
                        List.of("subsidy.read.platform", "catalog.clean"));
        mvc.perform(write(post("/api/admin/roles"), custom)).andExpect(status().isOk());
        mvc.perform(
                        write(
                                patch("/api/admin/users/2/roles"),
                                Map.of("expectedVersion", 1, "roleCodes", List.of("REGIONAL"))))
                .andExpect(status().isOk());
        mvc.perform(team(0, "MEMBER", "SUBMITTER")).andExpect(status().isNoContent());
        mvc.perform(team(1, "SUBMITTER", "MEMBER")).andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/admin/users/platform-candidates")
                                .session(admin)
                                .param("keyword", "同事2"))
                .andExpect(jsonPath("$.items[0].id").value("2"))
                .andExpect(jsonPath("$.items[0].canSubmit").value(false));
        assertThat(audience.recipients(TENANT, List.of(2L), "catalog.clean")).hasSize(1);
        assertThat(audience.recipients(TENANT, List.of(2L), "catalog.import")).isEmpty();
    }
}
