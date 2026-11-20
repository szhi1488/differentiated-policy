package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.auditSummary;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.formatTime;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.now;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.offset;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.policyNumber;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.validateFilter;
import static com.company.policy.support.policy.PolicyFormValidation.invalid;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyReviewQuery;
import com.company.policy.dto.PolicyReviewRequest;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.PolicyApplyMapper;
import com.company.policy.mapper.PolicyReviewMapper;
import com.company.policy.service.CatalogAccess;
import com.company.policy.service.PolicyCoverageService;
import com.company.policy.service.PolicyReviewService;
import com.company.policy.support.policy.PolicyFormValidation;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyResponse;
import com.company.policy.vo.PolicyResponse.AuditDetail;
import com.company.policy.vo.PolicyResponse.AuditSummary;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.company.policy.vo.PolicyReviewResponse.Coverage;
import com.company.policy.vo.PolicyReviewResponse.Detail;
import com.company.policy.vo.PolicyReviewResponse.Effective;
import com.company.policy.vo.PolicyReviewResponse.Page;
import com.company.policy.vo.PolicyReviewResponse.Review;
import com.company.policy.vo.PolicyReviewResponse.Summary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class PolicyReviewServiceImpl implements PolicyReviewService {
    private static final Set<String> STATUSES = Set.of("PENDING", "APPROVED", "REJECTED");
    private static final Set<String> CHANNELS = Set.of("RETAIL", "BILLION");
    private final PolicyCoverageService coverage;
    private final CatalogAccess access;
    private final PolicyReviewMapper reviews;
    private final PolicyApplyMapper policies;
    private final ObjectMapper json;
    private final PolicySnapshotCodec snapshots;

    PolicyReviewServiceImpl(
            PolicyCoverageService coverage,
            CatalogAccess access,
            PolicyReviewMapper reviews,
            PolicyApplyMapper policies,
            ObjectMapper json,
            PolicySnapshotCodec snapshots) {
        this.coverage = coverage;
        this.access = access;
        this.reviews = reviews;
        this.policies = policies;
        this.json = json;
        this.snapshots = snapshots;
    }

    @Override
    public Page list(CurrentUserResponse session, PolicyReviewQuery query) {
        var admin = access.requirePolicyAdmin(session);
        long start = offset(query.page(), query.pageSize());
        validateFilter(query.status(), STATUSES, "审核状态");
        validateFilter(query.perspective(), CHANNELS, "渠道");
        validateFilter(query.platformCode(), PolicyFormValidation.RETAIL, "平台");
        validateFilter(query.verifyType(), PolicyFormValidation.VERIFY_TYPES, "核销类型");
        validateFilter(query.sceneType(), PolicyFormValidation.SCENES, "涉及场景");
        if (query.submittedFrom() != null
                && query.submittedBefore() != null
                && !query.submittedFrom().isBefore(query.submittedBefore())) {
            throw invalid("提交日期范围无效。");
        }
        var filters =
                new PolicyReviewQuery(
                        query.page(),
                        query.pageSize(),
                        query.status(),
                        query.perspective(),
                        query.platformCode(),
                        query.verifyType(),
                        query.sceneType(),
                        like(query.applicant()),
                        query.submittedFrom(),
                        query.submittedBefore(),
                        like(query.keyword()));
        var counts =
                reviews.counts(admin.getTenantKey(), filters).stream()
                        .collect(
                                Collectors.toMap(
                                        PolicyReviewMapper.StatusCount::status,
                                        PolicyReviewMapper.StatusCount::total));
        long total =
                query.status() == null
                        ? counts.values().stream().mapToLong(Long::longValue).sum()
                        : counts.getOrDefault(query.status(), 0L);
        var items =
                start >= total
                        ? List.<Summary>of()
                        : reviews.list(admin.getTenantKey(), filters, start).stream()
                                .map(entry -> summary(entry, entry.getCreatedAt(), admin))
                                .toList();
        return new Page(items, total, query.page(), query.pageSize(), counts);
    }

    @Override
    public Detail detail(CurrentUserResponse session, long id, boolean includeCoverage) {
        var admin = access.requirePolicyAdmin(session);
        var entry = find(admin, id);
        var log = current(entry);
        var snapshot = snapshot(log);
        return new Detail(
                summary(entry, log.getCreatedAt(), admin),
                snapshot,
                auditSummary(log),
                !includeCoverage || "REJECTED".equals(entry.getStatus())
                        ? null
                        : coverage.submitted(entry, log, snapshot));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Review review(CurrentUserResponse session, long id, PolicyReviewRequest request) {
        var admin = access.requirePolicyAdmin(session);
        var entry = find(admin, id);
        checkPending(entry, request.recordVersion(), request.submissionVersion());
        if (!reviewable(entry, admin)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "POLICY_SELF_REVIEW", "申请人须由其他管理员审核。");
        }
        String status =
                switch (request.decision() == null ? "" : request.decision()) {
                    case "APPROVE" -> "APPROVED";
                    case "REJECT" -> "REJECTED";
                    default -> throw invalid("请选择通过或驳回。");
                };
        String remark = request.reviewRemark() == null ? null : request.reviewRemark().strip();
        if (remark != null && remark.codePointCount(0, remark.length()) > 512) {
            throw invalid("审核意见最多 512 字。");
        }
        var time = now();
        var tenant = admin.getTenantKey();
        int version = entry.getCurrentVersion();
        int record = entry.getRecordVersion();
        if (reviews.review(id, tenant, record, version, status, time) != 1
                || reviews.reviewAudit(
                                id,
                                tenant,
                                version,
                                status,
                                admin.getId(),
                                admin.getName(),
                                remark == null || remark.isEmpty() ? null : remark,
                                time)
                        != 1) {
            throw versionConflict();
        }
        return new Review(Long.toString(id), status, record + 1, version, formatTime(time));
    }

    @Override
    public Coverage preview(
            CurrentUserResponse session, long id, int recordVersion, int submissionVersion) {
        var entry = find(access.requirePolicyAdmin(session), id);
        checkPending(entry, recordVersion, submissionVersion);
        var log = current(entry);
        return coverage.submitted(entry, log, snapshot(log));
    }

    @Override
    public Effective effective(
            CurrentUserResponse session,
            String perspective,
            String platformCode,
            long goodsId,
            String at) {
        var admin = access.requirePolicyAdmin(session);
        validateFilter(perspective, CHANNELS, "渠道");
        validateFilter(
                platformCode,
                "BILLION".equals(perspective)
                        ? PolicyFormValidation.BILLION
                        : PolicyFormValidation.RETAIL,
                "平台");
        if (goodsId < 1) {
            throw invalid("商品 ID 须为正整数。");
        }
        var time = at == null ? now() : PolicyFormValidation.time(at);
        var matches =
                reviews.winners(
                        admin.getTenantKey(),
                        perspective,
                        time,
                        null,
                        null,
                        platformCode,
                        goodsId,
                        null);
        if (matches.isEmpty()) {
            return new Effective(formatTime(time), null, null);
        }
        var match = matches.get(0);
        return new Effective(
                formatTime(time),
                PolicyCoverageServiceImpl.winner(match),
                snapshot(reviews.current(match.policyId(), admin.getTenantKey(), match.version())));
    }

    @Override
    public PolicyResponse.Page<AuditSummary> auditLogs(
            CurrentUserResponse session, long id, int page, int pageSize) {
        var entry = find(access.requirePolicyAdmin(session), id);
        var tenant = entry.getTenantKey();
        long start = offset(page, pageSize);
        long total = policies.countAuditLogs(id, tenant);
        var items =
                start >= total
                        ? List.<AuditSummary>of()
                        : policies.listAuditLogs(id, tenant, pageSize, start).stream()
                                .map(PolicyApplyServiceImpl::auditSummary)
                                .toList();
        return new PolicyResponse.Page<>(items, total, page, pageSize);
    }

    @Override
    public AuditDetail auditDetail(CurrentUserResponse session, long id, long logId) {
        var entry = find(access.requirePolicyAdmin(session), id);
        var log = policies.findAuditLog(id, entry.getTenantKey(), logId);
        if (log == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "POLICY_AUDIT_NOT_FOUND", "未找到该轮提交记录。");
        }
        return new AuditDetail(auditSummary(log), snapshot(log));
    }

    private PolicyApply find(UserInfo admin, long id) {
        var entry = reviews.find(id, admin.getTenantKey());
        if (entry == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "未找到已提交的政策。");
        }
        return entry;
    }

    private PolicyAuditLog current(PolicyApply entry) {
        return reviews.current(entry.getId(), entry.getTenantKey(), entry.getCurrentVersion());
    }

    private Summary summary(PolicyApply entry, LocalDateTime submittedAt, UserInfo admin) {
        return new Summary(
                entry.getId().toString(),
                policyNumber(entry.getId()),
                entry.getApplicantName(),
                entry.getChannelType(),
                decode(entry.getPlatforms(), new TypeReference<List<String>>() {}),
                entry.getWriteOffType(),
                entry.getScene(),
                formatTime(submittedAt),
                entry.getStatus(),
                entry.getRecordVersion(),
                entry.getCurrentVersion(),
                reviewable(entry, admin));
    }

    private Snapshot snapshot(PolicyAuditLog log) {
        if (log == null) {
            throw new IllegalStateException("政策提交快照缺失。");
        }
        return PolicyApplyServiceImpl.displaySnapshot(snapshots.read(log.getSnapshotData()));
    }

    private <T> T decode(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法读取政策快照。", error);
        }
    }

    private static boolean reviewable(PolicyApply entry, UserInfo admin) {
        return "PENDING".equals(entry.getStatus()) && !entry.getApplicantId().equals(admin.getId());
    }

    private static void checkPending(PolicyApply entry, Integer record, Integer round) {
        if (record == null || record < 1 || round == null || round < 1) {
            throw invalid("请提供有效的记录版本和提交轮次。");
        }
        if (!"PENDING".equals(entry.getStatus())
                || entry.getRecordVersion() != record
                || entry.getCurrentVersion() != round) {
            throw versionConflict();
        }
    }

    private static ApiException versionConflict() {
        return new ApiException(
                HttpStatus.CONFLICT, "POLICY_VERSION_CONFLICT", "审核状态或版本已更新，请重新读取政策。");
    }

    private static String like(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.strip();
        if (value.length() > 128) {
            throw invalid("筛选文字最多 128 字。");
        }
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
