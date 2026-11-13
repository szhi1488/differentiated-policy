package com.company.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.common.exception.JackyunRateLimitException;
import com.company.policy.dto.GoodsSyncRequest;
import com.company.policy.dto.JackyunGoodsQuery;
import com.company.policy.dto.JackyunGoodsQuery.Phase;
import com.company.policy.service.CatalogCleaningService;
import com.company.policy.service.GoodsSyncService;
import com.company.policy.service.JackyunGoodsService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.UserInfoService;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.JackyunGoodsPage;
import com.company.policy.vo.JackyunGoodsPage.Goods;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
    @Autowired CatalogCleaningService cleaning;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JackyunGoodsService upstream;
    private MockHttpSession admin;

    @DynamicPropertySource
    static void optionalDisposableMySql(DynamicPropertyRegistry registry) {
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
    void sharesMergedStandardGoodsAcrossAdminsAndKeepsCleanStateOnIdenticalDatedReplay()
            throws Exception {
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
                                .param("catalogStatus", "ACTIVE")
                                .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(goodsId)))
                .andExpect(jsonPath("$.items[0].skuId").isString())
                .andExpect(jsonPath("$.items[0].sourceSkuId").value(original.skuId()))
                .andExpect(jsonPath("$.items[0].ram").value("16GB"))
                .andExpect(jsonPath("$.items[0].rom").value("1024GB"))
                .andExpect(jsonPath("$.items[0].goodsName").value("Find X8 16GB+1024GB 星耀黑"))
                .andExpect(jsonPath("$.items[0].status").value(1));
        mvc.perform(get(GOODS + "/" + goodsId).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandName").value(OPPO))
                .andExpect(jsonPath("$.colorName").value("星耀黑"))
                .andExpect(jsonPath("$.sourceSkuId").value(original.skuId()))
                .andExpect(jsonPath("$.sourceName").value(original.goodsName()));
        mvc.perform(get(GOODS).session(teammate).param("keyword", alias.skuId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sourceSkuId").value(original.skuId()));
        mvc.perform(get(GOODS + "/summary").session(teammate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.active").value(1))
                .andExpect(jsonPath("$.series[0].seriesId").isString())
                .andExpect(jsonPath("$.series[0].seriesName").value("Find系列"))
                .andExpect(jsonPath("$.series[0].total").value(1))
                .andExpect(jsonPath("$.models[0].spuId").value(spuId))
                .andExpect(jsonPath("$.models[0].modelName").value("Find X8"))
                .andExpect(jsonPath("$.models[0].active").value(1))
                .andExpect(jsonPath("$.models[0].total").value(1))
                .andExpect(jsonPath("$.configurations[0].rom").value("1024GB"));
        mvc.perform(get(GOODS).session(teammate).param("spuId", spuId).param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].spuId").value(spuId));
        mvc.perform(get(GOODS).session(teammate).param("spuId", spuId).param("brandName", ONEPLUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(GOODS).session(teammate).param("spuId", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(GOODS).session(teammate).param("ram", "16GB").param("rom", "1024GB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(GOODS).session(teammate).param("ram", "16GB").param("rom", "512GB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(GOODS).session(teammate).param("ram", "16GB"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(GOODS).session(session("other-tenant", "catalog-outsider", "ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get(GOODS).session(teammate).param("catalogStatus", "INVALID"))
                .andExpect(status().isBadRequest());
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
    void defaultsUnknownSalesStatusWhenReusingGoodsAndPreservesExplicitStatus() throws Exception {
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

        jdbc.update("UPDATE goods SET status=NULL WHERE id=?", id);
        var second =
                goods(
                        "Find X8 星耀黑 16+512",
                        "X8-SECOND",
                        "16+512",
                        "Find X8",
                        "Find系列",
                        "Find X8 16+512",
                        null);
        importForCleaning(List.of(second));
        startClean(OPPO, null, false, 1);
        cleaning.processNextBatch();
        mvc.perform(get(GOODS + "/" + id).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1));

        jdbc.update("UPDATE goods SET status=3 WHERE id=?", id);
        var third =
                goods(
                        "Find X8 星耀黑 16+512",
                        "X8-THIRD",
                        "16+512",
                        "Find X8",
                        "Find系列",
                        "Find X8 16+512",
                        null);
        importForCleaning(List.of(third));
        String job = startClean(OPPO, null, false, 1);
        cleaning.processNextBatch();
        mvc.perform(get(CLEANING + "/jobs/" + job).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCounts.AUTO_PASSED").value(1));
        mvc.perform(get(GOODS + "/" + id).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(3));
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
                        .andExpect(jsonPath("$.skuId").isString())
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
                .andExpect(jsonPath("$.catalogStatus").value("ACTIVE"));
        mvc.perform(
                        mutation(GOODS)
                                .session(session("test-tenant", "goods-applicant", "APPLICANT"))
                                .content(body.put("seriesId", series).toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletesByDisablingGoodsAndPreservesSourceMapping() throws Exception {
        var original =
                goods(
                        "Find X8 星耀黑 16+512",
                        "X8-REMOVE",
                        "16+512",
                        "Find X8",
                        "Find系列",
                        "Find X8 16+512",
                        null);
        importForCleaning(List.of(original));
        startClean(OPPO, null, false, 1);
        cleaning.processNextBatch();
        long id = jdbc.queryForObject("SELECT id FROM goods", Long.class);
        String path = GOODS + "/" + id;
        mvc.perform(delete(path).session(admin)).andExpect(status().isForbidden());
        mvc.perform(
                        delete(path)
                                .session(session("test-tenant", "remove-applicant", "APPLICANT"))
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1"))
                .andExpect(status().isForbidden());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(
                            delete(path)
                                    .session(admin)
                                    .header("Origin", "http://localhost:5173")
                                    .header("X-Policy-Request", "1"))
                    .andExpect(status().isNoContent());
        }
        assertThat(jdbc.queryForObject("SELECT goods_id FROM source_goods_item", Long.class))
                .isEqualTo(id);
        mvc.perform(get(path).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogStatus").value("DISABLED"));
        mvc.perform(get(GOODS).session(admin).param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(GOODS + "/summary").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.active").value(0))
                .andExpect(jsonPath("$.models").isEmpty());
        mvc.perform(get(GOODS).session(admin).param("catalogStatus", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(id)));

        var alias =
                goods(
                        "Find X8 星耀黑 16+512",
                        "X8-REMOVE-ALIAS",
                        "16+512",
                        "Find X8",
                        "Find系列",
                        "Find X8 16+512",
                        null);
        importForCleaning(List.of(alias));
        String job = startClean(OPPO, null, false, 1);
        cleaning.processNextBatch();
        mvc.perform(get(CLEANING + "/jobs/" + job).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCounts.REVIEW_REQUIRED").value(1));
        mvc.perform(get(path).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogStatus").value("DISABLED"));
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
        var user = users.saveVerifiedIdentity(tenant, openId, null, "测试用户");
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
