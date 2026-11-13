package com.company.policy.service.impl;

import com.company.policy.common.config.JackyunProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.CatalogCleaningRequest.Decision;
import com.company.policy.dto.CatalogCleaningRequest.Review;
import com.company.policy.dto.CatalogCleaningRequest.Start;
import com.company.policy.entity.CatalogCleaningJob;
import com.company.policy.entity.SourceGoodsItem;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.CatalogCleaningMapper;
import com.company.policy.mapper.CatalogCleaningMapper.Change;
import com.company.policy.mapper.CatalogCleaningMapper.Member;
import com.company.policy.mapper.CatalogGoodsMapper;
import com.company.policy.service.CatalogCleaningService;
import com.company.policy.vo.CatalogCleaningResponse.Candidate;
import com.company.policy.vo.CatalogCleaningResponse.Detail;
import com.company.policy.vo.CatalogCleaningResponse.Job;
import com.company.policy.vo.CatalogCleaningResponse.Page;
import com.company.policy.vo.CatalogCleaningResponse.Preview;
import com.company.policy.vo.CatalogCleaningResponse.Result;
import com.company.policy.vo.CatalogCleaningResponse.Results;
import com.company.policy.vo.CatalogCleaningResponse.Status;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private static final Set<String> TABS =
            Set.of("PENDING", "PASSED", "REVIEW_REQUIRED", "ERROR", "EXCLUDED");
    private static final int BATCH_SIZE = 100;
    private final CatalogCleaningMapper mapper;
    private final CatalogAccess access;
    private final CatalogGoodsMapper goods;
    private final JackyunProperties config;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private long lastWarning = System.nanoTime() - TimeUnit.MINUTES.toNanos(1);

    CatalogCleaningServiceImpl(
            CatalogCleaningMapper mapper,
            CatalogAccess access,
            CatalogGoodsMapper goods,
            JackyunProperties config,
            ObjectMapper json,
            PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.access = access;
        this.goods = goods;
        this.config = config;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(20);
    }

    @Override
    public Page<Job> jobs(CurrentUserResponse session, int page, int size) {
        access.requireAdmin(session);
        long offset = offset(page, size);
        return tx.execute(
                ignored -> {
                    String account = config.getSourceKey();
                    var jobs = mapper.jobs(account, size, offset);
                    Map<Long, Map<String, Long>> counts = new HashMap<>();
                    if (!jobs.isEmpty()) {
                        mapper.jobCounts(jobs.stream().map(CatalogCleaningJob::getId).toList())
                                .forEach(
                                        row ->
                                                counts.computeIfAbsent(
                                                                row.jobId(), key -> new HashMap<>())
                                                        .put(row.status(), row.total()));
                    }
                    return new Page<>(
                            jobs.stream()
                                    .map(
                                            job ->
                                                    snapshot(
                                                            job,
                                                            counts.getOrDefault(
                                                                    job.getId(), Map.of())))
                                    .toList(),
                            mapper.countJobs(account));
                });
    }

    @Override
    public Job job(CurrentUserResponse session, long id) {
        access.requireAdmin(session);
        return tx.execute(ignored -> snapshot(requireJob(id)));
    }

    @Override
    public Job active(CurrentUserResponse session) {
        access.requireAdmin(session);
        return tx.execute(
                ignored -> {
                    var job = mapper.active(config.getSourceKey());
                    return job == null ? null : snapshot(job);
                });
    }

    @Override
    public Job start(CurrentUserResponse session, Start request) {
        var admin = access.requireAdmin(session);
        validateBrand(request.brandName());
        var desired = newJob(admin, request.brandName(), request.requestKey());
        desired.setCreatedDate(request.createdDate());
        desired.setIncludeReviews(request.includeReviews());
        return create(
                desired,
                () -> {
                    long count =
                            mapper.insertMembers(
                                    desired.getId(),
                                    desired.getSourceKey(),
                                    desired.getBrandName(),
                                    desired.getCreatedDate(),
                                    false,
                                    desired.isIncludeReviews(),
                                    null);
                    if (count != request.expectedTotal()) {
                        throw conflict("CATALOG_CLEAN_SCOPE_CHANGED", "符合条件的商品数量已变化，请刷新预览后重新确认。");
                    }
                });
    }

    private Job create(CatalogCleaningJob desired, Runnable selectMembers) {
        try {
            return tx.execute(
                    ignored -> {
                        Job existing = existingJob(desired);
                        if (existing != null) {
                            return existing;
                        }
                        checked(mapper.insertJob(desired));
                        selectMembers.run();
                        return snapshot(requireJob(desired.getId()));
                    });
        } catch (DuplicateKeyException concurrent) {
            // 唯一键保证同一请求只创建一次，同账套只运行一个清洗任务。
            Job existing = tx.execute(ignored -> existingJob(desired));
            if (existing != null) {
                return existing;
            }
            throw conflict("CATALOG_CLEAN_ACTIVE", "清洗任务已变化，请查看清洗记录后再试。");
        }
    }

    private Job existingJob(CatalogCleaningJob desired) {
        String account = desired.getSourceKey();
        var requested = mapper.requested(account, desired.getRequestKey());
        if (requested != null) {
            if (!sameScope(requested, desired)) {
                throw conflict("CATALOG_CLEAN_REQUEST_CONFLICT", "该请求标识已用于其他清洗范围。");
            }
            return snapshot(requested);
        }
        var active = mapper.active(account);
        if (active == null) {
            return null;
        }
        if (sameScope(active, desired)) {
            return snapshot(active);
        }
        throw conflict("CATALOG_CLEAN_ACTIVE", "当前账套已有清洗任务在运行，请在清洗记录查看进度。");
    }

    private static boolean sameScope(CatalogCleaningJob a, CatalogCleaningJob b) {
        return a.getBrandName().equals(b.getBrandName())
                && Objects.equals(a.getCreatedDate(), b.getCreatedDate())
                && Objects.equals(a.getTargetSourceId(), b.getTargetSourceId())
                && a.isIncludeReviews() == b.isIncludeReviews();
    }

    private CatalogCleaningJob newJob(UserInfo admin, String brand, String requestKey) {
        var job = new CatalogCleaningJob();
        job.setSourceKey(config.getSourceKey());
        job.setBrandName(brand);
        job.setRequesterId(admin.getId());
        job.setRequesterName(admin.getName());
        job.setRequestKey(requestKey);
        return job;
    }

    @Override
    public Preview preview(
            CurrentUserResponse session,
            String brand,
            LocalDate createdDate,
            boolean includeReviews,
            String keyword,
            int page,
            int size) {
        access.requireAdmin(session);
        validateBrand(brand);
        String search = keyword(keyword);
        long offset = offset(page, size);
        return tx.execute(
                ignored -> {
                    String account = config.getSourceKey();
                    var items =
                            mapper
                                    .results(
                                            account,
                                            brand,
                                            createdDate,
                                            false,
                                            search,
                                            null,
                                            size,
                                            offset)
                                    .stream()
                                    .map(this::storedResult)
                                    .toList();
                    long count =
                            mapper.counts(account, brand, createdDate, false, search).stream()
                                    .mapToLong(CatalogCleaningMapper.Count::total)
                                    .sum();
                    long eligible =
                            mapper.eligibleCount(
                                    account, brand, createdDate, false, includeReviews, null);
                    return new Preview(items, count, eligible);
                });
    }

    @Override
    public Results results(
            CurrentUserResponse session,
            String brand,
            LocalDate createdDate,
            boolean unknownDate,
            String status,
            String keyword,
            int page,
            int size) {
        access.requireAdmin(session);
        if (brand != null) {
            validateBrand(brand);
        }
        if ((unknownDate && createdDate != null) || (status != null && !TABS.contains(status))) {
            throw invalid("建档日期或处理状态无效。");
        }
        long offset = offset(page, size);
        String search = keyword(keyword);
        return tx.execute(
                ignored -> {
                    String account = config.getSourceKey();
                    var items =
                            mapper
                                    .results(
                                            account,
                                            brand,
                                            createdDate,
                                            unknownDate,
                                            search,
                                            status,
                                            size,
                                            offset)
                                    .stream()
                                    .map(this::storedResult)
                                    .toList();
                    var counts = emptyCounts();
                    mapper.counts(account, brand, createdDate, unknownDate, search)
                            .forEach(row -> counts.put(row.status(), row.total()));
                    long all = counts.values().stream().mapToLong(Long::longValue).sum();
                    counts.put("PENDING", counts.get("PENDING") + counts.get("PROCESSING"));
                    counts.put("PASSED", counts.get("AUTO_PASSED") + counts.get("MANUAL_PASSED"));
                    counts.put("ALL", all);
                    return new Results(items, counts.get(status == null ? "ALL" : status), counts);
                });
    }

    private static void validateBrand(String brand) {
        if (brand == null || !CatalogAccess.BRANDS.contains(brand)) {
            throw invalid("请选择有效的商品品牌。");
        }
    }

    private static String keyword(String value) {
        if (value != null && value.length() > 128) {
            throw invalid("关键词最多 128 个字符。");
        }
        return value == null || value.isBlank() ? null : value.strip();
    }

    @Override
    public Detail detail(CurrentUserResponse session, long id) {
        access.requireAdmin(session);
        return toDetail(requireSource(id, false));
    }

    @Override
    public Detail review(CurrentUserResponse session, long id, Review request) {
        UserInfo admin = access.requireAdmin(session);
        if ((request.decision() == Decision.APPROVE) != (request.target() != null)) {
            throw invalid("审核通过须提供完整的系列、型号、配置和颜色，审核不通过时无需提交规格。");
        }
        return tx.execute(
                ignored -> {
                    var source = requireSource(id, true);
                    checkSource(source, request.recordVersion(), Status.REVIEW_REQUIRED);
                    if (source.getCleanJobId() == null) {
                        throw conflict("CATALOG_SOURCE_CHANGED", "该商品尚无可审核的结果。");
                    }
                    var job = requireJob(source.getCleanJobId());
                    Candidate previous = readCandidate(source);
                    Candidate candidate =
                            previous == null ? CatalogCleaningRule.parse(source) : previous;
                    String reason = request.reason();
                    if (reason == null || reason.isBlank()) {
                        reason = request.decision() == Decision.APPROVE ? "审核通过" : "审核不通过";
                    } else {
                        reason = reason.strip();
                    }
                    if (request.decision() == Decision.EXCLUDE) {
                        persist(
                                job,
                                source,
                                candidate,
                                Status.EXCLUDED,
                                new Mapping("EXCLUDED", null, null, null),
                                admin,
                                reason);
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
                        var mapping = resolve(source, candidate, "MANUAL");
                        persist(
                                job,
                                source,
                                candidate,
                                Status.MANUAL_PASSED,
                                mapping,
                                admin,
                                reason);
                    }
                    return toDetail(requireSource(id, false));
                });
    }

    @Override
    public Job retrySource(CurrentUserResponse session, long id, int version, String requestKey) {
        var admin = access.requireAdmin(session);
        var initial = requireSource(id, false);
        var desired = newJob(admin, initial.getBrandName(), requestKey);
        desired.setTargetSourceId(id);
        return create(
                desired,
                () -> {
                    var source = requireSource(id, true);
                    checkSource(source, version, Status.ERROR);
                    String importStatus = mapper.importStatus(source.getLastSeenJobId());
                    if (!"SUCCEEDED".equals(importStatus) && !"CANCELLED".equals(importStatus)) {
                        throw conflict("CATALOG_SOURCE_CHANGED", "该商品最近的导入尚未完成，请稍后再试。");
                    }
                    checked(mapper.insertMember(desired.getId(), source));
                });
    }

    @Override
    public void processNextBatch() {
        if (!config.hasImportScope()) {
            return;
        }
        try {
            var job = mapper.due(config.getSourceKey());
            String token = UUID.randomUUID().toString();
            if (job == null || mapper.claim(job, token) != 1) {
                return;
            }
            for (var member : mapper.pending(job.getId(), BATCH_SIZE)) {
                processMember(job, token, member);
            }
            tx.executeWithoutResult(
                    ignored -> {
                        lockClaim(job, token);
                        var state = snapshot(job);
                        long errors = state.resultCounts().getOrDefault("ERROR", 0L);
                        long skipped = state.resultCounts().getOrDefault("SKIPPED", 0L);
                        String status =
                                state.processed() < state.total()
                                        ? "RUNNING"
                                        : errors == state.total()
                                                ? "FAILED"
                                                : errors > 0 || skipped > 0
                                                        ? "PARTIAL"
                                                        : "COMPLETED";
                        checked(mapper.release(job, token, status));
                    });
        } catch (LeaseLost ignored) {
            // 失去租约后停止写入，未完成明细由后续执行者接手。
        } catch (RuntimeException error) {
            long now = System.nanoTime();
            if (now - lastWarning >= TimeUnit.MINUTES.toNanos(1)) {
                lastWarning = now;
                log.warn("商品清洗执行器暂不可用：{}", error.getClass().getSimpleName());
            }
        }
    }

    private void processMember(CatalogCleaningJob job, String token, Member member) {
        Candidate candidate = null;
        try {
            var source = mapper.source(job.getSourceKey(), member.sourceId(), null);
            if (matchesMember(job, member, source)) {
                candidate = CatalogCleaningRule.parse(source);
            }
            try {
                processTransaction(job, token, member, candidate, false);
            } catch (DuplicateKeyException | TransientDataAccessException concurrent) {
                processTransaction(job, token, member, candidate, false);
            }
        } catch (LeaseLost expired) {
            throw expired;
        } catch (RuntimeException error) {
            processTransaction(job, token, member, candidate, true);
            log.warn("来源商品 {} 清洗失败：{}", member.sourceId(), error.getClass().getSimpleName());
        }
    }

    private void processTransaction(
            CatalogCleaningJob job,
            String token,
            Member member,
            Candidate candidate,
            boolean failed) {
        tx.executeWithoutResult(
                ignored -> {
                    lockClaim(job, token);
                    var source = mapper.lockSource(job.getSourceKey(), member.sourceId());
                    if (!matchesMember(job, member, source)) {
                        checked(mapper.finishMember(job.getId(), member.sourceId(), "SKIPPED"));
                        return;
                    }
                    var outcome =
                            failed
                                    ? saveFailure(job, source, candidate)
                                    : applyCandidate(job, source, candidate);
                    checked(mapper.finishMember(job.getId(), member.sourceId(), outcome.name()));
                });
    }

    private static boolean matchesMember(
            CatalogCleaningJob job, Member member, SourceGoodsItem source) {
        return source != null
                && source.getRecordVersion() == member.sourceVersion()
                && job.getBrandName().equals(source.getBrandName());
    }

    private Status saveFailure(
            CatalogCleaningJob job, SourceGoodsItem source, Candidate candidate) {
        var failure =
                candidate == null
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
                                candidate, "CLEAN_EXECUTION_FAILED", "清洗执行失败，可以重试。", "RETRY");
        persist(job, source, failure, Status.ERROR, null, null, failure.explanation());
        return Status.ERROR;
    }

    private Status applyCandidate(
            CatalogCleaningJob job, SourceGoodsItem source, Candidate candidate) {
        if (candidate.code() != null) {
            return saveReview(job, source, candidate);
        }
        Mapping mapping;
        try {
            mapping = resolve(source, candidate, "AUTO");
        } catch (ApiException conflict) {
            if (conflict.getStatus() != HttpStatus.CONFLICT) {
                throw conflict;
            }
            return saveReview(
                    job,
                    source,
                    CatalogCleaningRule.issue(
                            candidate, conflict.getCode(), conflict.getMessage(), "REVIEW"));
        }
        boolean manual = "MANUAL".equals(source.getMappingOrigin());
        var status = manual ? Status.MANUAL_PASSED : Status.AUTO_PASSED;
        persist(
                job,
                source,
                candidate,
                status,
                manual ? null : mapping,
                null,
                candidate.explanation());
        return status;
    }

    private Status saveReview(CatalogCleaningJob job, SourceGoodsItem source, Candidate candidate) {
        persist(
                job,
                source,
                candidate,
                Status.REVIEW_REQUIRED,
                null,
                null,
                candidate.explanation());
        return Status.REVIEW_REQUIRED;
    }

    private Mapping resolve(SourceGoodsItem source, Candidate candidate, String origin) {
        var memory = CatalogCleaningRule.memory(candidate.spec());
        if (memory == null) {
            throw invalid("请填写有效的运行内存和机身存储，例如 16GB+512GB。");
        }
        String brandName = source.getBrandName();
        String edition = Objects.requireNonNullElse(candidate.edition(), "");
        boolean protect = "AUTO".equals(origin) && source.getGoodsId() != null;
        Long brand = goods.lockBrand(brandName);
        var model = brand == null ? null : goods.lockModel(brand, candidate.modelName(), edition);
        if (model != null && !candidate.seriesName().equals(model.seriesName())) {
            throw conflict("CATALOG_SERIES_CONFLICT", "同一机型版本已归属其他系列，请核对系列。");
        }
        Long color = goods.lockColor(candidate.colorName());
        var existing =
                model == null || color == null
                        ? null
                        : goods.lockGoods(model.id(), memory.ram(), memory.rom(), color);
        if (protect && (existing == null || !Objects.equals(source.getGoodsId(), existing.id()))) {
            throw conflict("CATALOG_MAPPING_CHANGED", "候选规格或颜色与已生效映射不同，需要人工确认。");
        }
        if (existing != null) {
            if (!"ACTIVE".equals(existing.catalogStatus())) {
                throw conflict("CATALOG_GOODS_INACTIVE", "该标准商品尚未启用，请先核对目录状态。");
            }
            if (existing.status() == null) {
                checked(goods.defaultToOnSale(existing.id()));
            }
            return new Mapping("MAPPED", origin, brand, existing.id());
        }

        // 在确认目录无业务冲突后建档，避免待审结果留下部分目录记录。
        if (brand == null) {
            checked(goods.insertBrand(brandName));
            brand = goods.lockBrand(brandName);
        }
        Long series =
                model == null
                        ? goods.lockSeries(brand, candidate.seriesName())
                        : Long.valueOf(model.seriesId());
        if (series == null) {
            checked(goods.insertSeries(brand, candidate.seriesName()));
            series = goods.lockSeries(brand, candidate.seriesName());
        }
        if (model == null) {
            checked(goods.insertModel(brand, series, candidate.modelName(), edition));
            model = goods.lockModel(brand, candidate.modelName(), edition);
        }
        if (color == null) {
            checked(goods.insertColor(candidate.colorName()));
            color = goods.lockColor(candidate.colorName());
        }
        String sku = "G-" + UUID.randomUUID();
        String name =
                String.join(
                                " ",
                                candidate.modelName(),
                                memory.ram() + "+" + memory.rom(),
                                candidate.colorName())
                        + (edition.isEmpty() ? "" : "（" + edition + "）");
        String productNo = source.getGoodsNo();
        checked(
                goods.insertGoods(
                        sku,
                        productNo == null || productNo.isBlank() ? null : productNo,
                        brand,
                        series,
                        model.id(),
                        name,
                        memory.ram(),
                        memory.rom(),
                        color,
                        1));
        return new Mapping(
                "MAPPED",
                origin,
                brand,
                goods.lockGoods(model.id(), memory.ram(), memory.rom(), color).id());
    }

    private void persist(
            CatalogCleaningJob job,
            SourceGoodsItem source,
            Candidate candidate,
            Status status,
            Mapping mapping,
            UserInfo operator,
            String reason) {
        var change =
                new Change(
                        status.name(),
                        write(candidate),
                        status == Status.ERROR || status == Status.REVIEW_REQUIRED
                                ? candidate.code()
                                : null,
                        reason,
                        mapping == null ? null : mapping.status(),
                        mapping == null ? null : mapping.origin(),
                        mapping == null ? null : mapping.brandId(),
                        mapping == null ? null : mapping.goodsId());
        checked(mapper.save(job, source, change, operator));
    }

    private Job snapshot(CatalogCleaningJob job) {
        var counts = new HashMap<String, Long>();
        mapper.jobCounts(List.of(job.getId()))
                .forEach(row -> counts.put(row.status(), row.total()));
        return snapshot(job, counts);
    }

    private static Job snapshot(CatalogCleaningJob job, Map<String, Long> counts) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long processed = total - counts.getOrDefault("PENDING", 0L);
        int progress = total == 0 ? 0 : (int) (processed * 100 / total);
        return new Job(
                id(job.getId()),
                job.getBrandName(),
                job.getCreatedDate(),
                job.isIncludeReviews(),
                id(job.getTargetSourceId()),
                job.getStatus(),
                job.getRequesterName(),
                job.getCreatedAt(),
                total,
                processed,
                progress,
                counts);
    }

    private static Map<String, Long> emptyCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Status status : Status.values()) {
            counts.put(status.name(), 0L);
        }
        return counts;
    }

    private CatalogCleaningJob requireJob(long id) {
        var job = mapper.job(config.getSourceKey(), id);
        if (job == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "CATALOG_CLEAN_JOB_NOT_FOUND", "未找到该清洗任务。");
        }
        return job;
    }

    private SourceGoodsItem requireSource(long id, boolean lock) {
        var source =
                lock
                        ? mapper.lockSource(config.getSourceKey(), id)
                        : mapper.source(config.getSourceKey(), id, null);
        if (source == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CATALOG_SOURCE_NOT_FOUND", "未找到该来源商品。");
        }
        return source;
    }

    private void lockClaim(CatalogCleaningJob job, String token) {
        if (mapper.lockClaim(job, token) == null) {
            throw new LeaseLost();
        }
    }

    private static void checkSource(SourceGoodsItem source, int version, Status expected) {
        if (version != source.getRecordVersion()
                || !expected.name().equals(source.getCleanStatus())) {
            throw conflict("CATALOG_SOURCE_CHANGED", "商品处理结果已变化，请刷新后操作。");
        }
    }

    private Detail toDetail(SourceGoodsItem source) {
        var candidate = readCandidate(source);
        return new Detail(
                result(source, candidate),
                source.original(),
                candidate,
                source.getMappingStatus(),
                id(source.getCatalogBrandId()),
                id(source.getGoodsId()),
                id(source.getReviewedBy()),
                source.getReviewedAt(),
                source.getReviewReason());
    }

    private Result storedResult(SourceGoodsItem source) {
        return result(source, readCandidate(source));
    }

    private static Result result(SourceGoodsItem source, Candidate candidate) {
        return new Result(
                id(source.getId()),
                source.getBrandName(),
                source.getGoodsName(),
                source.getGoodsNo(),
                source.getSkuName(),
                source.getCreatedDate(),
                source.getCleanStatus(),
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

    private record Mapping(String status, String origin, Long brandId, Long goodsId) {}

    private static final class LeaseLost extends RuntimeException {}
}
