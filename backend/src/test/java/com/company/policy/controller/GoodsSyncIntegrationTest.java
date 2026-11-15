package com.company.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.common.exception.JackyunRateLimitException;
import com.company.policy.dto.GoodsSyncRequest;
import com.company.policy.dto.JackyunGoodsQuery;
import com.company.policy.dto.JackyunGoodsQuery.Phase;
import com.company.policy.service.CatalogCleaningWorker;
import com.company.policy.service.GoodsSyncService;
import com.company.policy.service.JackyunGoodsService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.UserInfoService;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.JackyunGoodsPage;
import com.company.policy.vo.JackyunGoodsPage.Goods;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
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
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean JackyunGoodsService upstream;
    private MockHttpSession admin;

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
        jdbc.update("DELETE FROM policy_goods_detail");
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

        // 模拟旧版只保留最近导入批次的记录。
        jdbc.update("DELETE FROM catalog_operation_log WHERE batch_key LIKE 'CREATED-DATE-%'");
        mvc.perform(get(SOURCES).session(admin).param("keyword", ORIGINAL.skuId()))
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
                        "Find X8 星耀黑 16+1TB",
                        "X8-ONE",
                        "16+1024",
                        "Find X8",
                        "Find系列",
                        "Find X8 16+1TB",
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
        var beforeEdit = mvc.perform(get(GOODS + "/" + goodsId).session(admin)).andReturn();
        var rename = editBody(json.readTree(beforeEdit.getResponse().getContentAsByteArray()));
        ((ObjectNode) rename.path("product")).put("name", "Find X8 16GB+1024GB 星耀黑");
        mvc.perform(putGoods(GOODS + "/" + goodsId, rename)).andExpect(status().isOk());

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
                .andExpect(jsonPath("$.items[0].goodsName").value("Find X8 16GB+1024GB 星耀黑"))
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
    }

    @Test
    void defaultsUnknownSalesStatusWhenReprocessingSameSkuAndPreservesExplicitStatus()
            throws Exception {
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
        for (Integer state : new Integer[] {null, 3}) {
            jdbc.update("UPDATE goods SET status=? WHERE id=?", state, id);
            jdbc.update(
                    "UPDATE source_goods_item SET clean_status='PENDING' WHERE sku_id=?",
                    original.skuId());
            startClean(OPPO, null, false, 1);
            cleaning.processNextBatch();
            mvc.perform(get(GOODS + "/" + id).session(admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.skuId").value(original.skuId()))
                    .andExpect(jsonPath("$.status").value(state == null ? 1 : state));
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

        mvc.perform(get(dictionary).session(admin))
                .andExpect(jsonPath(usage, org.hamcrest.Matchers.contains(true)));
        mvc.perform(rename).andExpect(status().isConflict());
        mvc.perform(remove).andExpect(status().isConflict());
        String goodsId = item.path("id").asText();
        referenceGoods("SERIES-QA", goodsId);
        mvc.perform(get(dictionary).session(admin))
                .andExpect(jsonPath(usage, org.hamcrest.Matchers.contains(true)));
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
        mvc.perform(get(dictionary).session(admin))
                .andExpect(jsonPath(usage, org.hamcrest.Matchers.contains(false)));
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
    void applicantSubmitsOnlySavedGoodsAndFreezesTheirNames() throws Exception {
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
                "verifyType":"SALES","sceneType":"NATIONAL_SUB","estimated":false,
                "startTime":"2026-11-10T09:00:00+08:00","endTime":"2026-11-20T18:00:00+08:00",
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
                .andExpect(jsonPath("$.message").value("同一平台的该颜色已同时选入普通卡和指定卡，请先编辑普通卡并取消勾选该颜色。"));
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
        mvc.perform(policyRequest(post(path + "/submit"), owner).content("{\"recordVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.submissionVersion").value(1));
        mvc.perform(policyRequest(post(path + "/submit"), owner).content("{\"recordVersion\":2}"))
                .andExpect(status().isConflict());
        assertThat(
                        jdbc.queryForList(
                                "SELECT goods_id FROM policy_goods_detail ORDER BY platform_code",
                                String.class))
                .containsExactlyInAnyOrder(blackId, blackId, extraId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM policy_audit_log", Integer.class))
                .isOne();
        mvc.perform(deleteGoods(GOODS + "/" + whiteId)).andExpect(status().isNoContent());
        jdbc.update("UPDATE goods SET goods_name = '修改后的名称' WHERE id = ?", blackId);
        mvc.perform(get(path).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goods.length()").value(2))
                .andExpect(jsonPath("$.goods[0].goodsId").value(blackId))
                .andExpect(
                        jsonPath("$.goods[0].goodsName")
                                .value(imported.path("goodsName").asText()));
    }

    @Test
    void listsOnlyOwnedPoliciesWithServerSideFiltersAndStablePagination() throws Exception {
        var owner = session("test-tenant", "mine-owner", "APPLICANT");
        var other = session("test-tenant", "mine-other", "APPLICANT");
        var external = session("other-tenant", "mine-external", "APPLICANT");
        String base = "/api/policies";
        String retail = "{\"perspective\":\"RETAIL\",\"selectedPlatforms\":[\"JD\",\"PDD\"]}";
        String billion = "{\"perspective\":\"BILLION\",\"selectedPlatforms\":[\"PDD\"]}";
        String first = createPolicy(owner, retail).path("id").asText();
        String newest = createPolicy(owner, billion).path("id").asText();
        String deleted = createPolicy(owner, retail).path("id").asText();
        createPolicy(other, retail);
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
        var other = session("test-tenant", "resubmit-other", "APPLICANT");
        var external = session("other-tenant", "resubmit-external", "APPLICANT");
        String full =
                """
                {"perspective":"BILLION","selectedPlatforms":["PDD"],"specialRemark":"首轮说明",
                "startTime":"2026-11-10T09:00:00+08:00","endTime":"2026-11-20T18:00:00+08:00",
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
        mvc.perform(policyRequest(post(path + "/submit"), owner).content("{\"recordVersion\":1}"))
                .andExpect(status().isOk());
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
        mvc.perform(policyRequest(post(path + "/submit"), owner).content("{\"recordVersion\":4}"))
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
    void exposesShortPolicyNumbersWithoutChangingCreationIdempotencyOrLegacySnapshots()
            throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "short-policy-number-owner", "APPLICANT");
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
        mvc.perform(
                        policyRequest(post("/api/policies/" + id + "/submit"), owner)
                                .content("{\"recordVersion\":1}"))
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
        var snapshot =
                (ObjectNode)
                        json.readTree(
                                jdbc.queryForObject(
                                        "SELECT snapshot_data FROM policy_audit_log WHERE policy_id=?",
                                        String.class,
                                        id));
        snapshot.put("policyCode", internal);
        String stored = snapshot.toString();
        jdbc.update("UPDATE policy_audit_log SET snapshot_data=? WHERE policy_id=?", stored, id);
        mvc.perform(get("/api/admin/policies/" + id).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.policyCode").value(code))
                .andExpect(jsonPath("$.snapshot.policyCode").value(code));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT snapshot_data FROM policy_audit_log WHERE policy_id=?",
                                String.class,
                                id))
                .isEqualTo(stored);
    }

    @Test
    void resolvesLatestSubmittedPolicyAtEachApplicationsOwnStartAndApprovalTime() throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "coverage-owner", "APPLICANT");
        var outside = session("other-tenant", "coverage-external", "ADMIN");
        var older =
                coveragePolicy(
                        owner, good, "2020-01-02", "2020-01-03", "2020-01-01 09:00:00", true);
        var newer =
                coveragePolicy(
                        owner, good, "2020-01-06", "2020-01-07", "2020-01-04 09:00:00", false);
        var outOfOrder =
                coveragePolicy(
                        owner, good, "2020-01-04", "2020-01-05", "2020-01-02 09:00:00", false);
        var scheduled =
                coveragePolicy(
                        owner, good, "2099-01-03", "2099-01-04", "2020-01-05 09:00:00", false);
        String base = "/api/admin/policies";
        String goodsId = good.path("id").asText();
        String approve = "{\"recordVersion\":2,\"submissionVersion\":1,\"decision\":\"APPROVE\"}";

        mvc.perform(get(base).session(owner)).andExpect(status().isForbidden());
        mvc.perform(get(base).session(outside)).andExpect(status().isForbidden());
        mvc.perform(
                        get(base)
                                .session(admin)
                                .param("status", "PENDING")
                                .param("platformCode", "PDD")
                                .param("pageSize", "2")
                                .param("submittedFrom", "2020-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items[0].id").value(scheduled))
                .andExpect(jsonPath("$.items[1].id").value(newer));
        mvc.perform(policyRequest(post(base + "/" + older + "/review"), admin).content(approve))
                .andExpect(status().isOk());
        // 固定旧政策的审核时间，以便验证不同历史时点。
        jdbc.update(
                "UPDATE policy_audit_log SET audit_time='2020-01-02 10:00:00' WHERE policy_id=?",
                older);
        mvc.perform(
                        get(base + "/" + newer + "/override-preview")
                                .session(admin)
                                .param("recordVersion", "2")
                                .param("submissionVersion", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].outcome").value("REPLACES"))
                .andExpect(jsonPath("$.items[0].winner.id").value(older));

        var reviewed =
                mvc.perform(
                                policyRequest(post(base + "/" + newer + "/review"), admin)
                                        .content(approve))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("APPROVED"))
                        .andExpect(jsonPath("$.recordVersion").value(3))
                        .andReturn();
        String reviewTime =
                json.readTree(reviewed.getResponse().getContentAsByteArray())
                        .path("reviewTime")
                        .asText();
        String beforeReview =
                java.time.OffsetDateTime.parse(reviewTime)
                        .minusSeconds(1)
                        .format(
                                java.time.format.DateTimeFormatter.ofPattern(
                                        "uuuu-MM-dd'T'HH:mm:ssXXX"));
        mvc.perform(effectiveRequest(goodsId, "PDD", beforeReview))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winner.id").value(older));
        mvc.perform(effectiveRequest(goodsId, "PDD", reviewTime))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winner.id").value(newer))
                .andExpect(jsonPath("$.winner.effectiveFrom").value(reviewTime));
        mvc.perform(policyRequest(post(base + "/" + newer + "/review"), admin).content(approve))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/policies/" + newer + "/audit-logs").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].auditStatus").value("APPROVED"));

        mvc.perform(get(base + "/" + outOfOrder).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage.items[0].outcome").value("RETAINED"));
        mvc.perform(
                        policyRequest(post(base + "/" + outOfOrder + "/review"), admin)
                                .content(approve))
                .andExpect(status().isOk());
        mvc.perform(policyRequest(post(base + "/" + scheduled + "/review"), admin).content(approve))
                .andExpect(status().isOk());
        mvc.perform(get(base + "/" + scheduled).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage.items[0].outcome").value("SCHEDULED"));
        mvc.perform(effectiveRequest(goodsId, "PDD", "2099-01-02T23:59:59+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winner.id").value(newer));
        mvc.perform(effectiveRequest(goodsId, "PDD", "2099-01-03T09:00:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winner.id").value(scheduled))
                .andExpect(jsonPath("$.winner.effectiveFrom").value("2099-01-03T09:00:00+08:00"));
        mvc.perform(effectiveRequest(goodsId, "PDD", "2099-01-05T09:00:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winner.id").value(scheduled));
        mvc.perform(effectiveRequest(goodsId, "TAOBAO", "2099-01-05T09:00:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winner.id").value(older));
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
        mvc.perform(
                        policyRequest(post("/api/policies/" + id + "/submit"), owner)
                                .content("{\"recordVersion\":1}"))
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
        var other = session("test-tenant", "attachment-other", "APPLICANT");
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
        mvc.perform(policyRequest(post("/api/policies/drafts"), other).content(form.toString()))
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
        mvc.perform(policyRequest(post(path + "/submit"), owner).content("{\"recordVersion\":1}"))
                .andExpect(status().isOk());
        String logId =
                jdbc.queryForObject(
                        "SELECT id FROM policy_audit_log WHERE policy_id=?", String.class, id);
        String history =
                path + "/audit-logs/" + logId + "/attachments/" + attachmentId + "/content";
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
    }

    @Test
    void attachmentDeletionWaitsForBindingTransactionAndRejectsCommittedReference()
            throws Exception {
        var owner = session("test-tenant", "attachment-race", "APPLICANT");
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
    void keepsOnlySpecialRemarkAndReadsLegacySnapshotExplanationCompatibly() throws Exception {
        var good = importedGoodForEditing();
        var owner = session("test-tenant", "remark-only-owner", "APPLICANT");
        var form = attachmentPolicyForm(good, UUID.randomUUID().toString());
        form.putArray("attachmentInfo");
        form.put("specialRemark", "唯一特别备注").put("otherExplanation", "旧客户端说明");
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
        mvc.perform(policyRequest(post(path + "/submit"), owner).content("{\"recordVersion\":2}"))
                .andExpect(status().isOk());
        var snapshot =
                (ObjectNode)
                        json.readTree(
                                jdbc.queryForObject(
                                        "SELECT snapshot_data FROM policy_audit_log WHERE policy_id=?",
                                        String.class,
                                        id));
        ((ObjectNode) snapshot.path("form")).put("otherExplanation", "旧快照中的其他说明");
        jdbc.update(
                "UPDATE policy_audit_log SET snapshot_data=? WHERE policy_id=?",
                snapshot.toString(),
                id);
        String logId =
                jdbc.queryForObject(
                        "SELECT id FROM policy_audit_log WHERE policy_id=?", String.class, id);
        mvc.perform(get(path).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.form.specialRemark").value("更新后的特别备注"))
                .andExpect(jsonPath("$.form.otherExplanation").doesNotExist());
        mvc.perform(get(path + "/audit-logs/" + logId).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.form.otherExplanation").doesNotExist());
        mvc.perform(get("/api/admin/policies/" + id).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.form.specialRemark").value("更新后的特别备注"))
                .andExpect(jsonPath("$.snapshot.form.otherExplanation").doesNotExist());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT snapshot_data FROM policy_audit_log WHERE policy_id=?",
                                String.class,
                                id))
                .contains("旧快照中的其他说明");
    }

    @Test
    void physicallyDeletesOnlyOwnedUnsubmittedDraftAtTheExpectedVersion() throws Exception {
        var owner = session("test-tenant", "draft-delete-owner", "APPLICANT");
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
        var form = attachmentPolicyForm(good, UUID.randomUUID().toString());
        form.putArray("attachmentInfo");
        String id = createPolicy(owner, form.toString()).path("id").asText();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var submit =
                    executor.submit(
                            () -> {
                                start.await();
                                return mvc.perform(
                                                policyRequest(
                                                                post(
                                                                        "/api/policies/"
                                                                                + id
                                                                                + "/submit"),
                                                                owner)
                                                        .content("{\"recordVersion\":1}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
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
                "startTime":"2026-11-15T09:00:00+08:00","endTime":"2026-11-30T18:00:00+08:00",
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
        String full =
                """
                {"perspective":"BILLION","selectedPlatforms":["PDD"],
                "startTime":"2026-11-10T09:00:00+08:00","endTime":"2026-11-20T18:00:00+08:00",
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

        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var submit =
                    executor.submit(
                            () -> {
                                start.await();
                                return mvc.perform(
                                                policyRequest(
                                                                post(
                                                                        "/api/policies/"
                                                                                + id
                                                                                + "/submit"),
                                                                owner)
                                                        .content("{\"recordVersion\":1}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
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
                .andExpect(jsonPath("$.items[0].goodsName").value("Reno16 12GB+256GB 星河紫（简装版）"));
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
                                "Reno17 冰月白 12+512",
                                "PROTECTED",
                                "12+512",
                                "Reno17",
                                "Reno系列",
                                "Reon17 12+512",
                                null)));
        // 模拟迁入的旧待审记录，验证手动重识别可保留已人工确认的结果。
        String initial = startClean(OPPO, null, false, 8);
        cleaning.processNextBatch();
        long protectedId = sourceId("PROTECTED");
        jdbc.update(
                "UPDATE source_goods_item SET clean_status='REVIEW_REQUIRED', clean_error_code='TEST' WHERE goods_no IN ('ALIAS', 'ALIAS-PRO', 'PROTECTED')");
        mvc.perform(
                        mutation(SOURCES + "/" + protectedId + "/review")
                                .content(
                                        """
                {"recordVersion":1,"decision":"APPROVE","reason":"已确认冰月白",
                 "target":{"seriesName":"Reno系列","modelName":"Reno17","spec":"12+512","colorName":"冰月白"}}
                """))
                .andExpect(status().isOk());
        jdbc.update(
                """
                UPDATE source_goods_item SET mapping_status='UNMAPPED', mapping_origin=NULL,
                  catalog_brand_id=NULL, goods_id=NULL
                WHERE goods_no IN ('ALIAS', 'ALIAS-PRO')
                """);
        mvc.perform(get(CLEANING + "/preview").session(admin).param("brandName", OPPO))
                .andExpect(jsonPath("$.eligible").value(0));
        mvc.perform(
                        get(CLEANING + "/preview")
                                .session(admin)
                                .param("brandName", OPPO)
                                .param("includeReviews", "true"))
                .andExpect(jsonPath("$.eligible").value(7));
        String rerun = startClean(OPPO, null, true, 7);
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
                .andExpect(jsonPath("$.total").value(7));
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
