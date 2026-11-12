package com.company.policy.service.impl;

import com.company.policy.common.config.JackyunProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.common.exception.JackyunRateLimitException;
import com.company.policy.dto.GoodsSyncRequest;
import com.company.policy.dto.JackyunGoodsQuery;
import com.company.policy.entity.GoodsSyncJob;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.GoodsSyncJobMapper;
import com.company.policy.mapper.SourceGoodsItemMapper;
import com.company.policy.mapper.UserInfoMapper;
import com.company.policy.service.GoodsSyncService;
import com.company.policy.service.JackyunGoodsService;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.GoodsSyncResponse;
import com.company.policy.vo.GoodsSyncResponse.Overview;
import com.company.policy.vo.GoodsSyncResponse.Source;
import com.company.policy.vo.GoodsSyncResponse.SourcePage;
import com.company.policy.vo.JackyunGoodsPage;
import com.company.policy.vo.JackyunGoodsPage.Goods;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class GoodsSyncServiceImpl implements GoodsSyncService {
    private static final List<String> BRANDS = List.of("OPPO手机", "一加手机");
    private static final int PAGE_SIZE = 50;
    private static final int[] FIELD_LENGTHS = {512, 128, 256, 256, 1024, 1024, 1024};

    private final UserInfoMapper users;
    private final GoodsSyncJobMapper jobs;
    private final SourceGoodsItemMapper sources;
    private final JackyunGoodsService jackyun;
    private final JackyunProperties config;
    private final TransactionTemplate tx;
    private long lastWorkerWarning = System.nanoTime() - TimeUnit.MINUTES.toNanos(1);

    public GoodsSyncServiceImpl(
            UserInfoMapper users,
            GoodsSyncJobMapper jobs,
            SourceGoodsItemMapper sources,
            JackyunGoodsService jackyun,
            JackyunProperties config,
            PlatformTransactionManager manager) {
        this.users = users;
        this.jobs = jobs;
        this.sources = sources;
        this.jackyun = jackyun;
        this.config = config;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(60);
    }

    @Override
    public Overview overview(CurrentUserResponse session) {
        UserInfo admin = requireAdmin(session);
        var latest =
                BRANDS.stream()
                        .map(
                                brand ->
                                        jobs.latest(
                                                admin.getTenantKey(), config.getSourceKey(), brand))
                        .filter(Objects::nonNull)
                        .map(GoodsSyncResponse::from)
                        .toList();
        return new Overview(config.isImportReady(), latest);
    }

    @Override
    public GoodsSyncResponse start(CurrentUserResponse session, GoodsSyncRequest request) {
        UserInfo admin = requireAdmin(session);
        var job = new GoodsSyncJob();
        job.setTenantKey(admin.getTenantKey());
        job.setSourceKey(config.getSourceKey());
        job.setRequesterId(admin.getId());
        job.setBrandName(request.brandName());
        job.setIdempotencyKey(request.idempotencyKey());
        job.setPageSize(PAGE_SIZE);
        try {
            return tx.execute(
                    ignored -> {
                        var existing = replay(job);
                        if (existing != null) {
                            return existing;
                        }
                        requireReady();
                        if (Boolean.TRUE.equals(
                                jobs.coolingDown(job.getTenantKey(), job.getSourceKey()))) {
                            throw conflict("CATALOG_COOLDOWN", "账套正在冷却，请稍后再发起导入。");
                        }
                        // 首次请求也留出间隔，连续导入另一个品牌不会立即连发。
                        checkUpdated(jobs.insert(job, config.getImportIntervalSeconds()));
                        checkUpdated(jobs.audit(admin, job.getId(), "START"));
                        return GoodsSyncResponse.from(
                                jobs.find(job.getTenantKey(), job.getSourceKey(), job.getId()));
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
        var existing =
                jobs.findByKey(job.getTenantKey(), job.getSourceKey(), job.getIdempotencyKey());
        if (existing == null) {
            return null;
        }
        if (!Objects.equals(existing.getBrandName(), job.getBrandName())
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
        UserInfo admin = requireAdmin(session);
        if (resume) {
            requireReady();
        }
        return tx.execute(
                ignored -> {
                    var job = jobs.find(admin.getTenantKey(), config.getSourceKey(), id);
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
                    return GoodsSyncResponse.from(
                            jobs.find(admin.getTenantKey(), config.getSourceKey(), id));
                });
    }

    @Override
    public SourcePage sources(
            CurrentUserResponse session, String brand, String keyword, int page, int size) {
        UserInfo admin = requireAdmin(session);
        if ((brand != null && !BRANDS.contains(brand))
                || (keyword != null && keyword.length() > 128)
                || page < 1
                || size < 1
                || size > 100) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "CATALOG_INVALID_FILTER", "品牌、关键词或分页参数无效。");
        }
        String search = keyword == null || keyword.isBlank() ? null : keyword.strip();
        return tx.execute(
                ignored -> {
                    var items =
                            sources
                                    .page(
                                            admin.getTenantKey(),
                                            config.getSourceKey(),
                                            brand,
                                            search,
                                            size,
                                            (page - 1L) * size)
                                    .stream()
                                    .map(Source::from)
                                    .toList();
                    return new SourcePage(
                            items,
                            sources.count(
                                    admin.getTenantKey(), config.getSourceKey(), brand, search));
                });
    }

    private UserInfo requireAdmin(CurrentUserResponse session) {
        var user = users.findIdentity(session.tenantKey(), session.openId());
        if (user == null
                || !Objects.equals(session.id(), user.getId().toString())
                || !"ADMIN".equals(user.getRoleType())
                || !Integer.valueOf(0).equals(user.getIsDeleted())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "CATALOG_ADMIN_REQUIRED", "只有有效管理员可以访问源商品库。");
        }
        if (!config.hasImportScope()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_SCOPE_MISSING", "请先配置吉客云账套与飞书租户的绑定。");
        }
        if (!config.getTenantKey().equals(user.getTenantKey())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CATALOG_SCOPE_DENIED", "当前租户未授权访问此商品账套。");
        }
        return user;
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
            var job = jobs.findDue(config.getTenantKey(), config.getSourceKey());
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
                            job.isIncludeBlockup());
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
        int count = 0;
        for (Goods goods : page.goods()) {
            if (saveContent(job, goods)) {
                count++;
            }
        }
        String nextStatus = page.goods().isEmpty() ? "SUCCEEDED" : "RUNNING";
        if (jobs.advance(
                        job,
                        token,
                        nextStatus,
                        page.nextLastMaxId(),
                        page.goods().size(),
                        count,
                        config.getImportIntervalSeconds())
                != 1) {
            throw new LeaseLost();
        }
    }

    private boolean saveContent(GoodsSyncJob job, Goods goods) {
        String hash = hash(job, goods);
        int collision = 0;
        for (var candidate : sources.matchingHash(job, hash)) {
            if (goods.equals(candidate.original())) {
                if (job.getId().equals(candidate.getLastSeenJobId())) {
                    return false;
                }
                checkUpdated(sources.markSeen(job, candidate.getId()));
                return true;
            }
            collision = candidate.getCollisionNo() + 1;
        }
        checkUpdated(sources.insert(job, goods, hash, collision));
        return true;
    }

    private static void validateFields(Goods goods) {
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
            goods.goodsField5()
        };
    }

    private static String hash(GoodsSyncJob job, Goods goods) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            Stream.concat(
                            Stream.of(job.getTenantKey(), job.getSourceKey(), job.getBrandName()),
                            Stream.of(fields(goods)))
                    .forEach(
                            value -> {
                                // 固定顺序及 UTF-8 字节长度，区分 NULL、空串及字段边界。
                                byte[] bytes =
                                        value == null
                                                ? null
                                                : value.getBytes(StandardCharsets.UTF_8);
                                digest.update(
                                        ByteBuffer.allocate(Integer.BYTES)
                                                .putInt(bytes == null ? -1 : bytes.length)
                                                .array());
                                if (bytes != null) {
                                    digest.update(bytes);
                                }
                            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 Java 环境缺少 SHA-256", impossible);
        }
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
