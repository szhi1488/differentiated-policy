package com.company.policy.service.impl;

import com.company.policy.common.config.JackyunProperties;
import com.company.policy.entity.CatalogCleaningJob;
import com.company.policy.entity.CatalogCleaningJobItem;
import com.company.policy.entity.SourceGoodsItem;
import com.company.policy.mapper.CatalogCleaningMapper;
import com.company.policy.mapper.CatalogCleaningMapper.JobCount;
import com.company.policy.service.CatalogCleaningWorker;
import com.company.policy.service.impl.CatalogCleaningResultWriter.Mapping;
import com.company.policy.service.impl.CatalogCleaningResultWriter.MappingConflict;
import com.company.policy.vo.CatalogCleaningResponse.Candidate;
import com.company.policy.vo.CatalogCleaningResponse.Status;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class CatalogCleaningWorkerImpl implements CatalogCleaningWorker {
    private static final int BATCH_SIZE = 100;
    private final CatalogCleaningMapper mapper;
    private final CatalogCleaningResultWriter writer;
    private final JackyunProperties config;
    private final TransactionTemplate tx;
    private long lastWarning = System.nanoTime() - TimeUnit.MINUTES.toNanos(1);

    CatalogCleaningWorkerImpl(
            CatalogCleaningMapper mapper,
            CatalogCleaningResultWriter writer,
            JackyunProperties config,
            PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.writer = writer;
        this.config = config;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(20);
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
                        String status = batchStatus(mapper.jobCounts(List.of(job.getId())));
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

    private void processMember(
            CatalogCleaningJob job, String token, CatalogCleaningJobItem member) {
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
            CatalogCleaningJobItem member,
            Candidate candidate,
            boolean failed) {
        tx.executeWithoutResult(
                ignored -> {
                    lockClaim(job, token);
                    var source = mapper.lockSource(job.getSourceKey(), member.sourceId());
                    if (!matchesMember(job, member, source)) {
                        checked(mapper.finishMember(member, "SKIPPED"));
                        return;
                    }
                    var outcome =
                            failed
                                    ? saveFailure(job, source, candidate)
                                    : applyCandidate(job, source, candidate);
                    checked(mapper.finishMember(member, outcome.name()));
                });
    }

    private static boolean matchesMember(
            CatalogCleaningJob job, CatalogCleaningJobItem member, SourceGoodsItem source) {
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
        writer.persist(job, source, failure, Status.ERROR, null, null, failure.explanation());
        return Status.ERROR;
    }

    private Status applyCandidate(
            CatalogCleaningJob job, SourceGoodsItem source, Candidate candidate) {
        if (candidate.code() != null) {
            return saveReview(job, source, candidate);
        }
        Mapping mapping;
        try {
            mapping = writer.resolve(source, candidate, "AUTO");
        } catch (MappingConflict conflict) {
            return saveReview(
                    job,
                    source,
                    CatalogCleaningRule.issue(
                            candidate, conflict.getCode(), conflict.getMessage(), "REVIEW"));
        }
        boolean manual = "MANUAL".equals(source.getMappingOrigin());
        var status = manual ? Status.MANUAL_PASSED : Status.AUTO_PASSED;
        writer.persist(
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
        writer.persist(
                job,
                source,
                candidate,
                Status.REVIEW_REQUIRED,
                null,
                null,
                candidate.explanation());
        return Status.REVIEW_REQUIRED;
    }

    private void lockClaim(CatalogCleaningJob job, String token) {
        if (mapper.lockClaim(job, token) == null) {
            throw new LeaseLost();
        }
    }

    private static String batchStatus(List<JobCount> counts) {
        long total = 0;
        long pending = 0;
        long errors = 0;
        long skipped = 0;
        for (var count : counts) {
            total += count.total();
            switch (count.status()) {
                case "PENDING" -> pending = count.total();
                case "ERROR" -> errors = count.total();
                case "SKIPPED" -> skipped = count.total();
                default -> {}
            }
        }
        if (pending > 0) {
            return "RUNNING";
        }
        if (errors == total) {
            return "FAILED";
        }
        return errors > 0 || skipped > 0 ? "PARTIAL" : "COMPLETED";
    }

    private static void checked(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("清洗任务或明细更新行数不符");
        }
    }

    private static final class LeaseLost extends RuntimeException {}
}
