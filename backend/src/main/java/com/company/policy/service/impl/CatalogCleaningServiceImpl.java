package com.company.policy.service.impl;

import com.company.policy.common.config.JackyunProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.CatalogCleaningRequest.Decision;
import com.company.policy.dto.CatalogCleaningRequest.Review;
import com.company.policy.entity.GoodsSyncJob;
import com.company.policy.entity.SourceGoodsItem;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.CatalogCleaningMapper;
import com.company.policy.mapper.CatalogCleaningMapper.Change;
import com.company.policy.mapper.CatalogCleaningMapper.Query;
import com.company.policy.service.CatalogCleaningService;
import com.company.policy.vo.CatalogCleaningResponse.Candidate;
import com.company.policy.vo.CatalogCleaningResponse.Detail;
import com.company.policy.vo.CatalogCleaningResponse.Job;
import com.company.policy.vo.CatalogCleaningResponse.Log;
import com.company.policy.vo.CatalogCleaningResponse.Page;
import com.company.policy.vo.CatalogCleaningResponse.Result;
import com.company.policy.vo.CatalogCleaningResponse.Status;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class CatalogCleaningServiceImpl implements CatalogCleaningService {
    private static final String VERSION = CatalogCleaningRule.VERSION;
    private static final int BATCH_SIZE = 100;
    private final CatalogCleaningMapper mapper;
    private final CatalogAccess access;
    private final JackyunProperties config;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private long lastWarning = System.nanoTime() - TimeUnit.MINUTES.toNanos(1);

    CatalogCleaningServiceImpl(
            CatalogCleaningMapper mapper,
            CatalogAccess access,
            JackyunProperties config,
            ObjectMapper json,
            PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.access = access;
        this.config = config;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(20);
    }

    @Override
    public Job latest(CurrentUserResponse session, String brand) {
        UserInfo admin = access.requireAdmin(session);
        if (brand != null && !CatalogAccess.BRANDS.contains(brand)) {
            throw invalid("品牌无效。");
        }
        return tx.execute(
                ignored -> {
                    var job = mapper.latest(admin.getTenantKey(), config.getSourceKey(), brand);
                    return job == null ? null : snapshot(job);
                });
    }

    @Override
    public Job job(CurrentUserResponse session, long id) {
        UserInfo admin = access.requireAdmin(session);
        return tx.execute(ignored -> snapshot(requireJob(admin, id, false)));
    }

    @Override
    public Job start(CurrentUserResponse session, long id, int version, boolean retryErrors) {
        UserInfo admin = access.requireAdmin(session);
        return tx.execute(
                ignored -> {
                    var job = requireJob(admin, id, true);
                    if (!retryErrors && !"NOT_STARTED".equals(job.getCleanStatus())) {
                        return snapshot(job);
                    }
                    if (job.getRecordVersion() != version
                            || "RUNNING".equals(job.getCleanStatus())) {
                        throw conflict("CATALOG_CLEAN_JOB_CHANGED", "清洗任务已变化，请刷新后操作。");
                    }
                    if (retryErrors && mapper.resetErrors(job, null) == 0) {
                        throw conflict("CATALOG_CLEAN_NO_ERRORS", "该任务没有可重试的技术失败记录。");
                    }
                    queue(
                            job,
                            admin,
                            retryErrors ? "CLEAN_RETRY" : "START",
                            retryErrors ? "管理员重试技术失败记录。" : "管理员启动已导入商品的清洗。",
                            null);
                    return snapshot(requireJob(admin, id, false));
                });
    }

    @Override
    public Job reprocessReviews(CurrentUserResponse session, long id, int version) {
        UserInfo admin = access.requireAdmin(session);
        return tx.execute(
                ignored -> {
                    var job = requireJob(admin, id, true);
                    if (job.getRecordVersion() != version
                            || "RUNNING".equals(job.getCleanStatus())) {
                        throw conflict("CATALOG_CLEAN_JOB_CHANGED", "清洗任务已变化，请刷新后操作。");
                    }
                    int count =
                            mapper.resetReviews(
                                    job,
                                    CatalogCleaningRule.PREVIOUS_VERSION,
                                    CatalogCleaningRule.LEGACY_VERSION);
                    if (count == 0) {
                        throw conflict("CATALOG_CLEAN_NO_OLD_REVIEWS", "当前批次没有可重新识别的旧规则待审记录。");
                    }
                    queue(job, admin, "CLEAN_RETRY", "按新规则重新识别 " + count + " 条旧规则待审记录。", null);
                    return snapshot(requireJob(admin, id, false));
                });
    }

    @Override
    public Page<Result> preview(
            CurrentUserResponse session, long id, String ruleVersion, int page, int size) {
        UserInfo admin = access.requireAdmin(session);
        if (ruleVersion != null && !VERSION.equals(ruleVersion)) {
            throw invalid("清洗规则版本不可用。");
        }
        long offset = offset(page, size);
        return tx.execute(
                ignored -> {
                    var job = requireJob(admin, id, false);
                    var items =
                            mapper.preview(job, size, offset).stream()
                                    .map(
                                            source -> {
                                                var candidate = CatalogCleaningRule.parse(source);
                                                return result(
                                                        source,
                                                        candidate,
                                                        candidate.code() == null
                                                                ? Status.AUTO_PASSED.name()
                                                                : Status.REVIEW_REQUIRED.name(),
                                                        VERSION);
                                            })
                                    .toList();
                    return new Page<>(items, mapper.countPreview(job));
                });
    }

    @Override
    public Page<Result> results(
            CurrentUserResponse session,
            Long jobId,
            String brand,
            Status status,
            String ruleVersion,
            String keyword,
            int page,
            int size) {
        UserInfo admin = access.requireAdmin(session);
        if ((brand != null && !CatalogAccess.BRANDS.contains(brand))
                || (keyword != null && keyword.length() > 128)
                || (ruleVersion != null && ruleVersion.length() > 64)) {
            throw invalid("品牌、关键词或规则版本无效。");
        }
        long offset = offset(page, size);
        return tx.execute(
                ignored -> {
                    if (jobId != null) {
                        requireJob(admin, jobId, false);
                    }
                    var query =
                            new Query(
                                    admin.getTenantKey(),
                                    config.getSourceKey(),
                                    jobId,
                                    brand,
                                    status == null ? null : status.name(),
                                    ruleVersion,
                                    keyword == null || keyword.isBlank() ? null : keyword.strip());
                    var items =
                            mapper.results(query, size, offset).stream()
                                    .map(this::storedResult)
                                    .toList();
                    return new Page<>(items, mapper.count(query));
                });
    }

    @Override
    public Detail detail(CurrentUserResponse session, long id) {
        UserInfo admin = access.requireAdmin(session);
        return toDetail(requireSource(admin, id, false));
    }

    @Override
    public Detail review(CurrentUserResponse session, long id, Review request) {
        UserInfo admin = access.requireAdmin(session);
        if ((request.decision() == Decision.APPROVE) != (request.target() != null)) {
            throw invalid("通过审核必须提供完整的系列、型号、配置和颜色，排除时只填写原因。");
        }
        return tx.execute(
                ignored -> {
                    var initial = requireSource(admin, id, false);
                    var job = sourceJob(admin, initial);
                    var source = requireSource(admin, id, true);
                    checkSource(source, job, request.recordVersion(), Status.REVIEW_REQUIRED);
                    Candidate previous = readCandidate(source);
                    Candidate candidate =
                            previous == null ? CatalogCleaningRule.parse(source) : previous;
                    String reason = request.reason().strip();
                    if (request.decision() == Decision.EXCLUDE) {
                        persist(
                                job,
                                source,
                                candidate,
                                Status.EXCLUDED,
                                new Mapping("EXCLUDED", null, null, null),
                                admin,
                                reason,
                                "EXCLUDE");
                    } else {
                        if (CatalogCleaningRule.EXCLUSION.equals(candidate.code())) {
                            throw conflict("CATALOG_CLEAN_RULE_EXCLUSION", "该商品命中政策排除规则。");
                        }
                        candidate = CatalogCleaningRule.manual(request.target(), candidate);
                        if (!candidate.seriesName().endsWith("系列")
                                || candidate.seriesName().equals("系列")
                                || candidate.modelName().isBlank()
                                || candidate.spec().isBlank()
                                || candidate.colorName().isBlank()
                                || candidate.colorName().matches(".*[/、|].*")) {
                            throw invalid("标准系列须以系列结尾，型号、配置和一个明确的颜色均须填写。");
                        }
                        var mapping = resolve(job, source, candidate, "MANUAL");
                        persist(
                                job,
                                source,
                                candidate,
                                Status.MANUAL_PASSED,
                                mapping,
                                admin,
                                reason,
                                "CORRECT");
                    }
                    return toDetail(requireSource(admin, id, false));
                });
    }

    @Override
    public Job retrySource(CurrentUserResponse session, long id, int version) {
        UserInfo admin = access.requireAdmin(session);
        return tx.execute(
                ignored -> {
                    var job = sourceJob(admin, requireSource(admin, id, false));
                    var source = requireSource(admin, id, true);
                    checkSource(source, job, version, Status.ERROR);
                    if ("RUNNING".equals(job.getCleanStatus())
                            || mapper.resetErrors(job, id) != 1) {
                        throw conflict("CATALOG_CLEAN_JOB_CHANGED", "任务正在清洗或来源已进入其他导入批次，请刷新后操作。");
                    }
                    queue(job, admin, "CLEAN_RETRY", "管理员重试技术失败记录。", id);
                    return snapshot(requireJob(admin, job.getId(), false));
                });
    }

    @Override
    public Page<Log> logs(CurrentUserResponse session, long id, int page, int size) {
        UserInfo admin = access.requireAdmin(session);
        long offset = offset(page, size);
        return tx.execute(
                ignored -> {
                    var job = requireJob(admin, id, false);
                    var items =
                            mapper.logs(job.getTenantKey(), batch(job), size, offset).stream()
                                    .map(
                                            row ->
                                                    new Log(
                                                            Long.toString(row.id()),
                                                            id(job.getId()),
                                                            "SOURCE_GOODS_ITEM"
                                                                            .equals(
                                                                                    row
                                                                                            .entityType())
                                                                    ? Long.toString(row.entityId())
                                                                    : null,
                                                            row.actorType(),
                                                            row.operatorName(),
                                                            row.action(),
                                                            row.ruleVersion(),
                                                            row.reason(),
                                                            readTree(row.before()),
                                                            readTree(row.after()),
                                                            row.createdAt()))
                                    .toList();
                    return new Page<>(items, mapper.countLogs(job.getTenantKey(), batch(job)));
                });
    }

    @Override
    public void processNextBatch() {
        if (!config.hasImportScope()) {
            return;
        }
        try {
            var job =
                    mapper.due(
                            config.getTenantKey(),
                            config.getSourceKey(),
                            VERSION,
                            CatalogCleaningRule.PREVIOUS_VERSION,
                            CatalogCleaningRule.LEGACY_VERSION);
            String token = UUID.randomUUID().toString();
            if (job == null || mapper.claim(job, token) != 1) {
                return;
            }
            for (var source : mapper.pending(job, BATCH_SIZE)) {
                processSource(job, token, source);
            }
            tx.executeWithoutResult(
                    ignored -> {
                        lockClaim(job, token);
                        var state = snapshot(job);
                        long errors = state.resultCounts().get(Status.ERROR.name());
                        String status =
                                !mapper.pending(job, 1).isEmpty()
                                        ? "RUNNING"
                                        : errors == 0
                                                ? "COMPLETED"
                                                : errors == state.total() ? "FAILED" : "PARTIAL";
                        mapper.release(job, token, status);
                    });
        } catch (LeaseLost ignored) {
            // 已失去租约，当前执行者停止写入。
        } catch (RuntimeException error) {
            long now = System.nanoTime();
            if (now - lastWarning >= TimeUnit.MINUTES.toNanos(1)) {
                lastWarning = now;
                log.warn("商品清洗执行器暂不可用：{}", error.getClass().getSimpleName());
            }
        }
    }

    private void processSource(GoodsSyncJob job, String token, SourceGoodsItem source) {
        Candidate candidate = null;
        try {
            candidate = CatalogCleaningRule.parse(source, job.getCleanRuleVersion());
            try {
                applyCandidate(job, token, source, candidate);
            } catch (DuplicateKeyException | TransientDataAccessException concurrent) {
                // 目录唯一键或短暂锁冲突在新事务内重新校验一次。
                applyCandidate(job, token, source, candidate);
            }
        } catch (LeaseLost expired) {
            throw expired;
        } catch (RuntimeException error) {
            Candidate previous = candidate;
            tx.executeWithoutResult(
                    ignored -> {
                        lockClaim(job, token);
                        var current =
                                mapper.lockSource(
                                        job.getTenantKey(), job.getSourceKey(), source.getId());
                        if (samePending(source, current, job)) {
                            var failure =
                                    previous == null
                                            ? new Candidate(
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    List.of(),
                                                    null,
                                                    null,
                                                    null,
                                                    "CLEAN_EXECUTION_FAILED",
                                                    "清洗执行失败，可以重试。",
                                                    "RETRY",
                                                    List.of())
                                            : CatalogCleaningRule.issue(
                                                    previous,
                                                    "CLEAN_EXECUTION_FAILED",
                                                    "清洗执行失败，可以重试。",
                                                    "RETRY");
                            persist(
                                    job,
                                    current,
                                    failure,
                                    Status.ERROR,
                                    null,
                                    null,
                                    failure.explanation(),
                                    "CLEAN_ERROR");
                        }
                    });
            log.warn("来源商品 {} 清洗失败：{}", source.getId(), error.getClass().getSimpleName());
        }
    }

    private void applyCandidate(
            GoodsSyncJob job, String token, SourceGoodsItem source, Candidate candidate) {
        tx.executeWithoutResult(
                ignored -> {
                    lockClaim(job, token);
                    var current =
                            mapper.lockSource(
                                    job.getTenantKey(), job.getSourceKey(), source.getId());
                    if (!samePending(source, current, job)) {
                        return;
                    }
                    if (candidate.code() != null) {
                        saveReview(job, current, candidate);
                        return;
                    }
                    Mapping mapping;
                    try {
                        mapping = resolve(job, current, candidate, "AUTO");
                    } catch (ApiException conflict) {
                        saveReview(
                                job,
                                current,
                                CatalogCleaningRule.issue(
                                        candidate,
                                        conflict.getCode(),
                                        conflict.getMessage(),
                                        "REVIEW"));
                        return;
                    }
                    boolean manual = "MANUAL".equals(current.getMappingOrigin());
                    persist(
                            job,
                            current,
                            candidate,
                            manual ? Status.MANUAL_PASSED : Status.AUTO_PASSED,
                            manual ? null : mapping,
                            null,
                            candidate.explanation(),
                            "CLEAN_AUTO");
                });
    }

    private void saveReview(GoodsSyncJob job, SourceGoodsItem source, Candidate candidate) {
        persist(
                job,
                source,
                candidate,
                Status.REVIEW_REQUIRED,
                null,
                null,
                candidate.explanation(),
                "CLEAN_REVIEW");
    }

    private Mapping resolve(
            GoodsSyncJob job, SourceGoodsItem source, Candidate candidate, String origin) {
        String tenant = job.getTenantKey();
        String brand = source.getBrandName();
        boolean protect = "AUTO".equals(origin) && source.getCatalogVariantId() != null;
        var variant = mapper.variant(tenant, brand, candidate);
        if (protect
                && (variant == null
                        || !Objects.equals(source.getCatalogVariantId(), variant.id()))) {
            throw conflict("CATALOG_MAPPING_CHANGED", "候选规格与已生效映射不同，需要人工确认。");
        }
        if (variant == null) {
            checked(
                    mapper.insertVariant(
                            tenant,
                            brand,
                            candidate,
                            UUID.randomUUID().toString(),
                            candidate.modelName() + " " + candidate.spec()));
            variant = mapper.variant(tenant, brand, candidate);
        }
        if (!"ACTIVE".equals(variant.status())
                || !candidate.seriesName().equals(variant.seriesName())) {
            throw conflict("CATALOG_VARIANT_CONFLICT", "已有规格的系列或启用状态与候选不一致。");
        }
        var color = mapper.color(tenant, variant.id(), candidate.colorName());
        if (protect && (color == null || !Objects.equals(source.getVariantColorId(), color.id()))) {
            throw conflict("CATALOG_MAPPING_CHANGED", "候选颜色与已生效映射不同，需要人工确认。");
        }
        if (color == null) {
            checked(mapper.insertColor(tenant, variant.id(), candidate.colorName()));
            color = mapper.color(tenant, variant.id(), candidate.colorName());
        }
        if (!"ACTIVE".equals(color.status())) {
            throw conflict("CATALOG_COLOR_DISABLED", "该规格颜色尚未启用，需要管理员处理。");
        }
        return new Mapping("MAPPED", origin, variant.id(), color.id());
    }

    private void persist(
            GoodsSyncJob job,
            SourceGoodsItem source,
            Candidate candidate,
            Status status,
            Mapping mapping,
            UserInfo operator,
            String reason,
            String action) {
        String version =
                operator == null ? job.getCleanRuleVersion() : source.getCleanRuleVersion();
        var change =
                new Change(
                        status.name(),
                        version,
                        write(candidate),
                        status == Status.ERROR || status == Status.REVIEW_REQUIRED
                                ? candidate.code()
                                : null,
                        reason,
                        mapping == null ? null : mapping.status(),
                        mapping == null ? null : mapping.origin(),
                        mapping == null ? null : mapping.variantId(),
                        mapping == null ? null : mapping.colorId());
        checked(mapper.save(job, source, change, operator));
        var before =
                new AuditState(
                        source.getCleanStatus(),
                        readCandidate(source),
                        id(source.getCatalogVariantId()),
                        id(source.getVariantColorId()));
        var after =
                new AuditState(
                        status.name(),
                        candidate,
                        id(mapping == null ? source.getCatalogVariantId() : mapping.variantId()),
                        id(mapping == null ? source.getVariantColorId() : mapping.colorId()));
        checked(
                mapper.audit(
                        job,
                        operator,
                        version,
                        "SOURCE_GOODS_ITEM",
                        source.getId(),
                        action,
                        batch(job),
                        write(before),
                        write(after),
                        reason));
    }

    private void queue(
            GoodsSyncJob job, UserInfo operator, String action, String reason, Long sourceId) {
        checked(mapper.start(job, VERSION));
        String entity = sourceId == null ? "SYNC_JOB" : "SOURCE_GOODS_ITEM";
        checked(
                mapper.audit(
                        job,
                        operator,
                        VERSION,
                        entity,
                        sourceId == null ? job.getId() : sourceId,
                        action,
                        batch(job),
                        null,
                        null,
                        reason));
    }

    private Job snapshot(GoodsSyncJob job) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Status status : Status.values()) {
            counts.put(status.name(), 0L);
        }
        mapper.counts(job.getTenantKey(), job.getSourceKey(), job.getId())
                .forEach(row -> counts.put(row.status(), row.total()));
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long processed =
                total - counts.get(Status.PENDING.name()) - counts.get(Status.PROCESSING.name());
        int progress =
                total == 0
                        ? ("COMPLETED".equals(job.getCleanStatus()) ? 100 : 0)
                        : (int) (processed * 100 / total);
        return new Job(
                batch(job),
                id(job.getId()),
                job.getBrandName(),
                job.getCleanStatus(),
                job.getCleanRuleVersion() == null ? VERSION : job.getCleanRuleVersion(),
                job.getRecordVersion(),
                job.getCreatedAt(),
                total,
                processed,
                progress,
                counts,
                counts.get(Status.REVIEW_REQUIRED.name()) == 0
                        ? 0
                        : mapper.countReprocessable(
                                job,
                                CatalogCleaningRule.PREVIOUS_VERSION,
                                CatalogCleaningRule.LEGACY_VERSION));
    }

    private GoodsSyncJob requireJob(UserInfo admin, long id, boolean lock) {
        var job =
                lock
                        ? mapper.lockJob(admin.getTenantKey(), config.getSourceKey(), id)
                        : mapper.job(admin.getTenantKey(), config.getSourceKey(), id);
        if (job == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "CATALOG_CLEAN_JOB_NOT_FOUND", "未找到该已完成的导入任务。");
        }
        return job;
    }

    private SourceGoodsItem requireSource(UserInfo admin, long id, boolean lock) {
        var source =
                lock
                        ? mapper.lockSource(admin.getTenantKey(), config.getSourceKey(), id)
                        : mapper.source(admin.getTenantKey(), config.getSourceKey(), id);
        if (source == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CATALOG_SOURCE_NOT_FOUND", "未找到该来源商品。");
        }
        return source;
    }

    private GoodsSyncJob sourceJob(UserInfo admin, SourceGoodsItem source) {
        if (source.getCleanJobId() == null) {
            throw conflict("CATALOG_SOURCE_CHANGED", "该来源尚未生成可处理的清洗结果。");
        }
        return requireJob(admin, source.getCleanJobId(), true);
    }

    private void lockClaim(GoodsSyncJob job, String token) {
        if (mapper.lockClaim(job, token) == null) {
            throw new LeaseLost();
        }
    }

    private static boolean samePending(
            SourceGoodsItem expected, SourceGoodsItem actual, GoodsSyncJob job) {
        return actual != null
                && expected.getRecordVersion() == actual.getRecordVersion()
                && Objects.equals(job.getId(), actual.getLastSeenJobId())
                && Status.PENDING.name().equals(actual.getCleanStatus())
                && !"EXCLUDED".equals(actual.getMappingStatus());
    }

    private static void checkSource(
            SourceGoodsItem source, GoodsSyncJob job, int version, Status expected) {
        if (!Objects.equals(job.getId(), source.getCleanJobId())
                || version != source.getRecordVersion()
                || !expected.name().equals(source.getCleanStatus())) {
            throw conflict("CATALOG_SOURCE_CHANGED", "商品处理结果已变化，请刷新后操作。");
        }
    }

    private Detail toDetail(SourceGoodsItem source) {
        var candidate = readCandidate(source);
        return new Detail(
                result(source, candidate, source.getCleanStatus(), source.getCleanRuleVersion()),
                source.original(),
                candidate,
                source.getMappingStatus(),
                id(source.getCatalogVariantId()),
                id(source.getVariantColorId()));
    }

    private Result storedResult(SourceGoodsItem source) {
        return result(
                source,
                readCandidate(source),
                source.getCleanStatus(),
                source.getCleanRuleVersion());
    }

    private static Result result(
            SourceGoodsItem source, Candidate candidate, String status, String version) {
        return new Result(
                id(source.getId()),
                id(
                        source.getCleanJobId() == null
                                ? source.getLastSeenJobId()
                                : source.getCleanJobId()),
                source.getBrandName(),
                source.getGoodsName(),
                source.getGoodsNo(),
                status,
                version,
                source.getRecordVersion(),
                candidate == null ? null : candidate.seriesName(),
                candidate == null ? null : candidate.modelName(),
                candidate == null ? null : candidate.spec(),
                candidate == null ? null : candidate.colorName(),
                candidate == null ? List.of() : candidate.colorCandidates(),
                candidate == null ? null : candidate.explanation(),
                candidate == null ? null : candidate.code(),
                candidate == null ? null : candidate.suggestedAction(),
                source.getCleanedAt());
    }

    private Candidate readCandidate(SourceGoodsItem source) {
        try {
            return source.getCleanCandidate() == null
                    ? null
                    : json.readValue(source.getCleanCandidate(), Candidate.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("来源候选读取失败", error);
        }
    }

    private JsonNode readTree(String value) {
        try {
            return value == null ? null : json.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("清洗审计读取失败", error);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("清洗结果序列化失败", error);
        }
    }

    private static long offset(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw invalid("分页页码从 1 开始，每页支持 1 至 100 条。");
        }
        return (page - 1L) * size;
    }

    private static String id(Long id) {
        return id == null ? null : id.toString();
    }

    private static String batch(GoodsSyncJob job) {
        return "CLN-" + job.getId();
    }

    private static void checked(int count) {
        if (count != 1) {
            throw conflict("CATALOG_SOURCE_CHANGED", "清洗记录已变化，请刷新后重试。");
        }
    }

    private static ApiException conflict(String code, String reason) {
        return new ApiException(HttpStatus.CONFLICT, code, reason);
    }

    private static ApiException invalid(String reason) {
        return new ApiException(HttpStatus.BAD_REQUEST, "CATALOG_CLEAN_INVALID_REQUEST", reason);
    }

    private record Mapping(String status, String origin, Long variantId, Long colorId) {}

    private record AuditState(
            String status, Candidate candidate, String catalogVariantId, String variantColorId) {}

    private static final class LeaseLost extends RuntimeException {}
}
