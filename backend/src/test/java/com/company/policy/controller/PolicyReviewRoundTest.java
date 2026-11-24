package com.company.policy.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(
        properties = {
            "spring.config.import=",
            "feishu.tenant-key=company",
            "spring.datasource.url=jdbc:h2:mem:review_round;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.sql.init.schema-locations=classpath:user-info-schema.sql,classpath:catalog-import-schema.sql"
        })
@AutoConfigureMockMvc
class PolicyReviewRoundTest {
    @TempDir static Path storage;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("policy.storage.root", storage::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired LoginSessionService sessions;

    @BeforeEach
    void seed() {
        for (var table :
                java.util.List.of(
                        "policy_review_decision",
                        "policy_review_member",
                        "policy_review_round",
                        "policy_audit_log",
                        "policy_apply",
                        "user_info")) jdbc.update("DELETE FROM " + table);
        for (int i = 1; i <= 7; i++) {
            jdbc.update(
                    "INSERT INTO user_info(id, tenant_key, open_id, name, role_type) VALUES(?,?,?,?,?)",
                    i,
                    i == 7 ? "outside" : "company",
                    "user-" + i,
                    "成员" + i,
                    i == 4 || i == 6 ? "ADMIN" : "APPLICANT");
        }
        jdbc.update(
                """
                INSERT INTO policy_apply(id,tenant_key,policy_no,applicant_id,applicant_name,channel_type,status,current_version,record_version)
                VALUES(101,'company','first',1,'申请人','RETAIL','PENDING',1,2)
                """);
        round(1);
        assign(1, 2, "PRODUCT", "ACTIVE");
        assign(1, 3, "PRODUCT", "ACTIVE");
        assign(1, 4, "PURCHASE", "ACTIVE");
    }

    private void round(int version) {
        jdbc.update(
                "INSERT INTO policy_audit_log(tenant_key,policy_id,version,snapshot_data,workflow_version) VALUES('company',101,?,?,1)",
                version,
                "{\"id\":\"101\",\"submissionVersion\":"
                        + version
                        + ",\"form\":{\"specialRemark\":\"第"
                        + version
                        + "轮\"}}");
        jdbc.update(
                """
                INSERT INTO policy_review_round(tenant_key,policy_id,version,settings_version,stage,route_json)
                VALUES('company',101,?,1,'PRODUCT_REVIEW','{"requiresProductReview":true,"summary":"京东替换"}')
                """,
                version);
    }

    private void assign(int version, int user, String group, String state) {
        jdbc.update(
                """
                INSERT INTO policy_review_member(tenant_key,policy_id,version,group_code,user_id,display_name,member_status)
                VALUES('company',101,?,?,?,'指派时姓名',?)
                """,
                version,
                group,
                user,
                state);
    }

    private MockHttpSession session(int user) {
        var request = new MockHttpServletRequest();
        var account =
                new CurrentUserResponse(
                        Integer.toString(user),
                        "user-" + user,
                        user == 7 ? "outside" : "company",
                        "成员" + user,
                        user == 4 || user == 6 ? "ADMIN" : "APPLICANT",
                        null);
        sessions.establish(
                request, new AuthSessionResponse(account, Instant.now().plusSeconds(600)));
        return (MockHttpSession) request.getSession();
    }

    private ResultActions detail(int user, int version) throws Exception {
        return mvc.perform(
                get("/api/policy-reviews/101")
                        .param("submissionVersion", "" + version)
                        .session(session(user)));
    }

    private ResultActions file(int user, int version, String id) throws Exception {
        return mvc.perform(
                get("/api/policy-reviews/101/attachments/{file}/content", id)
                        .param("submissionVersion", "" + version)
                        .session(session(user)));
    }

    @Test
    void aDirectSubmissionShowsItsRecordedPurchaseStage() throws Exception {
        jdbc.update(
                """
                UPDATE policy_review_round SET stage='PURCHASE_REVIEW', route_json=? WHERE policy_id=101 AND version=1
                """,
                """
                {"requiresProductReview":false,"summary":"本单由采购直审。","changes":[{
                  "perspective":"RETAIL","kind":"UNCHANGED","scope":{"platformCode":"JD","goodsId":"8001"},
                  "from":"2026-12-01T00:00:00","through":"2026-12-01T23:59:59",
                  "before":[{"policyId":"17"}],"after":[{"policyId":"17"}],"added":[],"removed":[],"retained":[{"policyId":"17"}]}]}
                """);
        detail(4, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"))
                .andExpect(jsonPath("$.changeSummary.requiresProductReview").value(false))
                .andExpect(jsonPath("$.changes[0].before[0].policyId").value("17"))
                .andExpect(jsonPath("$.changes[0].after[0].policyId").value("17"))
                .andExpect(jsonPath("$.changes[0].candidates").isArray())
                .andExpect(jsonPath("$.changes[0].candidates").isEmpty());
    }

    @Test
    void detailReflectsCurrentAuthorityAndReadOnlyRoles() throws Exception {
        detail(2, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeSummary.summary").value("京东替换"))
                .andExpect(jsonPath("$.allowedActions.length()").value(2))
                .andExpect(jsonPath("$.productReviewers.length()").value(2))
                .andExpect(jsonPath("$.purchaseReviewers[0].userId").value("4"));
        for (int reader : new int[] {1, 4, 6}) {
            detail(reader, 1)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allowedActions").isEmpty());
        }
        detail(5, 1).andExpect(status().isNotFound());
        detail(7, 1).andExpect(status().isForbidden());
        detail(2, 2).andExpect(status().isNotFound());
        mvc.perform(get("/api/policy-reviews/101").session(session(2)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/policy-reviews/101").param("submissionVersion", "1"))
                .andExpect(status().isUnauthorized());
        jdbc.update("UPDATE user_info SET is_deleted=1 WHERE id=2");
        detail(2, 1).andExpect(status().isForbidden());
    }

    @Test
    void readOnlyPeersSeeTheSignerAndOnlyEligibleBuyersCanDecide() throws Exception {
        jdbc.update("UPDATE policy_review_member SET member_status='HANDLED' WHERE user_id=2");
        jdbc.update("UPDATE policy_review_member SET member_status='PEER_HANDLED' WHERE user_id=3");
        jdbc.update(
                """
                INSERT INTO policy_review_decision(policy_id,version,tenant_key,group_code,member_id,decision)
                SELECT policy_id,version,tenant_key,group_code,id,'APPROVE' FROM policy_review_member WHERE user_id=2
                """);
        jdbc.update("UPDATE policy_review_round SET stage='PURCHASE_REVIEW'");
        for (int user : new int[] {2, 3}) {
            detail(user, 1)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allowedActions").isEmpty())
                    .andExpect(jsonPath("$.productReviewers[0].decision").value("APPROVE"))
                    .andExpect(jsonPath("$.productReviewers[1].decision").doesNotExist())
                    .andExpect(
                            jsonPath("$.productReviewers[1].memberStatus").value("PEER_HANDLED"));
        }
        detail(4, 1).andExpect(jsonPath("$.allowedActions.length()").value(2));
        jdbc.update("UPDATE user_info SET role_type='APPLICANT' WHERE id=4");
        detail(4, 1).andExpect(status().isNotFound());
    }

    @Test
    void downloadChecksTheRequestedRoundItsSnapshotAndCurrentReader() throws Exception {
        var bytes = "%PDF-1.7 review evidence".getBytes(StandardCharsets.UTF_8);
        var upload =
                mvc.perform(
                                multipart("/api/attachments")
                                        .file(
                                                new MockMultipartFile(
                                                        "file", "证明.pdf", "application/pdf", bytes))
                                        .header("Origin", "http://localhost:5173")
                                        .header("X-Policy-Request", "1")
                                        .session(session(1)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        String id = json.readTree(upload.getContentAsString()).path("id").asText();
        jdbc.update(
                """
                UPDATE policy_audit_log SET snapshot_data=?, audit_status='REJECTED', auditor_id=2, auditor_name='初审',
                  audit_time=CURRENT_TIMESTAMP WHERE version=1
                """,
                "{\"id\":\"101\",\"submissionVersion\":1,\"consent\":{\"endAdjustments\":[{\"attachmentInfo\":[{\"type\":\"FILE\",\"id\":\""
                        + id
                        + "\"}]}]}}");
        jdbc.update("UPDATE policy_review_round SET stage='FINISHED' WHERE version=1");
        jdbc.update("UPDATE policy_review_member SET member_status='HANDLED' WHERE user_id=2");
        jdbc.update("UPDATE policy_apply SET current_version=2,record_version=5 WHERE id=101");
        round(2);
        assign(2, 3, "PRODUCT", "ACTIVE");
        for (int user : new int[] {1, 2, 4, 6})
            file(user, 1, id).andExpect(status().isOk()).andExpect(content().bytes(bytes));
        file(2, 2, id).andExpect(status().isNotFound());
        file(3, 2, id).andExpect(status().isNotFound());
        file(5, 1, id).andExpect(status().isNotFound());
        file(7, 1, id).andExpect(status().isForbidden());
        jdbc.update(
                "UPDATE policy_review_member SET member_status='REMOVED' WHERE user_id=3 AND version=1");
        detail(3, 1).andExpect(status().isNotFound());
        file(3, 1, id).andExpect(status().isNotFound());
        jdbc.update("UPDATE policy_apply SET is_deleted=1 WHERE id=101");
        file(2, 1, id).andExpect(status().isNotFound());
        detail(6, 1).andExpect(status().isNotFound());
    }
}
