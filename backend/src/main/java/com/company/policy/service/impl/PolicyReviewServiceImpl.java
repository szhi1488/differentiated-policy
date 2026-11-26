package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.auditSummary;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.formatTime;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.now;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.offset;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.policyNumber;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.validateFilter;
import static com.company.policy.support.policy.PolicyFormValidation.invalid;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.ChannelPlatform;
import com.company.policy.dto.PolicyReviewQuery;
import com.company.policy.dto.PolicyReviewRequest;
import com.company.policy.dto.PolicyTimelineQuery;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import com.company.policy.entity.PolicyConsent;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.PolicyApplyMapper;
import com.company.policy.mapper.PolicyPurchaseReviewMapper;
import com.company.policy.mapper.PolicyPurchaseReviewMapper.Permission;
import com.company.policy.mapper.PolicyReviewMapper;
import com.company.policy.mapper.PolicyReviewRoundMapper;
import com.company.policy.mapper.PolicyReviewSettingsMapper;
import com.company.policy.mapper.PolicyReviewWorkflowMapper;
import com.company.policy.mapper.PolicyReviewWorkflowMapper.Member;
import com.company.policy.mapper.UserManagementMapper;
import com.company.policy.service.CatalogAccess;
import com.company.policy.service.ExecutionNotices;
import com.company.policy.service.PolicyCoverageService;
import com.company.policy.service.PolicyReviewNotifications;
import com.company.policy.service.PolicyReviewService;
import com.company.policy.support.policy.PolicyConfirmation;
import com.company.policy.support.policy.PolicyFormValidation;
import com.company.policy.support.policy.PolicyScenes;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyApplicability;
import com.company.policy.vo.PolicyResponse;
import com.company.policy.vo.PolicyResponse.AuditDetail;
import com.company.policy.vo.PolicyResponse.AuditSummary;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.company.policy.vo.PolicyReviewResponse.Detail;
import com.company.policy.vo.PolicyReviewResponse.Effective;
import com.company.policy.vo.PolicyReviewResponse.OverridePreview;
import com.company.policy.vo.PolicyReviewResponse.Page;
import com.company.policy.vo.PolicyReviewResponse.Review;
import com.company.policy.vo.PolicyReviewResponse.Summary;
import com.company.policy.vo.PolicySubsidyTimeline;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    private final ExecutionNotices executionNotices;
    private final CatalogAccess access;
    private final PolicyReviewMapper reviews;
    private final PolicyPurchaseReviewMapper purchase;
    private final PolicyReviewRoundMapper rounds;
    private final PolicyReviewWorkflowMapper workflow;
    private final PolicyReviewSettingsMapper settings;
    private final UserManagementMapper users;
    private final PolicyReviewNotifications notifications;
    private final PolicyApplyMapper policies;
    private final ObjectMapper json;
    private final PolicySnapshotCodec snapshots;
    private final PolicyTimeHistory history;
    private final PolicyReviewProgressReader progress;
    private final com.company.policy.support.policy.PolicyBatchExtensions extensions;

    PolicyReviewServiceImpl(
            PolicyCoverageService coverage,
            CatalogAccess access,
            PolicyReviewMapper reviews,
            PolicyPurchaseReviewMapper purchase,
            PolicyReviewRoundMapper rounds,
            PolicyReviewWorkflowMapper workflow,
            PolicyReviewSettingsMapper settings,
            UserManagementMapper users,
            PolicyReviewNotifications notifications,
            PolicyApplyMapper policies,
            ObjectMapper json,
            PolicySnapshotCodec snapshots,
            com.company.policy.support.policy.PolicyBatchExtensions extensions,
            ExecutionNotices executionNotices,
            PolicyTimeHistory history,
            PolicyReviewProgressReader progress) {
        this.progress = progress;
        this.history = history;
        this.executionNotices = executionNotices;
        this.coverage = coverage;
        this.access = access;
        this.reviews = reviews;
        this.purchase = purchase;
        this.rounds = rounds;
        this.workflow = workflow;
        this.settings = settings;
        this.users = users;
        this.notifications = notifications;
        this.policies = policies;
        this.json = json;
        this.snapshots = snapshots;
        this.extensions = extensions;
    }

    private Long buyerScope(UserInfo user) {
        if (access.hasPermission(user, "policy.read.company_submitted")) return null;
        if (access.hasPermission(user, "policy.purchase.decide_assigned")) return user.getId();
        throw new ApiException(
                HttpStatus.FORBIDDEN, "POLICY_ADMIN_REQUIRED", "当前账号无采购审核或全公司政策查看权限。");
    }

    private PolicyApply currentReviewPolicy(UserInfo user, long id) {
        Long buyer = buyerScope(user);
        var entry = find(user, id);
        if (buyer != null
                && !rounds.purchaseVisible(
                        user.getTenantKey(), buyer, id, entry.getCurrentVersion()))
            throw new ApiException(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "未找到已指派的政策。");
        return entry;
    }

    private PolicyApply latestVisible(UserInfo user, long id, Long buyer) {
        var entry = reviews.latestVisible(id, user.getTenantKey(), buyer);
        if (entry == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "未找到可查看的政策轮次。");
        return entry;
    }

    @Override
    public Page list(CurrentUserResponse session, PolicyReviewQuery query) {
        var admin = access.requireActive(session);
        var buyer = buyerScope(admin);
        long start = offset(query.page(), query.pageSize());
        validateFilter(query.status(), STATUSES, "审核状态");
        validateFilter(query.reviewStage(), Set.of("PRODUCT_REVIEW", "PURCHASE_REVIEW"), "审核阶段");
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
                        query.reviewStage(),
                        query.perspective(),
                        query.platformCode(),
                        query.verifyType(),
                        query.sceneType(),
                        like(query.applicant()),
                        query.submittedFrom(),
                        query.submittedBefore(),
                        like(query.keyword()));
        var counts =
                reviews.counts(admin.getTenantKey(), filters, buyer).stream()
                        .collect(
                                Collectors.toMap(
                                        PolicyReviewMapper.StatusCount::status,
                                        PolicyReviewMapper.StatusCount::total));
        long total =
                query.status() == null
                        ? counts.values().stream().mapToLong(Long::longValue).sum()
                        : counts.getOrDefault(query.status(), 0L);
        var entries =
                start >= total
                        ? List.<PolicyApply>of()
                        : reviews.list(admin.getTenantKey(), filters, start, buyer);
        var ids =
                entries.stream()
                        .filter(entry -> "PENDING".equals(entry.getStatus()))
                        .map(PolicyApply::getId)
                        .toList();
        var allowed =
                ids.isEmpty()
                        ? Set.<Long>of()
                        : purchase.permissions(admin.getTenantKey(), admin.getId(), ids).stream()
                                .map(Permission::policyId)
                                .collect(Collectors.toSet());
        var items =
                entries.stream()
                        .map(
                                entry ->
                                        summary(
                                                entry,
                                                entry.getCreatedAt(),
                                                allowed.contains(entry.getId()),
                                                buyer == null))
                        .toList();
        return new Page(items, total, query.page(), query.pageSize(), counts);
    }

    @Override
    public Detail detail(CurrentUserResponse session, long id) {
        var admin = access.requireActive(session);
        var buyer = buyerScope(admin);
        var entry = latestVisible(admin, id, buyer);
        var log = current(entry);
        var snapshot = snapshot(log);
        return new Detail(
                summary(
                        entry,
                        log.getCreatedAt(),
                        "PENDING".equals(entry.getStatus()) && permission(entry, admin) != null,
                        buyer == null),
                snapshot,
                auditSummary(log),
                "APPROVED".equals(entry.getStatus())
                        ? history.read(
                                entry.getTenantKey(),
                                id,
                                log.getVersion(),
                                snapshot.form().endTime())
                        : null);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Review review(CurrentUserResponse session, long id, PolicyReviewRequest request) {
        var admin = access.requirePermission(session, "policy.purchase.decide_assigned");
        var entry = currentReviewPolicy(admin, id);
        checkPending(entry, request.recordVersion(), request.submissionVersion());
        if (entry.getApplicantId().equals(admin.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "POLICY_SELF_REVIEW", "本人提交的政策须由其他采购终审。");
        }
        requirePermission(entry, admin);
        boolean approve = approving(request);
        String status = approve ? "APPROVED" : "REJECTED";
        String remark = remark(request, false);
        lockUsers(entry, admin);
        if (approve)
            workflow.lockPlatformTeams(entry.getTenantKey(), id, entry.getCurrentVersion());
        var log = approve ? current(entry) : null;
        var snapshot = approve ? snapshot(log) : null;
        if (approve) {
            coverage.lockScope(
                    entry,
                    snapshot.form(),
                    snapshot.consent() == null ? List.of() : snapshot.consent().endAdjustments());
        }
        var tenant = admin.getTenantKey();
        var round = entry.getCurrentVersion();
        if (purchase.lockPending(tenant, id, round, entry.getRecordVersion()) == null)
            throw versionConflict();
        purchase.lockStage(tenant, id, round);
        var allowed = requirePermission(entry, admin);
        var time = now();
        if (approve) {
            var preview = preview(entry, log, snapshot, time);
            if (preview.requiresResubmission()) {
                boolean expired =
                        preview.applicability().status()
                                == PolicyApplicability.Status.NO_EFFECTIVE_PERIOD;
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        expired ? "POLICY_NO_EFFECTIVE_PERIOD" : "POLICY_RECONFIRM_REQUIRED",
                        expired
                                ? "该轮已无可生效时段，请驳回，由原申请人调整日期后重提。"
                                : "当前组合与申请人确认范围不同，请查看覆盖预览并驳回，由原申请人重新确认后提交。");
            }
        }
        var result = updatePolicy(entry, admin, status, remark, time, null);
        if (approve)
            extensions
                    .approve(entry, snapshot, admin, time)
                    .forEach(executionNotices::timeAdjusted);
        decide(
                entry,
                admin,
                "PURCHASE",
                allowed.memberId(),
                request.decision(),
                remark,
                "FINISHED",
                time);
        if (approve) executionNotices.approved(entry, snapshot, time);
        return result;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Review productReview(CurrentUserResponse session, long id, PolicyReviewRequest request) {
        var actor = access.requirePermission(session, "policy.product.decide_assigned");
        var tenant = actor.getTenantKey();
        validateVersions(request.recordVersion(), request.submissionVersion());
        int round = request.submissionVersion();
        if (rounds.productPermission(tenant, actor.getId(), id, round) == null) {
            if (rounds.visibleCount(tenant, actor.getId(), id, round) == 0)
                throw new ApiException(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "未找到可办理的政策。");
            checkPending(find(actor, id), request.recordVersion(), round);
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "POLICY_REVIEW_FORBIDDEN", "当前用户无权办理本轮产品初审。");
        }
        var entry = find(actor, id);
        checkPending(entry, request.recordVersion(), round);
        boolean approve = approving(request);
        String reason = remark(request, !approve);
        // 先核对请求版本再读成员，锁住主单时再次核对，以防期间已有人办理。
        var members = approve ? workflow.members(tenant, id, round) : List.<Member>of();
        var buyers =
                members.stream()
                        .filter(
                                m ->
                                        "PURCHASE".equals(m.groupCode())
                                                && "ACTIVE".equals(m.memberStatus()))
                        .toList();
        var ids = new TreeSet<Long>();
        ids.add(actor.getId());
        ids.add(entry.getApplicantId());
        buyers.forEach(m -> ids.add(m.userId()));
        var accounts = PolicyReviewAccounts.lockUsers(settings, tenant, ids);
        if (purchase.lockPending(tenant, id, round, request.recordVersion()) == null)
            throw versionConflict();
        purchase.lockStage(tenant, id, round);
        var memberId = rounds.productPermission(tenant, actor.getId(), id, round);
        if (memberId == null)
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "POLICY_REVIEW_FORBIDDEN", "当前用户无权办理本轮产品运营初审。");
        if (approve) requireEligibleBuyer(entry, actor.getId(), buyers, accounts);
        var next = approve ? "PURCHASE_REVIEW" : null;
        var time = now();
        var signer = accounts.get(actor.getId());
        var result =
                updatePolicy(entry, signer, approve ? "PENDING" : "REJECTED", reason, time, next);
        decide(
                entry,
                signer,
                "PRODUCT",
                memberId,
                request.decision(),
                reason,
                approve ? next : "FINISHED",
                time);
        return result;
    }

    // 转采购前按本次实际签署人和已锁定的账号复核。此时采购成员尚无待办。
    private void requireEligibleBuyer(
            PolicyApply entry, long actorId, List<Member> buyers, Map<Long, UserInfo> accounts) {
        if (buyers.size() == 1) {
            long buyerId = buyers.get(0).userId();
            if (buyerId != actorId
                    && buyerId != entry.getApplicantId()
                    && PolicyReviewAccounts.eligible(settings, accounts.get(buyerId), "PURCHASE"))
                return;
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "POLICY_REVIEW_ASSIGNEES_MISSING",
                "本轮指定的采购不具备终审资格，请联系管理员核对其账号和审核权限。");
    }

    private static boolean approving(PolicyReviewRequest request) {
        return switch (request.decision() == null ? "" : request.decision()) {
            case "APPROVE" -> true;
            case "REJECT" -> false;
            default -> throw invalid("请选择通过或驳回。");
        };
    }

    private static String remark(PolicyReviewRequest request, boolean required) {
        String value = PolicyFormValidation.trimReviewText(request.reviewRemark());
        if (required && value.isEmpty()) throw invalid("请填写初审退回原因。");
        if (value.codePointCount(0, value.length()) > 512) throw invalid("审核意见最多 512 字。");
        return value.isEmpty() ? null : value;
    }

    private void decide(
            PolicyApply entry,
            UserInfo actor,
            String group,
            long memberId,
            String decision,
            String reason,
            String nextStage,
            LocalDateTime time) {
        var tenant = entry.getTenantKey();
        int round = entry.getCurrentVersion();
        long id = entry.getId();
        if (purchase.decide(tenant, id, round, group, memberId, decision, reason, time) != 1
                || purchase.closeMembers(tenant, id, round, group, memberId) < 1
                || purchase.advance(tenant, id, round, group + "_REVIEW", nextStage) != 1)
            throw versionConflict();
        if ("REJECT".equals(decision) && purchase.assignRevision(tenant, id, round) != 1)
            throw versionConflict();
        notifications.decided(tenant, id, round, group, actor.getId(), decision, reason, time);
    }

    // 与分工变更、提交冻结一致，先按用户 ID 升序持锁，随后才锁机型、商品、申请和本轮节点。
    private void lockUsers(PolicyApply entry, UserInfo admin) {
        long actor = admin.getId();
        long applicant = entry.getApplicantId();
        var first = users.lockUser(admin.getTenantKey(), Math.min(actor, applicant));
        var last = users.lockUser(admin.getTenantKey(), Math.max(actor, applicant));
        var reviewer = actor < applicant ? first : last;
        if (reviewer == null
                || reviewer.isDeleted() != 0
                || !settings.eligible(admin.getTenantKey(), admin.getId(), "PURCHASE"))
            throw new ApiException(HttpStatus.FORBIDDEN, "POLICY_ADMIN_REQUIRED", "当前账号已失去采购终审资格。");
        admin.setName(reviewer.name());
    }

    private Permission permission(PolicyApply entry, UserInfo admin) {
        var found =
                purchase.permissions(admin.getTenantKey(), admin.getId(), List.of(entry.getId()));
        return found.isEmpty() ? null : found.get(0);
    }

    private Permission requirePermission(PolicyApply entry, UserInfo admin) {
        var permission = permission(entry, admin);
        if (permission == null) {
            // 初读与资格查询之间也可能已有同组成员办结，沿用版本冲突让客户端刷新结果。
            checkPending(
                    find(admin, entry.getId()),
                    entry.getRecordVersion(),
                    entry.getCurrentVersion());
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "POLICY_REVIEW_FORBIDDEN", "当前用户无权办理本轮采购终审，请刷新后查看。");
        }
        return permission;
    }

    private Review updatePolicy(
            PolicyApply entry,
            UserInfo signer,
            String status,
            String remark,
            LocalDateTime time,
            String stage) {
        long id = entry.getId();
        var tenant = signer.getTenantKey();
        int version = entry.getCurrentVersion();
        int record = entry.getRecordVersion();
        if (reviews.review(id, tenant, record, version, status, time) != 1
                || (!"PENDING".equals(status)
                        && reviews.reviewAudit(
                                        id,
                                        tenant,
                                        version,
                                        status,
                                        signer.getId(),
                                        signer.getName(),
                                        remark,
                                        time)
                                != 1)) {
            throw versionConflict();
        }
        return new Review(Long.toString(id), status, record + 1, version, formatTime(time), stage);
    }

    @Override
    public OverridePreview preview(
            CurrentUserResponse session, long id, int recordVersion, int submissionVersion) {
        var user = access.requireActive(session);
        var entry = currentReviewPolicy(user, id);
        if (!access.hasPermission(user, "policy.read.company_submitted"))
            requirePermission(entry, user);
        checkPending(entry, recordVersion, submissionVersion);
        var log = current(entry);
        return preview(entry, log, snapshot(log), now());
    }

    private OverridePreview preview(
            PolicyApply entry, PolicyAuditLog log, Snapshot snapshot, LocalDateTime at) {
        PolicyApplicability result;
        try {
            result = coverage.applicability(entry, snapshot, at, log.getCreatedAt(), log.getId());
        } catch (ApiException changed) {
            if (!"POLICY_ADJUSTMENT_CHANGED".equals(changed.getCode())) throw changed;
            var saved = snapshot.consent();
            var withoutCuts =
                    new PolicyConsent(
                            saved.ruleVersion(), saved.comparedRounds(), saved.decisions());
            result =
                    coverage.applicability(
                            entry,
                            snapshot.withConsent(withoutCuts),
                            at,
                            log.getCreatedAt(),
                            log.getId());
            return new OverridePreview(result, saved, true, "REJECT_FOR_RESUBMISSION");
        }
        var changed = PolicyConfirmation.requiresResubmission(snapshot.consent(), result);
        return new OverridePreview(
                result,
                snapshot.consent(),
                changed,
                changed ? "REJECT_FOR_RESUBMISSION" : "REVIEW");
    }

    @Override
    public PolicySubsidyTimeline subsidyTimeline(
            CurrentUserResponse session, PolicyTimelineQuery query) {
        var reader = access.requirePermission(session, "subsidy.read.company");
        query.validate();
        return coverage.subsidyTimeline(
                reader.getTenantKey(),
                query,
                ChannelPlatform.all().stream().filter(query::matches).toList(),
                access.subsidyBrandNames(reader));
    }

    @Override
    public Effective effective(
            CurrentUserResponse session,
            String perspective,
            String platformCode,
            long goodsId,
            String at) {
        var reader = access.requirePermission(session, "subsidy.read.company");
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
        var time = at == null ? null : PolicyFormValidation.time(at);
        return coverage.effective(
                reader.getTenantKey(),
                perspective,
                platformCode,
                goodsId,
                time,
                access.subsidyBrandNames(reader));
    }

    @Override
    public PolicyResponse.Page<AuditSummary> auditLogs(
            CurrentUserResponse session, long id, int page, int pageSize) {
        var user = access.requireActive(session);
        var buyer = buyerScope(user);
        var entry = latestVisible(user, id, buyer);
        var tenant = entry.getTenantKey();
        long start = offset(page, pageSize);
        long total = reviews.countVisibleAudits(id, tenant, buyer);
        var items =
                start >= total
                        ? List.<AuditSummary>of()
                        : reviews.visibleAudits(id, tenant, buyer, pageSize, start).stream()
                                .map(PolicyApplyServiceImpl::auditSummary)
                                .toList();
        return new PolicyResponse.Page<>(items, total, page, pageSize);
    }

    @Override
    public AuditDetail auditDetail(CurrentUserResponse session, long id, long logId) {
        var user = access.requireActive(session);
        var buyer = buyerScope(user);
        var entry = latestVisible(user, id, buyer);
        var log = policies.findAuditLog(id, entry.getTenantKey(), logId);
        if (log == null
                || (buyer != null
                        && !rounds.purchaseVisible(
                                user.getTenantKey(), user.getId(), id, log.getVersion()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "POLICY_AUDIT_NOT_FOUND", "未找到该轮提交记录。");
        }
        return new AuditDetail(
                auditSummary(log),
                snapshot(log),
                progress.read(entry.getTenantKey(), id, log.getVersion()));
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

    private Summary summary(
            PolicyApply entry,
            LocalDateTime submittedAt,
            boolean reviewable,
            boolean companyReader) {
        var scenes = PolicyScenes.read(json, entry.getSceneTypes());
        return new Summary(
                entry.getId().toString(),
                policyNumber(entry.getId()),
                entry.getApplicantName(),
                entry.getChannelType(),
                decode(entry.getPlatforms(), new TypeReference<List<String>>() {}),
                entry.getWriteOffType(),
                scenes,
                formatTime(submittedAt),
                entry.getStatus(),
                companyReader || "PENDING".equals(entry.getStatus())
                        ? entry.getRecordVersion()
                        : null,
                entry.getCurrentVersion(),
                reviewable,
                entry.getReviewStage(),
                entry.getProductReviewerName(),
                formatTime(entry.getProductReviewedAt()));
    }

    private Snapshot snapshot(PolicyAuditLog log) {
        if (log == null) {
            throw new IllegalStateException("政策提交快照缺失。");
        }
        return snapshots.read(log.getSnapshotData());
    }

    private <T> T decode(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法读取政策快照。", error);
        }
    }

    private static void validateVersions(Integer record, Integer round) {
        if (record == null || record < 1 || round == null || round < 1) {
            throw invalid("请提供有效的记录版本和提交轮次。");
        }
    }

    private static void checkPending(PolicyApply entry, Integer record, Integer round) {
        validateVersions(record, round);
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
