package com.company.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.common.exception.JackyunRateLimitException;
import com.company.policy.dto.GoodsSyncRequest;
import com.company.policy.dto.JackyunGoodsQuery;
import com.company.policy.dto.JackyunGoodsQuery.Phase;
import com.company.policy.dto.PolicyReviewRequest;
import com.company.policy.mapper.PolicyApplyMapper;
import com.company.policy.mapper.PolicyReviewMapper;
import com.company.policy.service.CatalogCleaningWorker;
import com.company.policy.service.GoodsSyncService;
import com.company.policy.service.JackyunGoodsService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.PolicyCoverageService;
import com.company.policy.service.PolicyReviewService;
import com.company.policy.service.UserInfoService;
import com.company.policy.support.policy.PolicyExecutionChange.Kind;
import com.company.policy.support.policy.PolicyRouteSummary;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.JackyunGoodsPage;
import com.company.policy.vo.JackyunGoodsPage.Goods;
import com.company.policy.vo.PolicyApplicability.Policy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        properties = {
            "spring.config.import=",
            "spring.datasource.url=jdbc:h2:mem:catalog_import;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.sql.init.schema-locations=classpath:user-info-schema.sql,classpath:catalog-import-schema.sql",
            "jackyun.app-key=test-app-key",
            "jackyun.app-secret=test-secret",
            "feishu.tenant-key=test-tenant",
            "jackyun.import-scheduling-enabled=false",
            "jackyun.clean-scheduling-enabled=false"
        })
@AutoConfigureMockMvc
class GoodsSyncIntegrationTest {
    @TempDir static Path attachmentDirectory;
    private static final String JOBS = "/api/admin/goods-sync/jobs";
    private static final String CLEANING = "/api/admin/catalog/cleaning";
    private static final String GOODS = "/api/admin/catalog/goods";
    private static final String SOURCES = "/api/admin/source-goods";
    private static final String OPPO = "OPPO手机";
    private static final String ONEPLUS = "一加手机";
    private static final Goods ORIGINAL = goods(" 商品 ", "SAMPLE", "8+128", "分类", "", null, "标签");
    private static final String CURSOR = "2216051345374512129";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserInfoService users;
    @Autowired LoginSessionService sessions;
    @Autowired GoodsSyncService sync;
    @Autowired CatalogCleaningWorker cleaning;
    @Autowired PolicyCoverageService coverage;
    @Autowired PolicyReviewService reviews;
    @Autowired PolicyApplyMapper policyMapper;
    @Autowired PolicyReviewMapper reviewMapper;
    @Autowired PolicySnapshotCodec snapshotCodec;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean JackyunGoodsService upstream;
    private MockHttpSession admin;
    private CurrentUserResponse productReviewer;

