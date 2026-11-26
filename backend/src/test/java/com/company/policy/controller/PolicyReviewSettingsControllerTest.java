package com.company.policy.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.ReviewAccounts;
import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyReviewSettingsUpdate;
import com.company.policy.dto.RoleDefinitionUpdate;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.PolicyReviewSettingsService;
import com.company.policy.service.PolicyReviewSettingsService.Assignment;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
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
            "spring.sql.init.schema-locations=classpath:user-info-schema.sql,classpath:catalog-import-schema.sql",
            "spring.datasource.url=jdbc:h2:mem:policy_review_settings;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "feishu.tenant-key=review-company",
            "feishu.policy-chat-id=local-review-group-fixture",
            "feishu.allowed-origins=http://localhost:5173"
        })
@AutoConfigureMockMvc
class PolicyReviewSettingsControllerTest {
    @Autowired com.company.policy.service.AuthorizationService roles;
    private static final String SETTINGS = "/api/admin/policy-review-settings";
    private static final String COMPANY = "review-company";
    private static final CurrentUserResponse ADMIN =
            new CurrentUserResponse(
                    "1",
                    "open-1",
                    COMPANY,
                    "管理甲",
                    java.util.List.of("ADMIN"),
                    java.util.List.of(),
                    null);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired LoginSessionService sessions;
    @Autowired PolicyReviewSettingsService settings;
    @Autowired TransactionTemplate tx;
    private MockHttpSession admin;

    @BeforeEach
    void setup() {
        for (String table :
                List.of(
                        "policy_review_series_owner",
                        "policy_review_brand_owner",
                        "policy_review_settings",
                        "sys_user_role",
                        "sys_role_permission",
                        "sys_role",
                        "user_info",
                        "series",
                        "brand")) jdbc.update("DELETE FROM " + table);
        for (int i = 1; i <= 7; i++) {
            String tenant = i == 6 ? "other-company" : COMPANY;
            String role =
                    switch (i) {
                        case 1 -> "ADMIN";
                        case 2 -> "OPERATIONS";
                        case 3, 4, 6 -> "PRODUCT_OPS";
                        default -> "PURCHASE";
                    };
            ReviewAccounts.create(
                    jdbc, roles, i, tenant, "open-" + i, i == 2 || i == 7 ? "成员" + i : "王宁", role);
            jdbc.update(
                    "UPDATE user_info SET department=?,is_deleted=? WHERE id=?",
                    i == 3 ? "运营_组" : "采购二组",
                    i == 5 ? 1 : 0,
                    i);
        }
        ReviewAccounts.grant(jdbc, 4, COMPANY, "PURCHASE");
        ReviewAccounts.grant(jdbc, 5, COMPANY, "PRODUCT_OPS");
        ReviewAccounts.grant(jdbc, 6, "other-company", "PURCHASE");
        roles.save(
                ADMIN,
                null,
                new RoleDefinitionUpdate(
                        "REVIEW_HELPER",
                        "审核协作",
                        true,
                        List.of(
                                "policy.product.decide_assigned",
                                "policy.purchase.decide_assigned"),
                        null));
        ReviewAccounts.create(jdbc, roles, 8, COMPANY, "open-8", "王宁", "REVIEW_HELPER");
        jdbc.update("INSERT INTO brand(id,name) VALUES(1,'OPPO手机'),(2,'一加手机')");
        jdbc.update(
                "INSERT INTO series(id,brand_id,name) VALUES(1,1,'Find'),(2,1,'K'),(3,2,'数字系列')");
        admin = session(ADMIN);
    }

