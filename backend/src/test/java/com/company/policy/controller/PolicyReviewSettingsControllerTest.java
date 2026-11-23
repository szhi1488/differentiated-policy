package com.company.policy.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyReviewSettingsUpdate;
import com.company.policy.entity.UserInfo;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.PolicyReviewSettingsService;
import com.company.policy.service.PolicyReviewSettingsService.Assignment;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        properties = {
            "spring.config.import=",
            "spring.datasource.url=jdbc:h2:mem:policy_review_settings;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "feishu.tenant-key=review-company",
            "feishu.policy-chat-id=local-review-group-fixture",
            "feishu.allowed-origins=http://localhost:5173"
        })
@AutoConfigureMockMvc
class PolicyReviewSettingsControllerTest {
    private static final String SETTINGS = "/api/admin/policy-review-settings";
    private static final String COMPANY = "review-company";
    private static final CurrentUserResponse ADMIN =
            new CurrentUserResponse("1", "open-1", COMPANY, "管理甲", "ADMIN", null);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired LoginSessionService sessions;
    @Autowired PolicyReviewSettingsService settings;
    @Autowired TransactionTemplate tx;
    private MockHttpSession admin;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM policy_review_setting_member");
        jdbc.update("DELETE FROM policy_review_settings");
        jdbc.update("DELETE FROM user_info");
        jdbc.update(
                """
            INSERT INTO user_info(id,tenant_key,open_id,name,department,role_type,is_deleted) VALUES
            (1,'review-company','open-1','管理甲','平台','ADMIN',0),
            (2,'review-company','open-2','申请人','零售','APPLICANT',0),
            (3,'review-company','open-3','王宁','运营_组','APPLICANT',0),
            (4,'review-company','open-4','王宁','采购二组','ADMIN',0),
            (5,'review-company','open-5','王宁','运营_组','ADMIN',1),
            (6,'other-company','open-6','王宁','采购二组','ADMIN',0),
            (7,'review-company','open-7','周青',NULL,'ADMIN',0)
            """);
        admin = session(ADMIN);
    }

    @Test
    void candidatesAreTenantRoleEnabledScopedAndNamesRemainDistinctAcrossPages() throws Exception {
        var first =
                read(
                        get(SETTINGS + "/candidates")
                                .session(admin)
                                .param("reviewGroup", "PRODUCT")
                                .param("name", " 王宁 ")
                                .param("pageSize", "1"));
        assertThat(first.path("total").asInt()).isEqualTo(2);
        assertThat(first.path("items").get(0).size()).isEqualTo(3);
        assertThat(first.at("/items/0/id").asText()).isEqualTo("4");
        assertThat(first.at("/items/0/department").asText()).isEqualTo("采购二组");
        var next =
                read(
                        get(SETTINGS + "/candidates")
                                .session(admin)
                                .param("reviewGroup", "PRODUCT")
                                .param("name", "王宁")
                                .param("pageSize", "1")
                                .param("page", "2"));
        assertThat(next.at("/items/0/id").asText()).isEqualTo("3");
        var filter =
                read(
                        get(SETTINGS + "/candidates")
                                .session(admin)
                                .param("reviewGroup", "PRODUCT")
                                .param("name", "宁")
                                .param("department", "_"));
        assertThat(filter.path("total").asInt()).isOne();
        assertThat(filter.at("/items/0/id").asText()).isEqualTo("3");
        var purchase =
                read(
                        get(SETTINGS + "/candidates")
                                .session(admin)
                                .param("reviewGroup", "PURCHASE")
                                .param("name", "王宁")
                                .param("tenantKey", "other-company")
                                .param("status", "DISABLED"));
        assertThat(purchase.path("items").findValuesAsText("id")).containsExactly("4");
        mvc.perform(get(SETTINGS + "/candidates").session(admin).param("reviewGroup", "OTHER"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get(SETTINGS + "/candidates")
                                .session(admin)
                                .param("reviewGroup", "PRODUCT")
                                .param("pageSize", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void settingsUseOptimisticVersionAndReturnDeduplicatedNamesAndDepartments() throws Exception {
        var initial = read(get(SETTINGS).session(admin));
        assertThat(initial.path("version").asInt()).isZero();
        assertThat(initial.get("policyChatConfigured").booleanValue()).isTrue();
        assertThat(initial.toString()).doesNotContain("local-review-group-fixture");
        var update =
                new PolicyReviewSettingsUpdate(0, List.of("3", "4", "3"), List.of("4", "7", "7"));
        var result = read(save(admin, update));
        assertThat(result.get("policyChatConfigured").booleanValue()).isTrue();
        assertThat(result.toString()).doesNotContain("local-review-group-fixture");
        assertThat(result.path("version").asInt()).isOne();
        assertThat(result.path("productReviewers").findValuesAsText("id"))
                .containsExactly("3", "4");
        assertThat(result.path("purchaseReviewers").findValuesAsText("id"))
                .containsExactly("4", "7");
        assertThat(result.at("/productReviewers/0/department").asText()).isEqualTo("运营_组");
        assertThat(result.at("/purchaseReviewers/1/department").isNull()).isTrue();
        mvc.perform(save(admin, new PolicyReviewSettingsUpdate(0, List.of("2"), List.of("1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_REVIEW_SETTINGS_CONFLICT"));
        assertThat(read(get(SETTINGS).session(admin))).isEqualTo(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5", "6", "1000", "2", "0", "0003", "9999999999999999999"})
    void invalidPurchaseNeverPartiallyReplacesTheCurrentSettings(String id) throws Exception {
        settings.save(ADMIN, new PolicyReviewSettingsUpdate(0, List.of("3"), List.of("7")));
        mvc.perform(save(admin, new PolicyReviewSettingsUpdate(1, List.of("4"), List.of(id))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POLICY_REVIEW_SETTINGS_INVALID"));
        var result = read(get(SETTINGS).session(admin));
        assertThat(result.path("version").asInt()).isOne();
        assertThat(result.path("productReviewers").findValuesAsText("id")).containsExactly("3");
        assertThat(result.path("purchaseReviewers").findValuesAsText("id")).containsExactly("7");
    }

    @Test
    void readsAndWritesRequireCurrentCompanyAdministratorAndTrustedWriteOrigin() throws Exception {
        var applicant =
                session(new CurrentUserResponse("2", "open-2", COMPANY, "申请人", "ADMIN", null));
        mvc.perform(get(SETTINGS)).andExpect(status().isUnauthorized());
        mvc.perform(
                        get(SETTINGS + "/candidates")
                                .param("reviewGroup", "PRODUCT")
                                .session(applicant))
                .andExpect(status().isForbidden());
        mvc.perform(get(SETTINGS).session(applicant)).andExpect(status().isForbidden());
        mvc.perform(save(applicant, new PolicyReviewSettingsUpdate(0, List.of("3"), List.of("4"))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put(SETTINGS)
                                .session(admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
        var foreign =
                session(
                        new CurrentUserResponse(
                                "6", "open-6", "other-company", "王宁", "ADMIN", null));
        mvc.perform(get(SETTINGS).session(foreign)).andExpect(status().isForbidden());
    }

    @Test
    void submissionFiltersTheOwnerAndInvalidAccountsAndReservesTheOnlyPurchaser() {
        assertThat(assign(true).version()).isZero();
        settings.save(
                ADMIN,
                new PolicyReviewSettingsUpdate(0, List.of("2", "3", "4", "7"), List.of("4", "7")));
        var both = assign(true);
        assertThat(both.productReviewers()).extracting(UserInfo::getId).containsExactly(3L, 4L, 7L);
        assertThat(both.purchaseReviewers()).extracting(UserInfo::getId).containsExactly(4L, 7L);
        jdbc.update("UPDATE user_info SET is_deleted=1 WHERE id=7");
        var reserve = assign(true);
        assertThat(reserve.productReviewers()).extracting(UserInfo::getId).containsExactly(3L);
        assertThat(reserve.purchaseReviewers()).extracting(UserInfo::getId).containsExactly(4L);
        assertThat(reserve.productReviewers().get(0).getDepartment()).isEqualTo("运营_组");
        assertThat(assign(false).productReviewers()).isEmpty();
        jdbc.update("UPDATE user_info SET role_type='APPLICANT' WHERE id=4");
        assertThatThrownBy(() -> assign(false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("采购");
    }

    @Test
    void staffingFailuresIdentifyTheMissingGroupAndReassignmentUsesTheLatestVersion() {
        settings.save(ADMIN, new PolicyReviewSettingsUpdate(0, List.of("4"), List.of("4")));
        assertThatThrownBy(() -> assign(true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不同人员");
        assertThat(assign(false).version()).isOne();
        settings.save(ADMIN, new PolicyReviewSettingsUpdate(1, List.of("2"), List.of("4")));
        assertThatThrownBy(() -> assign(true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("产品运营");
        settings.save(ADMIN, new PolicyReviewSettingsUpdate(2, List.of("3"), List.of("1")));
        assertThat(assign(true).version()).isEqualTo(3);
    }

    @Test
    void concurrentFirstSavesPublishOnlyOneCompleteVersion() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        try {
            Callable<String> save =
                    () -> {
                        start.await(5, TimeUnit.SECONDS);
                        try {
                            settings.save(
                                    ADMIN,
                                    new PolicyReviewSettingsUpdate(
                                            0, List.of("3"), List.of("4", "7")));
                            return "saved";
                        } catch (ApiException e) {
                            return e.getCode();
                        }
                    };
            var a = pool.submit(save);
            var b = pool.submit(save);
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("saved", "POLICY_REVIEW_SETTINGS_CONFLICT");
            assertThat(settings.read(ADMIN).purchaseReviewers()).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    private Assignment assign(boolean initial) {
        return tx.execute(
                status -> {
                    int version = settings.lockForSubmission(COMPANY, 2L);
                    var assignment = settings.assign(COMPANY, 2L, initial);
                    assertThat(assignment.version()).isEqualTo(version);
                    return assignment;
                });
    }

    private MockHttpSession session(CurrentUserResponse user) {
        var request = new MockHttpServletRequest();
        sessions.establish(request, new AuthSessionResponse(user, Instant.now().plusSeconds(3600)));
        return (MockHttpSession) request.getSession(false);
    }

    private MockHttpServletRequestBuilder save(
            MockHttpSession session, PolicyReviewSettingsUpdate update) throws Exception {
        return put(SETTINGS)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Origin", "http://localhost:5173")
                .header("X-Policy-Request", "1")
                .content(json.writeValueAsBytes(update));
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
}