    @Test
    void rejectsCleaningPreviewWithoutBrand() throws Exception {
        mvc.perform(get(CLEANING + "/preview").session(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATALOG_CLEAN_INVALID_REQUEST"));
    }

    @DynamicPropertySource
    static void optionalDisposableMySql(DynamicPropertyRegistry registry) {
        registry.add("policy.storage.root", () -> attachmentDirectory.toString());
        // 只接受显式开启的独立本机 QA 库；默认始终使用内存 H2，完全忽略应用的 DB_URL。
        String port = System.getProperty("catalog.qa.mysql.port");
        if (port != null) {
            int qaPort = Integer.parseInt(port);
            if (qaPort < 1024 || qaPort > 65535 || qaPort == 3306) {
                throw new IllegalArgumentException("验收必须使用独立 MySQL 端口");
            }
            registry.add(
                    "spring.datasource.url",
                    () ->
                            "jdbc:mysql://127.0.0.1:"
                                    + qaPort
                                    + "/catalog_import_qa?connectionTimeZone=Asia/Shanghai&characterEncoding=UTF-8");
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
            registry.add("spring.datasource.username", () -> "root");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.sql.init.mode", () -> "never");
        }
    }

    @BeforeEach
    void prepareDatabase() {
        if (System.getProperty("catalog.qa.mysql.port") != null) {
            assertThat(jdbc.queryForObject("SELECT @@datadir", String.class))
                    .matches(
                            "(?i).*[\\\\/]\\.local-cache[\\\\/]catalog-import-mysql-[0-9]+[\\\\/]data[\\\\/]");
        }
        jdbc.update("DELETE FROM user_platform_assignment");
        for (String table :
                List.of(
                        "policy_review_delivery",
                        "policy_review_reassignment",
                        "policy_review_decision",
                        "policy_review_member",
                        "policy_review_round",
                        "policy_review_setting_member",
                        "policy_review_settings")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM policy_goods_detail");
        jdbc.update("DELETE FROM policy_time_change");
        jdbc.update("DELETE FROM policy_audit_log");
        jdbc.update("DELETE FROM policy_apply");
        jdbc.update("DELETE FROM catalog_operation_log");
        jdbc.update("DELETE FROM catalog_clean_job_item");
        jdbc.update("DELETE FROM source_goods_item");
        jdbc.update("DELETE FROM catalog_clean_job");
        jdbc.update("DELETE FROM goods_sync_job");
        jdbc.update("DELETE FROM goods");
        jdbc.update("DELETE FROM color_dict");
        jdbc.update("DELETE FROM spu");
        jdbc.update("DELETE FROM series");
        jdbc.update("DELETE FROM brand");
        admin = session("test-tenant", "admin", "ADMIN");
        productReviewer =
                users.saveVerifiedIdentity("test-tenant", "product-reviewer", null, "产品运营", null);
        jdbc.update(
                "INSERT INTO policy_review_settings(tenant_key,version) VALUES('test-tenant',1)");
        jdbc.update(
                """
                INSERT INTO policy_review_setting_member(tenant_key,group_code,user_id)
                SELECT tenant_key, CASE open_id WHEN 'admin' THEN 'PURCHASE' ELSE 'PRODUCT' END, id
                FROM user_info WHERE tenant_key='test-tenant' AND open_id IN ('admin','product-reviewer')
                """);
    }

    @Test
    void importsOneBrandAtATimeAndUpdatesSkuInPlaceAcrossReplays() throws Exception {
        var key = UUID.randomUUID().toString();
        String oppo = start(OPPO, key);
        assertThat(start(OPPO, key)).isEqualTo(oppo);
        mvc.perform(startRequest(ONEPLUS, UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_JOB_ACTIVE"));
        mvc.perform(startRequest(ONEPLUS, key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_IDEMPOTENCY_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_operation_log", Integer.class))
                .isEqualTo(1);

        var changed = goods(" 商品 ", "SAMPLE", "8+128", "分类", null, null, "标签");
        when(upstream.queryPage(new JackyunGoodsQuery(OPPO, 50, "0", true)))
                .thenReturn(
                        new JackyunGoodsPage(OPPO, List.of(ORIGINAL, ORIGINAL, changed), CURSOR));
        when(upstream.queryPage(new JackyunGoodsQuery(OPPO, 50, CURSOR, true)))
                .thenReturn(new JackyunGoodsPage(OPPO, List.of(), CURSOR));
        step(oppo);
        assertThat(cursor(oppo)).isEqualTo(CURSOR);
        assertThat(state(oppo)).isEqualTo("RUNNING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT content_count FROM goods_sync_job WHERE id=?",
                                Long.class,
                                oppo))
                .isEqualTo(1);
        step(oppo);
        assertThat(state(oppo)).isEqualTo("SUCCEEDED");

        String oneplus = start(ONEPLUS, UUID.randomUUID().toString());
        when(upstream.queryPage(new JackyunGoodsQuery(ONEPLUS, 50, "0", true)))
                .thenReturn(
                        new JackyunGoodsPage(
                                ONEPLUS,
                                List.of(
                                        goods(
                                                " 商品 ",
                                                "SAMPLE-ONEPLUS",
                                                "8+128",
                                                "分类",
                                                "",
                                                null,
                                                "标签")),
                                CURSOR));
        when(upstream.queryPage(new JackyunGoodsQuery(ONEPLUS, 50, CURSOR, true)))
                .thenReturn(new JackyunGoodsPage(ONEPLUS, List.of(), CURSOR));
        step(oneplus);
        step(oneplus);
        mvc.perform(
                        get(SOURCES)
                                .session(admin)
                                .param("brandName", ONEPLUS)
                                .param("keyword", "SAMP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.items[0].goodsName").value(" 商品 "))
                .andExpect(jsonPath("$.items[0].goodsField4").value(""))
                .andExpect(jsonPath("$.items[0].goodsField5").value("标签"))
                .andExpect(jsonPath("$.items[0].skuId").value(testSku("SAMPLE-ONEPLUS")));

        String replay = start(OPPO, UUID.randomUUID().toString());
        when(upstream.queryPage(new JackyunGoodsQuery(OPPO, 50, "0", true)))
                .thenReturn(new JackyunGoodsPage(OPPO, List.of(changed), CURSOR));
        step(replay);
        step(replay);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_goods_item", Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM catalog_operation_log WHERE action_type='UPDATE'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM source_goods_item WHERE first_seen_job_id=? AND last_seen_job_id=?",
                                Integer.class,
                                oppo,
                                replay))
                .isEqualTo(1);
    }

    @Test
    void modifiedDayResumesWithinItsPhaseAndKeepsTheExistingMappingForReview() throws Exception {
        long previousTime = 1748428974000L;
        long modifiedTime = 1748428975000L;
        String skuId = "2216051345374512129";
        String path = "OPPO手机/Reno系列/Reno17";
        var previous =
                new Goods(
                        "Reno17 冰月白 12+512",
                        "RENO-W",
                        "12+512",
                        "Reno17",
                        "Reno系列",
                        "Reon17 12+512",
                        null,
                        skuId,
                        path,
                        previousTime,
                        previousTime,
                        false,
                        false);
        importForCleaning(List.of(previous));
        startClean(OPPO, null, false, 1);
        cleaning.processNextBatch();
        long source = sourceId("RENO-W");
        Long mappedColor =
                jdbc.queryForObject(
                        "SELECT goods_id FROM source_goods_item WHERE id=?", Long.class, source);
        assertThat(mappedColor).isNotNull();

        var date = LocalDate.of(2025, 5, 28);
        var scope =
                new GoodsSyncRequest(
                        OPPO, UUID.randomUUID().toString(), GoodsSyncRequest.Mode.MODIFIED, date);
        var body =
                mvc.perform(mutation(JOBS).content(json.writeValueAsBytes(scope)))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.syncMode").value("MODIFIED"))
                        .andExpect(jsonPath("$.syncDate").value("2025-05-28"))
                        .andExpect(jsonPath("$.syncPhase").value("GOODS_MODIFIED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        String batch = json.readTree(body).path("id").asText();
        var changed =
                new Goods(
                        "Reno17 月夜黑 12+512",
                        "RENO-B",
                        "12+512",
                        "Reno17",
                        "Reno系列",
                        "Reon17 12+512",
                        null,
                        skuId,
                        path,
                        modifiedTime,
                        modifiedTime,
                        false,
                        false);
        var goodsPhase = JackyunGoodsQuery.Phase.GOODS_MODIFIED;
        var skuPhase = JackyunGoodsQuery.Phase.SKU_MODIFIED;
        when(upstream.queryPage(new JackyunGoodsQuery(OPPO, 50, "0", true, goodsPhase, date)))
                .thenReturn(new JackyunGoodsPage(OPPO, List.of(changed), CURSOR));
        when(upstream.queryPage(new JackyunGoodsQuery(OPPO, 50, CURSOR, true, goodsPhase, date)))
                .thenReturn(new JackyunGoodsPage(OPPO, List.of(), CURSOR));
        when(upstream.queryPage(new JackyunGoodsQuery(OPPO, 50, "0", true, skuPhase, date)))
                .thenThrow(new JackyunRateLimitException(60))
                .thenReturn(new JackyunGoodsPage(OPPO, List.of(changed), CURSOR));
        when(upstream.queryPage(new JackyunGoodsQuery(OPPO, 50, CURSOR, true, skuPhase, date)))
                .thenReturn(new JackyunGoodsPage(OPPO, List.of(), CURSOR));
        step(batch);
        step(batch);
        assertThat(cursor(batch)).isEqualTo("0");
        assertThat(state(batch)).isEqualTo("RUNNING");
        step(batch);
        assertThat(state(batch)).isEqualTo("PAUSED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_review_delivery WHERE notice_key LIKE ?",
                                Integer.class,
                                "IMPORT/" + batch + "/%"))
                .isEqualTo(1);
        jdbc.update(
                "UPDATE goods_sync_job SET next_request_at=CURRENT_TIMESTAMP WHERE id=?", batch);
        mvc.perform(control(batch, "resume", version(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncPhase").value("SKU_MODIFIED"));
        step(batch);
        step(batch);
        assertThat(state(batch)).isEqualTo("SUCCEEDED");
        mvc.perform(get(JOBS).session(admin))
                .andExpect(jsonPath("$.jobs[0].readCount").value(2))
                .andExpect(jsonPath("$.jobs[0].contentCount").value(1));
        mvc.perform(get(SOURCES).session(admin).param("keyword", skuId))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(source)))
                .andExpect(jsonPath("$.items[0].cateFullName").value(path))
                .andExpect(jsonPath("$.items[0].gmtModified").value(modifiedTime))
                .andExpect(jsonPath("$.items[0].isBlockup").value(false));
        startClean(OPPO, null, false, 1);
        cleaning.processNextBatch();
        mvc.perform(get(SOURCES + "/" + source).session(admin))
                .andExpect(jsonPath("$.result.issueCode").value("CATALOG_MAPPING_CHANGED"))
                .andExpect(jsonPath("$.result.colorName").value("月夜黑"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT goods_id FROM source_goods_item WHERE id=?",
                                Long.class,
                                source))
                .isEqualTo(mappedColor);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM catalog_operation_log WHERE action_type='UPDATE' AND entity_id=?",
                                Integer.class,
                                source))
                .isOne();

        importForCleaning(List.of(previous));
        mvc.perform(get(SOURCES).session(admin).param("keyword", skuId))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(source)))
                .andExpect(jsonPath("$.items[0].goodsNo").value("RENO-B"))
                .andExpect(jsonPath("$.items[0].gmtModified").value(modifiedTime));
    }

    @Test
    void showsAndFiltersCreatedDateAfterLaterImports() throws Exception {
        var date = LocalDate.of(2026, 9, 16);
        var scope =
                new GoodsSyncRequest(
                        OPPO, UUID.randomUUID().toString(), GoodsSyncRequest.Mode.CREATED, date);
        var fullOnly = goods("其他商品", "FULL-ONLY", "8+128", "分类", null, null, null);
        importForCleaning(List.of(ORIGINAL, fullOnly));

        var response =
                mvc.perform(mutation(JOBS).content(json.writeValueAsBytes(scope)))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse();
        String dated =
                json.readTree(response.getContentAsString(StandardCharsets.UTF_8))
                        .path("id")
                        .asText();
        var first =
                new JackyunGoodsQuery(OPPO, 50, "0", true, JackyunGoodsQuery.Phase.CREATED, date);
        var next =
                new JackyunGoodsQuery(
                        OPPO, 50, CURSOR, true, JackyunGoodsQuery.Phase.CREATED, date);
        when(upstream.queryPage(first))
                .thenReturn(new JackyunGoodsPage(OPPO, List.of(ORIGINAL), CURSOR));
        when(upstream.queryPage(next)).thenReturn(new JackyunGoodsPage(OPPO, List.of(), CURSOR));
        step(dated);
        step(dated);
        mvc.perform(get(SOURCES).session(admin).param("keyword", ORIGINAL.skuId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].createdDate").value("2026-09-16"));

        importForCleaning(List.of(ORIGINAL));
        mvc.perform(
                        get(SOURCES)
                                .session(admin)
                                .param("createdDate", "2026-09-16")
                                .param("brandName", OPPO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].skuId").value(ORIGINAL.skuId()))
                .andExpect(jsonPath("$.items[0].createdDate").value("2026-09-16"));
        mvc.perform(get(SOURCES).session(admin).param("createdDate", "2026-09-17"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(
                        get(SOURCES)
                                .session(admin)
                                .param("createdDate", "2026-09-16")
                                .param("keyword", "FULL-ONLY"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(SOURCES).session(admin).param("createdDate", "2026-09-99"))
                .andExpect(status().isBadRequest());

        var dateOnly = goods("按日补导", "DATE-ONLY", "8+128", "分类", null, null, null);
        String secondDate =
                json.readTree(
                                mvc.perform(
                                                mutation(JOBS)
                                                        .content(
                                                                json.writeValueAsBytes(
                                                                        new GoodsSyncRequest(
                                                                                OPPO,
                                                                                UUID.randomUUID()
                                                                                        .toString(),
                                                                                GoodsSyncRequest
                                                                                        .Mode
                                                                                        .CREATED,
                                                                                date))))
                                        .andExpect(status().isAccepted())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("id")
                        .asText();
        when(upstream.queryPage(first))
                .thenReturn(new JackyunGoodsPage(OPPO, List.of(dateOnly), CURSOR));
        step(secondDate);
        step(secondDate);

        String oneplus = start(ONEPLUS, UUID.randomUUID().toString());
        when(upstream.queryPage(any()))
                .thenReturn(
                        new JackyunGoodsPage(
                                ONEPLUS,
                                List.of(
                                        goods(
                                                "一加样本",
                                                "ONEPLUS-ONLY",
                                                "8+128",
                                                "分类",
                                                null,
                                                null,
                                                null)),
                                CURSOR))
                .thenReturn(new JackyunGoodsPage(ONEPLUS, List.of(), CURSOR));
        step(oneplus);
        step(oneplus);
        mvc.perform(get(CLEANING + "/results").session(admin))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.counts.PENDING").value(4));
        mvc.perform(get(CLEANING + "/results").session(admin).param("unknownDate", "true"))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(
                        get(CLEANING + "/preview")
                                .session(admin)
                                .param("brandName", OPPO)
                                .param("createdDate", date.toString())
                                .param("keyword", "DATE-ONLY"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.eligible").value(2));
        String task = startClean(OPPO, date, false, 2);
        // 清洗确认后变更原文，已选成员必须跳过，其他成员照常处理。
        jdbc.update(
                "UPDATE source_goods_item SET record_version=record_version+1 WHERE goods_no='DATE-ONLY'");
        cleaning.processNextBatch();
        mvc.perform(get(CLEANING + "/jobs/" + task).session(admin))
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.resultCounts.SKIPPED").value(1))
                .andExpect(jsonPath("$.processed").value(2));
        mvc.perform(get(CLEANING + "/results").session(admin).param("status", "PENDING"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.counts.ALL").value(4));
        mvc.perform(get(CLEANING + "/preview").session(admin).param("brandName", OPPO))
                .andExpect(jsonPath("$.eligible").value(2));
    }

    @Test
    void directlyExcludesMatchingSourceTextBeforeOtherCleaningRules() throws Exception {
        var mixed =
                new Goods(
                        "工厂代发（简易演示样机）",
                        "多字段-全套演示样机",
                        "简易演示样机",
                        "工厂代发",
                        "全套演示样机",
                        "工厂代发",
                        "简易演示样机",
                        testSku("MIXED"),
                        "OPPO手机/全套演示样机",
                        null,
                        null,
                        true,
                        true);
        var sku =
                goods(
                        "Find X8 星耀黑 16+512",
                        "SKU-DEMO",
                        "16+512 简易演示样机",
                        "Find X8",
                        "Find系列",
                        null,
                        null);
        var policy =
                goods(
                        "Find X8 星耀黑 16+512",
                        "POLICY-DEMO",
                        "16+512",
                        "Find X8",
                        "Find系列",
                        "全套演示样机",
                        null);
        var separate =
                goods(
                        "Find X8 星耀黑 16+512",
                        "NORMAL",
                        "16+512",
                        "Find X8",
                        "Find系列",
                        "Find X8 16+512",
                        "工厂");
        var generic =
                goods(
                        "Find X8 星耀黑 16+512 演示样机",
                        "GENERIC",
                        "16+512",
                        "Find X8",
                        "Find系列",
                        null,
                        null);
        importForCleaning(List.of(mixed, sku, policy, separate, generic));
        String job = startClean(OPPO, null, false, 5);
        cleaning.processNextBatch();
        mvc.perform(get(CLEANING + "/jobs/" + job).session(admin))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.processed").value(5))
                .andExpect(jsonPath("$.resultCounts.EXCLUDED").value(3))
                .andExpect(jsonPath("$.resultCounts.REVIEW_REQUIRED").value(1))
                .andExpect(jsonPath("$.resultCounts.AUTO_PASSED").value(1));
        mvc.perform(get(CLEANING + "/results").session(admin).param("status", "EXCLUDED"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.counts.EXCLUDED").value(3));
        var path = SOURCES + "/" + sourceId(mixed.goodsNo());
        var detail =
                mvc.perform(get(path).session(admin))
                        .andExpect(jsonPath("$.result.issueCode").value("DIRECT_EXCLUSION"))
                        .andExpect(jsonPath("$.mappingStatus").value("EXCLUDED"))
                        .andExpect(jsonPath("$.goodsId").doesNotExist())
                        .andExpect(jsonPath("$.reviewedBy").doesNotExist())
                        .andExpect(jsonPath("$.reviewedAt").doesNotExist())
                        .andExpect(jsonPath("$.reviewReason").doesNotExist())
                        .andReturn();
        var evidence =
                json.readTree(detail.getResponse().getContentAsByteArray())
                        .path("candidate")
                        .path("evidence");
        var fields = new java.util.HashMap<String, String>();
        evidence.forEach(hit -> fields.put(hit.path("field").asText(), hit.path("value").asText()));
        assertThat(fields)
                .containsExactlyInAnyOrderEntriesOf(
                        java.util.Map.of(
                                "goodsName",
                                "工厂代发、简易演示样机",
                                "skuName",
                                "简易演示样机",
                                "goodsNo",
                                "全套演示样机",
                                "cateName",
                                "工厂代发",
                                "cateFullName",
                                "全套演示样机",
                                "goodsField1",
                                "工厂代发",
                                "goodsField4",
                                "全套演示样机",
                                "goodsField5",
                                "简易演示样机"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods", Integer.class)).isOne();
    }

    @Test
    void automaticallyExcludesChangedMappedSkuAndRechecksCorrectedSource() throws Exception {
        var good = importedGoodForEditing();
        String id = good.path("id").asText();
        referenceGoods("AUTO-EXCLUDED-SKU", id);
        String number = good.path("productNo").asText();
        var changed =
                goods("Find X8 星耀黑 16+512", number, "16+512", "Find X8", "Find系列", null, "工厂代发");
        importForCleaning(List.of(changed));
        startClean(OPPO, null, false, 1);
        cleaning.processNextBatch();
        mvc.perform(get(SOURCES + "/" + sourceId(number)).session(admin))
                .andExpect(jsonPath("$.result.status").value("EXCLUDED"))
                .andExpect(jsonPath("$.goodsId").doesNotExist());
        mvc.perform(get(GOODS + "/" + id).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skuId").doesNotExist());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_goods_detail WHERE goods_id=?",
                                Integer.class,
                                id))
                .isOne();
        importForCleaning(List.of(changed));
        mvc.perform(get(CLEANING + "/preview").param("brandName", OPPO).session(admin))
                .andExpect(jsonPath("$.eligible").value(0));
        importForCleaning(
                List.of(
                        goods(
                                "Find X8 星耀黑 16+512",
                                number,
                                "16+512",
                                "Find X8",
                                "Find系列",
                                null,
                                null)));
        startClean(OPPO, null, false, 1);
        cleaning.processNextBatch();
        mvc.perform(get(SOURCES + "/" + sourceId(number)).session(admin))
                .andExpect(jsonPath("$.result.status").value("AUTO_PASSED"))
                .andExpect(jsonPath("$.goodsId").isString());
    }

    @Test
    void reimportedSourceMustRunThroughCurrentExclusionsBeforeReview() throws Exception {
        String number = "CHANGED-DEMO";
        var sample =
                goods("Find X8 星耀黑 16+512 演示样机", number, "16+512", "Find X8", "Find系列", null, null);
        importForCleaning(List.of(sample));
        startClean(OPPO, null, false, 1);
        cleaning.processNextBatch();
        var path = SOURCES + "/" + sourceId(number);
        mvc.perform(get(path).session(admin))
                .andExpect(jsonPath("$.result.status").value("REVIEW_REQUIRED"));

        var changed =
                goods(
                        "Find X8 星耀黑 16+512 简易演示样机",
                        number,
                        "16+512",
                        "Find X8",
                        "Find系列",
                        null,
                        null);
        importForCleaning(List.of(changed));
        mvc.perform(
                        mutation(path + "/review")
                                .content(
                                        """
                {"recordVersion":1,"decision":"APPROVE",
                 "target":{"seriesName":"Find系列","modelName":"Find X8","spec":"16+512","colorName":"星耀黑"}}
                """))
                .andExpect(status().isConflict());
        startClean(OPPO, null, false, 1);
        cleaning.processNextBatch();
        mvc.perform(get(path).session(admin))
                .andExpect(jsonPath("$.result.status").value("EXCLUDED"));
    }

    @Test
    void usesCategoryPathAsEvidenceAndKeepsColorForIneligibleSku() throws Exception {
        importForCleaning(
                List.of(
                        withPath(
                                "PATH-CONFLICT",
                                "Reno系列",
                                "OPPO手机/Reno系列/Reno17 Pro",
                                false,
                                false),
                        withPath("PATH-SERIES", null, "OPPO手机/Reno系列/Reno17", false, false),
                        withPath("BLOCKED", "Reno系列", "OPPO手机/Reno系列/Reno17", true, false),
                        withPath("PACKAGE", "Reno系列", "组合装", false, true)));
        startClean(OPPO, null, false, 4);
        cleaning.processNextBatch();
        mvc.perform(get(SOURCES + "/" + sourceId("PATH-CONFLICT")).session(admin))
                .andExpect(jsonPath("$.result.issueCode").value("CATEGORY_PATH_CONFLICT"))
                .andExpect(jsonPath("$.result.colorName").value("冰月白"));
        mvc.perform(get(SOURCES + "/" + sourceId("PATH-SERIES")).session(admin))
                .andExpect(jsonPath("$.result.issueCode").value("SERIES_FROM_PATH"))
                .andExpect(jsonPath("$.result.seriesName").value("Reno系列"));
        for (String excluded : List.of("BLOCKED", "PACKAGE")) {
            mvc.perform(get(SOURCES + "/" + sourceId(excluded)).session(admin))
                    .andExpect(jsonPath("$.result.suggestedAction").value("EXCLUDE"))
                    .andExpect(jsonPath("$.result.colorName").value("冰月白"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods", Integer.class)).isZero();
    }

    private static Goods withPath(
            String goodsNo, String series, String path, boolean blocked, boolean packageGood) {
        return new Goods(
                "Reno17 冰月白 12+512",
                goodsNo,
                "12+512",
                "Reno17",
                series,
                null,
                null,
                testSku(goodsNo),
                path,
                null,
                null,
                blocked,
                packageGood);
    }

    @Test
    void rateLimitPausesWithoutAutomaticRetryAndResumesFromCommittedCursor() throws Exception {
        String id = start(OPPO, UUID.randomUUID().toString());
        when(upstream.queryPage(new JackyunGoodsQuery(OPPO, 50, "0", true)))
                .thenReturn(new JackyunGoodsPage(OPPO, List.of(ORIGINAL), CURSOR));
        var continuation = new JackyunGoodsQuery(OPPO, 50, CURSOR, true);
        when(upstream.queryPage(continuation))
                .thenThrow(new JackyunRateLimitException(600))
                .thenReturn(new JackyunGoodsPage(OPPO, List.of(), CURSOR));
        step(id);
        step(id);
        assertThat(state(id)).isEqualTo("PAUSED");
        assertThat(cursor(id)).isEqualTo(CURSOR);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT next_request_at >= TIMESTAMPADD(SECOND, 590, CURRENT_TIMESTAMP) FROM goods_sync_job WHERE id=?",
                                Boolean.class,
                                id))
                .isTrue();
        int version = version(id);
        mvc.perform(control(id, "resume", version))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_COOLDOWN"));
        step(id);
        verify(upstream, times(1)).queryPage(continuation);
        mvc.perform(control(id, "resume", version - 1)).andExpect(status().isConflict());
        mvc.perform(control(id, "resume", version)).andExpect(status().isOk());
        step(id);
        verify(upstream, times(2)).queryPage(continuation);
        assertThat(state(id)).isEqualTo("SUCCEEDED");
    }

    @Test
    void failedInsertRollsBackTheEntirePageAndDoesNotAdvanceTheCursor() throws Exception {
        jdbc.execute(
                "ALTER TABLE source_goods_item ADD CONSTRAINT ck_test_reject CHECK (goods_no <> 'BREAK_WRITE')");
        try {
            String id = start(OPPO, UUID.randomUUID().toString());
            var rejected = goods("拒绝写入", "BREAK_WRITE", null, null, null, null, null);
            when(upstream.queryPage(any()))
                    .thenReturn(new JackyunGoodsPage(OPPO, List.of(ORIGINAL, rejected), CURSOR));
            step(id);
            assertThat(state(id)).isEqualTo("PAUSED");
            assertThat(cursor(id)).isEqualTo("0");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_goods_item", Integer.class))
                    .isZero();
            mvc.perform(control(id, "cancel", version(id))).andExpect(status().isOk());
            mvc.perform(startRequest(ONEPLUS, UUID.randomUUID().toString()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CATALOG_COOLDOWN"));
        } finally {
            jdbc.execute("ALTER TABLE source_goods_item DROP CONSTRAINT ck_test_reject");
        }
    }

    @Test
    void staleWorkerCannotCommitAndItsReplacementStartsAtTheLastCommittedCursor() throws Exception {
        String id = start(OPPO, UUID.randomUUID().toString());
        when(upstream.queryPage(any()))
                .thenAnswer(
                        invocation -> {
                            jdbc.update(
                                    "UPDATE goods_sync_job SET lease_until=CURRENT_TIMESTAMP, record_version=record_version+1 WHERE id=?",
                                    id);
                            return new JackyunGoodsPage(OPPO, List.of(ORIGINAL), CURSOR);
                        })
                .thenReturn(new JackyunGoodsPage(OPPO, List.of(ORIGINAL), CURSOR));
        step(id);
        assertThat(cursor(id)).isEqualTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_goods_item", Integer.class))
                .isZero();
        step(id);
        verify(upstream, times(2)).queryPage(new JackyunGoodsQuery(OPPO, 50, "0", true));
        assertThat(cursor(id)).isEqualTo(CURSOR);
    }

    @Test
    void rejectsUnauthenticatedApplicantOtherTenantAndRevokedAdmin() throws Exception {
        mvc.perform(get(SOURCES)).andExpect(status().isUnauthorized());
        mvc.perform(get(SOURCES).session(session("test-tenant", "applicant", "APPLICANT")))
                .andExpect(status().isForbidden());
        mvc.perform(get(JOBS).session(session("other-tenant", "other", "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CATALOG_SCOPE_DENIED"));
        mvc.perform(post(JOBS).session(admin).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(startRequest("全部", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
        jdbc.update(
                "UPDATE user_info SET is_deleted=1 WHERE tenant_key='test-tenant' AND open_id='admin'");
        mvc.perform(startRequest(OPPO, UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        jdbc.update(
                "UPDATE user_info SET is_deleted=0 WHERE tenant_key='test-tenant' AND open_id='admin'");
        verifyNoInteractions(upstream);
    }

    @Test
    void keepsEachSourceSkuAsItsOwnGoodsAcrossAdminsAndIdenticalDatedReplay() throws Exception {
        var original =
                goods(
                        "  Ｆｉｎｄ X8　星耀黑 16+1TB  ",
                        "X8-ONE",
                        "16+1024",
                        "Find X8",
                        "Find系列",
                        " Ｆｉｎｄ X8　16+1TB ",
                        null);
        var alias =
                goods(
                        "Find X8 星耀黑 16+1024",
                        "X8-TWO",
                        "16+1TB",
                        "Find X8",
                        "Find系列",
                        "Find X8 16+1024",
                        null);
        importForCleaning(List.of(original, alias));
        String job = startClean(OPPO, null, false, 2);
        cleaning.processNextBatch();
        mvc.perform(get(CLEANING + "/jobs/" + job).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCounts.AUTO_PASSED").value(2));
        long source = sourceId("X8-ONE");
        long goodsId =
                jdbc.queryForObject(
                        "SELECT goods_id FROM source_goods_item WHERE id=?", Long.class, source);
        String spuId =
                jdbc.queryForObject("SELECT spu_id FROM goods WHERE id=?", String.class, goodsId);
        var updated = java.sql.Timestamp.valueOf("2026-11-12 12:00:00");
        jdbc.update("UPDATE source_goods_item SET updated_at=? WHERE id=?", updated, source);
        var sourceVersion =
                jdbc.queryForObject(
                        "SELECT record_version FROM source_goods_item WHERE id=?",
                        Integer.class,
                        source);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(DISTINCT goods_id) FROM source_goods_item",
                                Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT sku_id FROM goods ORDER BY id", String.class))
                .containsExactly(original.skuId(), alias.skuId());
        var beforeEdit =
                mvc.perform(get(GOODS + "/" + goodsId).session(admin))
                        .andExpect(jsonPath("$.goodsName").value(original.goodsName()))
                        .andExpect(jsonPath("$.policyName").value(original.goodsField1()))
                        .andReturn();
        var unchanged = editBody(json.readTree(beforeEdit.getResponse().getContentAsByteArray()));
        mvc.perform(putGoods(GOODS + "/" + goodsId, unchanged)).andExpect(status().isOk());

        var teammate = session("test-tenant", "catalog-teammate", "ADMIN");
        mvc.perform(get(CLEANING + "/jobs/" + job).session(teammate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCounts.AUTO_PASSED").value(2));
        mvc.perform(
                        get(GOODS)
                                .session(teammate)
                                .param("brandName", OPPO)
                                .param("seriesName", "Find")
                                .param("modelName", "X8")
                                .param("status", "1")
                                .param("ascending", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(goodsId)))
                .andExpect(jsonPath("$.items[0].skuId").value(original.skuId()))
                .andExpect(jsonPath("$.items[0].sourceSkuId").value(original.skuId()))
                .andExpect(jsonPath("$.items[0].ram").value("16GB"))
                .andExpect(jsonPath("$.items[0].rom").value("1024GB"))
                .andExpect(jsonPath("$.items[0].goodsName").value(original.goodsName()))
                .andExpect(jsonPath("$.items[0].policyName").value(original.goodsField1()))
                .andExpect(jsonPath("$.items[0].status").value(1));
        mvc.perform(get(GOODS + "/" + goodsId).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandName").value(OPPO))
                .andExpect(jsonPath("$.colorName").value("星耀黑"))
                .andExpect(jsonPath("$.skuId").value(original.skuId()))
                .andExpect(jsonPath("$.sourceSkuId").value(original.skuId()))
                .andExpect(jsonPath("$.sourceName").value(original.goodsName()));
        mvc.perform(get(GOODS).session(teammate).param("keyword", alias.skuId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].skuId").value(alias.skuId()))
                .andExpect(jsonPath("$.items[0].sourceSkuId").value(alias.skuId()));
        mvc.perform(get(GOODS + "/summary").session(teammate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.active").value(2))
                .andExpect(jsonPath("$.series[0].seriesId").isString())
                .andExpect(jsonPath("$.series[0].seriesName").value("Find系列"))
                .andExpect(jsonPath("$.series[0].total").value(2))
                .andExpect(jsonPath("$.models[0].spuId").value(spuId))
                .andExpect(jsonPath("$.models[0].modelName").value("Find X8"))
                .andExpect(jsonPath("$.models[0].active").value(2))
                .andExpect(jsonPath("$.models[0].total").value(2))
                .andExpect(jsonPath("$.configurations[0].rom").value("1024GB"));
        mvc.perform(get(GOODS).session(teammate).param("spuId", spuId).param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].spuId").value(spuId));
        mvc.perform(get(GOODS).session(teammate).param("spuId", spuId).param("brandName", ONEPLUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(GOODS).session(teammate).param("spuId", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(GOODS).session(teammate).param("ram", "16GB").param("rom", "1024GB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get(GOODS).session(teammate).param("ram", "16GB").param("rom", "512GB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(GOODS).session(teammate).param("ram", "16GB"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(GOODS).session(session("other-tenant", "catalog-outsider", "ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get(GOODS + "/0").session(teammate)).andExpect(status().isNotFound());

        LocalDate date = LocalDate.of(2026, 11, 12);
        var query = new JackyunGoodsQuery(OPPO, 50, "0", true, Phase.CREATED, date);
        when(upstream.queryPage(query))
                .thenReturn(new JackyunGoodsPage(OPPO, List.of(original), original.skuId()));
        when(upstream.queryPage(
                        new JackyunGoodsQuery(
                                OPPO, 50, original.skuId(), true, Phase.CREATED, date)))
                .thenReturn(new JackyunGoodsPage(OPPO, List.of(), original.skuId()));
        var scope =
                new GoodsSyncRequest(
                        OPPO, UUID.randomUUID().toString(), GoodsSyncRequest.Mode.CREATED, date);
        var response =
                mvc.perform(mutation(JOBS).content(json.writeValueAsBytes(scope)))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse();
        String replay =
                json.readTree(response.getContentAsString(StandardCharsets.UTF_8))
                        .path("id")
                        .asText();
        step(replay);
        step(replay);
        mvc.perform(get(SOURCES).session(teammate).param("createdDate", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(source)))
                .andExpect(jsonPath("$.items[0].cleanStatus").value("AUTO_PASSED"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT record_version FROM source_goods_item WHERE id=?",
                                Integer.class,
                                source))
                .isEqualTo(sourceVersion);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT updated_at FROM source_goods_item WHERE id=?",
                                java.sql.Timestamp.class,
                                source))
                .isEqualTo(updated);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM catalog_operation_log WHERE action_type='UPDATE'",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT content_count FROM goods_sync_job WHERE id=?",
                                Long.class,
                                replay))
                .isEqualTo(1L);
    }

    @Test
    void manualReviewKeepsEachSourcesSkuAndTransfersItWithoutChangingPolicyReferences()
            throws Exception {
        var first =
                goods(
                        "Find X8 星耀黑/白色 16+512",
                        "MANUAL-SKU-A",
                        "16+512",
                        "Find X8",
                        "Find系列",
                        null,
                        null);
        var second =
                goods(
                        "Find X8 星耀黑/白色 16+512",
                        "MANUAL-SKU-B",
                        "16+512",
                        "Find X8",
                        "Find系列",
                        null,
                        null);
        importForCleaning(List.of(first, second));
        startClean(OPPO, null, false, 2);
        cleaning.processNextBatch();
        String approval =
                """
                {"recordVersion":1,"decision":"APPROVE",
                 "target":{"seriesName":"Find系列","modelName":"Find X8","spec":"16+512","colorName":"星耀黑"}}
                """;
        for (var item : List.of(first, second)) {
            long source = sourceId(item.goodsNo());
            mvc.perform(mutation(SOURCES + "/" + source + "/review").content(approval))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.status").value("MANUAL_PASSED"));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT g.sku_id FROM goods g JOIN source_goods_item s ON s.goods_id=g.id WHERE s.id=?",
                                    String.class,
                                    source))
                    .isEqualTo(item.skuId());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods", Integer.class)).isEqualTo(2);
        long firstId =
                jdbc.queryForObject(
                        "SELECT goods_id FROM source_goods_item WHERE sku_id=?",
                        Long.class,
                        first.skuId());
        referenceGoods("SKU-REFERENCE", Long.toString(firstId));
        jdbc.update(
                "UPDATE source_goods_item SET clean_status='REVIEW_REQUIRED', clean_error_code='TEST' WHERE sku_id=?",
                first.skuId());
        var changed = (ObjectNode) json.readTree(approval);
        changed.put("recordVersion", 2);
        ((ObjectNode) changed.path("target")).put("spec", "16+256");
        // 在新映射已保存、旧 SKU 已释放后注入失败，整笔审核仍须回滚。
        jdbc.execute(
                "ALTER TABLE goods ADD CONSTRAINT ck_test_sku_transfer CHECK (sku_id IS NULL OR rom <> '256GB')");
        try {
            mvc.perform(
                            mutation(SOURCES + "/" + sourceId(first.goodsNo()) + "/review")
                                    .content(changed.toString()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("CATALOG_STORAGE_UNAVAILABLE"));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT goods_id FROM source_goods_item WHERE sku_id=?",
                                    Long.class,
                                    first.skuId()))
                    .isEqualTo(firstId);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT sku_id FROM goods WHERE id=?", String.class, firstId))
                    .isEqualTo(first.skuId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods", Integer.class))
                    .isEqualTo(2);
        } finally {
            jdbc.execute("ALTER TABLE goods DROP CONSTRAINT ck_test_sku_transfer");
        }
        mvc.perform(
                        mutation(SOURCES + "/" + sourceId(first.goodsNo()) + "/review")
                                .content(changed.toString()))
                .andExpect(status().isOk());
        long newId =
                jdbc.queryForObject(
                        "SELECT goods_id FROM source_goods_item WHERE sku_id=?",
                        Long.class,
                        first.skuId());
        assertThat(newId).isNotEqualTo(firstId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sku_id FROM goods WHERE id=?", String.class, firstId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT sku_id FROM goods WHERE id=?", String.class, newId))
                .isEqualTo(first.skuId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_goods_detail WHERE goods_id=?",
                                Integer.class,
                                firstId))
                .isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM goods WHERE sku_id=?",
                                Integer.class,
                                second.skuId()))
                .isOne();

        jdbc.update(
                "UPDATE source_goods_item SET clean_status='REVIEW_REQUIRED', clean_error_code='TEST' WHERE sku_id=?",
                second.skuId());
        mvc.perform(
                        mutation(SOURCES + "/" + sourceId(second.goodsNo()) + "/review")
                                .content("{\"recordVersion\":2,\"decision\":\"EXCLUDE\"}"))
                .andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM goods WHERE sku_id=?",
                                Integer.class,
                                second.skuId()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods", Integer.class)).isEqualTo(3);
        importForCleaning(
                List.of(
                        goods(
                                "Find X8 白色 16+512",
                                second.goodsNo(),
                                "16+512",
                                "Find X8",
                                "Find系列",
                                null,
                                null)));
        mvc.perform(get(SOURCES + "/" + sourceId(second.goodsNo())).session(admin))
                .andExpect(jsonPath("$.result.status").value("EXCLUDED"))
                .andExpect(jsonPath("$.reviewedBy").isString());
    }

    @Test
    void requiresSourceNameBeforeApprovingAManuallyIdentifiedProduct() throws Exception {
        var unnamed =
                goods("　 ", "X8-NAMELESS", "16+512", "Find X8", "Find系列", "Find X8 16+512", null);
        importForCleaning(List.of(unnamed));
        startClean(OPPO, null, false, 1);
        cleaning.processNextBatch();
        String path = SOURCES + "/" + sourceId(unnamed.goodsNo());
        mvc.perform(
                        mutation(path + "/review")
                                .content(
                                        """
                {"recordVersion":1,"decision":"APPROVE",
                 "target":{"seriesName":"Find系列","modelName":"Find X8","spec":"16+512","colorName":"星耀黑"}}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_SOURCE_NAME_MISSING"));
        mvc.perform(get(path).session(admin))
                .andExpect(jsonPath("$.result.status").value("REVIEW_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods", Integer.class)).isZero();
    }

    @Test
    void refreshesSourceNamesOnReprocessingAndPreservesExplicitStatus() throws Exception {
        var original =
                goods(
                        "Find X8 星耀黑 16+512",
                        "X8-FIRST",
                        "16+512",
                        "Find X8",
                        "Find系列",
                        "Find X8 16+512",
                        null);
        importForCleaning(List.of(original));
        startClean(OPPO, null, false, 1);
        cleaning.processNextBatch();
        long id = jdbc.queryForObject("SELECT id FROM goods", Long.class);
        String newName = "  Find X8 星耀黑 16+512";
        newName += " ".repeat(512 - newName.length());
        String newPolicyName =
                " ".repeat(1024 - original.goodsField1().length()) + original.goodsField1();
        importForCleaning(
                List.of(
                        goods(
                                newName,
                                original.goodsNo(),
                                original.skuName(),
                                original.cateName(),
                                original.goodsField4(),
                                newPolicyName,
                                null)));
        startClean(OPPO, null, false, 1);
        cleaning.processNextBatch();
        String path = GOODS + "/" + id;
        var detail =
                mvc.perform(get(path).session(admin))
                        .andExpect(jsonPath("$.goodsName").value(newName))
                        .andExpect(jsonPath("$.policyName").value(newPolicyName))
                        .andReturn();
        var edit = editBody(json.readTree(detail.getResponse().getContentAsByteArray()));
        ((ObjectNode) edit.path("product")).put("status", 2);
        mvc.perform(putGoods(path, edit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goodsName").value(newName));
        for (Integer state : new Integer[] {null, 3}) {
            String field1 = state == null ? "" : null;
            jdbc.update("UPDATE goods SET status=? WHERE id=?", state, id);
            jdbc.update(
                    "UPDATE source_goods_item SET goods_field1=?, clean_status='PENDING' WHERE sku_id=?",
                    field1,
                    original.skuId());
            startClean(OPPO, null, false, 1);
            cleaning.processNextBatch();
            mvc.perform(get(GOODS + "/" + id).session(admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.skuId").value(original.skuId()))
                    .andExpect(jsonPath("$.status").value(state == null ? 1 : state));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT policy_name FROM goods WHERE id=?", String.class, id))
                    .isEqualTo(field1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods", Integer.class)).isOne();
        }
    }

    @Test
    void maintainsSharedDictionaryAndRejectsStaleAndReferencedChanges() throws Exception {
        String path = "/api/admin/catalog/dictionary";
        mvc.perform(mutation(path + "/brand").content("{\"name\":\"测试品牌\"}"))
                .andExpect(status().isNoContent());
        String brand = jdbc.queryForObject("SELECT id FROM brand WHERE name='测试品牌'", String.class);
        mvc.perform(
                        mutation(path + "/series")
                                .content(
                                        json.writeValueAsString(
                                                java.util.Map.of(
                                                        "name", "测试系列", "parentId", brand))))
                .andExpect(status().isNoContent());
        mvc.perform(mutation(path + "/color").content("{\"name\":\"星耀黑\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(mutation(path + "/color").content("{\"name\":\"星耀黑\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_DICTIONARY_DUPLICATE"));

        var teammate = session("test-tenant", "dictionary-teammate", "ADMIN");
        mvc.perform(get(path).session(teammate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("brand"))
                .andExpect(jsonPath("$[0].inUse").value(true))
                .andExpect(jsonPath("$[2].type").value("series"))
                .andExpect(jsonPath("$[2].parentId").value(brand));
        mvc.perform(get(path).session(session("test-tenant", "dictionary-applicant", "APPLICANT")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        delete(path + "/brand/" + brand)
                                .session(admin)
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1")
                                .param("expectedName", "测试品牌"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_DICTIONARY_IN_USE"));

        String color =
                jdbc.queryForObject(
                        "SELECT id FROM color_dict WHERE color_name='星耀黑'", String.class);
        String colorPath = path + "/color/" + color;
        mvc.perform(
                        put(colorPath)
                                .session(admin)
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1")
                                .contentType("application/json")
                                .content("{\"name\":\"深黑色\",\"expectedName\":\"星耀黑\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(
                        delete(colorPath)
                                .session(admin)
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1")
                                .param("expectedName", "星耀黑"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_DICTIONARY_CHANGED"));
        mvc.perform(
                        delete(colorPath)
                                .session(admin)
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1")
                                .param("expectedName", "深黑色"))
                .andExpect(status().isNoContent());
        mvc.perform(get(path).session(teammate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'color')]").isEmpty());
    }

    @Test
    void releasesEmptySeriesAndPreservesModelsReferencedByPolicies() throws Exception {
        var imported = importedGoodForEditing();
        String brand = imported.path("brandId").asText();
        String dictionary = "/api/admin/catalog/dictionary";
        var seriesRequest = json.createObjectNode().put("name", "5555").put("parentId", brand);
        mvc.perform(mutation(dictionary + "/series").content(seriesRequest.toString()))
                .andExpect(status().isNoContent());
        String seriesId =
                jdbc.queryForObject("SELECT id FROM series WHERE name='5555'", String.class);
        String seriesPath = dictionary + "/series/" + seriesId;
        var product =
                ((ObjectNode) editBody(imported).path("product"))
                        .put("seriesId", seriesId)
                        .put("modelName", "测试机型");
        var created =
                mvc.perform(mutation(GOODS).content(product.toString()))
                        .andExpect(status().isCreated())
                        .andReturn();
        var item = json.readTree(created.getResponse().getContentAsByteArray());
        long modelId = item.path("spuId").asLong();
        var rename =
                putGoods(
                        seriesPath,
                        json.createObjectNode().put("name", "空系列").put("expectedName", "5555"));
        var remove = deleteGoods(seriesPath).param("expectedName", "5555");
        String usage = "$[?(@.type == 'series' && @.id == '" + seriesId + "')].inUse";

        mvc.perform(get(dictionary).session(admin)).andExpect(jsonPath(usage, contains(true)));
        mvc.perform(rename).andExpect(status().isConflict());
        mvc.perform(remove).andExpect(status().isConflict());
        String goodsId = item.path("id").asText();
        referenceGoods("SERIES-QA", goodsId);
        mvc.perform(get(dictionary).session(admin)).andExpect(jsonPath(usage, contains(true)));
        mvc.perform(rename).andExpect(status().isConflict());
        mvc.perform(remove)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_DICTIONARY_IN_USE"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM spu WHERE id=?", Integer.class, modelId))
                .isOne();

        jdbc.update("DELETE FROM policy_goods_detail WHERE spu_id=?", modelId);
        mvc.perform(deleteGoods(GOODS + "/" + goodsId)).andExpect(status().isNoContent());
        mvc.perform(get(dictionary).session(admin)).andExpect(jsonPath(usage, contains(false)));
        mvc.perform(rename).andExpect(status().isNoContent());
        mvc.perform(deleteGoods(seriesPath).param("expectedName", "空系列"))
                .andExpect(status().isNoContent());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM spu WHERE series_id=?",
                                Integer.class,
                                seriesId))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM series WHERE id=?", Integer.class, seriesId))
                .isZero();
        mvc.perform(get(GOODS + "/" + imported.path("id").asText()).session(admin))
                .andExpect(status().isOk());
    }

    @Test
    void createsStandardGoodsWithSeparateCapacityAndRejectsEquivalentDuplicate() throws Exception {
        String dictionary = "/api/admin/catalog/dictionary";
        mvc.perform(mutation(dictionary + "/brand").content("{\"name\":\"OPPO手机\"}"))
                .andExpect(status().isNoContent());
        String brand = jdbc.queryForObject("SELECT id FROM brand", String.class);
        mvc.perform(
                        mutation(dictionary + "/series")
                                .content(
                                        json.writeValueAsString(
                                                java.util.Map.of(
                                                        "name", "Find系列", "parentId", brand))))
                .andExpect(status().isNoContent());
        mvc.perform(mutation(dictionary + "/color").content("{\"name\":\"钛色\"}"))
                .andExpect(status().isNoContent());
        String series = jdbc.queryForObject("SELECT id FROM series", String.class);
        String color = jdbc.queryForObject("SELECT id FROM color_dict", String.class);
        var body =
                json.createObjectNode()
                        .put("name", "Find X9 16GB+1T 钛色")
                        .put("brandId", brand)
                        .put("seriesId", series)
                        .put("colorId", color)
                        .put("modelName", "Find X9")
                        .put("ram", "16GB")
                        .put("rom", "1T")
                        .put("status", 2);
        var response =
                mvc.perform(mutation(GOODS).content(body.toString()))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.goodsName").value("Find X9 16GB+1T 钛色"))
                        .andExpect(jsonPath("$.ram").value("16GB"))
                        .andExpect(jsonPath("$.rom").value("1024GB"))
                        .andExpect(jsonPath("$.edition").value(""))
                        .andExpect(jsonPath("$.status").value(2))
                        .andExpect(jsonPath("$.skuId").isEmpty())
                        .andExpect(jsonPath("$.sourceSkuId").isEmpty())
                        .andExpect(jsonPath("$.productNo").isEmpty())
                        .andReturn();
        String id =
                json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .path("id")
                        .asText();
        body.put("rom", "1024GB");
        mvc.perform(mutation(GOODS).content(body.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_GOODS_DUPLICATE"));
        body.put("ram", "1T");
        mvc.perform(mutation(GOODS).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATALOG_GOODS_INVALID"));
        body.put("ram", "16").put("rom", "97657TB");
        mvc.perform(mutation(GOODS).content(body.toString())).andExpect(status().isBadRequest());
        body.put("rom", "1TB").put("seriesId", Long.parseLong(series) + 1);
        mvc.perform(mutation(GOODS).content(body.toString())).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spu", Integer.class)).isOne();
        mvc.perform(
                        get(GOODS + "/" + id)
                                .session(session("test-tenant", "goods-teammate", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.catalogStatus").doesNotHaveJsonPath());
        mvc.perform(
                        mutation(GOODS)
                                .session(session("test-tenant", "goods-applicant", "APPLICANT"))
                                .content(body.put("seriesId", series).toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void searchesStandardGoodsByModelAndCombinedKeywords() throws Exception {
        var current = importedGoodForEditing();
        String id = current.path("id").asText();
        var edit = editBody(current);
        ((ObjectNode) edit.path("product"))
                .put("name", "展示机")
                .put("modelName", "一加 Ace7 Pro")
                .put("edition", "典藏版");
        mvc.perform(putGoods(GOODS + "/" + id, edit)).andExpect(status().isOk());

        for (String keyword :
                List.of("一加ACE7", "典藏 星耀黑  Ａｃｅ７", "find系列 x8-edit", "OPPO手机 Ace7", "　")) {
            mvc.perform(get(GOODS).session(admin).param("keyword", keyword))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(id));
        }
        for (String keyword : List.of("Ace7 白色", "一加%")) {
            mvc.perform(get(GOODS).session(admin).param("keyword", keyword))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.items").isEmpty());
        }
        mvc.perform(get(GOODS).session(admin).param("keyword", "Ace7").param("brandName", ONEPLUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(
                        get(GOODS)
                                .session(admin)
                                .param("keyword", "Ace7")
                                .param("brandName", OPPO)
                                .param("page", "2")
                                .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void editsSourceGoodsInPlaceAndRejectsOldSnapshots() throws Exception {
        var current = importedGoodForEditing();
        String id = current.path("id").asText();
        String path = GOODS + "/" + id;
        var edit = editBody(current);
        var product = (ObjectNode) edit.path("product");
        product.put("name", "修正商品名")
                .put("modelName", "Find X9")
                .put("edition", "典藏版")
                .put("rom", "1T")
                .put("status", 3);
        mvc.perform(
                        put(path)
                                .session(admin)
                                .contentType("application/json")
                                .content(edit.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        putGoods(path, edit)
                                .session(session("test-tenant", "edit-applicant", "APPLICANT")))
                .andExpect(status().isForbidden());
        var saved =
                mvc.perform(putGoods(path, edit))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.id").value(id))
                        .andExpect(jsonPath("$.skuId").value(current.path("skuId").asText()))
                        .andExpect(
                                jsonPath("$.sourceSkuId")
                                        .value(current.path("sourceSkuId").asText()))
                        .andExpect(jsonPath("$.productNo").value("X8-EDIT"))
                        .andExpect(jsonPath("$.modelName").value("Find X9"))
                        .andExpect(jsonPath("$.edition").value("典藏版"))
                        .andExpect(jsonPath("$.rom").value("1024GB"))
                        .andExpect(jsonPath("$.status").value(3))
                        .andReturn();
        var latest = json.readTree(saved.getResponse().getContentAsByteArray());
        assertThat(latest.path("editVersion").asText())
                .isNotEqualTo(current.path("editVersion").asText());
        mvc.perform(putGoods(path, edit))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_GOODS_CHANGED"));
        mvc.perform(get(path).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goodsName").value("修正商品名"));
        mvc.perform(get(GOODS).session(admin).param("status", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(get(GOODS + "/summary").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(0))
                .andExpect(jsonPath("$.models[0].modelName").value("Find X9"));
        mvc.perform(putGoods(path, editBody(latest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editVersion").value(latest.path("editVersion").asText()));
    }

    @Test
    void allowsIndependentSourceConfigurationsAndProtectsManualDuplicatesAndPolicyReferences()
            throws Exception {
        var current = importedGoodForEditing();
        String id = current.path("id").asText();
        String path = GOODS + "/" + id;
        jdbc.update("INSERT INTO brand (name) VALUES (?)", ONEPLUS);
        String otherBrand =
                jdbc.queryForObject("SELECT id FROM brand WHERE name=?", String.class, ONEPLUS);
        jdbc.update("INSERT INTO series (brand_id, name) VALUES (?, ?)", otherBrand, "数字系列");
        String otherSeries =
                jdbc.queryForObject(
                        "SELECT id FROM series WHERE brand_id=?", String.class, otherBrand);
        var edit = editBody(current);
        ((ObjectNode) edit.path("product")).put("brandId", otherBrand).put("seriesId", otherSeries);
        mvc.perform(putGoods(path, edit))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_GOODS_SOURCE_BOUND"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM spu WHERE brand_id=?",
                                Integer.class,
                                otherBrand))
                .isZero();

        edit = editBody(current);
        var product = (ObjectNode) edit.path("product");
        product.put("rom", "256GB");
        var manualResult =
                mvc.perform(mutation(GOODS).content(product.toString()))
                        .andExpect(status().isCreated())
                        .andReturn();
        var manual = json.readTree(manualResult.getResponse().getContentAsByteArray());
        var duplicateManual = editBody(manual);
        ((ObjectNode) duplicateManual.path("product")).put("rom", "512GB");
        mvc.perform(putGoods(GOODS + "/" + manual.path("id").asText(), duplicateManual))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_GOODS_DUPLICATE"));

        var saved =
                mvc.perform(putGoods(path, edit))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.skuId").value(current.path("skuId").asText()))
                        .andReturn();
        current = json.readTree(saved.getResponse().getContentAsByteArray());

        referenceGoods("EDIT-QA", id);
        edit = editBody(current);
        ((ObjectNode) edit.path("product")).put("rom", "512GB");
        mvc.perform(putGoods(path, edit))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_GOODS_POLICY_BOUND"));
        jdbc.update("INSERT INTO color_dict (color_name) VALUES ('白色')");
        String white =
                jdbc.queryForObject(
                        "SELECT id FROM color_dict WHERE color_name='白色'", String.class);
        edit = editBody(current);
        ((ObjectNode) edit.path("product")).put("colorId", white);
        mvc.perform(putGoods(path, edit))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_GOODS_POLICY_BOUND"));
        edit = editBody(current);
        ((ObjectNode) edit.path("product")).put("name", "仅修改名称");
        mvc.perform(putGoods(path, edit)).andExpect(status().isOk());
    }

    private JsonNode importedGoodForEditing() throws Exception {
        importForCleaning(
                List.of(
                        goods(
                                "Find X8 星耀黑 16+512",
                                "X8-EDIT",
                                "16+512",
                                "Find X8",
                                "Find系列",
                                "Find X8 16+512",
                                null)));
        startClean(OPPO, null, false, 1);
        cleaning.processNextBatch();
        long id = jdbc.queryForObject("SELECT id FROM goods", Long.class);
        var result =
                mvc.perform(get(GOODS + "/" + id).session(admin))
                        .andExpect(status().isOk())
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private ObjectNode editBody(JsonNode current) {
        var body =
                json.createObjectNode()
                        .put("expectedVersion", current.path("editVersion").asText());
        var product = body.putObject("product");
        product.set("name", current.get("goodsName"));
        for (String field :
                List.of(
                        "brandId",
                        "seriesId",
                        "modelName",
                        "edition",
                        "ram",
                        "rom",
                        "colorId",
                        "status")) {
            product.set(field, current.get(field));
        }
        return body;
    }

    private MockHttpServletRequestBuilder putGoods(String path, ObjectNode body) {
        return put(path)
                .session(admin)
                .header("Origin", "http://localhost:5173")
                .header("X-Policy-Request", "1")
                .contentType("application/json")
                .content(body.toString());
    }

    @Test
    void permanentlyDeletesUnreferencedGoodsAndReleasesSpecification() throws Exception {
        var imported = importedGoodForEditing();
        var product = ((ObjectNode) editBody(imported).path("product")).put("rom", "256GB");
        var created =
                mvc.perform(mutation(GOODS).content(product.toString()))
                        .andExpect(status().isCreated())
                        .andReturn();
        var createdGoods = json.readTree(created.getResponse().getContentAsByteArray());
        String id = createdGoods.path("id").asText();
        String path = GOODS + "/" + id;
        mvc.perform(delete(path).session(admin)).andExpect(status().isForbidden());
        mvc.perform(
                        delete(path)
                                .session(session("test-tenant", "remove-applicant", "APPLICANT"))
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1"))
                .andExpect(status().isForbidden());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(deleteGoods(path)).andExpect(status().isNoContent());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods WHERE id=?", Integer.class, id))
                .isZero();
        mvc.perform(get(path).session(admin)).andExpect(status().isNotFound());
        mvc.perform(putGoods(path, editBody(createdGoods))).andExpect(status().isNotFound());
        mvc.perform(get(GOODS).session(admin).param("ram", "16GB").param("rom", "256GB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(GOODS + "/summary").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(mutation(GOODS).content(product.toString())).andExpect(status().isCreated());
    }

    @Test
    void rejectsDeletingGoodsWithSourceOrPolicyReferences() throws Exception {
        var imported = importedGoodForEditing();
        String importedId = imported.path("id").asText();
        mvc.perform(deleteGoods(GOODS + "/" + importedId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_GOODS_SOURCE_BOUND"));
        assertThat(jdbc.queryForObject("SELECT goods_id FROM source_goods_item", String.class))
                .isEqualTo(importedId);
        var product = ((ObjectNode) editBody(imported).path("product")).put("rom", "256GB");
        var created =
                mvc.perform(mutation(GOODS).content(product.toString()))
                        .andExpect(status().isCreated())
                        .andReturn();
        String id =
                json.readTree(created.getResponse().getContentAsByteArray()).path("id").asText();
        referenceGoods("DELETE-QA", id);
        mvc.perform(deleteGoods(GOODS + "/" + id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_GOODS_POLICY_BOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods", Integer.class)).isEqualTo(2);
    }

    @Test
    void applicantMustConfirmAllOnSaleGoodsOfEachCommonConfigurationAndFreezesTheirNames()
            throws Exception {
        var imported = importedGoodForEditing();
        String blackId = imported.path("id").asText();
        String spu = imported.path("spuId").asText();
        jdbc.update("INSERT INTO color_dict (color_name) VALUES ('白色'), ('蓝色')");
        var product = (ObjectNode) editBody(imported).path("product");
        var white =
                mvc.perform(
                                mutation(GOODS)
                                        .content(
                                                product.deepCopy()
                                                        .put(
                                                                "colorId",
                                                                jdbc.queryForObject(
                                                                        "SELECT id FROM color_dict WHERE color_name='白色'",
                                                                        String.class))
                                                        .toString()))
                        .andExpect(status().isCreated())
                        .andReturn();
        String whiteId =
                json.readTree(white.getResponse().getContentAsByteArray()).path("id").asText();
        mvc.perform(
                        mutation(GOODS)
                                .content(
                                        product.deepCopy()
                                                .put(
                                                        "colorId",
                                                        jdbc.queryForObject(
                                                                "SELECT id FROM color_dict WHERE color_name='蓝色'",
                                                                String.class))
                                                .put("status", 2)
                                                .toString()))
                .andExpect(status().isCreated());
        var owner = session("test-tenant", "policy-owner", "APPLICANT");
        assignPlatforms("policy-owner", "RETAIL:JD", "RETAIL:PDD");
        var other = session("test-tenant", "policy-other", "APPLICANT");
        var external = session("other-tenant", "policy-external", "APPLICANT");
        String policies = "/api/policies";

        mvc.perform(get("/api/catalog/options").session(owner).param("spuId", spu))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configurations.length()").value(1))
                .andExpect(jsonPath("$.configurations[0].spuId").value(spu));
        mvc.perform(get("/api/catalog/goods").session(external)).andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/catalog/goods")
                                .session(owner)
                                .param("spuId", spu)
                                .param("ram", "16GB")
                                .param("rom", "512GB")
                                .param("page", "2")
                                .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].goodsId").value(whiteId))
                .andExpect(jsonPath("$.items[0].sourceSkuId").doesNotExist());

        var extraConfig =
                mvc.perform(
                                mutation(GOODS)
                                        .content(product.deepCopy().put("rom", "256GB").toString()))
                        .andExpect(status().isCreated())
                        .andReturn();
        String extraId =
                json.readTree(extraConfig.getResponse().getContentAsByteArray())
                        .path("id")
                        .asText();
        mvc.perform(
                        get("/api/catalog/goods")
                                .session(owner)
                                .param("configuration", spu + "/16GB/512GB", spu + "/16GB/256GB")
                                .param("keyword", "Find X8")
                                .param("page", "2")
                                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].rom").value("256GB"));
        mvc.perform(
                        get("/api/catalog/goods")
                                .session(owner)
                                .param("configuration", spu + "/16GB", spu + "/16GB/256GB"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATALOG_INVALID_FILTER"));

        String key = UUID.randomUUID().toString();
        String incomplete =
                """
                {"perspective":"RETAIL","selectedPlatforms":["JD"]}
                """;
        var saved =
                mvc.perform(
                                policyRequest(post(policies + "/drafts"), owner)
                                        .header("Idempotency-Key", key)
                                        .content(incomplete))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.recordVersion").value(1))
                        .andReturn();
        String id = json.readTree(saved.getResponse().getContentAsByteArray()).path("id").asText();
        String path = policies + "/" + id;
        mvc.perform(
                        policyRequest(post(policies + "/drafts"), owner)
                                .header("Idempotency-Key", key)
                                .content(incomplete))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(get(path).session(other)).andExpect(status().isNotFound());
        mvc.perform(policyRequest(post(path + "/submit"), owner).content("{\"recordVersion\":1}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM policy_audit_log", Integer.class))
                .isZero();

        String full =
                """
                {"recordVersion":1,"perspective":"RETAIL","selectedPlatforms":["JD","PDD"],
                "verifyType":"SALES","sceneTypes":["NATIONAL_SUB"],"estimated":false,
                "startTime":"2026-11-10T09:00:00+08:00","endTime":"2099-11-20T18:00:00+08:00",
                "goodsForm":{"cards":[{"key":"common","cardType":"COMMON","followsOrderPlatforms":true,
                "targets":[{"goodsId":"%s","spuId":"%s","ram":"16GB","rom":"512GB"}],
                "subsidyPlans":[{"key":"fixed","subsidyMode":"FIXED","fixedAmount":"0.10"}],
                "platformAssignments":[{"platformCode":"JD","planKey":"fixed"},{"platformCode":"PDD","planKey":"fixed"}]},
                {"key":"common-2","cardType":"COMMON","followsOrderPlatforms":false,
                "targets":[{"goodsId":"%s","spuId":"%s","ram":"16GB","rom":"256GB"}],
                "subsidyPlans":[{"key":"fixed","subsidyMode":"FIXED","fixedAmount":"20.00"}],
                "platformAssignments":[{"platformCode":"JD","planKey":"fixed"}]}]}}
                """
                        .formatted(blackId, spu, extraId, spu);
        String duplicate =
                full.replace("\"goodsId\":\"" + extraId + "\"", "\"goodsId\":\"" + blackId + "\"");
        mvc.perform(policyRequest(put(path + "/draft"), owner).content(duplicate))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("同一平台重复选择了同一商品，请整理商品卡片。"));
        var mixedCards = json.readTree(duplicate);
        var specified = (ObjectNode) mixedCards.path("goodsForm").withArray("cards").get(1);
        specified.put("cardType", "SPECIFIED");
        ((ObjectNode) specified.withArray("targets").get(0)).put("rom", "512GB");
        mvc.perform(policyRequest(put(path + "/draft"), owner).content(mixedCards.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value("同一平台的商品已同时选入普通卡和指定卡，请移除重复的指定卡，或将该配置改为仅用指定卡申报。"));
        var sameConfiguration = json.readTree(full);
        var cards = sameConfiguration.path("goodsForm").withArray("cards");
        var first = (ObjectNode) cards.get(0);
        first.put("followsOrderPlatforms", false);
        first.withArray("platformAssignments").remove(0);
        var secondTarget = (ObjectNode) cards.get(1).withArray("targets").get(0);
        secondTarget.put("goodsId", whiteId).put("rom", "512GB");
        mvc.perform(
                        policyRequest(put(path + "/draft"), owner)
                                .content(sameConfiguration.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("同一机型配置只能加入一张普通商品卡，请编辑已有卡片。"));
        mvc.perform(policyRequest(put(path + "/draft"), owner).content(full))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(2));
        mvc.perform(policyRequest(put(path + "/draft"), owner).content(full))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_VERSION_CONFLICT"));
        mvc.perform(get(path).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.form.startTime").value("2026-11-10T09:00:00+08:00"))
                .andExpect(jsonPath("$.form.goodsForm.cards[0].targets[0].goodsId").value(blackId))
                .andExpect(jsonPath("$.form.goodsForm.cards[1].targets[0].goodsId").value(extraId));
        jdbc.update("UPDATE goods SET status = 3 WHERE id = ?", blackId);
        mvc.perform(policyRequest(post(path + "/submit"), owner).content("{\"recordVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_GOODS_CHANGED"));
        assertThat(jdbc.queryForObject("SELECT status FROM policy_apply", String.class))
                .isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM policy_audit_log", Integer.class))
                .isZero();
        jdbc.update("UPDATE goods SET status = 1 WHERE id = ?", blackId);
        mvc.perform(get(path + "/submission-preview").session(other).param("recordVersion", "2"))
                .andExpect(status().isNotFound());
        mvc.perform(get(path + "/submission-preview").session(owner).param("recordVersion", "1"))
                .andExpect(status().isConflict());
        mvc.perform(get(path + "/submission-preview").session(owner).param("recordVersion", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_COMMON_SCOPE_CHANGED"));
        // 即使缺少的商品另填了指定卡，也不能用该指定卡凑齐普通卡的整组范围。
        var mixed = (ObjectNode) json.readTree(full);
        mixed.remove("recordVersion");
        var mixedCardsArray = ((ObjectNode) mixed.get("goodsForm")).withArray("cards");
        var whiteCard = ((ObjectNode) mixedCardsArray.get(0)).deepCopy();
        whiteCard.put("key", "white").put("cardType", "SPECIFIED");
        ((ObjectNode) whiteCard.withArray("targets").get(0)).put("goodsId", whiteId);
        mixedCardsArray.add(whiteCard);
        String mixedId = createPolicy(owner, mixed.toString()).path("id").asText();
        mvc.perform(
                        policyRequest(post("/api/policies/" + mixedId + "/submit"), owner)
                                .content("{\"recordVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_COMMON_SCOPE_CHANGED"));
        jdbc.update("UPDATE goods SET status = 2 WHERE id = ?", whiteId);
        mvc.perform(get(path + "/submission-preview").session(owner).param("recordVersion", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicability.status").value("DIRECT"));
        jdbc.update("UPDATE goods SET status = 1 WHERE id = ?", whiteId);
        mvc.perform(policyRequest(post(path + "/submit"), owner).content("{\"recordVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_COMMON_SCOPE_CHANGED"));
        var corrected = (ObjectNode) json.readTree(full);
        corrected.put("recordVersion", 2);
        var targets = ((ObjectNode) corrected.at("/goodsForm/cards/0")).withArray("targets");
        targets.add(((ObjectNode) targets.get(0)).deepCopy().put("goodsId", whiteId));
        mvc.perform(policyRequest(put(path + "/draft"), owner).content(corrected.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(3));
        mvc.perform(
                        policyRequest(post(path + "/submit"), owner)
                                .content("{\"recordVersion\":3,\"confirmationToken\":\"old\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_COVERAGE_CHANGED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM policy_audit_log", Integer.class))
                .isZero();
        mvc.perform(policyRequest(post(path + "/submit"), owner).content("{\"recordVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.submissionVersion").value(1));
        mvc.perform(policyRequest(post(path + "/submit"), owner).content("{\"recordVersion\":3}"))
                .andExpect(status().isConflict());
        assertThat(
                        jdbc.queryForList(
                                "SELECT goods_id FROM policy_goods_detail ORDER BY platform_code",
                                String.class))
                .containsExactlyInAnyOrder(blackId, blackId, whiteId, whiteId, extraId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM policy_audit_log", Integer.class))
                .isOne();
        mvc.perform(deleteGoods(GOODS + "/" + whiteId)).andExpect(status().isConflict());
        jdbc.update("UPDATE goods SET goods_name = '修改后的名称' WHERE id = ?", blackId);
        mvc.perform(get(path).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goods.length()").value(3))
                .andExpect(jsonPath("$.goods[0].goodsId").value(blackId))
                .andExpect(
                        jsonPath("$.goods[0].goodsName")
                                .value(imported.path("goodsName").asText()));
    }

    @Test
    void listsOnlyOwnedPoliciesWithServerSideFiltersAndStablePagination() throws Exception {
        var owner = session("test-tenant", "mine-owner", "APPLICANT");
        assignPlatforms("mine-owner", "RETAIL:JD", "RETAIL:PDD", "BILLION:TAOBAO");
        var other = session("test-tenant", "mine-other", "APPLICANT");
        assignPlatforms("mine-other", "RETAIL:KUAISHOU");
        var external = session("other-tenant", "mine-external", "APPLICANT");
        String base = "/api/policies";
        String retail = "{\"perspective\":\"RETAIL\",\"selectedPlatforms\":[\"JD\",\"PDD\"]}";
        String billion = "{\"perspective\":\"BILLION\",\"selectedPlatforms\":[\"TAOBAO\"]}";
        String first = createPolicy(owner, retail).path("id").asText();
        String newest = createPolicy(owner, billion).path("id").asText();
        String deleted = createPolicy(owner, retail).path("id").asText();
        createPolicy(other, "{\"perspective\":\"RETAIL\",\"selectedPlatforms\":[\"KUAISHOU\"]}");
        jdbc.update("UPDATE policy_apply SET is_deleted = 1 WHERE id = ?", deleted);
        jdbc.update("UPDATE policy_apply SET created_at = '2026-11-14 10:00:00'");

        mvc.perform(get(base + "/mine").session(owner).param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.items[0].id").value(newest))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-11-14T10:00:00+08:00"));
        mvc.perform(get(base + "/mine").session(owner).param("pageSize", "1").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(first));
        mvc.perform(
                        get(base + "/mine")
                                .session(owner)
                                .param("status", "DRAFT")
                                .param("perspective", "RETAIL")
                                .param("platformCode", "JD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first))
                .andExpect(jsonPath("$.items[0].selectedPlatforms[1]").value("PDD"));
        mvc.perform(get(base + "/mine").session(owner).param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(base + "/mine").session(owner).param("status", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POLICY_VALIDATION_FAILED"));
        mvc.perform(get(base + "/mine").session(owner).param("pageSize", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(base + "/mine").session(external)).andExpect(status().isForbidden());
        mvc.perform(get(base + "/mine").session(admin)).andExpect(status().isForbidden());
        mvc.perform(get(base + "/mine")).andExpect(status().isUnauthorized());
        mvc.perform(get(base + "/" + deleted + "/audit-logs").session(owner))
                .andExpect(status().isNotFound());
        mvc.perform(get(base + "/" + first + "/audit-logs").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void rejectedPolicyResubmitsUnderOriginalIdAndPreservesEachAuditSnapshot() throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "resubmit-owner", "APPLICANT");
        assignPlatforms("resubmit-owner", "BILLION:PDD");
        var other = session("test-tenant", "resubmit-other", "APPLICANT");
        var external = session("other-tenant", "resubmit-external", "APPLICANT");
        String full =
                """
                {"perspective":"BILLION","selectedPlatforms":["PDD"],"specialRemark":"首轮说明",
                "startTime":"2026-11-10T09:00:00+08:00","endTime":"2099-11-20T18:00:00+08:00",
                "goodsForm":{"cards":[{"key":"common","cardType":"COMMON","followsOrderPlatforms":true,
                "targets":[{"goodsId":"%s","spuId":"%s","ram":"%s","rom":"%s"}],
                "subsidyPlans":[{"key":"fixed","subsidyMode":"FIXED","fixedAmount":"0.10"}],
                "platformAssignments":[{"platformCode":"PDD","planKey":"fixed"}]}]}}
                """
                        .formatted(
                                good.path("id").asText(), good.path("spuId").asText(),
                                good.path("ram").asText(), good.path("rom").asText());
        var created = createPolicy(owner, full);
        String id = created.path("id").asText();
        String path = "/api/policies/" + id;
        String logs = path + "/audit-logs";
        String otherId = createPolicy(owner, full).path("id").asText();
        mvc.perform(confirmedSubmission(path, owner, 1)).andExpect(status().isOk());
        String oldLog =
                jdbc.queryForObject(
                        "SELECT id FROM policy_audit_log WHERE policy_id=?", String.class, id);
        String adminName =
                jdbc.queryForObject(
                        "SELECT name FROM user_info WHERE tenant_key='test-tenant' AND open_id='admin'",
                        String.class);
        var rejection =
                mvc.perform(
                                policyRequest(post("/api/admin/policies/" + id + "/review"), admin)
                                        .content(
                                                """
                                {"recordVersion":2,"submissionVersion":1,"decision":"REJECT","reviewRemark":"请补充说明"}
                                """))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("REJECTED"))
                        .andReturn();
        String rejectedAt =
                json.readTree(rejection.getResponse().getContentAsByteArray())
                        .path("reviewTime")
                        .asText();
        jdbc.update("UPDATE goods SET goods_name='第二轮商品名称' WHERE id=?", good.path("id").asText());

        mvc.perform(get(path).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.recordVersion").value(3))
                .andExpect(jsonPath("$.goods[0].goodsName").value("第二轮商品名称"));
        String edited =
                ((ObjectNode) json.readTree(full))
                        .put("recordVersion", 3)
                        .put("specialRemark", "补充后的说明")
                        .toString();
        mvc.perform(policyRequest(put(path + "/draft"), owner).content(edited))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.recordVersion").value(4));
        mvc.perform(confirmedSubmission(path, owner, 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyCode").value(created.path("policyCode").asText()))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.submissionVersion").value(2));
        mvc.perform(get(path).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.form.specialRemark").value("补充后的说明"));
        mvc.perform(get(logs).session(owner).param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].submissionVersion").value(2))
                .andExpect(jsonPath("$.items[0].auditStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.items[0].auditorName").isEmpty())
                .andExpect(jsonPath("$.items[0].snapshot").doesNotExist());
        mvc.perform(get(logs).session(owner).param("pageSize", "1").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(oldLog))
                .andExpect(jsonPath("$.items[0].auditComment").value("请补充说明"));
        mvc.perform(get(logs + "/" + oldLog).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.log.auditTime").value(rejectedAt))
                .andExpect(jsonPath("$.log.auditorName").value(adminName))
                .andExpect(jsonPath("$.snapshot.form.specialRemark").value("首轮说明"))
                .andExpect(
                        jsonPath("$.snapshot.goods[0].goodsName")
                                .value(good.path("goodsName").asText()));
        mvc.perform(get(logs).session(other)).andExpect(status().isNotFound());
        mvc.perform(get(logs + "/" + oldLog).session(other)).andExpect(status().isNotFound());
        mvc.perform(get(logs + "/" + oldLog).session(external)).andExpect(status().isForbidden());
        mvc.perform(get("/api/policies/" + otherId + "/audit-logs/" + oldLog).session(owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POLICY_AUDIT_NOT_FOUND"));
    }

    @Test
    void retailSceneSelectionsRoundTripAndFilterTheSubmittedSnapshot() throws Exception {
        var owner = session("test-tenant", "multi-scene-owner", "APPLICANT");
        assignPlatforms("multi-scene-owner", "RETAIL:PDD", "BILLION:PDD");
        var form = attachmentPolicyForm(importedGoodForEditing(), UUID.randomUUID().toString());
        form.putArray("attachmentInfo");
        form.put("perspective", "RETAIL").put("verifyType", "SALES").put("estimated", false);
        form.putArray("sceneTypes");
        String id = createPolicy(owner, form.toString()).path("id").asText();
        String path = "/api/policies/" + id;
        mvc.perform(get(path + "/submission-preview").session(owner).param("recordVersion", "1"))
                .andExpect(status().isBadRequest());
        form.put("recordVersion", 1);
        for (var invalid : List.of(List.of("ALL"), List.of("SELF_SUB", "SELF_SUB"))) {
            form.set("sceneTypes", json.valueToTree(invalid));
            mvc.perform(policyRequest(put(path + "/draft"), owner).content(form.toString()))
                    .andExpect(status().isBadRequest());
        }
        form.putArray("sceneTypes").add("SELF_SUB").add("LOCAL_SUB");
        mvc.perform(policyRequest(put(path + "/draft"), owner).content(form.toString()))
                .andExpect(status().isOk());
        mvc.perform(get(path).session(owner))
                .andExpect(jsonPath("$.form.sceneTypes").value(contains("LOCAL_SUB", "SELF_SUB")))
                .andExpect(jsonPath("$.form.sceneType").doesNotExist());
        form.put("recordVersion", 2);
        form.put("perspective", "BILLION").putNull("verifyType").putNull("estimated");
        form.putArray("sceneTypes").add("LOCAL_SUB");
        mvc.perform(policyRequest(put(path + "/draft"), owner).content(form.toString()))
                .andExpect(status().isBadRequest());

        mvc.perform(confirmedSubmission(path, owner, 2)).andExpect(status().isOk());
        mvc.perform(get(path).session(owner))
                .andExpect(jsonPath("$.form.sceneTypes").value(contains("LOCAL_SUB", "SELF_SUB")));
        for (String scene : List.of("LOCAL_SUB", "SELF_SUB", "NATIONAL_SUB")) {
            int expected = scene.equals("NATIONAL_SUB") ? 0 : 1;
            var result =
                    mvc.perform(get("/api/admin/policies").session(admin).param("sceneType", scene))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.total").value(expected));
            if (expected == 1) {
                result.andExpect(jsonPath("$.counts.PENDING").value(1))
                        .andExpect(
                                jsonPath("$.items[0].sceneTypes")
                                        .value(contains("LOCAL_SUB", "SELF_SUB")));
            }
        }
        mvc.perform(get("/api/admin/policies/" + id).session(admin))
                .andExpect(
                        jsonPath("$.snapshot.form.sceneTypes")
                                .value(contains("LOCAL_SUB", "SELF_SUB")));
        String log =
                jdbc.queryForObject(
                        "SELECT id FROM policy_audit_log WHERE policy_id=?", String.class, id);
        mvc.perform(get("/api/admin/policies/" + id + "/audit-logs/" + log).session(admin))
                .andExpect(
                        jsonPath("$.snapshot.form.sceneTypes")
                                .value(contains("LOCAL_SUB", "SELF_SUB")));
    }

    @Test
    void adminListAndFiltersKeepLastSubmissionUntilRejectedPolicyIsResubmitted() throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "submitted-view-owner", "APPLICANT");
        assignPlatforms("submitted-view-owner", "RETAIL:PDD", "BILLION:TAOBAO");
        var form = attachmentPolicyForm(good, UUID.randomUUID().toString());
        form.putArray("attachmentInfo");
        form.put("perspective", "RETAIL");
        form.put("verifyType", "SALES");
        form.putArray("sceneTypes").add("NATIONAL_SUB");
        form.put("estimated", false);
        String id = createPolicy(owner, form.toString()).path("id").asText();
        String ownPath = "/api/policies/" + id;
        String adminPath = "/api/admin/policies";
        mvc.perform(confirmedSubmission(ownPath, owner, 1)).andExpect(status().isOk());
        mvc.perform(
                        policyRequest(post(adminPath + "/" + id + "/review"), admin)
                                .content(
                                        "{\"recordVersion\":2,\"submissionVersion\":1,\"decision\":\"REJECT\"}"))
                .andExpect(status().isOk());
        var before =
                json.readTree(
                        mvc.perform(get(adminPath).session(admin))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());

        form.put("recordVersion", 3);
        form.put("perspective", "BILLION");
        form.putArray("selectedPlatforms").add("TAOBAO");
        form.putNull("verifyType");
        form.putNull("sceneTypes");
        form.putNull("estimated");
        ((ObjectNode)
                        form.path("goodsForm")
                                .path("cards")
                                .get(0)
                                .path("platformAssignments")
                                .get(0))
                .put("platformCode", "TAOBAO");
        mvc.perform(policyRequest(put(ownPath + "/draft"), owner).content(form.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordVersion").value(4));
        mvc.perform(get(ownPath).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.form.perspective").value("BILLION"))
                .andExpect(jsonPath("$.form.selectedPlatforms[0]").value("TAOBAO"));

        // 管理员仍看到首轮拼多多及原渠道，四类筛选和状态计数使用同一份快照。
        mvc.perform(
                        get(adminPath)
                                .session(admin)
                                .param("perspective", "RETAIL")
                                .param("platformCode", "PDD")
                                .param("verifyType", "SALES")
                                .param("sceneType", "NATIONAL_SUB")
                                .param("status", "REJECTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.counts.REJECTED").value(1))
                .andExpect(jsonPath("$.items[0].perspective").value("RETAIL"))
                .andExpect(jsonPath("$.items[0].platforms[0]").value("PDD"))
                .andExpect(jsonPath("$.items[0].verifyType").value("SALES"))
                .andExpect(jsonPath("$.items[0].sceneTypes").value(contains("NATIONAL_SUB")))
                .andExpect(jsonPath("$.items[0].recordVersion").value(4))
                .andExpect(jsonPath("$.items[0].submissionVersion").value(1))
                .andExpect(
                        jsonPath("$.items[0].submittedAt")
                                .value(before.path("items").get(0).path("submittedAt").asText()));
        for (String[] filter :
                List.of(
                        new String[] {"perspective", "BILLION"},
                        new String[] {"platformCode", "TAOBAO"})) {
            mvc.perform(get(adminPath).session(admin).param(filter[0], filter[1]))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.counts").isEmpty());
        }
        mvc.perform(get(adminPath + "/" + id).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.perspective").value("RETAIL"))
                .andExpect(jsonPath("$.policy.platforms[0]").value("PDD"))
                .andExpect(jsonPath("$.policy.verifyType").value("SALES"))
                .andExpect(jsonPath("$.policy.sceneTypes").value(contains("NATIONAL_SUB")))
                .andExpect(jsonPath("$.policy.sceneTypes").value(contains("NATIONAL_SUB")))
                .andExpect(jsonPath("$.snapshot.form.sceneTypes").value(contains("NATIONAL_SUB")))
                .andExpect(jsonPath("$.snapshot.form.selectedPlatforms[0]").value("PDD"))
                .andExpect(jsonPath("$.policy.reviewable").value(false));

        mvc.perform(confirmedSubmission(ownPath, owner, 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionVersion").value(2));
        mvc.perform(
                        get(adminPath)
                                .session(admin)
                                .param("perspective", "BILLION")
                                .param("platformCode", "TAOBAO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.counts.PENDING").value(1))
                .andExpect(jsonPath("$.items[0].perspective").value("BILLION"))
                .andExpect(jsonPath("$.items[0].platforms[0]").value("TAOBAO"))
                .andExpect(jsonPath("$.items[0].verifyType").isEmpty())
                .andExpect(jsonPath("$.items[0].sceneType").doesNotExist())
                .andExpect(jsonPath("$.items[0].submissionVersion").value(2));
        for (String[] filter :
                List.of(
                        new String[] {"perspective", "RETAIL"},
                        new String[] {"platformCode", "PDD"},
                        new String[] {"verifyType", "SALES"},
                        new String[] {"sceneType", "NATIONAL_SUB"})) {
            mvc.perform(get(adminPath).session(admin).param(filter[0], filter[1]))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
        }
        mvc.perform(get(adminPath + "/" + id).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.perspective").value("BILLION"))
                .andExpect(jsonPath("$.policy.platforms[0]").value("TAOBAO"))
                .andExpect(jsonPath("$.policy.verifyType").isEmpty())
                .andExpect(jsonPath("$.policy.sceneType").doesNotExist())
                .andExpect(jsonPath("$.policy.sceneTypes").isEmpty())
                .andExpect(jsonPath("$.snapshot.form.sceneTypes").isEmpty())
                .andExpect(jsonPath("$.snapshot.form.selectedPlatforms[0]").value("TAOBAO"))
                .andExpect(jsonPath("$.policy.reviewable").value(true));
    }

    @Test
    void storesThePublicShortNumberInTheSnapshotAndKeepsCreationIdempotent() throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "short-policy-number-owner", "APPLICANT");
        assignPlatforms("short-policy-number-owner", "BILLION:PDD");
        var form = attachmentPolicyForm(good, UUID.randomUUID().toString());
        form.putArray("attachmentInfo");
        String key = UUID.randomUUID().toString();
        var created =
                mvc.perform(
                                policyRequest(post("/api/policies/drafts"), owner)
                                        .header("Idempotency-Key", key)
                                        .content(form.toString()))
                        .andExpect(status().isCreated())
                        .andReturn();
        var record = json.readTree(created.getResponse().getContentAsByteArray());
        String id = record.path("id").asText();
        String code = String.format(java.util.Locale.ROOT, "ZC-%06d", Long.parseLong(id));
        assertThat(record.path("policyCode").asText()).isEqualTo(code);
        mvc.perform(
                        policyRequest(post("/api/policies/drafts"), owner)
                                .header("Idempotency-Key", key)
                                .content(form.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.policyCode").value(code));
        mvc.perform(get("/api/policies/" + id).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyCode").value(code));
        mvc.perform(get("/api/policies/mine").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].policyCode").value(code));
        mvc.perform(confirmedSubmission("/api/policies/" + id, owner, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyCode").value(code));
        mvc.perform(
                        get("/api/admin/policies")
                                .session(admin)
                                .param("keyword", code.toLowerCase(java.util.Locale.ROOT)))
                .andExpect(result -> assertThat(result.getResolvedException()).isNull())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].policyCode").value(code));
        String internal =
                jdbc.queryForObject(
                        "SELECT policy_no FROM policy_apply WHERE id=?", String.class, id);
        assertThat(internal).matches("[0-9A-F]{32}");
        String stored =
                jdbc.queryForObject(
                        "SELECT snapshot_data FROM policy_audit_log WHERE policy_id=?",
                        String.class,
                        id);
        var snapshot = (ObjectNode) json.readTree(stored);
        assertThat(snapshot.path("policyCode").asText()).isEqualTo(code);
        ((ObjectNode) snapshot.path("form")).putNull("sceneType");
        String saved = snapshot.toString();
        jdbc.update("UPDATE policy_audit_log SET snapshot_data=? WHERE policy_id=?", saved, id);
        mvc.perform(get("/api/admin/policies/" + id).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.policyCode").value(code))
                .andExpect(jsonPath("$.snapshot.policyCode").value(code))
                .andExpect(
                        jsonPath("$.snapshot.form.goodsForm.cards[0].subsidyPlans[0].subsidyMode")
                                .isString())
                .andExpect(jsonPath("$.snapshot.goods[0].goodsId").isString());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT snapshot_data FROM policy_audit_log WHERE policy_id=?",
                                String.class,
                                id))
                .isEqualTo(saved);
    }

    @Test
    void billionPreviewSplitsFutureConfigurationMatchesAndShowsSpecifiedColors() throws Exception {
        var black = importedGoodForEditing();
        jdbc.update("INSERT INTO color_dict (color_name) VALUES ('白色')");
        var product = (ObjectNode) editBody(black).path("product");
        product.put(
                "colorId",
                jdbc.queryForObject(
                        "SELECT id FROM color_dict WHERE color_name='白色'", String.class));
        var owner = session("test-tenant", "interval-owner", "APPLICANT");
        assignPlatforms("interval-owner", "BILLION:PDD");
        var history = attachmentPolicyForm(black, UUID.randomUUID().toString());
        history.remove("attachmentInfo");
        String approve = "{\"recordVersion\":2,\"submissionVersion\":1,\"decision\":\"APPROVE\"}";
        var priorIds = new ArrayList<String>();
        for (String[] dates : List.of(new String[] {"03", "08"}, new String[] {"15", "20"})) {
            history.put("startTime", "2099-11-" + dates[0] + "T00:00:00+08:00");
            history.put("endTime", "2099-11-" + dates[1] + "T09:00:00+08:00");
            String id = createPolicy(owner, history.toString()).path("id").asText();
            priorIds.add(id);
            mvc.perform(confirmedSubmission("/api/policies/" + id, owner, 1))
                    .andExpect(status().isOk());
            mvc.perform(
                            policyRequest(post("/api/admin/policies/" + id + "/review"), admin)
                                    .content(approve))
                    .andExpect(status().isOk());
        }
        // 以已提交快照的截止日期为准，主表日期不同也不能改变试算结果。
        jdbc.update(
                "UPDATE policy_apply SET end_time='2099-12-31 23:59:59' WHERE id=?",
                priorIds.get(0));
        var response =
                mvc.perform(mutation(GOODS).content(product.toString()))
                        .andExpect(status().isCreated())
                        .andReturn();
        var white = json.readTree(response.getResponse().getContentAsByteArray());
        var draft = attachmentPolicyForm(white, UUID.randomUUID().toString());
        draft.remove("attachmentInfo");
        draft.put("startTime", "2099-11-01T00:00:00+08:00")
                .put("endTime", "2099-11-30T12:00:00+08:00");
        var card = (ObjectNode) draft.path("goodsForm").path("cards").get(0);
        card.put("cardType", "SPECIFIED");
        ((ObjectNode) card.path("platformAssignments").get(0)).put("quantity", 0);
        String id = createPolicy(owner, draft.toString()).path("id").asText();
        String path = "/api/policies/" + id;
        mvc.perform(get(path + "/submission-preview").session(owner).param("recordVersion", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicability.scopes.length()").value(1))
                .andExpect(
                        jsonPath("$.applicability.scopes[0].scope.spuId")
                                .value(black.path("spuId").asText()))
                .andExpect(jsonPath("$.applicability.scopes[0].segments.length()").value(2))
                .andExpect(
                        jsonPath("$.applicability.scopes[0].segments[0].action")
                                .value("STACK_OR_CHOOSE"))
                .andExpect(
                        jsonPath("$.applicability.scopes[0].segments[0].eligiblePolicyIds")
                                .value(contains(id, priorIds.get(0))))
                .andExpect(
                        jsonPath("$.applicability.scopes[0].segments[0].through")
                                .value("2099-11-08T09:00:00+08:00"))
                .andExpect(
                        jsonPath("$.applicability.scopes[0].segments[1].from")
                                .value("2099-11-15T00:00:00+08:00"))
                .andExpect(
                        jsonPath("$.applicability.scopes[0].segments[1].eligiblePolicyIds")
                                .value(contains(id, priorIds.get(1))))
                .andExpect(
                        jsonPath("$.applicability.scopes[0].policies[0].selections[0].cardType")
                                .value("SPECIFIED"))
                .andExpect(
                        jsonPath("$.applicability.scopes[0].policies[0].selections[0].quantity")
                                .value(0))
                .andExpect(
                        jsonPath(
                                        "$.applicability.scopes[0].policies[0].selections[0].products[0].colorName")
                                .value("白色"));
        mvc.perform(get(path).session(owner))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.recordVersion").value(1));
    }

    @Test
    void confirmationIncludesEachProductsPriorSubsidyFromItsApprovedRound() throws Exception {
        var black = importedGoodForEditing();
        jdbc.update("INSERT INTO color_dict (color_name) VALUES ('白色')");
        var product = (ObjectNode) editBody(black).path("product");
        product.put(
                "colorId",
                jdbc.queryForObject(
                        "SELECT id FROM color_dict WHERE color_name='白色'", String.class));
        var whiteResult =
                mvc.perform(mutation(GOODS).content(product.toString()))
                        .andExpect(status().isCreated())
                        .andReturn();
        var white = json.readTree(whiteResult.getResponse().getContentAsByteArray());
        var owner = session("test-tenant", "coverage-color-owner", "APPLICANT");
        assignPlatforms("coverage-color-owner", "BILLION:PDD");
        var form = attachmentPolicyForm(black, UUID.randomUUID().toString());
        form.putArray("attachmentInfo");
        var cards =
                (com.fasterxml.jackson.databind.node.ArrayNode)
                        form.path("goodsForm").path("cards");
        ((ObjectNode) cards.get(0)).put("cardType", "SPECIFIED");
        var second = ((ObjectNode) cards.get(0)).deepCopy();
        second.put("key", "white");
        ((ObjectNode) second.path("targets").get(0)).put("goodsId", white.path("id").asText());
        ((ObjectNode) second.path("subsidyPlans").get(0)).put("fixedAmount", "0.30");
        cards.add(second);
        String prior = createPolicy(owner, form.toString()).path("id").asText();
        mvc.perform(confirmedSubmission("/api/policies/" + prior, owner, 1))
                .andExpect(status().isOk());
        ((ObjectNode) cards.get(0).path("subsidyPlans").get(0)).put("fixedAmount", "0.50");
        ((ObjectNode) cards.get(0)).put("cardType", "COMMON");
        ((com.fasterxml.jackson.databind.node.ArrayNode) cards.get(0).path("targets"))
                .add(second.path("targets").get(0).deepCopy());
        cards.remove(1);
        String current = createPolicy(owner, form.toString()).path("id").asText();
        var path = "/api/policies/" + current;
        var stale = confirmedSubmission(path, owner, 1);
        mvc.perform(
                        policyRequest(post("/api/admin/policies/" + prior + "/review"), admin)
                                .content(
                                        "{\"recordVersion\":2,\"submissionVersion\":1,\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk());
        // 改主表补贴原文模拟数据差异，预览的旧补贴必须继续来自正式提交快照。
        jdbc.update("UPDATE policy_apply SET goods_form_json='{}' WHERE id=?", prior);
        mvc.perform(stale)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_COVERAGE_CHANGED"));
        mvc.perform(get(path).session(owner)).andExpect(jsonPath("$.status").value("DRAFT"));
        mvc.perform(get(path + "/submission-preview").param("recordVersion", "1").session(owner))
                .andExpect(jsonPath("$.applicability.scopes[0].policies[1].policyId").value(prior))
                .andExpect(
                        jsonPath(
                                        "$.applicability.scopes[0].policies[1].selections[*].subsidy.fixedAmount")
                                .value(contains("0.10", "0.30")));
        mvc.perform(confirmedSubmission("/api/policies/" + current, owner, 1))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/policies/" + prior).session(admin))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.snapshot.form.goodsForm.cards[*].subsidyPlans[0].fixedAmount")
                                .value(contains("0.10", "0.30")));
    }

    @Test
    void assignsPlatformsIndependentlyByChannelAndChecksCurrentScopeAtSubmission()
            throws Exception {
        var owner = session("test-tenant", "assigned-owner", "APPLICANT");
        var colleague = session("test-tenant", "assigned-colleague", "APPLICANT");
        var outside = session("other-tenant", "assigned-outside", "APPLICANT");
        String id =
                jdbc.queryForObject(
                        "SELECT id FROM user_info WHERE open_id='assigned-owner'", String.class);
        String colleagueId =
                jdbc.queryForObject(
                        "SELECT id FROM user_info WHERE open_id='assigned-colleague'",
                        String.class);
        String outsideId =
                jdbc.queryForObject(
                        "SELECT id FROM user_info WHERE open_id='assigned-outside'", String.class);
        String mine = "/api/policies/platforms";
        String adminPath = "/api/admin/users/" + id + "/platforms";
        String otherPath = "/api/admin/users/" + colleagueId + "/platforms";
        String pdd = "{\"perspective\":\"BILLION\",\"platformCode\":\"PDD\"}";
        String retailPdd = pdd.replace("BILLION", "RETAIL");
        String jd = retailPdd.replace("PDD", "JD");
        String dewu = retailPdd.replace("PDD", "DEWU");
        String mixed = "[" + pdd + "," + jd + "," + dewu + "]";
        mvc.perform(get(mine).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(policyRequest(patch(adminPath), owner).content(scopeUpdate(mixed, "[]")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        policyRequest(patch("/api/admin/users/" + outsideId + "/platforms"), admin)
                                .content(scopeUpdate(mixed, "[]")))
                .andExpect(status().isNotFound());
        mvc.perform(get(mine).session(outside)).andExpect(status().isForbidden());
        mvc.perform(
                        policyRequest(patch(adminPath), admin)
                                .content(scopeUpdate("[" + pdd + "," + pdd + "]", "[]")))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        policyRequest(patch(adminPath), admin)
                                .content(
                                        scopeUpdate(
                                                "[" + jd.replace("RETAIL", "BILLION") + "]", "[]")))
                .andExpect(status().isBadRequest());
        String bothChannels = "[" + pdd + "," + retailPdd + "]";
        mvc.perform(policyRequest(patch(adminPath), admin).content(scopeUpdate(bothChannels, "[]")))
                .andExpect(status().isNoContent());
        mvc.perform(get(mine).session(owner))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].perspective").value("BILLION"))
                .andExpect(jsonPath("$[1].perspective").value("RETAIL"))
                .andExpect(jsonPath("$[0].platformCode").value("PDD"))
                .andExpect(jsonPath("$[1].platformCode").value("PDD"));
        mvc.perform(
                        policyRequest(patch(otherPath), admin)
                                .content(scopeUpdate("[" + retailPdd + "]", "[]")))
                .andExpect(status().isConflict());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_review_delivery WHERE notice_key LIKE 'PL/%/PDD/%'",
                                Integer.class))
                .isEqualTo(2);
        // 移交零售拼多多时，原负责人仍保留百亿拼多多。
        mvc.perform(
                        policyRequest(patch(adminPath), admin)
                                .content(scopeUpdate(mixed, bothChannels)))
                .andExpect(status().isNoContent());
        mvc.perform(
                        policyRequest(patch(otherPath), admin)
                                .content(scopeUpdate("[" + retailPdd + "]", "[]")))
                .andExpect(status().isNoContent());
        mvc.perform(
                        policyRequest(patch(otherPath), admin)
                                .content(scopeUpdate("[" + jd + "]", "[" + retailPdd + "]")))
                .andExpect(status().isConflict());
        mvc.perform(get(mine).session(colleague))
                .andExpect(jsonPath("$[0].perspective").value("RETAIL"))
                .andExpect(jsonPath("$[0].platformCode").value("PDD"));
        mvc.perform(get("/api/admin/users/platform-assignments").session(admin))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].userId").value(id));
        mvc.perform(get(mine).session(owner)).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(policyRequest(patch(adminPath), admin).content(scopeUpdate("[]", "[]")))
                .andExpect(status().isConflict());

        var form = attachmentPolicyForm(importedGoodForEditing(), UUID.randomUUID().toString());
        form.remove("attachmentInfo");
        mvc.perform(policyRequest(post("/api/policies/drafts"), colleague).content(form.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POLICY_PLATFORM_FORBIDDEN"));
        String path = "/api/policies/" + createPolicy(owner, form.toString()).path("id").asText();
        var confirm = confirmedSubmission(path, owner, 1);
        mvc.perform(
                        policyRequest(patch(adminPath), admin)
                                .content(scopeUpdate("[" + jd + "]", mixed)))
                .andExpect(status().isNoContent());
        mvc.perform(confirm)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POLICY_PLATFORM_FORBIDDEN"));
        mvc.perform(get(path + "/submission-preview").session(owner).param("recordVersion", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        policyRequest(put(path + "/draft"), owner)
                                .content(form.put("recordVersion", 1).toString()))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).session(owner))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.recordVersion").value(1));
        mvc.perform(
                        policyRequest(patch("/api/admin/users/" + colleagueId + "/status"), admin)
                                .content(
                                        "{\"status\":\"DISABLED\",\"expectedStatus\":\"ENABLED\",\"expectedRoleType\":\"APPLICANT\"}"))
                .andExpect(status().isOk());
        mvc.perform(
                        policyRequest(patch(adminPath), admin)
                                .content(
                                        scopeUpdate(
                                                "[" + jd + "," + retailPdd + "]", "[" + jd + "]")))
                .andExpect(status().isNoContent());
    }

    @Test
    void onlyOneOfTwoAdminsCanAssignTheSameChannelPlatform() throws Exception {
        var secondAdmin = session("test-tenant", "assignment-admin", "ADMIN");
        session("test-tenant", "assignment-first", "APPLICANT");
        session("test-tenant", "assignment-second", "APPLICANT");
        String path = "/api/admin/users/";
        String first =
                jdbc.queryForObject(
                        "SELECT id FROM user_info WHERE open_id='assignment-first'", String.class);
        String second =
                jdbc.queryForObject(
                        "SELECT id FROM user_info WHERE open_id='assignment-second'", String.class);
        String body = scopeUpdate("[{\"perspective\":\"RETAIL\",\"platformCode\":\"JD\"}]", "[]");
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a =
                    executor.submit(
                            () -> {
                                start.await();
                                return mvc.perform(
                                                policyRequest(
                                                                patch(path + first + "/platforms"),
                                                                admin)
                                                        .content(body))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            var b =
                    executor.submit(
                            () -> {
                                start.await();
                                return mvc.perform(
                                                policyRequest(
                                                                patch(path + second + "/platforms"),
                                                                secondAdmin)
                                                        .content(body))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            start.countDown();
            assertThat(java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(204, 409);
            mvc.perform(get(path + "platform-assignments").session(admin))
                    .andExpect(jsonPath("$.length()").value(1));
        } finally {
            executor.shutdownNow();
        }
    }

    private static String scopeUpdate(String platforms, String expected) {
        return "{\"platforms\":" + platforms + ",\"expectedPlatforms\":" + expected + "}";
    }

    @Test
    void applicantConfirmsPlatformHistoryAcrossOwnersWithinTheSameChannel() throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "platform-owner", "APPLICANT");
        assignPlatforms("platform-owner", "RETAIL:JD", "RETAIL:PDD", "BILLION:PDD");
        var colleague = session("test-tenant", "platform-colleague", "APPLICANT");
        var form = attachmentPolicyForm(good, UUID.randomUUID().toString());
        form.remove("attachmentInfo");
        form.put("startTime", "2020-01-01T00:00:00+08:00");
        form.put("endTime", "2099-11-30T18:00:00+08:00");
        form.put("perspective", "RETAIL").put("verifyType", "SALES").put("estimated", false);
        form.putArray("sceneTypes").add("NATIONAL_SUB");
        form.putArray("selectedPlatforms").add("JD");
        var card = (ObjectNode) form.path("goodsForm").path("cards").get(0);
        var assignments = card.putArray("platformAssignments");
        assignments.addObject().put("platformCode", "JD").put("planKey", "fixed");
        String ownId = createPolicy(owner, form.toString()).path("id").asText();
        String approve = "{\"recordVersion\":2,\"submissionVersion\":1,\"decision\":\"APPROVE\"}";
        mvc.perform(confirmedSubmission("/api/policies/" + ownId, owner, 1))
                .andExpect(status().isOk());
        mvc.perform(
                        policyRequest(post("/api/admin/policies/" + ownId + "/review"), admin)
                                .content(approve))
                .andExpect(status().isOk());

        // 百亿与零售分别确定同一商品的适用范围。
        String billionId =
                coveragePolicy(
                        owner, good, "2020-01-01", "2099-12-31", "2020-01-01 09:00:00", false);
        mvc.perform(
                        policyRequest(post("/api/admin/policies/" + billionId + "/review"), admin)
                                .content(approve))
                .andExpect(status().isOk());
        var billionDraft = attachmentPolicyForm(good, UUID.randomUUID().toString());
        billionDraft.remove("attachmentInfo");
        String billionDraftId = createPolicy(owner, billionDraft.toString()).path("id").asText();
        mvc.perform(
                        get("/api/policies/" + billionDraftId + "/submission-preview")
                                .session(owner)
                                .param("recordVersion", "1"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.applicability.scopes[0].policies[1].policyId")
                                .value(billionId));

        form.withArray("selectedPlatforms").add("PDD");
        assignments.addObject().put("platformCode", "PDD").put("planKey", "fixed");
        ((ObjectNode) card.path("subsidyPlans").get(0)).put("fixedAmount", "85.00");
        assignPlatforms("platform-colleague", "RETAIL:JD", "RETAIL:PDD");
        String colleagueId = createPolicy(colleague, form.toString()).path("id").asText();
        mvc.perform(confirmedSubmission("/api/policies/" + colleagueId, colleague, 1))
                .andExpect(status().isOk());
        assignPlatforms("platform-owner", "RETAIL:JD", "RETAIL:PDD");
        String path = "/api/policies/" + createPolicy(owner, form.toString()).path("id").asText();
        var confirmedBeforeApproval = confirmedSubmission(path, owner, 1);
        mvc.perform(reviewRound(colleagueId, 2, 1, "APPROVE")).andExpect(status().isOk());

        // 两个平台都包含接手前获批的同渠道记录，京东另含本人的较早申请。
        var result =
                mvc.perform(
                                get(path + "/submission-preview")
                                        .session(owner)
                                        .param("recordVersion", "1"))
                        .andExpect(status().isOk())
                        .andReturn();
        var applicability =
                json.readTree(result.getResponse().getContentAsByteArray()).path("applicability");
        assertThat(applicability.path("canDirectSubmit").asBoolean()).isFalse();
        assertThat(applicability.path("dateBasis").asText()).isEqualTo("SNAPSHOT_DATES");
        assertThat(applicability.path("scopes").size()).isEqualTo(2);
        for (var scope : applicability.path("scopes")) {
            assertThat(scope.path("policies").findValuesAsText("policyId"))
                    .contains(colleagueId)
                    .doesNotContain(billionId);
            assertThat(scope.path("segments").get(0).path("action").asText())
                    .isEqualTo("SELECT_RETAIL_POLICIES");
            assertThat(scope.has("applicantName")).isFalse();
        }
        assertThat(applicability.toString())
                .doesNotContain("attachmentInfo", "specialRemark", "applicantId", "applicantName");
        for (var scope : applicability.path("scopes")) {
            var ids = scope.path("policies").findValuesAsText("policyId");
            if ("JD".equals(scope.path("scope").path("platformCode").asText())) {
                assertThat(ids).contains(ownId);
            } else {
                assertThat(ids).doesNotContain(ownId);
            }
        }
        mvc.perform(
                        get("/api/admin/policies/effective")
                                .session(admin)
                                .param("perspective", "RETAIL")
                                .param("platformCode", "JD")
                                .param("goodsId", good.path("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policies[*].winner.id").value(contains(colleagueId)));
        // 移交前后负责人的政策均参与整单确认。原标识不能确认新增的平台组合。
        mvc.perform(confirmedBeforeApproval)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_COVERAGE_CHANGED"));
        mvc.perform(confirmedSubmission(path, owner, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void appliesApprovedRoundsAtTheirOwnStartAndThroughTheLastSecond() throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "coverage-owner", "APPLICANT");
        assignPlatforms("coverage-owner", "BILLION:PDD", "BILLION:TAOBAO");
        var outside = session("other-tenant", "coverage-external", "ADMIN");
        String base = "/api/admin/policies";
        var older =
                coveragePolicy(
                        owner, good, "2099-01-02", "2099-01-05", "2020-01-01 09:00:00", true);
        var later =
                coveragePolicy(
                        owner, good, "2099-01-06", "2099-01-07", "2020-01-02 09:00:00", false);
        String goodsId = good.path("id").asText();
        String approve = "{\"recordVersion\":2,\"submissionVersion\":1,\"decision\":\"APPROVE\"}";
        mvc.perform(get(base).session(owner)).andExpect(status().isForbidden());
        mvc.perform(get(base).session(outside)).andExpect(status().isForbidden());
        mvc.perform(
                        get(base)
                                .session(admin)
                                .param("status", "PENDING")
                                .param("submittedFrom", "2020-01-01"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(later));
        mvc.perform(policyRequest(post(base + "/" + older + "/review"), admin).content(approve))
                .andExpect(status().isOk());
        mvc.perform(
                        get(base + "/" + later + "/override-preview")
                                .session(admin)
                                .param("recordVersion", "2")
                                .param("submissionVersion", "1"))
                .andExpect(jsonPath("$.applicability.canDirectSubmit").value(true))
                .andExpect(jsonPath("$.requiresResubmission").value(false));
        mvc.perform(policyRequest(post(base + "/" + later + "/review"), admin).content(approve))
                .andExpect(status().isOk());
        mvc.perform(policyRequest(post(base + "/" + later + "/review"), admin).content(approve))
                .andExpect(status().isConflict());
        mvc.perform(effectiveRequest(goodsId, "PDD", "2099-01-02T08:59:59+08:00"))
                .andExpect(jsonPath("$.policies").isEmpty());
        mvc.perform(effectiveRequest(goodsId, "TAOBAO", "2099-01-05T18:00:00+08:00"))
                .andExpect(jsonPath("$.policies[*].winner.id").value(contains(older)));
        mvc.perform(effectiveRequest(goodsId, "TAOBAO", "2099-01-05T18:00:01+08:00"))
                .andExpect(jsonPath("$.policies").isEmpty());
        mvc.perform(effectiveRequest(goodsId, "PDD", "2099-01-06T00:00:00+08:00"))
                .andExpect(jsonPath("$.policies").isEmpty());
        mvc.perform(effectiveRequest(goodsId, "PDD", "2099-01-06T09:00:00+08:00"))
                .andExpect(jsonPath("$.policies[*].winner.id").value(contains(later)));
        mvc.perform(effectiveRequest(goodsId, "PDD", "2099-01-08T00:00:00+08:00"))
                .andExpect(jsonPath("$.policies").isEmpty());
    }

    @Test
    void concurrentApprovalsRecheckTheSameConfigAcrossColorsAndPreserveBothRounds()
            throws Exception {
        var black = importedGoodForEditing();
        jdbc.update("INSERT INTO color_dict (color_name) VALUES ('白色')");
        var product = (ObjectNode) editBody(black).path("product");
        product.put(
                "colorId",
                jdbc.queryForObject(
                        "SELECT id FROM color_dict WHERE color_name='白色'", String.class));
        var white =
                json.readTree(
                        mvc.perform(mutation(GOODS).content(product.toString()))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        var owner = session("test-tenant", "decision-race", "APPLICANT");
        assignPlatforms("decision-race", "BILLION:PDD");
        var ids = new ArrayList<String>();
        for (var good : List.of(black, white)) {
            var form = attachmentPolicyForm(good, UUID.randomUUID().toString());
            form.remove("attachmentInfo");
            ((ObjectNode) form.at("/goodsForm/cards/0")).put("cardType", "SPECIFIED");
            form.put("startTime", "2099-03-01T00:00:00+08:00")
                    .put("endTime", "2099-03-31T09:00:00+08:00");
            var id = createPolicy(owner, form.toString()).path("id").asText();
            ids.add(id);
            mvc.perform(
                            policyRequest(post("/api/policies/" + id + "/submit"), owner)
                                    .content("{\"recordVersion\":1}"))
                    .andExpect(status().isOk());
        }
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var responses = new ArrayList<java.util.concurrent.Future<Integer>>();
        try {
            for (var id : ids)
                responses.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return mvc.perform(reviewRound(id, 2, 1, "APPROVE"))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            start.countDown();
            int first = responses.get(0).get(10, TimeUnit.SECONDS);
            int second = responses.get(1).get(10, TimeUnit.SECONDS);
            assertThat(List.of(first, second)).containsExactlyInAnyOrder(200, 409);
            String approved = ids.get(first == 200 ? 0 : 1);
            String pending = ids.get(first == 409 ? 0 : 1);
            String path = "/api/policies/" + pending;
            mvc.perform(reviewRound(pending, 2, 1, "APPROVE"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("POLICY_RECONFIRM_REQUIRED"));
            mvc.perform(
                            get("/api/admin/policies/" + pending + "/override-preview")
                                    .session(admin)
                                    .param("recordVersion", "2")
                                    .param("submissionVersion", "1"))
                    .andExpect(jsonPath("$.nextAction").value("REJECT_FOR_RESUBMISSION"))
                    .andExpect(jsonPath("$.consent.decisions").isEmpty())
                    .andExpect(jsonPath("$.applicability.scopes[0].policies.length()").value(2));
            mvc.perform(reviewRound(pending, 2, 1, "REJECT"))
                    .andExpect(jsonPath("$.status").value("REJECTED"));
            var preview =
                    json.readTree(
                            mvc.perform(
                                            get(path + "/submission-preview")
                                                    .session(owner)
                                                    .param("recordVersion", "3"))
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsByteArray());
            var body =
                    json.createObjectNode()
                            .put("recordVersion", 3)
                            .put("confirmationToken", preview.path("confirmationToken").asText());
            mvc.perform(policyRequest(post(path + "/submit"), owner).content(body.toString()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("POLICY_CONFIRMATION_REQUIRED"));
            var scope = preview.path("applicability").path("scopes").get(0);
            var decision =
                    body.putArray("decisions")
                            .addObject()
                            .put("through", scope.path("segments").get(0).path("through").asText());
            decision.set("scope", scope.path("scope"));
            decision.put("choice", "RETURN_TO_EDIT");
            mvc.perform(policyRequest(post(path + "/submit"), owner).content(body.toString()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("POLICY_RETURN_TO_EDIT"));
            decision.put("choice", "CHOOSE_ONE").put("policyId", "999999999");
            mvc.perform(policyRequest(post(path + "/submit"), owner).content(body.toString()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("POLICY_CONFIRMATION_REQUIRED"));
            decision.put("policyId", approved);
            mvc.perform(policyRequest(post(path + "/submit"), owner).content(body.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.submissionVersion").value(2));
            mvc.perform(reviewRound(pending, 4, 2, "APPROVE")).andExpect(status().isOk());
            var audits =
                    jdbc.queryForList(
                            "SELECT snapshot_data FROM policy_audit_log WHERE policy_id=? ORDER BY version",
                            String.class,
                            pending);
            assertThat(audits).hasSize(2);
            assertThat(json.readTree(audits.get(0)).path("consent").path("decisions").size())
                    .isZero();
            assertThat(
                            json.readTree(audits.get(1))
                                    .path("consent")
                                    .path("decisions")
                                    .get(0)
                                    .path("selectedPolicyIds")
                                    .get(0)
                                    .asText())
                    .isEqualTo(approved);
            for (var good : List.of(white, black))
                mvc.perform(
                                effectiveRequest(
                                        good.path("id").asText(),
                                        "PDD",
                                        "2099-03-15T09:00:00+08:00"))
                        .andExpect(jsonPath("$.policies.length()").value(1))
                        .andExpect(jsonPath("$.policies[*].winner.id").value(contains(approved)));

            var winningGood = first == 200 ? black : white;
            var extra = attachmentPolicyForm(winningGood, UUID.randomUUID().toString());
            extra.remove("attachmentInfo");
            ((ObjectNode) extra.at("/goodsForm/cards/0")).put("cardType", "SPECIFIED");
            extra.put("startTime", "2099-03-10T00:00:00+08:00")
                    .put("endTime", "2099-03-20T00:00:00+08:00");
            String third = createPolicy(owner, extra.toString()).path("id").asText();
            mvc.perform(confirmedSubmission("/api/policies/" + third, owner, 1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reviewStage").value("PRODUCT_REVIEW"));
            approveProduct(third, 2, 1);
            mvc.perform(reviewRound(third, 3, 1, "APPROVE")).andExpect(status().isOk());
            mvc.perform(
                            effectiveRequest(
                                    black.path("id").asText(), "PDD", "2099-03-20T00:00:00+08:00"))
                    .andExpect(
                            jsonPath("$.policies[*].winner.id").value(contains(third, approved)));
            mvc.perform(
                            effectiveRequest(
                                    black.path("id").asText(), "PDD", "2099-03-20T00:00:01+08:00"))
                    .andExpect(jsonPath("$.policies[*].winner.id").value(contains(approved)));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void retailSelectionsAreSavedPerPeriodAndRestoredAfterEachOverlap() throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "retail-segments", "APPLICANT");
        assignPlatforms("retail-segments", "RETAIL:PDD");
        var form = attachmentPolicyForm(good, UUID.randomUUID().toString());
        form.remove("attachmentInfo");
        form.put("perspective", "RETAIL").put("estimated", false).put("verifyType", "SALES");
        form.putArray("sceneTypes").add("NATIONAL_SUB").add("LOCAL_SUB");
        var ids = new ArrayList<String>();
        for (var dates : List.of(List.of("01", "30"), List.of("10", "20"), List.of("15", "25"))) {
            form.put("startTime", "2099-06-" + dates.get(0) + "T00:00:00+08:00")
                    .put("endTime", "2099-06-" + dates.get(1) + "T11:00:00+08:00");
            if (!ids.isEmpty()) form.putArray("sceneTypes").add("NATIONAL_SUB");
            if (ids.size() == 2) form.putArray("sceneTypes").add("SELF_SUB");
            var id = createPolicy(owner, form.toString()).path("id").asText();
            var request =
                    confirmedSubmission(
                            "/api/policies/" + id,
                            owner,
                            1,
                            (segment, row) -> {
                                if (ids.size() != 2) return;
                                var selected = row.putArray("selectedPolicyIds");
                                if (segment.path("through").asText().startsWith("2099-06-20")) {
                                    ids.forEach(selected::add);
                                } else selected.add(id);
                            });
            mvc.perform(request).andExpect(status().isOk());
            if (!ids.isEmpty()) approveProduct(id, 2, 1);
            mvc.perform(reviewRound(id, ids.isEmpty() ? 2 : 3, 1, "APPROVE"))
                    .andExpect(status().isOk());
            ids.add(id);
        }
        var expected =
                java.util.Map.of(
                        "01",
                        List.of(ids.get(0)),
                        "10",
                        List.of(ids.get(0), ids.get(1)),
                        "20",
                        List.of(ids.get(0), ids.get(1)),
                        "21",
                        List.of(ids.get(2)),
                        "26",
                        List.of(ids.get(0)));
        // 只查首日也展示这张原单完整期间的选择，后面两份获批单作对照。
        var track =
                json.readTree(
                                mvc.perform(
                                                timelineRequest(good, "2099-06-01", "2099-06-01")
                                                        .param("platformCode", "PDD")
                                                        .session(admin))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsByteArray())
                        .at("/tracks/0");
        var policyIds = new java.util.HashMap<String, String>();
        track.path("policies")
                .forEach(
                        p ->
                                policyIds.put(
                                        p.path("auditLogId").asText(),
                                        p.path("policyId").asText()));
        assertThat(track.path("segments").size()).isZero();
        for (var day : expected.entrySet()) {
            String at = "2099-06-" + day.getKey() + "T11:00:00+08:00";
            var plan =
                    java.util.stream.StreamSupport.stream(
                                    track.path("plannedSegments").spliterator(), false)
                            .filter(
                                    s ->
                                            s.path("startAt").asText().compareTo(at) <= 0
                                                    && s.path("endAt").asText().compareTo(at) > 0)
                            .findFirst()
                            .orElseThrow();
            var planned = new ArrayList<String>();
            plan.path("adoptedAuditLogIds").forEach(a -> planned.add(policyIds.get(a.asText())));
            assertThat(planned).containsExactlyInAnyOrderElementsOf(day.getValue());
            if ("20".equals(day.getKey())) {
                assertThat(plan.path("excludedAuditLogIds").size()).isOne();
                assertThat(policyIds.get(plan.path("excludedAuditLogIds").get(0).asText()))
                        .isEqualTo(ids.get(2));
            }
            var result =
                    mvc.perform(
                                    get("/api/admin/policies/effective")
                                            .session(admin)
                                            .param("perspective", "RETAIL")
                                            .param("goodsId", good.path("id").asText())
                                            .param("platformCode", "PDD")
                                            .param("at", at))
                            .andExpect(status().isOk())
                            .andReturn();
            var policies =
                    json.readTree(result.getResponse().getContentAsByteArray()).path("policies");
            var actual = new ArrayList<String>();
            policies.forEach(p -> actual.add(p.path("winner").path("id").asText()));
            assertThat(actual).containsExactlyInAnyOrderElementsOf(day.getValue());
        }
    }

    @Test
    void modelSummaryAndTimelineReadApprovedSnapshotsForAdminsIncludingOffSaleGoods()
            throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "timeline-owner", "APPLICANT");
        assignPlatforms("timeline-owner", "BILLION:PDD");
        var id =
                coveragePolicy(
                        owner, good, "2020-01-01", "2099-12-31", "2020-01-01 08:00:00", false);
        mvc.perform(timelineRequest(good, "2020-01-01", "2099-12-31").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configurationGoodsCount").value(1))
                .andExpect(jsonPath("$.tracks").isEmpty());
        mvc.perform(reviewRound(id, 2, 1, "APPROVE")).andExpect(status().isOk());
        jdbc.update("UPDATE goods SET status=4 WHERE id=?", good.path("id").asLong());
        mvc.perform(get(GOODS + "/summary").session(admin))
                .andExpect(jsonPath("$.modelConfigurations").doesNotExist());
        mvc.perform(
                        get(GOODS + "/summary")
                                .session(admin)
                                .param("includeModelConfigurations", "true"))
                .andExpect(jsonPath("$.modelConfigurations.length()").value(1))
                .andExpect(
                        jsonPath("$.modelConfigurations[0].spuId")
                                .value(good.path("spuId").asText()))
                .andExpect(
                        jsonPath("$.modelConfigurations[0].ram").value(good.path("ram").asText()))
                .andExpect(
                        jsonPath("$.modelConfigurations[0].rom").value(good.path("rom").asText()));
        var history =
                json.readTree(
                        mvc.perform(
                                        timelineRequest(good, "2020-01-01", "2099-12-31")
                                                .session(admin))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.tracks.length()").value(1))
                                .andExpect(jsonPath("$.tracks[0].perspective").value("BILLION"))
                                .andExpect(jsonPath("$.configurationGoodsCount").value(1))
                                .andExpect(jsonPath("$.tracks[0].policies[0].policyId").value(id))
                                .andExpect(
                                        jsonPath(
                                                        "$.tracks[0].policies[0].selections[0].subsidy.fixedAmount")
                                                .value("0.10"))
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        var track = history.path("tracks").get(0);
        var auditId = track.path("policies").get(0).path("auditLogId").asText();
        var planned = track.path("plannedSegments").get(0);
        assertThat(Instant.parse(planned.path("startAt").asText()))
                .isEqualTo(Instant.parse(history.path("asOf").asText()).plusSeconds(1));
        assertThat(planned.path("adoptedAuditLogIds").get(0).asText()).isEqualTo(auditId);
        assertThat(track.path("segments").get(0).path("excludedAuditLogIds").get(0).asText())
                .isEqualTo(auditId);
        assertThat(track.path("segments").get(1).path("adoptedAuditLogIds").get(0).asText())
                .isEqualTo(auditId);
        mvc.perform(
                        effectiveRequest(
                                good.path("id").asText(), "PDD", history.path("asOf").asText()))
                .andExpect(jsonPath("$.policies[0].winner.auditLogId").value(auditId));
        mvc.perform(
                        timelineRequest(good, "2020-01-01", "2099-12-31")
                                .session(admin)
                                .param("platformCode", "JD"))
                .andExpect(jsonPath("$.tracks").isEmpty());
        mvc.perform(timelineRequest(good, "2099-12-01", "2099-12-31").session(admin))
                .andExpect(jsonPath("$.tracks[0].policies[0].policyId").value(id))
                .andExpect(jsonPath("$.tracks[0].segments").isEmpty());
        mvc.perform(timelineRequest(good, "2026-02-28", "2026-01-01").session(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(timelineRequest(good, "2026-02-30", "2026-03-01").session(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        timelineRequest(good, "2026-01-01", "2026-01-01")
                                .session(admin)
                                .param("platformCode", "OTHER"))
                .andExpect(status().isBadRequest());
        mvc.perform(timelineRequest(good, "2020-01-01", "2099-12-31").session(owner))
                .andExpect(status().isForbidden());
        mvc.perform(
                        timelineRequest(good, "2020-01-01", "2099-12-31")
                                .session(session("other-tenant", "timeline-external", "ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(timelineRequest(good, "2020-01-01", "2099-12-31"))
                .andExpect(status().isUnauthorized());
        jdbc.update("UPDATE policy_apply SET is_deleted=1 WHERE id=?", id);
        mvc.perform(timelineRequest(good, "2020-01-01", "2099-12-31").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tracks").isEmpty());
    }

    @Test
    void applicantTimelineFollowsCurrentChannelAssignmentsAcrossAuthorsAndMultiPlatformPolicies()
            throws Exception {
        var good = importedGoodForEditing();
        // 百亿整组提交后新增同配置商品，零售使用指定卡仅申报原商品。
        jdbc.update("INSERT INTO color_dict (color_name) VALUES ('白色')");
        var sibling = (ObjectNode) editBody(good).path("product");
        sibling.put(
                "colorId",
                jdbc.queryForObject(
                        "SELECT id FROM color_dict WHERE color_name='白色'", String.class));
        var author = session("test-tenant", "timeline-author", "APPLICANT");
        var viewer = session("test-tenant", "timeline-reader", "APPLICANT");
        assignPlatforms("timeline-author", "RETAIL:PDD", "RETAIL:TAOBAO", "BILLION:PDD");
        var billionId =
                coveragePolicy(
                        author, good, "2020-01-01", "2099-12-31", "2020-01-01 08:00:00", false);
        mvc.perform(reviewRound(billionId, 2, 1, "APPROVE")).andExpect(status().isOk());

        mvc.perform(mutation(GOODS).content(sibling.toString())).andExpect(status().isCreated());
        var form = attachmentPolicyForm(good, "unused");
        form.remove("attachmentInfo");
        form.put("perspective", "RETAIL").put("estimated", false).put("verifyType", "SALES");
        form.put("startTime", "2020-01-01T09:00:00+08:00");
        form.putArray("sceneTypes").add("NATIONAL_SUB");
        form.withArray("selectedPlatforms").add("TAOBAO");
        var card = (ObjectNode) form.at("/goodsForm/cards/0");
        card.put("cardType", "SPECIFIED");
        card.withArray("subsidyPlans")
                .addObject()
                .put("key", "taobao")
                .put("subsidyMode", "FIXED")
                .put("fixedAmount", "88.88");
        card.withArray("platformAssignments")
                .addObject()
                .put("platformCode", "TAOBAO")
                .put("planKey", "taobao");
        var retailId = createPolicy(author, form.toString()).path("id").asText();
        mvc.perform(confirmedSubmission("/api/policies/" + retailId, author, 1))
                .andExpect(status().isOk());
        mvc.perform(reviewRound(retailId, 2, 1, "APPROVE")).andExpect(status().isOk());

        assignPlatforms("timeline-reader", "RETAIL:PDD");
        var history =
                json.readTree(
                        mvc.perform(applicantTimelineRequest(good).session(viewer))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.tracks.length()").value(1))
                                .andExpect(jsonPath("$.tracks[0].perspective").value("RETAIL"))
                                .andExpect(jsonPath("$.tracks[0].platformCode").value("PDD"))
                                .andExpect(jsonPath("$.configurationGoodsCount").value(2))
                                .andExpect(
                                        jsonPath("$.tracks[0].goodsIds")
                                                .value(contains(good.path("id").asText())))
                                .andExpect(
                                        jsonPath("$.tracks[0].policies[*].policyId")
                                                .value(contains(retailId)))
                                .andExpect(
                                        jsonPath(
                                                        "$.tracks[0].policies[0].selections[*].subsidy.fixedAmount")
                                                .value(contains("0.10")))
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        var track = history.at("/tracks/0");
        var auditId = track.at("/policies/0/auditLogId").asText();
        for (String period : List.of("segments", "plannedSegments")) {
            var adopted = new ArrayList<String>();
            for (var segment : track.path(period)) {
                segment.path("adoptedAuditLogIds").forEach(id -> adopted.add(id.asText()));
                segment.path("excludedAuditLogIds")
                        .forEach(id -> assertThat(id.asText()).isEqualTo(auditId));
            }
            assertThat(adopted).containsOnly(auditId);
        }
        mvc.perform(applicantTimelineRequest(good).session(viewer).param("platformCode", "PDD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tracks[*].perspective").value(contains("RETAIL")));
        mvc.perform(
                        applicantTimelineRequest(good)
                                .session(viewer)
                                .param("platformCode", "PDD")
                                .param("perspective", "BILLION"))
                .andExpect(status().isForbidden());
        mvc.perform(applicantTimelineRequest(good).session(viewer).param("platformCode", "TAOBAO"))
                .andExpect(status().isForbidden());
        mvc.perform(timelineRequest(good, "2020-01-01", "2099-12-31").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tracks.length()").value(3));

        // 同一登录会话先增加同名平台的另一个渠道，再逐项移回原负责人。
        assignPlatforms("timeline-reader", "BILLION:PDD");
        mvc.perform(applicantTimelineRequest(good).session(viewer).param("platformCode", "PDD"))
                .andExpect(jsonPath("$.tracks.length()").value(2));
        mvc.perform(
                        applicantTimelineRequest(good)
                                .session(viewer)
                                .param("platformCode", "PDD")
                                .param("perspective", "BILLION"))
                .andExpect(jsonPath("$.tracks[*].policies[*].policyId").value(contains(billionId)));
        assignPlatforms("timeline-author", "RETAIL:PDD");
        mvc.perform(applicantTimelineRequest(good).session(viewer))
                .andExpect(jsonPath("$.tracks[*].perspective").value(contains("BILLION")));
        assignPlatforms("timeline-author", "BILLION:PDD");
        mvc.perform(applicantTimelineRequest(good).session(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configurationGoodsCount").value(0))
                .andExpect(jsonPath("$.tracks").isEmpty());
        mvc.perform(applicantTimelineRequest(good).session(viewer).param("platformCode", "PDD"))
                .andExpect(status().isForbidden());
    }

    @Test
    void applicantSubsidyReadsRequireAnActiveApplicantAndIncludeOffSaleConfigurations()
            throws Exception {
        var good = importedGoodForEditing();
        var viewer = session("test-tenant", "timeline-catalogue", "APPLICANT");
        assignPlatforms("timeline-catalogue", "RETAIL:JD");
        jdbc.update("UPDATE goods SET status=4 WHERE id=?", good.path("id").asLong());
        String options = "/api/catalog/subsidy-options";
        mvc.perform(get(options).session(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configurations[0].spuId").value(good.path("spuId").asText()))
                .andExpect(jsonPath("$.configurations[0].ram").value(good.path("ram").asText()))
                .andExpect(jsonPath("$.configurations[0].rom").value(good.path("rom").asText()));
        mvc.perform(get("/api/catalog/options").session(viewer))
                .andExpect(jsonPath("$.configurations").isEmpty());
        var external = session("other-tenant", "timeline-catalogue-external", "APPLICANT");
        for (var request : List.of(get(options), applicantTimelineRequest(good))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
            mvc.perform(request.session(admin)).andExpect(status().isForbidden());
            mvc.perform(request.session(external)).andExpect(status().isForbidden());
        }
        jdbc.update("UPDATE user_info SET is_deleted=1 WHERE open_id='timeline-catalogue'");
        mvc.perform(get(options).session(viewer)).andExpect(status().isForbidden());
        mvc.perform(applicantTimelineRequest(good).session(viewer))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvedFuturePolicyAppearsInFutureAndMixedQueriesWithoutAdoptionHistory()
            throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "scheduled-owner", "APPLICANT");
        assignPlatforms("scheduled-owner", "BILLION:PDD");
        var today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var month = today.plusMonths(1).withDayOfMonth(1);
        var form = attachmentPolicyForm(good, "unused");
        form.remove("attachmentInfo");
        form.put("startTime", month.plusDays(1) + "T00:00:00+08:00")
                .put("endTime", month.plusDays(4) + "T00:00:00+08:00");
        var plan = (ObjectNode) form.at("/goodsForm/cards/0/subsidyPlans/0");
        plan.put("fixedAmount", "100.00").put("maxSubsidyAmount", "200.00");
        var id = createPolicy(owner, form.toString()).path("id").asText();
        mvc.perform(confirmedSubmission("/api/policies/" + id, owner, 1))
                .andExpect(status().isOk());
        var end = month.plusDays(23).toString();
        mvc.perform(timelineRequest(good, month.toString(), end).session(admin))
                .andExpect(jsonPath("$.tracks").isEmpty());
        mvc.perform(reviewRound(id, 2, 1, "APPROVE")).andExpect(status().isOk());
        for (var start :
                List.of(
                        month.toString(),
                        today.minusDays(1).toString(),
                        month.plusDays(4).toString())) {
            mvc.perform(
                            timelineRequest(good, start, end)
                                    .param("platformCode", "PDD")
                                    .session(admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tracks.length()").value(1))
                    .andExpect(jsonPath("$.tracks[0].perspective").value("BILLION"))
                    .andExpect(jsonPath("$.tracks[0].policies[0].policyId").value(id))
                    .andExpect(jsonPath("$.tracks[0].policies[0].matchesQuery").value(true))
                    .andExpect(
                            jsonPath(
                                            "$.tracks[0].policies[0].selections[0].subsidy.maxSubsidyAmount")
                                    .value("200.00"))
                    .andExpect(jsonPath("$.tracks[0].segments").isEmpty())
                    .andExpect(
                            jsonPath("$.tracks[0].plannedSegments[0].startAt")
                                    .value(month.plusDays(1) + "T00:00:00+08:00"))
                    .andExpect(
                            jsonPath("$.tracks[0].plannedSegments[0].endAt")
                                    .value(month.plusDays(4) + "T00:00:01+08:00"))
                    .andExpect(
                            jsonPath("$.tracks[0].plannedSegments[0].adoptedAuditLogIds.length()")
                                    .value(1))
                    .andExpect(
                            jsonPath("$.tracks[0].plannedSegments[0].excludedAuditLogIds")
                                    .isEmpty());
        }
        mvc.perform(timelineRequest(good, month.plusDays(5).toString(), end).session(admin))
                .andExpect(jsonPath("$.tracks").isEmpty());
        mvc.perform(
                        timelineRequest(good, month.toString(), end)
                                .param("platformCode", "JD")
                                .session(admin))
                .andExpect(jsonPath("$.tracks").isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"RETAIL", "BILLION"})
    void approvedAdjustmentEndsTheEntirePolicyAcrossProductsAndPlatforms(String channel)
            throws Exception {
        var good = importedGoodForEditing();
        jdbc.update("INSERT INTO color_dict (color_name) VALUES ('浅青色')");
        var product = (ObjectNode) editBody(good).path("product");
        product.put(
                "colorId",
                jdbc.queryForObject(
                        "SELECT id FROM color_dict WHERE color_name='浅青色'", String.class));
        var other =
                json.readTree(
                        mvc.perform(mutation(GOODS).content(product.toString()))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        String applicant = "cutoff-" + channel;
        var owner = session("test-tenant", applicant, "APPLICANT");
        assignPlatforms(applicant, channel + ":PDD", channel + ":TAOBAO");
        var form = attachmentPolicyForm(good, "unused");
        form.remove("attachmentInfo");
        form.put("perspective", channel);
        if ("RETAIL".equals(channel)) {
            form.put("estimated", false).put("verifyType", "SALES");
            form.putArray("sceneTypes").add("NATIONAL_SUB");
        }
        form.put("startTime", "2099-03-01T00:00:00+08:00")
                .put("endTime", "2099-03-30T23:59:59+08:00");
        form.withArray("selectedPlatforms").add("TAOBAO");
        var card = (ObjectNode) form.at("/goodsForm/cards/0");
        card.withArray("platformAssignments")
                .addObject()
                .put("platformCode", "TAOBAO")
                .put("planKey", "fixed");
        var targets = card.withArray("targets");
        targets.add(
                ((ObjectNode) targets.get(0)).deepCopy().put("goodsId", other.path("id").asText()));
        String prior = createPolicy(owner, form.toString()).path("id").asText();
        mvc.perform(confirmedSubmission("/api/policies/" + prior, owner, 1))
                .andExpect(status().isOk());
        mvc.perform(reviewRound(prior, 2, 1, "APPROVE")).andExpect(status().isOk());
        form.withArray("selectedPlatforms").remove(1);
        card.withArray("platformAssignments").remove(1);
        targets.remove(1);
        card.put("cardType", "SPECIFIED");
        form.put("startTime", "2099-03-04T00:00:00+08:00")
                .put("endTime", "2099-03-09T23:59:59+08:00");
        if ("RETAIL".equals(channel)) form.putArray("sceneTypes").add("LOCAL_SUB");
        String id = createPolicy(owner, form.toString()).path("id").asText();
        String path = "/api/policies/" + id;
        var initial =
                json.readTree(
                        mvc.perform(
                                        get(path + "/submission-preview")
                                                .session(owner)
                                                .param("recordVersion", "1"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        assertThat(initial.at("/applicability/adjustmentOptions").size()).isOne();
        var body = json.createObjectNode().put("recordVersion", 1);
        var change =
                body.putArray("endAdjustments")
                        .addObject()
                        .put("endTime", "2099-03-06T14:15:16+08:00")
                        .put("reason", "  ");
        change.set("target", initial.at("/applicability/adjustmentOptions/0/target"));
        mvc.perform(
                        policyRequest(post(path + "/submission-preview"), owner)
                                .content(body.toString()))
                .andExpect(status().isBadRequest());
        change.put("reason", "原活动整单结束");
        var target = (ObjectNode) change.path("target");
        var validGoodsId = good.path("id").asText();
        target.put("policyId", id);
        mvc.perform(
                        policyRequest(post(path + "/submission-preview"), owner)
                                .content(body.toString()))
                .andExpect(status().isConflict());
        target.put("policyId", prior);
        change.put("endTime", target.path("previousEnd").asText());
        mvc.perform(
                        policyRequest(post(path + "/submission-preview"), owner)
                                .content(body.toString()))
                .andExpect(status().isBadRequest());
        change.put("endTime", "2099-03-06T14:15:16+08:00");
        var adjusted =
                json.readTree(
                        mvc.perform(
                                        policyRequest(post(path + "/submission-preview"), owner)
                                                .content(body.toString()))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        addDecisions(body, adjusted, (segment, row) -> {});
        body.put("confirmationToken", adjusted.path("confirmationToken").asText());
        mvc.perform(policyRequest(post(path + "/submit"), owner).content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"));
        // 两张待审单基于相同的旧截止，批准第一张后第二张必须重提。
        String secondId = createPolicy(owner, form.toString()).path("id").asText();
        String secondPath = "/api/policies/" + secondId;
        var secondBody = json.createObjectNode().put("recordVersion", 1);
        secondBody.set("endAdjustments", body.path("endAdjustments").deepCopy());
        var secondPreview =
                json.readTree(
                        mvc.perform(
                                        policyRequest(
                                                        post(secondPath + "/submission-preview"),
                                                        owner)
                                                .content(secondBody.toString()))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        secondBody.put("confirmationToken", secondPreview.path("confirmationToken").asText());
        addDecisions(secondBody, secondPreview, (segment, row) -> {});
        mvc.perform(
                        policyRequest(post(secondPath + "/submit"), owner)
                                .content(secondBody.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStage").value("PURCHASE_REVIEW"));
        var at = "2099-03-06T14:15:17+08:00";
        mvc.perform(get(path).session(owner))
                .andExpect(jsonPath("$.endAdjustments[0].reason").value("原活动整单结束"));
        mvc.perform(get("/api/admin/policies/" + id).session(admin))
                .andExpect(
                        jsonPath("$.snapshot.consent.endAdjustments[0].target.previousEnd")
                                .value("2099-03-30T23:59:59+08:00"));
        assertThat(effectiveIds(channel, validGoodsId, "PDD", at)).containsExactly(prior);
        mvc.perform(reviewRound(id, 2, 1, "APPROVE")).andExpect(status().isOk());
        assertThat(effectiveIds(channel, validGoodsId, "PDD", "2099-03-06T14:15:16+08:00"))
                .contains(prior, id);
        assertThat(effectiveIds(channel, validGoodsId, "PDD", at)).containsExactly(id);
        mvc.perform(reviewRound(secondId, 2, 1, "APPROVE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_RECONFIRM_REQUIRED"));
        mvc.perform(reviewRound(secondId, 2, 1, "REJECT")).andExpect(status().isOk());
        String afterOwnerEnds = "2099-03-11T00:00:00+08:00";
        assertThat(effectiveIds(channel, validGoodsId, "PDD", afterOwnerEnds)).isEmpty();
        assertThat(effectiveIds(channel, validGoodsId, "TAOBAO", afterOwnerEnds)).isEmpty();
        assertThat(effectiveIds(channel, other.path("id").asText(), "PDD", afterOwnerEnds))
                .isEmpty();
        mvc.perform(get("/api/policies/" + prior).session(owner))
                .andExpect(jsonPath("$.timeHistory.currentEnd").value("2099-03-06T14:15:16+08:00"));
        mvc.perform(get("/api/admin/policies/" + prior).session(admin))
                .andExpect(jsonPath("$.timeHistory.changes[0].reason").value("原活动整单结束"));
        var available =
                json.readTree(
                        mvc.perform(get("/api/policies/time-adjustments/options").session(owner))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        var options = available.valueStream().toList();
        assertThat(options).extracting(o -> o.at("/target/policyId").asText()).contains(prior);
        var receipt =
                options.stream()
                        .filter(
                                o ->
                                        "2099-03-06T14:15:16+08:00"
                                                .equals(o.at("/target/previousEnd").asText()))
                        .findFirst()
                        .orElseThrow();
        String receiptId = receipt.at("/target/policyId").asText();
        assertThat(receiptId).isEqualTo(prior);
        assertThat(receipt.path("canExtend").asBoolean()).isTrue();
        var timeline =
                json.readTree(
                        mvc.perform(
                                        timelineRequest(good, "2099-03-11", "2099-03-11")
                                                .session(admin)
                                                .param("platformCode", "PDD"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        assertThat(timeline.path("tracks").isEmpty()).isTrue();
        var otherPlatform =
                json.readTree(
                        mvc.perform(
                                        timelineRequest(good, "2099-03-06", "2099-03-06")
                                                .session(admin)
                                                .param("platformCode", "TAOBAO"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        assertThat(otherPlatform.at("/tracks/0/policies/0/policyThrough").asText())
                .isEqualTo("2099-03-30T23:59:59+08:00");
        assertThat(otherPlatform.at("/tracks/0/policies/0/effectiveThrough").asText())
                .isEqualTo("2099-03-06T14:15:16+08:00");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_time_change WHERE policy_id=?",
                                Integer.class,
                                prior))
                .isOne();
    }

    @Test
    void applicantCanEndAnUnrelatedApprovedPolicyWithinAssignedPlatforms() throws Exception {
        var oldGood = importedGoodForEditing();
        var newProduct = (ObjectNode) editBody(oldGood).path("product");
        newProduct.put("modelName", "另一款测试机型");
        var newGood =
                json.readTree(
                        mvc.perform(mutation(GOODS).content(newProduct.toString()))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        assertThat(newGood.path("spuId")).isNotEqualTo(oldGood.path("spuId"));

        var formerOwner = session("test-tenant", "previous-policy-owner", "APPLICANT");
        assignPlatforms("previous-policy-owner", "BILLION:PDD", "BILLION:TAOBAO");
        var prior =
                coveragePolicy(
                        formerOwner,
                        oldGood,
                        "2099-03-01",
                        "2099-03-09",
                        "2026-01-01 12:00:00",
                        true);
        mvc.perform(reviewRound(prior, 2, 1, "APPROVE")).andExpect(status().isOk());
        var applicant = session("test-tenant", "new-policy-owner", "APPLICANT");
        assignPlatforms("new-policy-owner", "BILLION:PDD", "RETAIL:JD");
        var form = attachmentPolicyForm(newGood, "unused");
        form.remove("attachmentInfo");
        form.put("perspective", "RETAIL").put("verifyType", "SALES").put("estimated", false);
        form.putArray("sceneTypes").add("NATIONAL_SUB");
        form.putArray("selectedPlatforms").add("JD");
        ((ObjectNode) form.at("/goodsForm/cards/0/platformAssignments/0"))
                .put("platformCode", "JD");
        form.put("startTime", "2099-04-01T00:00:00+08:00")
                .put("endTime", "2099-04-03T23:59:59+08:00");
        var id = createPolicy(applicant, form.toString()).path("id").asText();
        var path = "/api/policies/" + id;
        var preview =
                get(path + "/submission-preview").session(applicant).param("recordVersion", "1");
        var result =
                mvc.perform(preview)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.applicability.scopes").isEmpty())
                        .andExpect(jsonPath("$.applicability.status").value("DIRECT"))
                        .andExpect(jsonPath("$.applicability.adjustmentOptions.length()").value(1))
                        .andExpect(
                                jsonPath("$.applicability.adjustmentOptions[0].target.policyId")
                                        .value(prior))
                        .andReturn();
        var option =
                json.readTree(result.getResponse().getContentAsByteArray())
                        .at("/applicability/adjustmentOptions/0");
        var end = "2099-03-06T14:15:16+08:00";
        var afterEnd = "2099-03-06T14:15:17+08:00";
        var body = json.createObjectNode().put("recordVersion", 1);
        body.putArray("endAdjustments")
                .addObject()
                .put("endTime", end)
                .put("reason", "百亿活动结束，与本次零售申请独立")
                .set("target", option.path("target"));
        var recalculate =
                policyRequest(post(path + "/submission-preview"), applicant)
                        .content(body.toString());

        // 旧单作者移交负责平台后，接受方可以发起；移走最后一个命中平台立即阻止重用旧选项。
        assignPlatforms("previous-policy-owner", "BILLION:PDD");
        mvc.perform(preview).andExpect(jsonPath("$.applicability.adjustmentOptions").isEmpty());
        mvc.perform(recalculate)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_ADJUSTMENT_CHANGED"));
        assignPlatforms("new-policy-owner", "BILLION:PDD");
        var adjusted =
                mvc.perform(recalculate)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.applicability.scopes").isEmpty())
                        .andExpect(jsonPath("$.applicability.status").value("DIRECT"))
                        .andReturn();
        body.put(
                "confirmationToken",
                json.readTree(adjusted.getResponse().getContentAsByteArray())
                        .path("confirmationToken")
                        .asText());
        body.putArray("decisions");
        mvc.perform(policyRequest(post(path + "/submit"), applicant).content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        var route = workflowRoute(id);
        assertThat(route.requiresProductReview()).isFalse();
        assertThat(route.triggerSegments()).isZero();
        assertThat(route.summary()).contains("采购直审", "提前至", option.path("policyCode").asText());
        assertThat(route.adjustments())
                .singleElement()
                .satisfies(
                        adjustment -> {
                            assertThat(adjustment.target().policyId()).isEqualTo(prior);
                            assertThat(adjustment.endTime()).isEqualTo(end);
                        });
        var oldId = oldGood.path("id").asText();
        assertThat(effectiveIds("BILLION", oldId, "TAOBAO", afterEnd)).containsExactly(prior);
        mvc.perform(reviewRound(id, 2, 1, "APPROVE")).andExpect(status().isOk());
        assertThat(effectiveIds("BILLION", oldId, "TAOBAO", end)).containsExactly(prior);
        assertThat(effectiveIds("BILLION", oldId, "TAOBAO", afterEnd)).isEmpty();
        assertThat(
                        effectiveIds(
                                "RETAIL",
                                newGood.path("id").asText(),
                                "JD",
                                "2099-04-02T12:00:00+08:00"))
                .containsExactly(id);
    }

    @Test
    void accompanyingRetailExtensionContinuesOneSelectionThroughTheOriginalDeadline()
            throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "retail-continuation-owner", "APPLICANT");
        assignPlatforms("retail-continuation-owner", "RETAIL:PDD");
        var form = attachmentPolicyForm(good, "unused");
        form.remove("attachmentInfo");
        var start = "2099-01-01T00:00:00+08:00";
        var oldEnd = "2099-01-10T00:00:00+08:00";
        var continuation = "2099-01-10T00:00:01+08:00";
        var mainEnd = "2099-01-13T00:00:00+08:00";
        var extendedEnd = "2099-01-14T00:00:00+08:00";
        form.put("perspective", "RETAIL").put("estimated", false).put("verifyType", "ACTIVATION");
        form.putArray("sceneTypes").add("NATIONAL_SUB");
        form.put("startTime", start).put("endTime", oldEnd);
        var original = createPolicy(owner, form.toString()).path("id").asText();
        mvc.perform(confirmedSubmission("/api/policies/" + original, owner, 1))
                .andExpect(status().isOk());
        mvc.perform(reviewRound(original, 2, 1, "REJECT")).andExpect(status().isOk());
        mvc.perform(confirmedSubmission("/api/policies/" + original, owner, 3))
                .andExpect(status().isOk());
        mvc.perform(reviewRound(original, 4, 2, "APPROVE")).andExpect(status().isOk());

        form.put("verifyType", "SALES").put("endTime", mainEnd);
        var parent = createPolicy(owner, form.toString()).path("id").asText();
        var path = "/api/policies/" + parent;
        var response =
                mvc.perform(
                                get(path + "/submission-preview")
                                        .session(owner)
                                        .param("recordVersion", "1"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        var target =
                json.readTree(response.getContentAsByteArray())
                        .at("/applicability/adjustmentOptions/0/target");
        var body = json.createObjectNode().put("recordVersion", 1);
        var adjustment = body.putArray("endAdjustments").addObject().put("reason", "活动改期");
        adjustment.set("target", target);
        var shortened = "2099-01-07T00:00:00+08:00";
        adjustment.put("endTime", shortened);
        mvc.perform(
                        policyRequest(post(path + "/submission-preview"), owner)
                                .content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicability.scopes[0].segments.length()").value(1))
                .andExpect(
                        jsonPath("$.applicability.scopes[0].segments[0].through").value(shortened));

        adjustment.put("endTime", extendedEnd);
        var result =
                mvc.perform(
                                policyRequest(post(path + "/submission-preview"), owner)
                                        .content(body.toString()))
                        .andExpect(status().isOk())
                        .andReturn();
        var preview = json.readTree(result.getResponse().getContentAsByteArray());
        var segments = preview.at("/applicability/scopes/0/segments");
        assertThat(
                        preview.at("/applicability/scopes/0/policies")
                                .valueStream()
                                .filter(
                                        p ->
                                                p.path("policyId")
                                                        .asText()
                                                        .equals(
                                                                "extension:"
                                                                        + parent
                                                                        + ":"
                                                                        + original))
                                .toList())
                .singleElement()
                .satisfies(p -> assertThat(p.path("submissionVersion").asInt()).isEqualTo(2));
        assertThat(segments.size()).isEqualTo(2);
        assertThat(segments.get(0).path("continuesPrevious").asBoolean()).isFalse();
        assertThat(segments.get(1).path("continuesPrevious").asBoolean()).isTrue();
        assertThat(segments.get(0).path("through").asText()).isEqualTo(oldEnd);
        assertThat(segments.get(1).path("through").asText()).isEqualTo(mainEnd);
        // 界面只勾一次，提交时两个原始范围都选择同一组业务单号。
        body.put("confirmationToken", preview.path("confirmationToken").asText());
        addDecisions(body, preview, (segment, choice) -> {});
        mvc.perform(policyRequest(post(path + "/submit"), owner).content(body.toString()))
                .andExpect(status().isOk());
        approveProduct(parent, 2, 1);
        var review =
                mvc.perform(
                                get("/api/policy-reviews/" + parent)
                                        .param("submissionVersion", "1")
                                        .session(admin))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.changeSummary.triggerSegments").value(1))
                        .andExpect(jsonPath("$.adjustments[0].target.policyId").value(original))
                        .andReturn()
                        .getResponse();
        var detail = json.readTree(review.getContentAsByteArray());
        assertThat(detail.path("changes").toString()).doesNotContain("extension:", "（延期）");
        var solo =
                detail.path("changes")
                        .valueStream()
                        .filter(
                                change ->
                                        change.path("from")
                                                .asText()
                                                .equals("2099-01-13T00:00:01+08:00"))
                        .findFirst()
                        .orElseThrow();
        assertThat(solo.at("/after/0/policyId").asText()).isEqualTo(original);
        assertThat(solo.at("/after/0/submissionVersion").asInt()).isEqualTo(2);
        var stacked =
                detail.path("changes")
                        .valueStream()
                        .filter(change -> change.path("after").size() == 2)
                        .toList();
        assertThat(stacked)
                .singleElement()
                .satisfies(
                        change -> {
                            assertThat(change.path("from").asText()).isEqualTo(start);
                            assertThat(change.path("through").asText()).isEqualTo(mainEnd);
                            assertThat(change.path("after").findValuesAsText("policyId"))
                                    .containsExactlyInAnyOrder(original, parent);
                        });
        assertThat(detail.at("/changeSummary/summary").asText())
                .contains("2099-01-01 00:00:00 至 2099-01-13 00:00:00")
                .doesNotContain("（延期）");
        mvc.perform(reviewRound(parent, 3, 1, "APPROVE")).andExpect(status().isOk());
        mvc.perform(get("/api/policies/" + original).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeHistory.changes.length()").value(1))
                .andExpect(jsonPath("$.timeHistory.changes[0].previousEnd").value(oldEnd))
                .andExpect(jsonPath("$.timeHistory.changes[0].actualEnd").value(extendedEnd))
                .andExpect(jsonPath("$.timeHistory.changes[0].reason").value("活动改期"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_review_delivery WHERE notice_key LIKE ?",
                                Integer.class,
                                "P/" + original + "/EXTENDED/%"))
                .isOne();
        var goodsId = good.path("id").asText();
        for (var point : List.of(start, oldEnd, continuation, mainEnd)) {
            assertThat(effectiveIds("RETAIL", goodsId, "PDD", point))
                    .containsExactlyInAnyOrder(original, parent);
        }
        for (var point : List.of("2099-01-13T00:00:01+08:00", extendedEnd)) {
            assertThat(effectiveIds("RETAIL", goodsId, "PDD", point)).containsExactly(original);
        }
        assertThat(effectiveIds("RETAIL", goodsId, "PDD", "2099-01-14T00:00:01+08:00")).isEmpty();
        mvc.perform(
                        timelineRequest(good, "2099-01-01", "2099-01-14")
                                .session(admin)
                                .param("platformCode", "PDD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tracks[0].policies.length()").value(2))
                .andExpect(jsonPath("$.tracks[0].plannedSegments.length()").value(2))
                .andExpect(
                        jsonPath("$.tracks[0].plannedSegments[0].adoptedAuditLogIds.length()")
                                .value(2))
                .andExpect(
                        jsonPath("$.tracks[0].plannedSegments[1].adoptedAuditLogIds.length()")
                                .value(1));
    }

    @Test
    void batchExtensionRequiresTheApprovedSourcesRoundProductsPlatformsAndPlanReferences()
            throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "extension-source-snapshot", "APPLICANT");
        assignPlatforms("extension-source-snapshot", "BILLION:PDD");
        var source =
                coveragePolicy(
                        owner, good, "2099-04-01", "2099-04-03", "2026-01-01 09:00:00", false);
        mvc.perform(reviewRound(source, 2, 1, "APPROVE")).andExpect(status().isOk());
        var form = attachmentPolicyForm(good, "unused");
        form.remove("attachmentInfo");
        form.put("startTime", "2099-07-01T00:00:00+08:00")
                .put("endTime", "2099-07-02T23:59:59+08:00");
        var parent = createPolicy(owner, form.toString()).path("id").asText();
        var path = "/api/policies/" + parent + "/submission-preview";
        var response =
                mvc.perform(get(path).session(owner).param("recordVersion", "1"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        var target =
                json.readTree(response.getContentAsByteArray())
                        .at("/applicability/adjustmentOptions/0/target");
        var body = json.createObjectNode().put("recordVersion", 1);
        body.putArray("endAdjustments")
                .addObject()
                .put("endTime", "2099-04-08T15:30:00+08:00")
                .put("reason", "原活动延长")
                .set("target", target);
        var preview = policyRequest(post(path), owner).content(body.toString());
        mvc.perform(preview).andExpect(status().isOk());

        var saved =
                (ObjectNode)
                        json.readTree(
                                reviewMapper
                                        .current(Long.parseLong(source), "test-tenant", 1)
                                        .getSnapshotData());
        for (var field :
                List.of("id", "submissionVersion", "targets", "platformAssignments", "planKey")) {
            var damaged = saved.deepCopy();
            var card = (ObjectNode) damaged.at("/form/goodsForm/cards/0");
            switch (field) {
                case "id" -> damaged.put(field, parent);
                case "submissionVersion" -> damaged.put(field, 2);
                case "planKey" ->
                        ((ObjectNode) card.at("/platformAssignments/0")).put(field, "缺失方案");
                default -> card.putArray(field);
            }
            jdbc.update(
                    "UPDATE policy_audit_log SET snapshot_data=? WHERE policy_id=?",
                    damaged.toString(),
                    source);
            mvc.perform(preview)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("POLICY_ADJUSTMENT_CHANGED"));
        }
        // 旧快照可以缺少目录展示信息，按其已确认的商品与平台承接延期。
        saved.remove("goods");
        jdbc.update(
                "UPDATE policy_audit_log SET snapshot_data=? WHERE policy_id=?",
                saved.toString(),
                source);
        mvc.perform(preview).andExpect(status().isOk());
    }

    @Test
    void batchExtensionsConfirmTheParentSiblingsAndOtherApprovedPoliciesInOneTransaction()
            throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "batch-extension-owner", "APPLICANT");
        session("test-tenant", "batch-transfer", "APPLICANT");
        assignPlatforms("batch-extension-owner", "BILLION:PDD", "BILLION:TAOBAO");
        var first =
                coveragePolicy(
                        owner, good, "2099-05-01", "2099-05-03", "2026-01-02 09:00:00", false);
        mvc.perform(reviewRound(first, 2, 1, "APPROVE")).andExpect(status().isOk());
        var second =
                coveragePolicy(
                        owner, good, "2099-05-01", "2099-05-05", "2026-01-03 09:00:00", true);
        approveProduct(second, 2, 1);
        mvc.perform(reviewRound(second, 3, 1, "APPROVE")).andExpect(status().isOk());
        var later =
                coveragePolicy(
                        owner, good, "2099-05-11", "2099-05-31", "2026-01-04 09:00:00", false);
        mvc.perform(reviewRound(later, 2, 1, "APPROVE")).andExpect(status().isOk());
        var form = attachmentPolicyForm(good, "unused");
        form.remove("attachmentInfo");
        form.put("startTime", "2099-05-07T00:00:00+08:00")
                .put("endTime", "2099-05-09T23:59:59+08:00");
        var parent = createPolicy(owner, form.toString()).path("id").asText();
        var path = "/api/policies/" + parent;
        var initial =
                mvc.perform(
                                get(path + "/submission-preview")
                                        .session(owner)
                                        .param("recordVersion", "1"))
                        .andExpect(status().isOk())
                        .andReturn();
        var body = json.createObjectNode().put("recordVersion", 1);
        var changes = body.putArray("endAdjustments");
        for (var option :
                json.readTree(initial.getResponse().getContentAsByteArray())
                        .at("/applicability/adjustmentOptions")) {
            var id = option.at("/target/policyId").asText();
            if (id.equals(later)) {
                changes.addObject()
                        .put("endTime", "2099-05-12T12:30:45+08:00")
                        .put("reason", "缩短五月活动")
                        .set("target", option.path("target"));
                continue;
            }
            assertThat(option.path("canExtend").asBoolean()).isTrue();
            changes.addObject()
                    .put(
                            "endTime",
                            id.equals(first)
                                    ? "2099-05-10T16:17:18+08:00"
                                    : "2099-05-15T19:20:21+08:00")
                    .put("reason", "延长活动 " + id)
                    .set("target", option.path("target"));
        }
        assertThat(changes.size()).isEqualTo(3);
        var result =
                mvc.perform(
                                policyRequest(post(path + "/submission-preview"), owner)
                                        .content(body.toString()))
                        .andExpect(status().isOk())
                        .andReturn();
        var preview = json.readTree(result.getResponse().getContentAsByteArray());
        var secondKey = "extension:" + parent + ":" + second;
        var firstKey = "extension:" + parent + ":" + first;
        var all = new ArrayList<List<String>>();
        for (var scope : preview.at("/applicability/scopes")) {
            for (var segment : scope.path("segments")) {
                var ids = new ArrayList<String>();
                segment.path("eligiblePolicyIds").forEach(node -> ids.add(node.asText()));
                all.add(ids);
            }
        }
        assertThat(all)
                .anySatisfy(
                        ids ->
                                assertThat(ids)
                                        .containsExactlyInAnyOrder(parent, firstKey, secondKey));
        assertThat(all)
                .anySatisfy(ids -> assertThat(ids).containsExactlyInAnyOrder(later, secondKey));
        body.put("confirmationToken", preview.path("confirmationToken").asText());
        mvc.perform(policyRequest(post(path + "/submit"), owner).content(body.toString()))
                .andExpect(status().isConflict());
        addDecisions(
                body,
                preview,
                (segment, choice) -> {
                    var ids = new ArrayList<String>();
                    segment.path("eligiblePolicyIds").forEach(id -> ids.add(id.asText()));
                    if (ids.contains(parent))
                        choice.put("choice", "CHOOSE_ONE").put("policyId", secondKey);
                    if (ids.contains(later))
                        choice.put("choice", "CHOOSE_ONE").put("policyId", later);
                });
        mvc.perform(policyRequest(post(path + "/submit"), owner).content(body.toString()))
                .andExpect(status().isOk());
        var route = workflowRoute(parent);
        assertThat(route.requiresProductReview()).isTrue();
        assertThat(route.adjustments()).hasSize(3);
        assertThat(route.changes())
                .anySatisfy(
                        segment -> {
                            // 第一张开始延期后即与第二张叠加；第二张随后跨过原截止，仍是同一组合。
                            assertThat(segment.from()).isEqualTo("2099-05-03T18:00:01");
                            assertThat(segment.through()).isEqualTo("2099-05-06T23:59:59");
                            assertThat(segment.kind()).isEqualTo(Kind.STACK);
                            assertThat(segment.before())
                                    .extracting(Policy::policyId)
                                    .containsExactly(second);
                            assertThat(segment.after())
                                    .extracting(Policy::policyId)
                                    .containsExactlyInAnyOrder(first, second);
                        });
        assignPlatforms("batch-transfer", "BILLION:TAOBAO");
        approveProduct(parent, 2, 1);
        mvc.perform(reviewRound(parent, 3, 1, "APPROVE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_RECONFIRM_REQUIRED"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_time_change WHERE direction='EXTEND'",
                                Integer.class))
                .isZero();
        assignPlatforms("batch-extension-owner", "BILLION:TAOBAO");
        mvc.perform(reviewRound(parent, 3, 1, "APPROVE")).andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_time_change WHERE direction='EXTEND'",
                                Integer.class))
                .isEqualTo(2);
        mvc.perform(get("/api/policies/" + second).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeHistory.changes[0].reason").value("延长活动 " + second))
                .andExpect(
                        jsonPath("$.timeHistory.changes[0].effectiveAt")
                                .value("2099-05-05T18:00:01+08:00"));
        var goodsId = good.path("id").asText();
        mvc.perform(effectiveRequest(goodsId, "PDD", "2099-05-08T12:00:00+08:00"))
                .andExpect(jsonPath("$.policies[*].winner.id").value(contains(second)));
        mvc.perform(effectiveRequest(goodsId, "PDD", "2099-05-12T12:00:00+08:00"))
                .andExpect(jsonPath("$.policies[*].winner.id").value(contains(later)));
        mvc.perform(effectiveRequest(goodsId, "PDD", "2099-05-12T12:30:46+08:00"))
                .andExpect(jsonPath("$.policies[*].winner.id").value(contains(second)));
        mvc.perform(effectiveRequest(goodsId, "TAOBAO", "2099-05-15T19:20:21+08:00"))
                .andExpect(jsonPath("$.policies[*].winner.id").value(contains(second)));
        mvc.perform(effectiveRequest(goodsId, "TAOBAO", "2099-05-15T19:20:22+08:00"))
                .andExpect(jsonPath("$.policies").isEmpty());
        mvc.perform(
                        timelineRequest(good, "2099-05-14", "2099-05-15")
                                .session(admin)
                                .param("platformCode", "TAOBAO"))
                .andExpect(jsonPath("$.tracks[0].policies[0].policyId").value(second));
    }

    @Test
    void directlyAdjustsMultiplePoliciesOnceAndKeepsTheSelectedExecutionPeriods() throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "direct-adjust-owner", "APPLICANT");
        var other = session("test-tenant", "direct-adjust-other", "APPLICANT");
        assignPlatforms("direct-adjust-owner", "BILLION:PDD");
        var original =
                coveragePolicy(
                        owner, good, "2099-04-01", "2099-04-05", "2099-01-01 09:00:00", false);
        var later =
                coveragePolicy(
                        owner, good, "2099-04-06", "2099-04-20", "2099-01-02 09:00:00", false);
        for (var id : List.of(original, later)) {
            mvc.perform(reviewRound(id, 2, 1, "APPROVE")).andExpect(status().isOk());
        }
        var path = "/api/policies/time-adjustments";
        mvc.perform(get(path + "/options").session(other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        var options =
                json.readTree(
                        mvc.perform(get(path + "/options").session(owner))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        assertThat(options.size()).isEqualTo(2);
        var body = json.createObjectNode();
        var changes = body.putArray("endAdjustments");
        String cutoff = "2099-04-09T16:30:15+08:00";
        for (var option : options) {
            boolean extend = original.equals(option.at("/target/policyId").asText());
            changes.addObject()
                    .put("endTime", extend ? "2099-04-15T21:30:00+08:00" : cutoff)
                    .put("reason", extend ? "延续原补贴" : "渠道活动提前结束")
                    .set("target", option.get("target"));
        }
        var preview =
                json.readTree(
                        mvc.perform(
                                        policyRequest(post(path + "/preview"), owner)
                                                .content(body.toString()))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        assertThat(preview.at("/applicability/scopes").size()).isPositive();
        body.put("confirmationToken", preview.get("confirmationToken").asText());
        String key = UUID.randomUUID().toString();
        mvc.perform(
                        policyRequest(post(path), owner)
                                .header("Idempotency-Key", key)
                                .content(body.toString()))
                .andExpect(status().isConflict());
        addDecisions(
                body,
                preview,
                (segment, choice) -> choice.put("choice", "CHOOSE_ONE").put("policyId", later));

        // 分工在预览后变更，整批回滚。恢复原分工后可继续核对同一批。
        assignPlatforms("direct-adjust-other", "BILLION:PDD");
        var apply =
                policyRequest(post(path), owner)
                        .header("Idempotency-Key", key)
                        .content(body.toString());
        mvc.perform(apply).andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM policy_apply", Integer.class))
                .isEqualTo(2);
        assignPlatforms("direct-adjust-owner", "BILLION:PDD");
        var saved =
                mvc.perform(apply)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].status").value("APPROVED"))
                        .andExpect(jsonPath("$[1].status").value("APPROVED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        mvc.perform(apply).andExpect(status().isOk()).andExpect(content().bytes(saved));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM policy_apply", Integer.class))
                .isEqualTo(2);
        mvc.perform(get("/api/policies/" + original).session(owner))
                .andExpect(jsonPath("$.timeHistory.changes[0].reason").value("延续原补贴"))
                .andExpect(
                        jsonPath("$.timeHistory.changes[0].effectiveAt")
                                .value("2099-04-05T18:00:01+08:00"));
        mvc.perform(get("/api/policies/" + later).session(owner))
                .andExpect(jsonPath("$.timeHistory.currentEnd").value(cutoff))
                .andExpect(jsonPath("$.timeHistory.changes[0].reason").value("渠道活动提前结束"));
        var goodsId = good.path("id").asText();
        assertThat(effectiveIds("BILLION", goodsId, "PDD", cutoff)).containsExactly(later);
        assertThat(effectiveIds("BILLION", goodsId, "PDD", "2099-04-09T16:30:16+08:00"))
                .containsExactly(original);
        for (var row : json.readTree(saved)) {
            var url = "/api/policy-reviews/" + row.get("id").asText();
            mvc.perform(get(url).param("submissionVersion", "1").session(owner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allowedActions").isEmpty());
            mvc.perform(get(url).param("submissionVersion", "1").session(other))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void twoEarlyEndAdjustmentsAreApprovedTogetherWithTheirOwnTimesAndReasons() throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "batch-end-owner", "APPLICANT");
        var other = session("test-tenant", "batch-end-other", "APPLICANT");
        assignPlatforms("batch-end-owner", "BILLION:PDD", "BILLION:TAOBAO");
        var form = attachmentPolicyForm(good, "unused");
        form.remove("attachmentInfo");
        form.put("startTime", "2099-03-01T00:00:00+08:00")
                .put("endTime", "2099-03-31T23:59:59+08:00");

        record Cut(String platform, String end, String reason) {}
        var expected = new java.util.LinkedHashMap<String, Cut>();
        for (var cut :
                List.of(
                        new Cut("PDD", "2099-03-06T14:15:16+08:00", "拼多多活动结束"),
                        new Cut("TAOBAO", "2099-03-08T10:20:30+08:00", "淘宝补贴额度用完"))) {
            form.putArray("selectedPlatforms").add(cut.platform());
            ((ObjectNode) form.at("/goodsForm/cards/0/platformAssignments/0"))
                    .put("platformCode", cut.platform());
            var prior = createPolicy(owner, form.toString()).path("id").asText();
            mvc.perform(confirmedSubmission("/api/policies/" + prior, owner, 1))
                    .andExpect(status().isOk());
            mvc.perform(reviewRound(prior, 2, 1, "APPROVE")).andExpect(status().isOk());
            expected.put(prior, cut);
        }
        form.put("startTime", "2099-04-01T00:00:00+08:00")
                .put("endTime", "2099-04-12T23:59:59+08:00");
        var requestId = createPolicy(owner, form.toString()).path("id").asText();
        var path = "/api/policies/" + requestId;
        var preview =
                mvc.perform(
                                get(path + "/submission-preview")
                                        .param("recordVersion", "1")
                                        .session(owner))
                        .andExpect(status().isOk())
                        .andReturn();
        var options =
                json.readTree(preview.getResponse().getContentAsByteArray())
                        .at("/applicability/adjustmentOptions");
        assertThat(options.size()).isEqualTo(2);
        var body = json.createObjectNode().put("recordVersion", 1);
        var changes = body.putArray("endAdjustments");
        for (var option : options) {
            var target = option.path("target");
            var cut = expected.get(target.path("policyId").asText());
            changes.addObject()
                    .put("endTime", cut.end())
                    .put("reason", cut.reason())
                    .set("target", target);
        }
        var fileId = uploadChangeFile(owner, "活动证明.pdf");
        var otherFile = uploadChangeFile(other, "其他人的证明.pdf");
        var first = (ObjectNode) changes.get(0);
        var attachment = first.putArray("attachmentInfo").addObject();
        attachment.put("type", "FILE").put("id", otherFile);
        mvc.perform(
                        policyRequest(post(path + "/submission-preview"), owner)
                                .content(body.toString()))
                .andExpect(status().isNotFound());
        attachment.put("type", "LINK").put("url", "https://example.org");
        mvc.perform(
                        policyRequest(post(path + "/submission-preview"), owner)
                                .content(body.toString()))
                .andExpect(status().isBadRequest());
        attachment.remove("url");
        attachment.put("type", "FILE").put("id", fileId).put("originalName", "客户端文件名.pdf");
        var recalculated =
                mvc.perform(
                                policyRequest(post(path + "/submission-preview"), owner)
                                        .content(body.toString()))
                        .andExpect(status().isOk())
                        .andReturn();
        body.put(
                "confirmationToken",
                json.readTree(recalculated.getResponse().getContentAsByteArray())
                        .path("confirmationToken")
                        .asText());
        body.putArray("decisions");
        var files = first.remove("attachmentInfo");
        mvc.perform(policyRequest(post(path + "/submit"), owner).content(body.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_COVERAGE_CHANGED"));
        first.set("attachmentInfo", files);
        attachment.put("originalName", "另一份客户端文件名.pdf");
        mvc.perform(policyRequest(post(path + "/submit"), owner).content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get(path).session(owner))
                .andExpect(
                        jsonPath("$.endAdjustments[0].attachmentInfo[0].originalName")
                                .value("活动证明.pdf"))
                .andExpect(jsonPath("$.endAdjustments[1].attachmentInfo").doesNotExist());
        String logId =
                jdbc.queryForObject(
                        "SELECT id FROM policy_audit_log WHERE policy_id=?",
                        String.class,
                        requestId);
        String suffix = "/attachments/" + fileId + "/content";
        String history = "/audit-logs/" + logId + suffix;
        mvc.perform(get(path + suffix).session(owner)).andExpect(status().isOk());
        mvc.perform(get(path + history).session(owner)).andExpect(status().isOk());
        mvc.perform(get(path + history).session(other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/policies/" + requestId + history).session(admin))
                .andExpect(status().isOk());
        mvc.perform(policyRequest(delete("/api/attachments/" + fileId), owner))
                .andExpect(status().isConflict());
        // 一张目标失去授权时，整个待审轮次均不能生效。
        assignPlatforms("batch-end-other", "BILLION:PDD");
        mvc.perform(reviewRound(requestId, 2, 1, "APPROVE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_RECONFIRM_REQUIRED"));
        for (var prior : expected.keySet()) {
            mvc.perform(get("/api/policies/" + prior).session(owner))
                    .andExpect(jsonPath("$.timeHistory.changes").isEmpty());
        }
        assignPlatforms("batch-end-owner", "BILLION:PDD");
        mvc.perform(reviewRound(requestId, 2, 1, "APPROVE")).andExpect(status().isOk());
        for (var entry : expected.entrySet()) {
            var cut = entry.getValue();
            mvc.perform(get("/api/policies/" + entry.getKey()).session(owner))
                    .andExpect(jsonPath("$.timeHistory.currentEnd").value(cut.end()))
                    .andExpect(jsonPath("$.timeHistory.changes[0].reason").value(cut.reason()));
            mvc.perform(get("/api/policies/" + entry.getKey() + suffix).session(owner))
                    .andExpect(status().isNotFound());
        }
        var goodsId = good.path("id").asText();
        var between = "2099-03-07T12:00:00+08:00";
        assertThat(effectiveIds("BILLION", goodsId, "PDD", between)).isEmpty();
        assertThat(effectiveIds("BILLION", goodsId, "TAOBAO", between)).hasSize(1);
    }

    private String uploadChangeFile(MockHttpSession owner, String name) throws Exception {
        var file =
                new MockMultipartFile(
                        "file",
                        name,
                        "application/pdf",
                        "%PDF-1.7".getBytes(StandardCharsets.UTF_8));
        var upload =
                mvc.perform(
                                multipart("/api/attachments")
                                        .file(file)
                                        .session(owner)
                                        .header("Origin", "http://localhost:5173")
                                        .header("X-Policy-Request", "1"))
                        .andExpect(status().isCreated())
                        .andReturn();
        return json.readTree(upload.getResponse().getContentAsByteArray()).path("id").asText();
    }

    private List<String> effectiveIds(String channel, String goods, String platform, String at)
            throws Exception {
        var request =
                get("/api/admin/policies/effective")
                        .session(admin)
                        .param("perspective", channel)
                        .param("platformCode", platform)
                        .param("goodsId", goods)
                        .param("at", at);
        var result =
                json.readTree(
                        mvc.perform(request)
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        var ids = new ArrayList<String>();
        for (var p : result.path("policies")) ids.add(p.at("/winner/id").asText());
        return ids;
    }

    @Test
    void standaloneAdjustmentsAuthorizeProofsAndKeepTheApprovedSnapshot() throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "change-owner", "APPLICANT");
        var other = session("test-tenant", "other-owner", "APPLICANT");
        assignPlatforms("change-owner", "BILLION:PDD");
        var id =
                coveragePolicy(
                        owner, good, "2096-02-01", "2096-02-28", "2026-01-11 00:00:00", false);
        mvc.perform(reviewRound(id, 2, 1, "APPROVE")).andExpect(status().isOk());
        var endpoint = "/api/policies/time-adjustments";
        var options =
                mvc.perform(get(endpoint + "/options").session(owner))
                        .andExpect(status().isOk())
                        .andReturn();
        var target = json.readTree(options.getResponse().getContentAsByteArray()).at("/0/target");
        var body = json.createObjectNode();
        var change =
                body.putArray("endAdjustments")
                        .addObject()
                        .put("endTime", "2096-02-28T18:00:01+08:00");
        change.set("target", target);
        mvc.perform(policyRequest(post(endpoint + "/preview"), owner).content(body.toString()))
                .andExpect(status().isBadRequest());
        change.put("reason", "活动延续一秒");
        var attachment = change.putArray("attachmentInfo").addObject().put("type", "FILE");
        attachment.put("id", uploadChangeFile(other, "另一账号的依据.pdf"));
        mvc.perform(policyRequest(post(endpoint + "/preview"), owner).content(body.toString()))
                .andExpect(status().isNotFound());
        var evidence = uploadChangeFile(owner, "延续证明.pdf");
        attachment.put("id", evidence);
        var preview =
                mvc.perform(
                                policyRequest(post(endpoint + "/preview"), owner)
                                        .content(body.toString()))
                        .andExpect(status().isOk())
                        .andReturn();
        body.put(
                "confirmationToken",
                json.readTree(preview.getResponse().getContentAsByteArray())
                        .path("confirmationToken")
                        .asText());
        mvc.perform(
                        policyRequest(post(endpoint), owner)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
        var detail =
                mvc.perform(get("/api/policies/" + id).session(owner))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.form.endTime").value("2096-02-28T18:00:00+08:00"))
                        .andExpect(
                                jsonPath("$.timeHistory.currentEnd")
                                        .value("2096-02-28T18:00:01+08:00"))
                        .andExpect(
                                jsonPath("$.timeHistory.changes[0].attachmentInfo[0].originalName")
                                        .value("延续证明.pdf"))
                        .andReturn();
        var changeId =
                json.readTree(detail.getResponse().getContentAsByteArray())
                        .at("/timeHistory/changes/0/id")
                        .asText();
        var url =
                "/api/policy-reviews/"
                        + id
                        + "/time-changes/"
                        + changeId
                        + "/attachments/"
                        + evidence
                        + "/content";
        mvc.perform(get(url).param("submissionVersion", "1").session(owner))
                .andExpect(status().isOk());
        mvc.perform(get(url).param("submissionVersion", "1").session(other))
                .andExpect(status().isNotFound());
        mvc.perform(policyRequest(delete("/api/attachments/" + evidence), owner))
                .andExpect(status().isConflict());
        assertThat(
                        effectiveIds(
                                "BILLION",
                                good.path("id").asText(),
                                "PDD",
                                "2096-02-28T18:00:01+08:00"))
                .containsExactly(id);
        assertThat(
                        effectiveIds(
                                "BILLION",
                                good.path("id").asText(),
                                "PDD",
                                "2096-02-28T18:00:02+08:00"))
                .isEmpty();
    }

    private MockHttpServletRequestBuilder timelineRequest(JsonNode good, String from, String to) {
        return timelineRequest("/api/admin/policies/subsidy-timeline", good, from, to);
    }

    private MockHttpServletRequestBuilder applicantTimelineRequest(JsonNode good) {
        return timelineRequest("/api/policies/subsidy-timeline", good, "2020-01-01", "2099-12-31");
    }

    private MockHttpServletRequestBuilder timelineRequest(
            String path, JsonNode good, String from, String to) {
        return get(path)
                .param("spuId", good.path("spuId").asText())
                .param("ram", good.path("ram").asText())
                .param("rom", good.path("rom").asText())
                .param("dateFrom", from)
                .param("dateTo", to);
    }

    private void approveProduct(String id, int record, int round) {
        var transferred =
                reviews.productReview(
                        productReviewer,
                        Long.parseLong(id),
                        new PolicyReviewRequest(record, round, "APPROVE", null));
        assertThat(transferred.status()).isEqualTo("PENDING");
        assertThat(transferred.reviewStage()).isEqualTo("PURCHASE_REVIEW");
        assertThat(transferred.submissionVersion()).isEqualTo(round);
        assertThat(transferred.recordVersion()).isEqualTo(record + 1);
    }

    private MockHttpServletRequestBuilder reviewRound(
            String id, int record, int round, String decision) {
        return policyRequest(post("/api/admin/policies/" + id + "/review"), admin)
                .content(
                        json.createObjectNode()
                                .put("recordVersion", record)
                                .put("submissionVersion", round)
                                .put("decision", decision)
                                .toString());
    }

    private String coveragePolicy(
            MockHttpSession owner,
            JsonNode good,
            String start,
            String end,
            String submitted,
            boolean secondPlatform)
            throws Exception {
        String extraPlatform = secondPlatform ? ",\"TAOBAO\"" : "";
        String extraAssignment =
                secondPlatform ? ",{\"platformCode\":\"TAOBAO\",\"planKey\":\"fixed\"}" : "";
        String form =
                """
                {"perspective":"BILLION","selectedPlatforms":["PDD"%s],
                "startTime":"%sT09:00:00+08:00","endTime":"%sT18:00:00+08:00",
                "goodsForm":{"cards":[{"key":"common","cardType":"COMMON","followsOrderPlatforms":true,
                "targets":[{"goodsId":"%s","spuId":"%s","ram":"%s","rom":"%s"}],
                "subsidyPlans":[{"key":"fixed","subsidyMode":"FIXED","fixedAmount":"0.10"}],
                "platformAssignments":[{"platformCode":"PDD","planKey":"fixed"}%s]}]}}
                """
                        .formatted(
                                extraPlatform,
                                start,
                                end,
                                good.path("id").asText(),
                                good.path("spuId").asText(),
                                good.path("ram").asText(),
                                good.path("rom").asText(),
                                extraAssignment);
        String id = createPolicy(owner, form).path("id").asText();
        mvc.perform(confirmedSubmission("/api/policies/" + id, owner, 1))
                .andExpect(status().isOk());
        jdbc.update(
                "UPDATE policy_audit_log SET created_at=? WHERE policy_id=?",
                java.sql.Timestamp.valueOf(submitted),
                id);
        return id;
    }

    private MockHttpServletRequestBuilder effectiveRequest(
            String goodsId, String platform, String at) {
        return get("/api/admin/policies/effective")
                .session(admin)
                .param("perspective", "BILLION")
                .param("platformCode", platform)
                .param("goodsId", goodsId)
                .param("at", at);
    }

    private PolicyRouteSummary workflowRoute(String policyId) {
        long id = Long.parseLong(policyId);
        var log = reviewMapper.current(id, "test-tenant", 1);
        var snapshot = snapshotCodec.read(log.getSnapshotData());
        var entry = policyMapper.find(id, "test-tenant", Long.parseLong(snapshot.applicantId()));
        return coverage.workflowRoute(entry, snapshot, log.getCreatedAt());
    }

    private MockHttpServletRequestBuilder confirmedSubmission(
            String path, MockHttpSession owner, int version) throws Exception {
        return confirmedSubmission(path, owner, version, (segment, row) -> {});
    }

    private MockHttpServletRequestBuilder confirmedSubmission(
            String path,
            MockHttpSession owner,
            int version,
            java.util.function.BiConsumer<JsonNode, ObjectNode> adjustChoice)
            throws Exception {
        var result =
                mvc.perform(
                                get(path + "/submission-preview")
                                        .session(owner)
                                        .param("recordVersion", Integer.toString(version)))
                        .andExpect(status().isOk())
                        .andReturn();
        var preview = json.readTree(result.getResponse().getContentAsByteArray());
        var body =
                json.createObjectNode()
                        .put("recordVersion", version)
                        .put("confirmationToken", preview.path("confirmationToken").asText());
        addDecisions(body, preview, adjustChoice);
        return policyRequest(post(path + "/submit"), owner).content(body.toString());
    }

    private void addDecisions(
            ObjectNode body,
            JsonNode preview,
            java.util.function.BiConsumer<JsonNode, ObjectNode> adjustChoice) {
        var choices = body.putArray("decisions");
        for (var scope : preview.path("applicability").path("scopes")) {
            for (var segment : scope.path("segments")) {
                var row = choices.addObject().put("through", segment.path("through").asText());
                if ("SELECT_RETAIL_POLICIES".equals(segment.path("action").asText())) {
                    row.put("choice", "SELECT");
                    var selected = row.putArray("selectedPolicyIds");
                    segment.path("groups")
                            .forEach(
                                    group -> selected.add(group.path("policyIds").get(0).asText()));
                } else row.put("choice", "STACK");
                row.set("scope", scope.path("scope"));
                adjustChoice.accept(segment, row);
            }
        }
    }

    private JsonNode createPolicy(MockHttpSession owner, String form) throws Exception {
        var result =
                mvc.perform(policyRequest(post("/api/policies/drafts"), owner).content(form))
                        .andExpect(status().isCreated())
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    void uploadsBindsRestoresAndPreservesPolicyAttachmentsWithScopedDownloads() throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "attachment-owner", "APPLICANT");
        assignPlatforms("attachment-owner", "BILLION:PDD");
        var other = session("test-tenant", "attachment-other", "APPLICANT");
        assignPlatforms("attachment-other", "BILLION:TAOBAO");
        var file =
                new MockMultipartFile(
                        "file",
                        "政策说明.pdf",
                        "application/pdf",
                        "%PDF-1.7\n政策附件".getBytes(StandardCharsets.UTF_8));
        var uploaded =
                mvc.perform(
                                multipart("/api/attachments")
                                        .file(file)
                                        .session(owner)
                                        .header("Origin", "http://localhost:5173")
                                        .header("X-Policy-Request", "1"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String attachmentId =
                json.readTree(uploaded.getResponse().getContentAsByteArray()).path("id").asText();
        var form = attachmentPolicyForm(good, attachmentId);
        mvc.perform(
                        policyRequest(post("/api/policies/drafts"), other)
                                .content(form.toString().replace("\"PDD\"", "\"TAOBAO\"")))
                .andExpect(status().isNotFound());
        String id = createPolicy(owner, form.toString()).path("id").asText();
        String path = "/api/policies/" + id;
        mvc.perform(get(path).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.form.attachmentInfo[0].id").value(attachmentId))
                .andExpect(jsonPath("$.form.attachmentInfo[0].originalName").value("政策说明.pdf"));
        mvc.perform(get(path + "/attachments/" + attachmentId + "/content").session(owner))
                .andExpect(status().isOk())
                .andExpect(content().bytes(file.getBytes()));
        mvc.perform(get(path + "/attachments/" + attachmentId + "/content").session(other))
                .andExpect(status().isNotFound());
        mvc.perform(policyRequest(delete("/api/attachments/" + attachmentId), owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_REFERENCED"));
        mvc.perform(confirmedSubmission(path, owner, 1)).andExpect(status().isOk());
        String logId =
                jdbc.queryForObject(
                        "SELECT id FROM policy_audit_log WHERE policy_id=?", String.class, id);
        String history =
                path + "/audit-logs/" + logId + "/attachments/" + attachmentId + "/content";
        // 待审当前内容必须取提交快照，即使主表附件与该轮不一致。
        jdbc.update("UPDATE policy_apply SET attachment_urls='[]' WHERE id=?", id);
        mvc.perform(get(path + "/attachments/" + attachmentId + "/content").session(owner))
                .andExpect(status().isOk())
                .andExpect(content().bytes(file.getBytes()));
        mvc.perform(get(history).session(other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POLICY_NOT_FOUND"));
        String adminDownload =
                "/api/admin/policies/"
                        + id
                        + "/audit-logs/"
                        + logId
                        + "/attachments/"
                        + attachmentId
                        + "/content";
        mvc.perform(get(history).session(owner))
                .andExpect(status().isOk())
                .andExpect(content().bytes(file.getBytes()));
        mvc.perform(get(adminDownload).session(admin))
                .andExpect(status().isOk())
                .andExpect(content().bytes(file.getBytes()));
        mvc.perform(get(adminDownload).session(other)).andExpect(status().isForbidden());
        mvc.perform(
                        get(adminDownload.replace("/audit-logs/" + logId + "/", "/audit-logs/0/"))
                                .session(admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POLICY_AUDIT_NOT_FOUND"));
        mvc.perform(get("/api/attachments/" + attachmentId + "/content").session(admin))
                .andExpect(status().isNotFound());
        String otherId = createPolicy(owner, form.toString()).path("id").asText();
        mvc.perform(
                        get(adminDownload.replace(
                                        "/" + id + "/audit-logs/", "/" + otherId + "/audit-logs/"))
                                .session(admin))
                .andExpect(status().isNotFound());
        mvc.perform(
                        policyRequest(post("/api/admin/policies/" + id + "/review"), admin)
                                .content(
                                        "{\"recordVersion\":2,\"submissionVersion\":1,\"decision\":\"REJECT\"}"))
                .andExpect(status().isOk());
        form.put("recordVersion", 3);
        form.putArray("attachmentInfo");
        mvc.perform(policyRequest(put(path + "/draft"), owner).content(form.toString()))
                .andExpect(status().isOk());
        mvc.perform(get(path + "/attachments/" + attachmentId + "/content").session(owner))
                .andExpect(status().isNotFound());
        mvc.perform(get(history).session(owner))
                .andExpect(status().isOk())
                .andExpect(content().bytes(file.getBytes()));
        mvc.perform(policyRequest(delete("/api/attachments/" + attachmentId), owner))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE policy_apply SET is_deleted=1 WHERE id=?", id);
        mvc.perform(get(history).session(owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POLICY_NOT_FOUND"));
        mvc.perform(get(adminDownload).session(admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POLICY_NOT_FOUND"));
    }

    @Test
    void attachmentDeletionWaitsForBindingTransactionAndRejectsCommittedReference()
            throws Exception {
        var owner = session("test-tenant", "attachment-race", "APPLICANT");
        assignPlatforms("attachment-race", "BILLION:PDD");
        var uploaded =
                mvc.perform(
                                multipart("/api/attachments")
                                        .file(
                                                new MockMultipartFile(
                                                        "file",
                                                        "并发.pdf",
                                                        "application/pdf",
                                                        "%PDF-1.7"
                                                                .getBytes(StandardCharsets.UTF_8)))
                                        .session(owner)
                                        .header("Origin", "http://localhost:5173")
                                        .header("X-Policy-Request", "1"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String fileId =
                json.readTree(uploaded.getResponse().getContentAsByteArray()).path("id").asText();
        String form =
                "{\"perspective\":\"BILLION\",\"attachmentInfo\":[{\"type\":\"FILE\",\"id\":\""
                        + fileId
                        + "\"}]}";
        var bound = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var deletingStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var binding =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactions)
                                            .execute(
                                                    status -> {
                                                        try {
                                                            var created = createPolicy(owner, form);
                                                            bound.countDown();
                                                            if (!commit.await(10, TimeUnit.SECONDS))
                                                                throw new IllegalStateException(
                                                                        "等待事务提交超时");
                                                            return created.path("id").asText();
                                                        } catch (Exception error) {
                                                            throw new IllegalStateException(error);
                                                        }
                                                    }));
            assertThat(bound.await(10, TimeUnit.SECONDS)).isTrue();
            var deleting =
                    executor.submit(
                            () -> {
                                deletingStarted.countDown();
                                return mvc.perform(
                                                policyRequest(
                                                        delete("/api/attachments/" + fileId),
                                                        owner))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            assertThat(deletingStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> deleting.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            commit.countDown();
            assertThat(binding.get(10, TimeUnit.SECONDS)).isNotBlank();
            assertThat(deleting.get(10, TimeUnit.SECONDS)).isEqualTo(409);
            mvc.perform(get("/api/attachments/" + fileId + "/content").session(owner))
                    .andExpect(status().isOk());
        } finally {
            commit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void keepsSpecialRemarkInTheDraftAndSubmittedRound() throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "remark-only-owner", "APPLICANT");
        assignPlatforms("remark-only-owner", "BILLION:PDD");
        var form = attachmentPolicyForm(good, UUID.randomUUID().toString());
        form.putArray("attachmentInfo");
        form.put("specialRemark", "唯一特别备注");
        String id = createPolicy(owner, form.toString()).path("id").asText();
        String path = "/api/policies/" + id;
        mvc.perform(get(path).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.form.specialRemark").value("唯一特别备注"))
                .andExpect(jsonPath("$.form.otherExplanation").doesNotExist());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT remark FROM policy_apply WHERE id=?", String.class, id))
                .isEqualTo("唯一特别备注");
        form.put("recordVersion", 1).put("specialRemark", "更新后的特别备注");
        mvc.perform(policyRequest(put(path + "/draft"), owner).content(form.toString()))
                .andExpect(status().isOk());
        mvc.perform(confirmedSubmission(path, owner, 2)).andExpect(status().isOk());
        String logId =
                jdbc.queryForObject(
                        "SELECT id FROM policy_audit_log WHERE policy_id=?", String.class, id);
        mvc.perform(get(path).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.form.specialRemark").value("更新后的特别备注"))
                .andExpect(jsonPath("$.form.otherExplanation").doesNotExist());
        mvc.perform(get(path + "/audit-logs/" + logId).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.form.specialRemark").value("更新后的特别备注"));
        mvc.perform(get("/api/admin/policies/" + id).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.form.specialRemark").value("更新后的特别备注"))
                .andExpect(jsonPath("$.snapshot.form.otherExplanation").doesNotExist());
    }

    @Test
    void physicallyDeletesOnlyOwnedUnsubmittedDraftAtTheExpectedVersion() throws Exception {
        var owner = session("test-tenant", "draft-delete-owner", "APPLICANT");
        assignPlatforms("draft-delete-owner", "BILLION:PDD");
        var other = session("test-tenant", "draft-delete-other", "APPLICANT");
        String id = createPolicy(owner, "{\"perspective\":\"BILLION\"}").path("id").asText();
        String path = "/api/policies/" + id + "/draft";
        mvc.perform(policyRequest(delete(path), other).param("recordVersion", "1"))
                .andExpect(status().isNotFound());
        mvc.perform(policyRequest(delete(path), admin).param("recordVersion", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        delete(path)
                                .param("recordVersion", "1")
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(path).session(owner).param("recordVersion", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(policyRequest(delete(path), owner)).andExpect(status().isBadRequest());
        for (String version : List.of("0", "-1", "bad")) {
            mvc.perform(policyRequest(delete(path), owner).param("recordVersion", version))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        policyRequest(put(path), owner)
                                .content("{\"perspective\":\"BILLION\",\"recordVersion\":1}"))
                .andExpect(status().isOk());
        mvc.perform(policyRequest(delete(path), owner).param("recordVersion", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_VERSION_CONFLICT"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_apply WHERE id=?", Integer.class, id))
                .isEqualTo(1);
        mvc.perform(policyRequest(delete(path), owner).param("recordVersion", "2"))
                .andExpect(status().isNoContent());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_apply WHERE id=?", Integer.class, id))
                .isZero();
        mvc.perform(get("/api/policies/" + id).session(owner)).andExpect(status().isNotFound());
        mvc.perform(get("/api/policies/mine").session(owner))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(policyRequest(delete(path), owner).param("recordVersion", "2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsDeletionAfterSubmissionOrWhenSubmissionHistoryExists() throws Exception {
        var owner = session("test-tenant", "draft-delete-history", "APPLICANT");
        assignPlatforms("draft-delete-history", "BILLION:PDD");
        for (String state : List.of("PENDING", "APPROVED", "REJECTED")) {
            String id = createPolicy(owner, "{\"perspective\":\"BILLION\"}").path("id").asText();
            jdbc.update(
                    "UPDATE policy_apply SET status=?, current_version=1 WHERE id=?", state, id);
            mvc.perform(
                            policyRequest(delete("/api/policies/" + id + "/draft"), owner)
                                    .param("recordVersion", "1"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("POLICY_STATE_CONFLICT"));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM policy_apply WHERE id=?",
                                    Integer.class,
                                    id))
                    .isEqualTo(1);
        }
        String id = createPolicy(owner, "{\"perspective\":\"BILLION\"}").path("id").asText();
        jdbc.update(
                "INSERT INTO policy_audit_log (policy_id,tenant_key,version,snapshot_data) VALUES (?,'test-tenant',1,'{}')",
                id);
        mvc.perform(
                        policyRequest(delete("/api/policies/" + id + "/draft"), owner)
                                .param("recordVersion", "1"))
                .andExpect(status().isConflict());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM policy_audit_log WHERE policy_id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
    }

    @Test
    void deletingDraftReleasesOnlyItsAttachmentReference() throws Exception {
        var owner = session("test-tenant", "draft-delete-files", "APPLICANT");
        assignPlatforms("draft-delete-files", "BILLION:PDD");
        var uploaded =
                mvc.perform(
                                multipart("/api/attachments")
                                        .file(
                                                new MockMultipartFile(
                                                        "file",
                                                        "共享附件.pdf",
                                                        "application/pdf",
                                                        "%PDF-1.7"
                                                                .getBytes(StandardCharsets.UTF_8)))
                                        .session(owner)
                                        .header("Origin", "http://localhost:5173")
                                        .header("X-Policy-Request", "1"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String fileId =
                json.readTree(uploaded.getResponse().getContentAsByteArray()).path("id").asText();
        String form =
                "{\"perspective\":\"BILLION\",\"attachmentInfo\":[{\"type\":\"FILE\",\"id\":\""
                        + fileId
                        + "\"}]}";
        String first = createPolicy(owner, form).path("id").asText();
        String second = createPolicy(owner, form).path("id").asText();
        mvc.perform(
                        policyRequest(delete("/api/policies/" + first + "/draft"), owner)
                                .param("recordVersion", "1"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/attachments/" + fileId + "/content").session(owner))
                .andExpect(status().isOk());
        mvc.perform(policyRequest(delete("/api/attachments/" + fileId), owner))
                .andExpect(status().isConflict());
        mvc.perform(
                        policyRequest(delete("/api/policies/" + second + "/draft"), owner)
                                .param("recordVersion", "1"))
                .andExpect(status().isNoContent());
        mvc.perform(policyRequest(delete("/api/attachments/" + fileId), owner))
                .andExpect(status().isNoContent());
    }

    @Test
    void draftDeletionAndSubmissionCannotBothSucceed() throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "draft-delete-race", "APPLICANT");
        assignPlatforms("draft-delete-race", "BILLION:PDD");
        var form = attachmentPolicyForm(good, UUID.randomUUID().toString());
        form.putArray("attachmentInfo");
        String id = createPolicy(owner, form.toString()).path("id").asText();
        var start = new CountDownLatch(1);
        var request = confirmedSubmission("/api/policies/" + id, owner, 1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var submit =
                    executor.submit(
                            () -> {
                                start.await();
                                return mvc.perform(request).andReturn().getResponse().getStatus();
                            });
            var deleting =
                    executor.submit(
                            () -> {
                                start.await();
                                return mvc.perform(
                                                policyRequest(
                                                                delete(
                                                                        "/api/policies/"
                                                                                + id
                                                                                + "/draft"),
                                                                owner)
                                                        .param("recordVersion", "1"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            start.countDown();
            int submitted = submit.get(10, TimeUnit.SECONDS);
            int deleted = deleting.get(10, TimeUnit.SECONDS);
            if (submitted == 200) {
                assertThat(deleted).isEqualTo(409);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT status FROM policy_apply WHERE id=?",
                                        String.class,
                                        id))
                        .isEqualTo("PENDING");
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM policy_audit_log WHERE policy_id=?",
                                        Integer.class,
                                        id))
                        .isEqualTo(1);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM policy_goods_detail WHERE policy_id=?",
                                        Integer.class,
                                        id))
                        .isEqualTo(1);
            } else {
                assertThat(deleted).isEqualTo(204);
                assertThat(submitted).isIn(404, 409);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM policy_apply WHERE id=?",
                                        Integer.class,
                                        id))
                        .isZero();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM policy_audit_log WHERE policy_id=?",
                                        Integer.class,
                                        id))
                        .isZero();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM policy_goods_detail WHERE policy_id=?",
                                        Integer.class,
                                        id))
                        .isZero();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private ObjectNode attachmentPolicyForm(JsonNode good, String attachmentId) throws Exception {
        var form =
                (ObjectNode)
                        json.readTree(
                                """
                {"perspective":"BILLION","selectedPlatforms":["PDD"],
                "startTime":"2026-11-15T09:00:00+08:00","endTime":"2099-11-30T18:00:00+08:00",
                "goodsForm":{"cards":[{"key":"common","cardType":"COMMON","followsOrderPlatforms":true,
                "targets":[{"goodsId":"%s","spuId":"%s","ram":"%s","rom":"%s"}],
                "subsidyPlans":[{"key":"fixed","subsidyMode":"FIXED","fixedAmount":"0.10"}],
                "platformAssignments":[{"platformCode":"PDD","planKey":"fixed"}]}]}}
                """
                                        .formatted(
                                                good.path("id").asText(),
                                                good.path("spuId").asText(),
                                                good.path("ram").asText(),
                                                good.path("rom").asText()));
        form.putArray("attachmentInfo")
                .addObject()
                .put("type", "FILE")
                .put("id", attachmentId)
                .put("originalName", "前端伪造的文件名.pdf");
        return form;
    }

    @Test
    void submittingAndDeletingTheSameProductCannotBothSucceed() throws Exception {
        var imported = importedGoodForEditing();
        var product = ((ObjectNode) editBody(imported).path("product")).put("rom", "256GB");
        var created =
                mvc.perform(mutation(GOODS).content(product.toString()))
                        .andExpect(status().isCreated())
                        .andReturn();
        var selected = json.readTree(created.getResponse().getContentAsByteArray());
        String goodsId = selected.path("id").asText();
        var owner = session("test-tenant", "policy-race", "APPLICANT");
        assignPlatforms("policy-race", "BILLION:PDD");
        String full =
                """
                {"perspective":"BILLION","selectedPlatforms":["PDD"],
                "startTime":"2026-11-10T09:00:00+08:00","endTime":"2099-11-20T18:00:00+08:00",
                "goodsForm":{"cards":[{"key":"specified","cardType":"SPECIFIED","followsOrderPlatforms":true,
                "targets":[{"goodsId":"%s","spuId":"%s","ram":"16GB","rom":"256GB"}],
                "subsidyPlans":[{"key":"ratio","subsidyMode":"RATIO","ratio":"9.25","priceBase":"POLICY_PRICE"}],
                "platformAssignments":[{"platformCode":"PDD","planKey":"ratio","quantity":null}]}]}}
                """
                        .formatted(goodsId, selected.path("spuId").asText());
        var saved =
                mvc.perform(policyRequest(post("/api/policies/drafts"), owner).content(full))
                        .andExpect(status().isCreated())
                        .andReturn();
        String id = json.readTree(saved.getResponse().getContentAsByteArray()).path("id").asText();

        var request = confirmedSubmission("/api/policies/" + id, owner, 1);
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var submit =
                    executor.submit(
                            () -> {
                                start.await();
                                return mvc.perform(request).andReturn().getResponse().getStatus();
                            });
            var delete =
                    executor.submit(
                            () -> {
                                start.await();
                                return mvc.perform(deleteGoods(GOODS + "/" + goodsId))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            start.countDown();
            int submitted = submit.get(10, TimeUnit.SECONDS);
            int deleted = delete.get(10, TimeUnit.SECONDS);
            if (submitted == 200) {
                assertThat(deleted).isEqualTo(409);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT status FROM policy_apply WHERE id=?",
                                        String.class,
                                        id))
                        .isEqualTo("PENDING");
                assertThat(
                                jdbc.queryForList(
                                        "SELECT goods_id FROM policy_goods_detail", String.class))
                        .containsExactly(goodsId);
            } else {
                assertThat(submitted).isEqualTo(409);
                assertThat(deleted).isEqualTo(204);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT status FROM policy_apply WHERE id=?",
                                        String.class,
                                        id))
                        .isEqualTo("DRAFT");
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM policy_audit_log", Integer.class))
                        .isZero();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM policy_goods_detail", Integer.class))
                        .isZero();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private MockHttpServletRequestBuilder policyRequest(
            MockHttpServletRequestBuilder request, MockHttpSession owner) {
        return request.session(owner)
                .header("Origin", "http://localhost:5173")
                .header("X-Policy-Request", "1")
                .contentType("application/json");
    }

    private void referenceGoods(String code, String goodsId) {
        jdbc.update(
                """
                INSERT INTO policy_apply (tenant_key, policy_no, applicant_id, applicant_name,
                    channel_type, status, current_version)
                SELECT tenant_key, ?, id, name, 'RETAIL', 'PENDING', 1 FROM user_info
                WHERE tenant_key = 'test-tenant' AND open_id = 'admin'
                """,
                code);
        jdbc.update(
                """
                INSERT INTO policy_audit_log (policy_id, tenant_key, version, snapshot_data)
                SELECT id, tenant_key, 1, '{}' FROM policy_apply WHERE policy_no = ?
                """,
                code);
        jdbc.update(
                """
                INSERT INTO policy_goods_detail (policy_id, version, platform_code, goods_id, spu_id, ram, rom)
                SELECT p.id, 1, 'JD', g.id, g.spu_id, g.ram, g.rom
                FROM policy_apply p CROSS JOIN goods g WHERE p.policy_no = ? AND g.id = ?
                """,
                code,
                goodsId);
    }

    private MockHttpServletRequestBuilder deleteGoods(String path) {
        return delete(path)
                .session(admin)
                .header("Origin", "http://localhost:5173")
                .header("X-Policy-Request", "1");
    }

    private String start(String brand, String key) throws Exception {
        var result =
                mvc.perform(startRequest(brand, key))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.id").isString())
                        .andExpect(jsonPath("$.lastMaxId").doesNotExist())
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("id")
                .asText();
    }

    @Test
    void cleansRealNameShapesAndReviewsConflictsAndExclusions() throws Exception {
        importForCleaning(
                List.of(
                        goods(
                                "A5 活力版 琥珀黑 8+256",
                                "A5-H",
                                "8+256",
                                "A5 活力版",
                                "A系列",
                                "A5 活力版8+256",
                                "手机-一加（高成本折扣）"),
                        goods(
                                "A5 活力版 (8GB+256GB) 琥珀黑",
                                "A5-H2",
                                "8+256",
                                "A5 活力版",
                                "A系列",
                                "A5 活力版 8+256",
                                null),
                        goods(
                                "A5 活力版 琥珀黑 12+256",
                                "A5-H3",
                                "12+256",
                                "A5 活力版",
                                "A系列",
                                "A5 活力版 12+256",
                                null),
                        goods("A11 暮辰紫 8+256", "A11-Z", "6+256", "A11", "A系列", "A116+256", null),
                        goods(
                                "Reno16 星河紫 12+256 简装版",
                                "R16-Z",
                                "12+256 简装版",
                                "Reno16",
                                "Reno系列",
                                "Reno16 12+256 简装",
                                null),
                        goods("Reno12 周边", "R12-X", "0", "IOT&配件", "Reno系列", null, null),
                        goods(
                                "A55 律动黑 6+128",
                                "A55-H",
                                "6+128",
                                "A55",
                                "手机-一加（高成本折扣）",
                                "A55 6+128",
                                null)));
        cleaning.processNextBatch();
        mvc.perform(get(CLEANING + "/jobs/active").session(admin))
                .andExpect(status().isNoContent());
        mvc.perform(get(CLEANING + "/preview").session(admin).param("brandName", OPPO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(7))
                .andExpect(jsonPath("$.eligible").value(7))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods", Integer.class)).isZero();
        String batch = startClean(OPPO, null, false, 7);
        cleaning.processNextBatch();
        mvc.perform(get(CLEANING + "/jobs/" + batch).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress").value(100))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.resultCounts.AUTO_PASSED").value(3))
                .andExpect(jsonPath("$.resultCounts.REVIEW_REQUIRED").value(4));
        mvc.perform(
                        get("/api/admin/catalog/cleaning/results")
                                .session(admin)
                                .param("keyword", "A11")
                                .param("status", "REVIEW_REQUIRED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].issueCode").value("SPEC_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM color_dict", Integer.class)).isOne();
        long excluded = sourceId("R12-X");
        mvc.perform(get(SOURCES + "/" + excluded).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.suggestedAction").value("EXCLUDE"));
        mvc.perform(
                        mutation(SOURCES + "/" + excluded + "/review")
                                .content("{\"recordVersion\":1,\"decision\":\"EXCLUDE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("EXCLUDED"))
                .andExpect(jsonPath("$.original.goodsField4").value("Reno系列"));
        mvc.perform(
                        mutation(SOURCES + "/" + excluded + "/review")
                                .content("{\"recordVersion\":1,\"decision\":\"EXCLUDE\"}"))
                .andExpect(status().isConflict());
        long shortPack = sourceId("R16-Z");
        mvc.perform(get(SOURCES + "/" + shortPack).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidate.colorName").value("星河紫"))
                .andExpect(
                        jsonPath("$.candidate.displayGoodsName").value("Reno16 星河紫 12+256（简装版）"));
        mvc.perform(
                        mutation(SOURCES + "/" + shortPack + "/review")
                                .content(
                                        """
                {"recordVersion":1,"decision":"APPROVE",
                 "target":{"seriesName":"Reno系列","modelName":"Reno16","spec":"12GB+256GB 简装版","colorName":"星河紫"}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("MANUAL_PASSED"))
                .andExpect(jsonPath("$.goodsId").isString())
                .andExpect(jsonPath("$.reviewedBy").isString())
                .andExpect(jsonPath("$.reviewReason").value("审核通过"));
        mvc.perform(get(GOODS).session(admin).param("keyword", "R16-Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].productNo").value("R16-Z"))
                .andExpect(jsonPath("$.items[0].status").value(1))
                .andExpect(jsonPath("$.items[0].edition").value("简装版"))
                .andExpect(jsonPath("$.items[0].goodsName").value("Reno16 星河紫 12+256 简装版"))
                .andExpect(jsonPath("$.items[0].policyName").value("Reno16 12+256 简装"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM catalog_operation_log WHERE entity_type = 'CLEAN_JOB' OR action_type LIKE 'CLEAN_%'",
                                Integer.class))
                .isZero();
        mvc.perform(
                        get(SOURCES + "/" + shortPack)
                                .session(session("other-tenant", "other-cleaning", "ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void startsManualJobAndRetriesWithoutLeavingPartialMappings() throws Exception {
        importForCleaning(
                List.of(
                        goods(
                                "K10 星耀黑 12+256-活力版",
                                "K10-H",
                                "12+256",
                                "K10活力版",
                                "K系列",
                                "K10 12+256",
                                null)));
        mvc.perform(cleanRequest(OPPO, null, false, 2, "stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_CLEAN_SCOPE_CHANGED"));
        String batch = startClean(OPPO, null, false, 1);
        mvc.perform(cleanRequest(OPPO, null, false, 1, "same-scope"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(batch));
        mvc.perform(cleanRequest(ONEPLUS, null, false, 1, "other-scope"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_CLEAN_ACTIVE"));
        jdbc.execute(
                "ALTER TABLE goods ADD CONSTRAINT ck_test_goods CHECK (product_no <> 'K10-H')");
        try {
            cleaning.processNextBatch();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spu", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM color_dict", Integer.class))
                    .isZero();
            mvc.perform(get(CLEANING + "/jobs/" + batch).session(admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.resultCounts.ERROR").value(1));
        } finally {
            jdbc.execute("ALTER TABLE goods DROP CONSTRAINT ck_test_goods");
        }
        long source = sourceId("K10-H");
        mvc.perform(
                        mutation(SOURCES + "/" + source + "/clean-retry")
                                .content("{\"recordVersion\":1,\"requestKey\":\"retry-test\"}"))
                .andExpect(status().isAccepted());
        cleaning.processNextBatch();
        mvc.perform(get(SOURCES + "/" + source).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("AUTO_PASSED"))
                .andExpect(jsonPath("$.result.modelName").value("K10活力版"))
                .andExpect(jsonPath("$.result.colorName").value("星耀黑"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM color_dict", Integer.class)).isOne();
    }

    @Test
    void reprocessesUnmappedReviewsAndKeepsColorEvidenceWhenOtherFieldsConflict() throws Exception {
        importForCleaning(
                List.of(
                        goods(
                                "Reno17 冰月白 12+512",
                                "ALIAS",
                                "12+512",
                                "Reno17",
                                "Reno系列",
                                "Reon17 12+512",
                                null),
                        goods(
                                "Reno17 Pro 初雪白 12+256",
                                "ALIAS-PRO",
                                "12+256",
                                "Reno17 Pro",
                                "Reno系列",
                                "Reon17 Pro 12+256",
                                null),
                        goods(
                                "Reno17 月夜黑 12+512",
                                "SPEC-CONFLICT",
                                "12+512",
                                "Reno17",
                                "Reno系列",
                                "Reon17 12+256",
                                null),
                        goods(
                                "Reno17 冰月白 12+512",
                                "OTHER-MODEL",
                                "12+512",
                                "Reno17",
                                "Reno系列",
                                "Reon17 Pro 12+512",
                                null),
                        goods(
                                "Reno17 12+512 蓝/紫",
                                "MULTIPLE",
                                "12+512",
                                "Reno17",
                                "Reno系列",
                                null,
                                null),
                        goods(
                                "Reno17（奇遇喵）",
                                "NO-CONFIG-IN-TITLE",
                                "12+512",
                                "Reno17",
                                "Reno系列",
                                null,
                                null),
                        goods(
                                "Reno17 12+512",
                                "NO-COLOR",
                                "12+512",
                                "Reno17",
                                "Reno系列",
                                null,
                                null),
                        goods(
                                "Reno17 12+512 蓝/紫",
                                "PROTECTED",
                                "12+512",
                                "Reno17",
                                "Reno系列",
                                "Reon17 12+512",
                                null)));
        String initial = startClean(OPPO, null, false, 8);
        cleaning.processNextBatch();
        long protectedId = sourceId("PROTECTED");
        mvc.perform(
                        mutation(SOURCES + "/" + protectedId + "/review")
                                .content(
                                        """
                {"recordVersion":1,"decision":"APPROVE",
                 "target":{"seriesName":"Reno系列","modelName":"Reno17","spec":"12+512","colorName":"冰月白"}}
                """))
                .andExpect(status().isOk());
        mvc.perform(get(CLEANING + "/preview").session(admin).param("brandName", OPPO))
                .andExpect(jsonPath("$.eligible").value(0));
        mvc.perform(
                        get(CLEANING + "/preview")
                                .session(admin)
                                .param("brandName", OPPO)
                                .param("includeReviews", "true"))
                .andExpect(jsonPath("$.eligible").value(5));
        String rerun = startClean(OPPO, null, true, 5);
        assertThat(rerun).isNotEqualTo(initial);
        cleaning.processNextBatch();
        mvc.perform(get(SOURCES + "/" + sourceId("ALIAS")).session(admin))
                .andExpect(jsonPath("$.result.status").value("AUTO_PASSED"))
                .andExpect(jsonPath("$.result.modelName").value("Reno17"))
                .andExpect(jsonPath("$.result.colorName").value("冰月白"))
                .andExpect(jsonPath("$.original.goodsField1").value("Reon17 12+512"));
        mvc.perform(get(SOURCES + "/" + sourceId("ALIAS-PRO")).session(admin))
                .andExpect(jsonPath("$.result.status").value("AUTO_PASSED"))
                .andExpect(jsonPath("$.result.colorName").value("初雪白"));
        mvc.perform(get(SOURCES + "/" + sourceId("SPEC-CONFLICT")).session(admin))
                .andExpect(jsonPath("$.result.issueCode").value("SPEC_CONFLICT"))
                .andExpect(jsonPath("$.result.colorName").value("月夜黑"));
        mvc.perform(get(SOURCES + "/" + sourceId("OTHER-MODEL")).session(admin))
                .andExpect(jsonPath("$.result.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.result.colorName").value("冰月白"));
        mvc.perform(get(SOURCES + "/" + sourceId("MULTIPLE")).session(admin))
                .andExpect(jsonPath("$.result.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.result.colorCandidates[0]").value("蓝"))
                .andExpect(jsonPath("$.result.colorCandidates[1]").value("紫"));
        mvc.perform(get(SOURCES + "/" + sourceId("NO-CONFIG-IN-TITLE")).session(admin))
                .andExpect(jsonPath("$.result.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.result.colorName").value("奇遇喵"));
        mvc.perform(get(SOURCES + "/" + sourceId("NO-COLOR")).session(admin))
                .andExpect(jsonPath("$.result.colorCandidates").isEmpty());
        mvc.perform(get(SOURCES + "/" + protectedId).session(admin))
                .andExpect(jsonPath("$.result.status").value("MANUAL_PASSED"))
                .andExpect(jsonPath("$.result.recordVersion").value(2));
        mvc.perform(get(CLEANING + "/jobs/" + rerun).session(admin))
                .andExpect(jsonPath("$.total").value(5));
    }

    private MockHttpServletRequestBuilder cleanRequest(
            String brand, LocalDate date, boolean reviews, long count, String key)
            throws Exception {
        return mutation(CLEANING + "/jobs")
                .content(
                        json.writeValueAsBytes(
                                new com.company.policy.dto.CatalogCleaningRequest.Start(
                                        brand, date, reviews, count, key)));
    }

    private String startClean(String brand, LocalDate date, boolean reviews, long count)
            throws Exception {
        var response =
                mvc.perform(cleanRequest(brand, date, reviews, count, UUID.randomUUID().toString()))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse();
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8))
                .path("id")
                .asText();
    }

    @Test
    void includesCommittedGoodsAfterEndingAnIncompleteImport() throws Exception {
        String importId = start(OPPO, UUID.randomUUID().toString());
        when(upstream.queryPage(any()))
                .thenReturn(new JackyunGoodsPage(OPPO, List.of(ORIGINAL), CURSOR))
                .thenThrow(new JackyunRateLimitException(60));
        step(importId);
        step(importId);
        assertThat(state(importId)).isEqualTo("PAUSED");
        mvc.perform(get(CLEANING + "/preview").session(admin).param("brandName", OPPO))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.eligible").value(0));
        mvc.perform(control(importId, "cancel", version(importId))).andExpect(status().isOk());
        mvc.perform(get(CLEANING + "/preview").session(admin).param("brandName", OPPO))
                .andExpect(jsonPath("$.eligible").value(1));
        startClean(OPPO, null, false, 1);
        cleaning.processNextBatch();
        mvc.perform(get(SOURCES + "/" + sourceId("SAMPLE")).session(admin))
                .andExpect(jsonPath("$.result.status").value("REVIEW_REQUIRED"));
    }

    private static String testSku(String goodsNo) {
        return Long.toString(
                1_000_000_000_000_000_000L + Integer.toUnsignedLong(goodsNo.hashCode()));
    }

    private static Goods goods(
            String name,
            String goodsNo,
            String sku,
            String category,
            String field4,
            String field1,
            String field5) {
        return new Goods(
                name,
                goodsNo,
                sku,
                category,
                field4,
                field1,
                field5,
                testSku(goodsNo),
                null,
                null,
                null,
                null,
                null);
    }

    private String importForCleaning(List<Goods> goods) throws Exception {
        String id = start(OPPO, UUID.randomUUID().toString());
        when(upstream.queryPage(any()))
                .thenReturn(new JackyunGoodsPage(OPPO, goods, CURSOR))
                .thenReturn(new JackyunGoodsPage(OPPO, List.of(), CURSOR));
        step(id);
        step(id);
        return id;
    }

    private long sourceId(String goodsNo) {
        return jdbc.queryForObject(
                "SELECT id FROM source_goods_item WHERE goods_no = ?", Long.class, goodsNo);
    }

    private MockHttpServletRequestBuilder startRequest(String brand, String key) throws Exception {
        return mutation(JOBS)
                .content(json.writeValueAsBytes(new GoodsSyncRequest(brand, key, null, null)));
    }

    private MockHttpServletRequestBuilder control(String id, String action, int version)
            throws Exception {
        return mutation(JOBS + "/" + id + "/" + action)
                .content(json.writeValueAsBytes(new GoodsSyncRequest.Control(version)));
    }

    private MockHttpServletRequestBuilder mutation(String path) {
        return post(path)
                .session(admin)
                .header("Origin", "http://localhost:5173")
                .header("X-Policy-Request", "1")
                .contentType("application/json");
    }

    // 为历史场景准备当时的测试分工，包含同一渠道平台在不同时点的交接。
    private void assignPlatforms(String openId, String... scopes) {
        for (var scope : scopes) {
            var pair = scope.split(":");
            jdbc.update(
                    "DELETE FROM user_platform_assignment WHERE tenant_key='test-tenant' AND perspective=? AND platform_code=?",
                    pair[0],
                    pair[1]);
            jdbc.update(
                    "INSERT INTO user_platform_assignment (tenant_key,perspective,platform_code,user_id) SELECT tenant_key,?,?,id FROM user_info WHERE tenant_key='test-tenant' AND open_id=?",
                    pair[0],
                    pair[1],
                    openId);
        }
    }

    private MockHttpSession session(String tenant, String openId, String role) {
        var user = users.saveVerifiedIdentity(tenant, openId, null, "测试用户", null);
        jdbc.update("UPDATE user_info SET role_type=? WHERE id=?", role, user.id());
        var request = new MockHttpServletRequest();
        sessions.establish(request, new AuthSessionResponse(user, Instant.now().plusSeconds(600)));
        return (MockHttpSession) request.getSession();
    }

    private void step(String id) {
        jdbc.update("UPDATE goods_sync_job SET next_request_at=CURRENT_TIMESTAMP WHERE id=?", id);
        sync.processNextPage();
    }

    private String cursor(String id) {
        return jdbc.queryForObject(
                "SELECT last_max_id FROM goods_sync_job WHERE id=?", String.class, id);
    }

    private String state(String id) {
        return jdbc.queryForObject(
                "SELECT status FROM goods_sync_job WHERE id=?", String.class, id);
    }

    private int version(String id) {
        return jdbc.queryForObject(
                "SELECT record_version FROM goods_sync_job WHERE id=?", Integer.class, id);
    }
}