    @Test
    void inFlightReassignmentEndpointsAreAbsentEvenForAdministrators() throws Exception {
        mvc.perform(get(SETTINGS + "/pending-rounds").session(admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(
                        post("/api/admin/policy-reviews/1/reassign")
                                .session(admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1")
                                .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
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
        assertThat(next.path("total").asInt()).isEqualTo(2);
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
        assertThat(purchase.path("total").asInt()).isOne();
        mvc.perform(get(SETTINGS + "/candidates").session(admin).param("reviewGroup", "OTHER"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get(SETTINGS + "/candidates")
                                .session(admin)
                                .param("reviewGroup", "PRODUCT")
                                .param("pageSize", "101"))
                .andExpect(status().isBadRequest());
    }

    private PolicyReviewSettingsUpdate update(int version, String buyer, String product) {
        return update(version, buyer, product, "4");
    }

    private PolicyReviewSettingsUpdate update(
            int version, String buyer, String product, String onePlusBuyer) {
        return new PolicyReviewSettingsUpdate(
                version,
                List.of(
                        new PolicyReviewSettingsUpdate.Brand(
                                "1",
                                "BY_SERIES",
                                buyer,
                                null,
                                List.of(
                                        new PolicyReviewSettingsUpdate.Series("1", product),
                                        new PolicyReviewSettingsUpdate.Series("2", "4"))),
                        new PolicyReviewSettingsUpdate.Brand(
                                "2",
                                "BY_SERIES",
                                onePlusBuyer,
                                null,
                                List.of(new PolicyReviewSettingsUpdate.Series("3", "3")))));
    }

    @Test
    void settingsResolveEachBrandAndSeriesAndRejectStaleVersions() throws Exception {
        assertThat(read(get(SETTINGS).session(admin)).path("brands")).hasSize(2);
        var saved = read(save(admin, update(0, "7", "3")));
        assertThat(saved.get("version").asInt()).isOne();
        assertThat(saved.get("policyChatConfigured").booleanValue()).isTrue();
        assertThat(assign(1, 1, true).productReviewer().getId()).isEqualTo(3L);
        assertThat(assign(1, 2, true).productReviewer().getId()).isEqualTo(4L);
        assertThat(assign(2, 3, true).purchaseReviewer().getId()).isEqualTo(4L);
        mvc.perform(save(admin, update(0, "4", "3"))).andExpect(status().isConflict());
        mvc.perform(
                        save(
                                session(
                                        new CurrentUserResponse(
                                                "2",
                                                "open-2",
                                                COMPANY,
                                                "运营",
                                                List.of("ADMIN"),
                                                List.of(),
                                                null)),
                                update(1, "4", "3")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2", "3", "5", "6", "8", "999"})
    void ineligibleBuyerCannotReplaceConfiguredOwners(String id) throws Exception {
        settings.save(ADMIN, update(0, "7", "3"));
        mvc.perform(save(admin, update(1, id, "3"))).andExpect(status().isBadRequest());
        assertThat(assign(1, 1, false).purchaseReviewer().getId()).isEqualTo(7L);
    }

    @Test
    void assigningOneBuyerToTwoBrandsPreservesPreviousSettingsAndAllowsSwappingOwners()
            throws Exception {
        settings.save(ADMIN, update(0, "7", "3"));
        mvc.perform(save(admin, update(1, "4", "3")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POLICY_REVIEW_SETTINGS_INVALID"))
                .andExpect(jsonPath("$.message").value("每名采购只能负责一个品牌，请为「OPPO手机」和「一加手机」选择不同的采购。"));
        assertThat(assign(1, 1, false).purchaseReviewer().getId()).isEqualTo(7L);
        assertThat(assign(2, 3, false).purchaseReviewer().getId()).isEqualTo(4L);
        read(save(admin, update(1, "4", "3", "7")));
        assertThat(assign(1, 1, false).purchaseReviewer().getId()).isEqualTo(4L);
        assertThat(assign(2, 3, false).purchaseReviewer().getId()).isEqualTo(7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2", "5", "6", "7", "8"})
    void ineligibleProductReviewerCannotReplaceConfiguredOwners(String id) throws Exception {
        settings.save(ADMIN, update(0, "7", "3"));
        mvc.perform(save(admin, update(1, "7", id))).andExpect(status().isBadRequest());
        var active = assign(1, 1, true);
        assertThat(active.version()).isOne();
        assertThat(active.productReviewer().getId()).isEqualTo(3L);
    }

    @Test
    void disabledProductRoleCannotBeReplacedByTheSamePermissionFromAnotherRole() throws Exception {
        settings.save(ADMIN, update(0, "7", "3"));
        ReviewAccounts.grant(jdbc, 3, COMPANY, "REVIEW_HELPER");
        jdbc.update(
                "UPDATE sys_role SET is_enabled=0 WHERE tenant_key=? AND role_code='PRODUCT_OPS'",
                COMPANY);
        var candidates =
                read(get(SETTINGS + "/candidates").session(admin).param("reviewGroup", "PRODUCT"));
        assertThat(candidates.path("items")).isEmpty();
        assertThat(candidates.path("total").asInt()).isZero();
        mvc.perform(save(admin, update(1, "7", "3"))).andExpect(status().isBadRequest());
        assertThatThrownBy(() -> assign(1, 1, true)).isInstanceOf(ApiException.class);
        assertThat(assign(1, 1, false).purchaseReviewer().getId()).isEqualTo(7L);
    }

    @Test
    void changedQualificationsAndConflictingGatesBlockTheAffectedRound() {
        settings.save(ADMIN, update(0, "7", "3"));
        jdbc.update("UPDATE user_info SET is_deleted=1 WHERE id=3");
        assertThatThrownBy(() -> assign(1, 1, true)).isInstanceOf(ApiException.class);
        assertThat(assign(1, 1, false).version()).isOne();
        jdbc.update("UPDATE user_info SET is_deleted=0 WHERE id=3");
        settings.save(ADMIN, update(1, "4", "4", "7"));
        assertThatThrownBy(() -> assign(1, 1, true)).isInstanceOf(ApiException.class);
    }

    private Assignment assign(long brand, long series, boolean product) {
        return tx.execute(s -> settings.assign(COMPANY, 2L, brand, series, product));
    }

    @Test
    void changingOnePlusAceLeavesOtherSeriesAndBrandProcurementIntact() throws Exception {
        jdbc.update("INSERT INTO series(id,brand_id,name) VALUES(4,2,'Ace')");
        assertThat(read(get(SETTINGS).session(admin)).at("/brands/1/productMode").asText())
                .isEqualTo("BY_SERIES");
        read(save(admin, onePlusReviewers(0, "4")));
        assertThat(assign(2, 4, true).productReviewer().getId()).isEqualTo(4L);
        assertThat(assign(2, 3, true).productReviewer().getId()).isEqualTo(3L);

        var saved = read(save(admin, onePlusReviewers(1, "3")));
        assertThat(saved.at("/brands/1/series/1/productReviewer/id").asText()).isEqualTo("3");
        assertThat(assign(2, 4, true).productReviewer().getId()).isEqualTo(3L);
        assertThat(assign(2, 3, true).productReviewer().getId()).isEqualTo(3L);
        jdbc.update("INSERT INTO series(id,brand_id,name) VALUES(5,2,'Turbo')");
        assertThatThrownBy(() -> assign(2, 5, true)).isInstanceOf(ApiException.class);
        assertThat(assign(2, 5, false).purchaseReviewer().getId()).isEqualTo(7L);
    }

    private PolicyReviewSettingsUpdate onePlusReviewers(int version, String aceReviewer) {
        var series =
                List.of(
                        new PolicyReviewSettingsUpdate.Series("3", "3"),
                        new PolicyReviewSettingsUpdate.Series("4", aceReviewer));
        return new PolicyReviewSettingsUpdate(
                version,
                List.of(new PolicyReviewSettingsUpdate.Brand("2", "BY_SERIES", "7", null, series)));
    }

    @Test
    void onePlusBrandReviewerCannotBeUsedForAnUnassignedSeries() throws Exception {
        jdbc.update("INSERT INTO policy_review_settings(tenant_key,version) VALUES(?,0)", COMPANY);
        jdbc.update(
                "INSERT INTO policy_review_brand_owner(tenant_key,brand_id,purchase_user_id,product_mode,brand_product_user_id) VALUES(?,2,7,'BY_BRAND',3)",
                COMPANY);
        var brand = read(get(SETTINGS).session(admin)).at("/brands/1");
        assertThat(brand.get("productMode").asText()).isEqualTo("BY_SERIES");
        assertThat(brand.get("brandProductReviewer").isNull()).isTrue();
        assertThat(brand.at("/series/0/productReviewer").isNull()).isTrue();
        assertThat(brand.at("/purchaseReviewer/id").asText()).isEqualTo("7");
        assertThatThrownBy(() -> assign(2, 3, true)).isInstanceOf(ApiException.class);
        assertThat(assign(2, 3, false).purchaseReviewer().getId()).isEqualTo(7L);

        var brandDefault =
                new PolicyReviewSettingsUpdate.Brand("2", "BY_BRAND", "7", "3", List.of());
        mvc.perform(save(admin, new PolicyReviewSettingsUpdate(0, List.of(brandDefault))))
                .andExpect(status().isBadRequest());
        read(save(admin, update(0, "7", "3")));
        assertThat(assign(2, 3, true).productReviewer().getId()).isEqualTo(3L);
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
