package com.company.policy.service.impl;

import com.company.policy.common.config.JackyunProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.common.exception.JackyunRateLimitException;
import com.company.policy.dto.GoodsSyncRequest;
import com.company.policy.dto.GoodsSyncRequest.Mode;
import com.company.policy.dto.JackyunGoodsQuery;
import com.company.policy.dto.JackyunGoodsQuery.Phase;
import com.company.policy.entity.GoodsSyncJob;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.GoodsSyncJobMapper;
import com.company.policy.mapper.SourceGoodsItemMapper;
import com.company.policy.service.GoodsSyncService;
import com.company.policy.service.JackyunGoodsService;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.GoodsSyncResponse;
import com.company.policy.vo.GoodsSyncResponse.Overview;
import com.company.policy.vo.GoodsSyncResponse.Source;
import com.company.policy.vo.GoodsSyncResponse.SourcePage;
import com.company.policy.vo.JackyunGoodsPage;
import com.company.policy.vo.JackyunGoodsPage.Goods;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class GoodsSyncServiceImpl implements GoodsSyncService {
    private static final List<String> BRANDS = CatalogAccess.BRANDS;
    private static final int PAGE_SIZE = 50;
    private static final int[] FIELD_LENGTHS = {512, 128, 256, 256, 1024, 1024, 1024, 1024};

    private final CatalogAccess access;
    private final GoodsSyncJobMapper jobs;
    private final SourceGoodsItemMapper sources;
    private final JackyunGoodsService jackyun;
    private final JackyunProperties config;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private long lastWorkerWarning = System.nanoTime() - TimeUnit.MINUTES.toNanos(1);

    GoodsSyncServiceImpl(
            CatalogAccess access,
            GoodsSyncJobMapper jobs,
            SourceGoodsItemMapper sources,
            JackyunGoodsService jackyun,
            JackyunProperties config,
            ObjectMapper json,
            PlatformTransactionManager manager) {
        this.access = access;
        this.jobs = jobs;
        this.sources = sources;
        this.jackyun = jackyun;
        this.config = config;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(60);
    }

    @Override
    public Overview overview(CurrentUserResponse session) {
        access.requireAdmin(session);
        var latest =
                BRANDS.stream()
                        .map(brand -> jobs.latest(config.getSourceKey(), brand))
                        .filter(Objects::nonNull)
                        .map(GoodsSyncResponse::from)
                        .toList();
        return new Overview(config.isImportReady(), latest);
    }

    @Override
    public GoodsSyncResponse start(CurrentUserResponse session, GoodsSyncRequest request) {
        UserInfo admin = access.requireAdmin(session);
        if ((request.mode() == Mode.FULL) != (request.date() == null)
                || (request.date() != null
                        && (request.date().getYear() < 1000 || request.date().getYear() > 9999))) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "CATALOG_INVALID_SYNC_DATE", "按日导入须填写有效日期，全量导入无需日期。");
        }
        var job = new GoodsSyncJob();
        job.setSourceKey(config.getSourceKey());
        job.setRequesterId(admin.getId());
        job.setBrandName(request.brandName());
        job.setIdempotencyKey(request.idempotencyKey());
        job.setPageSize(PAGE_SIZE);
        job.setIncludeBlockup(true);
        job.setSyncMode(request.mode());
        job.setSyncDate(request.date());
        job.setSyncPhase(
                switch (request.mode()) {
                    case FULL -> Phase.FULL;
                    case CREATED -> Phase.CREATED;
                    case MODIFIED -> Phase.GOODS_MODIFIED;
                });
        try {
            return tx.execute(
                    ignored -> {
                        var existing = replay(job);
                        if (existing != null) {
                            return existing;
                        }
                        requireReady();
                        if (Boolean.TRUE.equals(jobs.coolingDown(job.getSourceKey()))) {
                            throw conflict("CATALOG_COOLDOWN", "账套正在冷却，请稍后再发起导入。");
                        }
                        // 首次请求也留出间隔，连续导入另一个品牌不会立即连发。
                        checkUpdated(jobs.insert(job, config.getImportIntervalSeconds()));
                        checkUpdated(jobs.audit(admin, job.getId(), "START"));
                        return GoodsSyncResponse.from(jobs.find(job.getSourceKey(), job.getId()));
                    });
        } catch (DuplicateKeyException conflict) {
            // 唯一键处理并发提交；重新读取已提交结果，避免事务快照漏掉并发任务。
            var existing = replay(job);
            if (existing != null) {
                return existing;
            }
            throw conflict("CATALOG_JOB_ACTIVE", "已有商品导入任务，请先完成或结束该任务。");
        }
    }

    private GoodsSyncResponse replay(GoodsSyncJob job) {
        var existing = jobs.findByKey(job.getSourceKey(), job.getIdempotencyKey());
        if (existing == null) {
            return null;
        }
        if (!Objects.equals(existing.getBrandName(), job.getBrandName())
                || existing.getSyncMode() != job.getSyncMode()
                || !Objects.equals(existing.getSyncDate(), job.getSyncDate())
                || !Objects.equals(existing.getRequesterId(), job.getRequesterId())) {
            throw conflict("CATALOG_IDEMPOTENCY_CONFLICT", "该导入请求编号已被其他操作使用。");
        }
        return GoodsSyncResponse.from(existing);
    }

    @Override
    public GoodsSyncResponse resume(CurrentUserResponse session, long id, int version) {
        return control(session, id, version, true);
    }

    @Override
    public GoodsSyncResponse cancel(CurrentUserResponse session, long id, int version) {
        return control(session, id, version, false);
    }

    private GoodsSyncResponse control(
            CurrentUserResponse session, long id, int version, boolean resume) {
        UserInfo admin = access.requireAdmin(session);
        if (resume) {
            requireReady();
        }
        return tx.execute(
                ignored -> {
                    var job = jobs.find(config.getSourceKey(), id);
                    if (job == null) {
                        throw new ApiException(
                                HttpStatus.NOT_FOUND, "CATALOG_JOB_NOT_FOUND", "未找到该导入任务。");
                    }
                    if (!"PAUSED".equals(job.getStatus()) || job.getRecordVersion() != version) {
                        throw changed();
                    }
                    if (resume && !job.isReady()) {
                        throw conflict("CATALOG_COOLDOWN", "任务正在冷却，请稍后继续。");
                    }
                    if (resume && job.getPageCount() >= config.getImportMaxPages()) {
                        throw conflict("CATALOG_PAGE_LIMIT", "请先由运维核对商品规模并调整导入页数上限。");
                    }
                    if (jobs.control(job, resume ? "QUEUED" : "CANCELLED") != 1) {
                        throw changed();
                    }
                    checkUpdated(jobs.audit(admin, id, resume ? "RESUME" : "CANCEL"));
                    return GoodsSyncResponse.from(jobs.find(config.getSourceKey(), id));
                });
    }

    @Override
    public SourcePage sources(
            CurrentUserResponse session,
            String brand,
            String keyword,
            LocalDate createdDate,
            int page,
            int size) {
        access.requireAdmin(session);
        if ((brand != null && !BRANDS.contains(brand))
                || (keyword != null && keyword.length() > 128)
                || (createdDate != null
                        && (createdDate.getYear() < 1000 || createdDate.getYear() > 9999))
                || page < 1
                || size < 1
                || size > 100) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "CATALOG_INVALID_FILTER", "品牌、关键词、建档日或分页参数无效。");
        }
        String search = keyword == null || keyword.isBlank() ? null : keyword.strip();
        return tx.execute(
                ignored -> {
                    var items =
                            sources
                                    .page(
                                            config.getSourceKey(),
                                            brand,
                                            search,
                                            createdDate,
                                            size,
                                            (page - 1L) * size)
                                    .stream()
                                    .map(Source::from)
                                    .toList();
                    return new SourcePage(
                            items,
                            sources.count(config.getSourceKey(), brand, search, createdDate));
                });
    }

    private void requireReady() {
        if (!config.isImportReady()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CATALOG_IMPORT_NOT_CONFIGURED",
                    "请检查吉客云凭证及导入频率配置。");
        }
    }

    @Override
    public void processNextPage() {
        if (!config.isImportReady()) {
            return;
        }
        try {
            var job = jobs.findDue(config.getSourceKey());
            if (job == null) {
                return;
            }
            String token = UUID.randomUUID().toString();
            if (jobs.claim(job, token) != 1) {
                return;
            }
            job.setRecordVersion(job.getRecordVersion() + 1);
            importClaimedPage(job, token);
        } catch (RuntimeException error) {
            // 数据库不可用时保留已有断点；限频记录异常类型，避免输出 SQL、原文或凭证。
            long now = System.nanoTime();
            if (now - lastWorkerWarning >= TimeUnit.MINUTES.toNanos(1)) {
                lastWorkerWarning = now;
                log.warn("商品导入执行器暂不可用：{}", error.getClass().getSimpleName());
            }
        }
    }

    private void importClaimedPage(GoodsSyncJob job, String token) {
        if (job.getPageCount() >= config.getImportMaxPages()) {
            jobs.pause(job, token, "CATALOG_PAGE_LIMIT", 0);
            return;
        }
        try {
            // 上游调用不占数据库事务。提交前再核对版本和租约。
            var query =
                    new JackyunGoodsQuery(
                            job.getBrandName(),
                            job.getPageSize(),
                            job.getLastMaxId(),
                            job.isIncludeBlockup(),
                            job.getSyncPhase(),
                            job.getSyncDate());
            var page = jackyun.queryPage(query);
            page.goods().forEach(GoodsSyncServiceImpl::validateFields);
            tx.executeWithoutResult(ignored -> commitPage(job, token, page));
        } catch (LeaseLost ignored) {
            // 另一执行者接手，旧响应不能修改商品、任务状态或游标。
        } catch (ApiException error) {
            int cooldown =
                    error instanceof JackyunRateLimitException limit
                            ? limit.getRetryAfterSeconds()
                            : 60;
            jobs.pause(
                    job,
                    token,
                    error.getCode(),
                    Math.max(cooldown, config.getImportIntervalSeconds()));
        } catch (RuntimeException error) {
            jobs.pause(
                    job,
                    token,
                    "CATALOG_PAGE_WRITE_FAILED",
                    Math.max(60, config.getImportIntervalSeconds()));
            log.warn("商品导入任务 {} 页写入失败：{}", job.getId(), error.getClass().getSimpleName());
        }
    }

    private void commitPage(GoodsSyncJob job, String token, JackyunGoodsPage page) {
        if (jobs.lockOwned(job, token) == null) {
            throw new LeaseLost();
        }
        var skuIds = page.goods().stream().map(Goods::skuId).toList();
        if (!skuIds.isEmpty()) {
            // 覆盖最近批次前保留旧版本已确认的建档日。
            sources.recordCreatedDates(job, skuIds);
        }
        int count = 0;
        for (Goods goods : page.goods()) {
            if (saveContent(job, goods)) {
                count++;
            }
        }
        if (job.getSyncMode() == Mode.CREATED && !skuIds.isEmpty()) {
            sources.recordCreatedDates(job, skuIds);
        }
        boolean nextPhase = page.goods().isEmpty() && job.getSyncPhase() == Phase.GOODS_MODIFIED;
        String nextStatus = page.goods().isEmpty() && !nextPhase ? "SUCCEEDED" : "RUNNING";
        if (jobs.advance(
                        job,
                        token,
                        nextStatus,
                        nextPhase ? "0" : page.nextLastMaxId(),
                        nextPhase ? Phase.SKU_MODIFIED : job.getSyncPhase(),
                        page.goods().size(),
                        count,
                        config.getImportIntervalSeconds())
                != 1) {
            throw new LeaseLost();
        }
    }

    private boolean saveContent(GoodsSyncJob job, Goods goods) {
        var existing = sources.lockSku(goods.skuId());
        if (existing == null) {
            checkUpdated(sources.insert(job, goods));
            return true;
        }
        if (!job.getBrandName().equals(existing.getBrandName())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CATALOG_SKU_BRAND_CONFLICT",
                    "同一 SKU 出现在不同品牌，请先核对吉客云品牌归属。");
        }
        if (!job.getSourceKey().equals(existing.getSourceKey())) {
            throw conflict("CATALOG_SKU_SOURCE_CONFLICT", "该 SKU 已属于另一个吉客云账套，请核对服务端配置。");
        }
        boolean firstInJob = !job.getId().equals(existing.getLastSeenJobId());
        var previous = existing.original();
        if (older(goods.gmtModified(), previous.gmtModified())
                || older(goods.goodsGmtModified(), previous.goodsGmtModified())) {
            if (firstInJob) {
                checkUpdated(sources.markSeen(job, existing.getId()));
            }
            return firstInJob;
        }
        var current =
                new Goods(
                        goods.goodsName(),
                        goods.goodsNo(),
                        goods.skuName(),
                        goods.cateName(),
                        goods.goodsField4(),
                        goods.goodsField1(),
                        goods.goodsField5(),
                        goods.skuId(),
                        goods.cateFullName(),
                        known(goods.gmtModified(), previous.gmtModified()),
                        known(goods.goodsGmtModified(), previous.goodsGmtModified()),
                        known(goods.isBlockup(), previous.isBlockup()),
                        known(goods.isPackageGood(), previous.isPackageGood()));
        if (previous.equals(current)) {
            if (firstInJob) {
                checkUpdated(sources.markSeen(job, existing.getId()));
            }
            return firstInJob;
        }
        boolean changed =
                !java.util.Arrays.equals(fields(previous), fields(current))
                        || !Objects.equals(previous.isBlockup(), current.isBlockup())
                        || !Objects.equals(previous.isPackageGood(), current.isPackageGood());
        checkUpdated(
                sources.updateSku(
                        job,
                        existing,
                        current,
                        changed && !"EXCLUDED".equals(existing.getMappingStatus())));
        if (changed) {
            checkUpdated(
                    sources.auditUpdate(
                            existing.getId(),
                            "SYNC-" + job.getId(),
                            auditSnapshot(previous),
                            auditSnapshot(current)));
        }
        return firstInJob;
    }

    private String auditSnapshot(Goods goods) {
        var snapshot = json.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(goods);
        snapshot.remove("skuId");
        return snapshot.toString();
    }

    private static boolean older(Long incoming, Long saved) {
        return incoming != null && saved != null && incoming < saved;
    }

    private static <T> T known(T incoming, T saved) {
        return incoming == null ? saved : incoming;
    }

    private static void validateFields(Goods goods) {
        if (goods.skuId() == null || !goods.skuId().matches("[1-9][0-9]{0,18}")) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_INVALID_SKU", "吉客云未返回有效 SKU ID。");
        }
        String[] values = fields(goods);
        boolean present = false;
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            if (value != null) {
                present = true;
                if (value.codePointCount(0, value.length()) > FIELD_LENGTHS[i]) {
                    throw new ApiException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "CATALOG_FIELD_TOO_LONG",
                            "吉客云商品字段超过存储上限。");
                }
            }
        }
        if (!present) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_EMPTY_SOURCE", "吉客云未返回任何来源字段。");
        }
    }

    private static String[] fields(Goods goods) {
        return new String[] {
            goods.goodsName(),
            goods.goodsNo(),
            goods.skuName(),
            goods.cateName(),
            goods.goodsField4(),
            goods.goodsField1(),
            goods.goodsField5(),
            goods.cateFullName()
        };
    }

    private static void checkUpdated(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("商品导入数据库写入行数不符");
        }
    }

    private static ApiException changed() {
        return conflict("CATALOG_JOB_CHANGED", "导入任务状态已变化，请刷新后核对。");
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static final class LeaseLost extends RuntimeException {}
}
