package com.company.policy.service.impl;

import static com.company.policy.support.policy.PolicyFormValidation.entries;
import static com.company.policy.support.policy.PolicyFormValidation.invalid;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.ChannelPlatform;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyDraftRequest.GoodsForm;
import com.company.policy.dto.PolicyDraftRequest.Link;
import com.company.policy.dto.PolicyDraftRequest.Target;
import com.company.policy.dto.PolicyListQuery;
import com.company.policy.dto.PolicyTimelineQuery;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import com.company.policy.entity.PolicyConsent;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.CatalogGoodsMapper;
import com.company.policy.mapper.PolicyApplyMapper;
import com.company.policy.mapper.PolicyApplyMapper.DetailLine;
import com.company.policy.mapper.UserPlatformMapper;
import com.company.policy.service.AttachmentService;
import com.company.policy.service.CatalogAccess;
import com.company.policy.service.PolicyApplyService;
import com.company.policy.service.PolicyCoverageService;
import com.company.policy.service.PolicyReviewWorkflow;
import com.company.policy.support.policy.PolicyApplicabilityEvaluator;
import com.company.policy.support.policy.PolicyConfirmation;
import com.company.policy.support.policy.PolicyFormValidation;
import com.company.policy.support.policy.PolicyScenes;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.vo.ApplicantCatalogResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyApplicability;
import com.company.policy.vo.PolicyResponse;
import com.company.policy.vo.PolicyResponse.ApprovedEnd;
import com.company.policy.vo.PolicyResponse.AuditDetail;
import com.company.policy.vo.PolicyResponse.AuditSummary;
import com.company.policy.vo.PolicyResponse.Detail;
import com.company.policy.vo.PolicyResponse.Page;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.company.policy.vo.PolicyResponse.SubmissionPreview;
import com.company.policy.vo.PolicyResponse.Summary;
import com.company.policy.vo.PolicySubsidyTimeline;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PolicyApplyServiceImpl implements PolicyApplyService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Set<String> STATUSES = Set.of("DRAFT", "PENDING", "APPROVED", "REJECTED");
    private static final Set<String> PERSPECTIVES = Set.of("RETAIL", "BILLION");
    private final PolicyCoverageService coverage;
    private final PolicyReviewWorkflow workflow;
    private final CatalogAccess access;
    private final UserPlatformMapper platforms;
    private final PolicyApplyMapper policies;
    private final CatalogGoodsMapper catalog;
    private final ObjectMapper json;
    private final PolicySnapshotCodec snapshots;
    private final AttachmentService attachments;

    PolicyApplyServiceImpl(
            PolicyCoverageService coverage,
            CatalogAccess access,
            UserPlatformMapper platforms,
            PolicyApplyMapper policies,
            CatalogGoodsMapper catalog,
            ObjectMapper json,
            PolicySnapshotCodec snapshots,
            AttachmentService attachments,
            PolicyReviewWorkflow workflow) {
        this.coverage = coverage;
        this.workflow = workflow;
        this.access = access;
        this.platforms = platforms;
        this.policies = policies;
        this.catalog = catalog;
        this.json = json;
        this.snapshots = snapshots;
        this.attachments = attachments;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PolicyResponse create(
            CurrentUserResponse session, PolicyDraftRequest request, String requestKey) {
        var owner = access.requireApplicant(session);
        if (request.recordVersion() != null) {
            throw invalid("首次保存草稿时请省略记录版本。");
        }
        var form = PolicyFormValidation.validate(request, false);
        requirePlatforms(owner, form, true);
        var entry = newDraft(owner, requestKey, form, session);
        try {
            checkUpdated(policies.insert(entry, now()));
            return response(entry);
        } catch (DuplicateKeyException error) {
            var saved =
                    policies.findByCode(entry.getPolicyNo(), owner.getTenantKey(), owner.getId());
            if (requestKey != null
                    && saved != null
                    && "DRAFT".equals(saved.getStatus())
                    && saved.getRecordVersion() == 1
                    && readForm(entry).equals(readForm(saved))) {
                return response(saved);
            }
            throw conflict("POLICY_CREATE_CONFLICT", "创建标识已使用，请核对原申请后继续。");
        }
    }

    private PolicyApply newDraft(
            UserInfo owner,
            String requestKey,
            PolicyDraftRequest form,
            CurrentUserResponse session) {
        var entry = new PolicyApply();
        entry.setPolicyNo(creationCode(owner, requestKey));
        entry.setTenantKey(owner.getTenantKey());
        entry.setApplicantId(owner.getId());
        entry.setApplicantName(owner.getName());
        entry.setStatus("DRAFT");
        entry.setRecordVersion(1);
        writeForm(entry, form);
        entry.setAttachmentUrls(encode(attachments.bind(form.attachmentInfo(), session)));
        return entry;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PolicyResponse extend(
            CurrentUserResponse session, long id, PolicyDraftRequest.Extension request) {
        return createTimeAdjustment(session, id, request, null, true);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PolicyResponse adjustTime(
            CurrentUserResponse session,
            long id,
            PolicyDraftRequest.Extension request,
            String requestKey) {
        return createTimeAdjustment(session, id, request, requestKey, false);
    }

    private PolicyResponse createTimeAdjustment(
            CurrentUserResponse session,
            long id,
            PolicyDraftRequest.Extension request,
            String requestKey,
            boolean extensionOnly) {
        var owner = access.requireApplicant(session);
        var source = owned(owner, id);
        checkVersion(source, request.recordVersion());
        var basis = adjustmentBasis(owner, source);
        var end = requestedEnd(request);
        var previousEnd = basis.target().previousEnd();
        if (request.expectedSourceEnd() != null
                && !previousEnd.equals(request.expectedSourceEnd())) {
            throw conflict("POLICY_EXTENSION_SOURCE_CHANGED", "原政策实际截止已变化，请刷新列表后重新选择。");
        }
        boolean extend = end.isAfter(PolicyFormValidation.time(previousEnd));
        if (extensionOnly && !extend) throw invalid("延期的新截止须晚于原政策截止。");
        var change = checkedChange(owner, session, basis, request, end, extend);
        var form = basis.snapshot().form().withTimeAdjustment(change);
        var entry = newDraft(owner, requestKey, form, session);
        if (extend) {
            entry.setEndTime(end);
            var start = PolicyFormValidation.time(previousEnd).plusSeconds(1);
            var existing = policies.findExtension(id, owner.getTenantKey(), owner.getId());
            if (existing != null) {
                if ("DRAFT".equals(existing.getStatus())
                        && existing.getEndTime().equals(end)
                        && existing.getStartTime().equals(start)
                        && change.equals(readForm(existing).goodsForm().timeAdjustment())) {
                    return response(existing);
                }
                throw conflict(
                        "POLICY_EXTENSION_EXISTS",
                        "该政策已有延期申请 " + policyNumber(existing.getId()) + "，请在「我的提交」查看该单。");
            }
            entry.setExtensionSourceId(id);
            entry.setStartTime(start);
        }
        if (requestKey != null) {
            var previous =
                    policies.findByCode(entry.getPolicyNo(), owner.getTenantKey(), owner.getId());
            if (previous != null) {
                if ("DRAFT".equals(previous.getStatus())
                        && readForm(entry).equals(readForm(previous))) return response(previous);
                throw conflict("POLICY_CREATE_CONFLICT", "创建标识已使用，请核对原申请。");
            }
        }
        checkUpdated(policies.insert(entry, now()));
        return response(entry);
    }

    private record AdjustmentBasis(
            Snapshot snapshot, PolicyConsent.Target target, LocalDateTime earliestEnd) {}

    private AdjustmentBasis adjustmentBasis(UserInfo owner, PolicyApply source) {
        if (!"APPROVED".equals(source.getStatus()))
            throw conflict("POLICY_STATE_CONFLICT", "只可调整已通过政策的时间。");
        if (platforms.lockApplicant(owner.getTenantKey(), owner.getId()) == null)
            throw conflict("POLICY_STATE_CONFLICT", "申请人状态已变化。");
        var logs = policies.listAuditLogs(source.getId(), source.getTenantKey(), 1, 0);
        if (logs.isEmpty()
                || !"APPROVED".equals(logs.get(0).getAuditStatus())
                || logs.get(0).getVersion() != source.getCurrentVersion())
            throw conflict("POLICY_STATE_CONFLICT", "原政策已通过轮次已变化。");
        var log = logs.get(0);
        var snapshot =
                snapshots.read(
                        policies.findSnapshot(
                                source.getId(), source.getTenantKey(), log.getVersion()));
        var form = snapshot.form();
        if (snapshot.sourcePolicyCode() == null && form.goodsForm().timeAdjustment() != null)
            throw invalid("请从对应原政策发起时间调整。");
        coverage.lockScope(source, form, List.of());
        var actual =
                coverage.approvedEnds(owner.getTenantKey(), now()).get(source.getId().toString());
        var through =
                actual == null
                        ? formatTime(PolicyApplicabilityEvaluator.originalThrough(snapshot))
                        : actual.actualEnd();
        var start = PolicyFormValidation.time(form.startTime());
        if (start.isBefore(log.getAuditTime())) start = log.getAuditTime();
        return new AdjustmentBasis(
                snapshot,
                new PolicyConsent.Target(
                        source.getId().toString(), log.getId().toString(), through),
                start.minusSeconds(1));
    }

    private EndAdjustment checkedChange(
            UserInfo owner,
            CurrentUserResponse session,
            AdjustmentBasis basis,
            PolicyDraftRequest.Extension request,
            LocalDateTime end,
            boolean extend) {
        var previous = PolicyFormValidation.time(basis.target().previousEnd());
        var form = basis.snapshot().form();
        if (extend) {
            requirePlatforms(owner, form, false);
            if (!end.isAfter(previous.plusSeconds(1)) || end.isBefore(now()))
                throw invalid("新到期时间须晚于延长段开始时刻，且不能早于当前北京时间。");
        } else {
            var assigned = platforms.assigned(owner.getTenantKey(), owner.getId());
            var responsible =
                    form.goodsForm().cards().stream()
                            .flatMap(card -> card.platformAssignments().stream())
                            .anyMatch(
                                    item ->
                                            assigned.contains(
                                                    new ChannelPlatform(
                                                            form.perspective(),
                                                            item.platformCode())));
            if (!responsible)
                throw new ApiException(
                        HttpStatus.FORBIDDEN, "POLICY_PLATFORM_FORBIDDEN", "该政策的实际使用平台已全部移交。");
            if (previous.isBefore(now())
                    || !end.isBefore(previous)
                    || end.isBefore(basis.earliestEnd()))
                throw invalid("截止须早于原截止，最早可选政策起效前一秒，已结束的政策不能再提前。");
        }
        var reason = request.reason() == null ? "" : request.reason().strip();
        if (reason.isBlank() || reason.codePointCount(0, reason.length()) > 512)
            throw invalid("请填写 1 至 512 字的调整原因。");
        return new EndAdjustment(
                basis.target(),
                formatTime(end),
                reason,
                attachments.resolveChangeFiles(request.attachmentInfo(), session));
    }

    private static LocalDateTime requestedEnd(PolicyDraftRequest.Extension request) {
        if (request.endTime() != null && request.endDate() == null)
            return PolicyFormValidation.time(request.endTime());
        if (request.endDate() != null && request.endTime() == null)
            return PolicyFormValidation.time(request.endDate() + "T23:59:59+08:00");
        throw invalid("请选择一个新的到期时间。");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PolicyResponse saveExtensionDate(
            CurrentUserResponse session, long id, PolicyDraftRequest.Extension request) {
        var owner = access.requireApplicant(session);
        var entry = owned(owner, id);
        checkEditable(entry, request.recordVersion());
        var saved = readForm(entry);
        var old = saved.goodsForm().timeAdjustment();
        boolean extend = entry.getExtensionSourceId() != null;
        if (!extend && old == null) throw invalid("请选择时间调整申请。");
        var sourceId =
                extend ? entry.getExtensionSourceId() : Long.valueOf(old.target().policyId());
        var basis = adjustmentBasis(owner, owned(owner, sourceId));
        var lastEnd =
                old != null
                        ? old.target().previousEnd()
                        : formatTime(entry.getStartTime().minusSeconds(1));
        var expected = request.expectedSourceEnd() == null ? lastEnd : request.expectedSourceEnd();
        if (!basis.target().previousEnd().equals(expected)) {
            throw conflict("POLICY_EXTENSION_SOURCE_CHANGED", "原政策截止已变化，请读取最新版本，核对后再保存。");
        }
        if (extend) entry.setStartTime(PolicyFormValidation.time(expected).plusSeconds(1));
        var end = requestedEnd(request);
        var change = checkedChange(owner, session, basis, request, end, extend);
        entry.setGoodsFormJson(encode(saved.withTimeAdjustment(change).goodsForm()));
        if (extend) entry.setEndTime(end);
        checkUpdated(policies.updateDraft(entry, now()));
        entry.setRecordVersion(entry.getRecordVersion() + 1);
        return response(entry);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PolicyResponse save(CurrentUserResponse session, long id, PolicyDraftRequest request) {
        var owner = access.requireApplicant(session);
        var entry = owned(owner, id);
        checkEditable(entry, request.recordVersion());
        if (entry.getExtensionSourceId() != null
                || readForm(entry).goodsForm().timeAdjustment() != null) {
            throw invalid("时间调整申请请通过调整政策时间入口保存。");
        }
        if ("RETAIL".equals(request.perspective()) && request.sceneTypes() == null) {
            var savedScenes = PolicyScenes.read(json, entry.getSceneTypes());
            if (savedScenes != null && savedScenes.size() > 1) {
                throw conflict("POLICY_SCENES_CHANGED", "当前申请有多个场景，请刷新页面后重新保存。");
            }
        }
        var form = PolicyFormValidation.validate(request, false);
        requirePlatforms(owner, form, true);
        writeForm(entry, form);
        entry.setAttachmentUrls(encode(attachments.bind(form.attachmentInfo(), session)));
        checkUpdated(policies.updateDraft(entry, now()));
        entry.setRecordVersion(entry.getRecordVersion() + 1);
        return response(entry);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteDraft(CurrentUserResponse session, long id, Integer recordVersion) {
        var entry = owned(access.requireApplicant(session), id);
        if (!"DRAFT".equals(entry.getStatus()) || entry.getCurrentVersion() != 0) {
            throw conflict("POLICY_STATE_CONFLICT", "只能删除本人尚未提交的草稿。");
        }
        checkEditable(entry, recordVersion);
        if (policies.deleteDraft(entry) != 1) {
            throw conflict("POLICY_VERSION_CONFLICT", "草稿已变更或已提交，请刷新列表后重试。");
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SubmissionPreview preview(CurrentUserResponse session, long id, Integer recordVersion) {
        return previewAdjusted(
                session, id, new PolicyDraftRequest.AdjustmentPreview(recordVersion, List.of()));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SubmissionPreview previewAdjusted(
            CurrentUserResponse session, long id, PolicyDraftRequest.AdjustmentPreview request) {
        var recordVersion = request.recordVersion();
        var owner = access.requireApplicant(session);
        var entry = owned(owner, id);
        checkEditable(entry, recordVersion);
        var prepared = prepareSubmission(owner, entry, request.endAdjustments(), false);
        var asOf = now();
        var preview = withAdjustments(prepared.snapshot(), request.endAdjustments(), session);
        var result = coverage.applicability(entry, preview, asOf);
        return new SubmissionPreview(
                prepared.snapshot(),
                coverage.previewDraft(entry, prepared.snapshot(), prepared.lines(), asOf),
                confirmationToken(entry, preview, result),
                result);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PolicyResponse submit(
            CurrentUserResponse session, long id, PolicyDraftRequest.Submission request) {
        var recordVersion = request.recordVersion();
        var owner = access.requireApplicant(session);
        var entry = owned(owner, id);
        checkEditable(entry, recordVersion);
        int workflowVersion = workflow.lockForSubmission(entry);
        var prepared = prepareSubmission(owner, entry, request.endAdjustments(), true);
        var now = now();
        var proposal = withAdjustments(prepared.snapshot(), request.endAdjustments(), session);
        var result = coverage.applicability(entry, proposal, now);
        var token = request.confirmationToken();
        if (token != null && !confirmationToken(entry, proposal, result).equals(token)) {
            throw conflict("POLICY_COVERAGE_CHANGED", "适用范围已变化，请重新预览并核对本次组合。");
        }
        if ((result.status() == PolicyApplicability.Status.NEEDS_CONFIRMATION
                        || !proposal.consent().endAdjustments().isEmpty())
                && token == null) {
            throw conflict("POLICY_CONFIRMATION_REQUIRED", "请先预览并确认本次适用范围和政策时间调整。");
        }
        var confirmed = PolicyConfirmation.confirm(result, request.decisions());
        var snapshot =
                proposal.withConsent(
                        proposal.consent().endAdjustments().isEmpty()
                                ? confirmed
                                : new PolicyConsent(
                                        proposal.consent().endAdjustments().stream()
                                                        .anyMatch(
                                                                com.company.policy.support.policy
                                                                                .PolicyEndTimes
                                                                        ::isExtension)
                                                ? 4
                                                : 3,
                                        confirmed.comparedRounds(),
                                        confirmed.decisions(),
                                        proposal.consent().endAdjustments()));
        var lines = prepared.lines();
        int version = snapshot.submissionVersion();
        checkUpdated(policies.markSubmitted(entry, now));
        checkUpdated(
                policies.insertAudit(
                        id,
                        entry.getTenantKey(),
                        version,
                        snapshots.write(snapshot),
                        now,
                        workflowVersion));
        // 分批写入同一事务，避免单条 SQL 的参数数随商品数和平台数相乘。
        for (int offset = 0; offset < lines.size(); offset += 500) {
            var batch = lines.subList(offset, Math.min(offset + 500, lines.size()));
            if (policies.insertDetails(id, version, batch, now) != batch.size()) {
                throw new IllegalStateException("政策商品明细写入数量不一致");
            }
        }
        var stage = workflow.submitted(entry, snapshot, now);
        entry.setStatus("PENDING");
        entry.setRecordVersion(entry.getRecordVersion() + 1);
        entry.setCurrentVersion(version);
        return response(entry, stage);
    }

    @Override
    public List<ChannelPlatform> assignedPlatforms(CurrentUserResponse session) {
        var owner = access.requireApplicant(session);
        return platforms.assigned(owner.getTenantKey(), owner.getId());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PolicySubsidyTimeline subsidyTimeline(
            CurrentUserResponse session, PolicyTimelineQuery query) {
        var owner = access.requireApplicant(session);
        query.validate();
        var scopes =
                platforms.assigned(owner.getTenantKey(), owner.getId()).stream()
                        .filter(query::matches)
                        .toList();
        if (query.platformCode() != null && scopes.isEmpty()) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "POLICY_PLATFORM_FORBIDDEN", "当前所选平台未分配给本人。");
        }
        return coverage.subsidyTimeline(owner.getTenantKey(), query, scopes);
    }

    private void requirePlatforms(UserInfo owner, PolicyDraftRequest form, boolean writing) {
        String tenant = owner.getTenantKey();
        long id = owner.getId();
        // 与分工和账号状态更新共用用户行锁，移交与写入按取得锁的顺序生效。
        if ((writing && platforms.lockApplicant(tenant, id) == null)
                || !platforms
                        .assigned(tenant, id)
                        .containsAll(
                                entries(form.selectedPlatforms()).stream()
                                        .map(code -> new ChannelPlatform(form.perspective(), code))
                                        .toList())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "POLICY_PLATFORM_FORBIDDEN", "负责平台已变化，请核对当前渠道并移除未分配的平台。");
        }
    }

    private record PreparedSubmission(Snapshot snapshot, List<DetailLine> lines) {}

    private PreparedSubmission prepareSubmission(
            UserInfo owner, PolicyApply entry, List<EndAdjustment> changes, boolean writing) {
        var saved = readForm(entry);
        var change = saved.goodsForm().timeAdjustment();
        if (entry.getExtensionSourceId() == null && change != null) {
            if (!entries(changes).isEmpty() && !List.of(change).equals(changes))
                throw invalid("单独提前结束申请仅可调整已选原单。");
            if (writing) coverage.lockScope(entry, saved, List.of(change));
            var source = owned(owner, Long.parseLong(change.target().policyId()));
            var original =
                    snapshots.read(
                            policies.findSnapshot(
                                    source.getId(),
                                    source.getTenantKey(),
                                    source.getCurrentVersion()));
            return new PreparedSubmission(
                    new Snapshot(
                            entry.getId().toString(),
                            policyNumber(entry.getId()),
                            owner.getId().toString(),
                            owner.getName(),
                            entry.getCurrentVersion() + 1,
                            saved,
                            original.goods(),
                            null,
                            null),
                    List.of());
        }
        if (entry.getExtensionSourceId() != null && change == null)
            throw invalid("请先填写延期原因并保存时间调整申请。");
        var form = PolicyFormValidation.validate(saved, true).withTimeAdjustment(change);
        requirePlatforms(owner, form, writing);
        var ids = targetIds(form);
        if (writing) {
            coverage.lockScope(entry, form, changes);
            for (int offset = 0; offset < ids.size(); offset += 500) {
                var batch = ids.subList(offset, Math.min(offset + 500, ids.size()));
                if (catalog.lockSelected(batch).size() != batch.size()) throw goodsChanged();
            }
        }
        return prepare(entry, form, ids);
    }

    private PreparedSubmission prepare(PolicyApply entry, PolicyDraftRequest form, List<Long> ids) {
        var goods = readGoods(ids);
        var lines = new ArrayList<DetailLine>();
        for (var card : form.goodsForm().cards()) {
            for (var target : card.targets()) {
                long goodsId = PolicyFormValidation.id(target.goodsId());
                var item = goods.get(goodsId);
                if (item == null || !item.onSale() || !sameConfiguration(item, target)) {
                    throw goodsChanged();
                }
                for (var assignment : card.platformAssignments()) {
                    lines.add(
                            new DetailLine(
                                    assignment.platformCode(),
                                    goodsId,
                                    PolicyFormValidation.id(target.spuId()),
                                    target.ram(),
                                    target.rom()));
                }
            }
        }

        int version = entry.getCurrentVersion() + 1;
        var snapshot =
                new Snapshot(
                        entry.getId().toString(),
                        policyNumber(entry.getId()),
                        entry.getApplicantId().toString(),
                        entry.getApplicantName(),
                        version,
                        form,
                        ids.stream().map(goods::get).toList(),
                        null,
                        sourcePolicyCode(entry));
        return new PreparedSubmission(snapshot, lines);
    }

    private Snapshot withAdjustments(
            Snapshot snapshot, List<EndAdjustment> changes, CurrentUserResponse session) {
        var single = snapshot.form().goodsForm().timeAdjustment();
        if (single != null && snapshot.sourcePolicyCode() == null) changes = List.of(single);
        var checked = new ArrayList<EndAdjustment>();
        for (var change : entries(changes)) {
            if (change == null || change.target() == null) throw invalid("请选择要调整时间的原政策。");
            var files = attachments.resolveChangeFiles(change.attachmentInfo(), session);
            checked.add(
                    new EndAdjustment(change.target(), change.endTime(), change.reason(), files));
        }
        return snapshot.withConsent(new PolicyConsent(3, List.of(), List.of(), checked));
    }

    private String confirmationToken(
            PolicyApply entry, Snapshot snapshot, PolicyApplicability result) {
        // 正在生效的首段按相对起点比较，用户阅读期间自然推进的秒数不会使确认失效。
        var ranges =
                PolicyConfirmation.ranges(result).stream()
                        .map(
                                d ->
                                        List.of(
                                                d.scope(),
                                                d.from().equals(result.effectiveFrom())
                                                        ? "START"
                                                        : d.from(),
                                                d.through(),
                                                d.action(),
                                                d.groups(),
                                                d.eligiblePolicyIds()))
                        .toList();
        var contents =
                encode(
                        List.of(
                                snapshot.id(),
                                entry.getRecordVersion(),
                                snapshot.goods(),
                                snapshot.consent().endAdjustments(),
                                result.status(),
                                PolicyConfirmation.comparedRounds(result),
                                ranges));
        try {
            var digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(contents.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Detail detail(CurrentUserResponse session, long id) {
        var entry = owned(access.requireApplicant(session), id);
        PolicyDraftRequest form;
        List<ApplicantCatalogResponse> goods;
        List<EndAdjustment> endAdjustments = List.of();
        if (editable(entry)) {
            form = readForm(entry);
            if ("REJECTED".equals(entry.getStatus())) {
                var last =
                        snapshots.read(
                                policies.findSnapshot(
                                        id, entry.getTenantKey(), entry.getCurrentVersion()));
                if (last.consent() != null) endAdjustments = last.consent().endAdjustments();
            }
            var ids = targetIds(form);
            var current = readGoods(ids);
            goods = ids.stream().filter(current::containsKey).map(current::get).toList();
        } else {
            var snapshot =
                    snapshots.read(
                            policies.findSnapshot(
                                    id, entry.getTenantKey(), entry.getCurrentVersion()));
            if (snapshot.consent() != null) endAdjustments = snapshot.consent().endAdjustments();
            form = snapshot.form();
            goods = snapshot.goods();
        }
        var change = form.goodsForm().timeAdjustment();
        String currentSourceEnd = null;
        if (editable(entry) && (change != null || entry.getExtensionSourceId() != null)) {
            var targetId =
                    change != null
                            ? change.target().policyId()
                            : entry.getExtensionSourceId().toString();
            var actual = coverage.approvedEnds(entry.getTenantKey(), now()).get(targetId);
            currentSourceEnd =
                    actual != null
                            ? actual.actualEnd()
                            : change != null
                                    ? change.target().previousEnd()
                                    : formatTime(entry.getStartTime().minusSeconds(1));
        }
        return new Detail(
                Long.toString(id),
                policyNumber(entry.getId()),
                entry.getStatus(),
                entry.getRecordVersion(),
                entry.getCurrentVersion(),
                entry.getApplicantName(),
                form,
                goods,
                sourcePolicyCode(entry),
                endAdjustments,
                "APPROVED".equals(entry.getStatus())
                        ? coverage.approvedEnds(entry.getTenantKey(), now()).get(Long.toString(id))
                        : null,
                currentSourceEnd,
                entry.getReviewStage());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Summary> mine(CurrentUserResponse session, PolicyListQuery query) {
        var owner = access.requireApplicant(session);
        long offset = offset(query.page(), query.pageSize());
        validateFilter(query.status(), STATUSES, "申请状态");
        validateFilter(query.perspective(), PERSPECTIVES, "渠道");
        validateFilter(query.platformCode(), PolicyFormValidation.RETAIL, "平台");
        long total = policies.countMine(owner.getTenantKey(), owner.getId(), query);
        var ends =
                offset >= total
                        ? Map.<String, ApprovedEnd>of()
                        : coverage.approvedEnds(owner.getTenantKey(), now());
        var applicant = new PolicyApply();
        applicant.setApplicantId(owner.getId());
        applicant.setTenantKey(owner.getTenantKey());
        var options = new HashMap<String, PolicyApplicability.AdjustmentOption>();
        if (offset < total) {
            for (var option : coverage.adjustableFor(applicant, now(), ends))
                options.put(option.target().policyId(), option);
        }
        var items =
                offset >= total
                        ? List.<Summary>of()
                        : policies
                                .listMine(owner.getTenantKey(), owner.getId(), query, offset)
                                .stream()
                                .map(
                                        entry ->
                                                summary(
                                                        entry,
                                                        ends.get(entry.getId().toString()),
                                                        options.get(entry.getId().toString())))
                                .toList();
        return new Page<>(items, total, query.page(), query.pageSize());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<AuditSummary> auditLogs(
            CurrentUserResponse session, long id, int page, int pageSize) {
        var entry = owned(access.requireApplicant(session), id);
        long offset = offset(page, pageSize);
        long total = policies.countAuditLogs(id, entry.getTenantKey());
        var items =
                offset >= total
                        ? List.<AuditSummary>of()
                        : policies
                                .listAuditLogs(id, entry.getTenantKey(), pageSize, offset)
                                .stream()
                                .map(PolicyApplyServiceImpl::auditSummary)
                                .toList();
        return new Page<>(items, total, page, pageSize);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AuditDetail auditDetail(CurrentUserResponse session, long id, long logId) {
        var entry = owned(access.requireApplicant(session), id);
        var log = policies.findAuditLog(id, entry.getTenantKey(), logId);
        if (log == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "POLICY_AUDIT_NOT_FOUND", "未找到该轮提交记录。");
        }
        return new AuditDetail(
                auditSummary(log), displaySnapshot(snapshots.read(log.getSnapshotData())));
    }

    private Summary summary(
            PolicyApply entry,
            ApprovedEnd approvedEnd,
            PolicyApplicability.AdjustmentOption option) {

        return new Summary(
                entry.getId().toString(),
                policyNumber(entry.getId()),
                entry.getStatus(),
                entry.getRecordVersion(),
                entry.getCurrentVersion(),
                entry.getChannelType(),
                entries(decode(entry.getPlatforms(), new TypeReference<List<String>>() {})),
                formatTime(entry.getCreatedAt()),
                formatTime(entry.getUpdatedAt()),
                approvedEnd != null
                        ? approvedEnd.actualEnd()
                        : formatTime(
                                "APPROVED".equals(entry.getStatus())
                                                && entry.getExtensionSourceId() == null
                                                && entry.getEndTime() != null
                                        ? entry.getEndTime().toLocalDate().atTime(23, 59, 59)
                                        : entry.getEndTime()),
                sourcePolicyCode(entry),
                entry.isAdjustmentOnly(),
                option == null ? null : option.earliestEnd(),
                option != null && option.canExtend(),
                option != null && option.canShorten(),
                entry.getReviewStage());
    }

    static AuditSummary auditSummary(PolicyAuditLog log) {
        return new AuditSummary(
                log.getId().toString(),
                log.getVersion(),
                log.getAuditStatus(),
                log.getAuditorName(),
                log.getAuditComment(),
                formatTime(log.getAuditTime()),
                formatTime(log.getCreatedAt()),
                log.getWorkflowVersion(),
                log.getRejectedAtStage());
    }

    static long offset(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw invalid("页码须大于等于 1，每页条数须在 1 至 100 之间。");
        }
        return (page - 1L) * pageSize;
    }

    static void validateFilter(String value, Set<String> allowed, String field) {
        if (value != null && !allowed.contains(value)) {
            throw invalid(field + "筛选值无效。");
        }
    }

    private static List<Long> targetIds(PolicyDraftRequest form) {
        return entries(form.goodsForm().cards()).stream()
                .flatMap(card -> entries(card.targets()).stream())
                .map(target -> PolicyFormValidation.id(target.goodsId()))
                .distinct()
                .sorted()
                .toList();
    }

    private Map<Long, ApplicantCatalogResponse> readGoods(List<Long> ids) {
        var result = new HashMap<Long, ApplicantCatalogResponse>();
        for (int offset = 0; offset < ids.size(); offset += 500) {
            var batch = ids.subList(offset, Math.min(offset + 500, ids.size()));
            for (var item : catalog.findForPolicy(batch)) {
                result.put(item.id(), ApplicantCatalogResponse.from(item));
            }
        }
        return result;
    }

    private static boolean sameConfiguration(ApplicantCatalogResponse item, Target target) {
        return item.spuId().equals(target.spuId())
                && item.ram().equals(target.ram())
                && item.rom().equals(target.rom());
    }

    private PolicyApply owned(UserInfo owner, long id) {
        var entry = policies.find(id, owner.getTenantKey(), owner.getId());
        if (entry == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "未找到此申请。");
        }
        return entry;
    }

    private static boolean editable(PolicyApply entry) {
        return "DRAFT".equals(entry.getStatus()) || "REJECTED".equals(entry.getStatus());
    }

    private static void checkEditable(PolicyApply entry, Integer version) {
        if (!editable(entry)) {
            throw conflict("POLICY_STATE_CONFLICT", "当前申请状态不允许保存或提交。");
        }
        checkVersion(entry, version);
    }

    private static void checkVersion(PolicyApply entry, Integer version) {
        if (version == null || version < 1) {
            throw invalid("请提供有效的记录版本。");
        }
        if (entry.getRecordVersion() != version) {
            throw versionConflict();
        }
    }

    private void writeForm(PolicyApply entry, PolicyDraftRequest form) {
        entry.setChannelType(form.perspective());
        entry.setPlatforms(encode(form.selectedPlatforms()));
        entry.setWriteOffType(form.verifyType());
        entry.setSceneTypes(form.sceneTypes() == null ? null : encode(form.sceneTypes()));
        entry.setIsEstimated(form.estimated());
        entry.setStartTime(PolicyFormValidation.time(form.startTime()));
        entry.setEndTime(PolicyFormValidation.time(form.endTime()));
        entry.setRemark(form.specialRemark());
        entry.setAttachmentUrls(encode(form.attachmentInfo()));
        entry.setGoodsFormJson(encode(form.goodsForm()));
    }

    private PolicyDraftRequest readForm(PolicyApply entry) {
        var scenes = PolicyScenes.read(json, entry.getSceneTypes());
        var goods = decode(entry.getGoodsFormJson(), new TypeReference<GoodsForm>() {});
        return new PolicyDraftRequest(
                null,
                entry.getChannelType(),
                entries(decode(entry.getPlatforms(), new TypeReference<List<String>>() {})),
                entry.getWriteOffType(),
                PolicyScenes.single(scenes),
                scenes,
                entry.getIsEstimated(),
                formatTime(entry.getStartTime()),
                formatTime(entry.getEndTime()),
                entry.getRemark(),
                entries(decode(entry.getAttachmentUrls(), new TypeReference<List<Link>>() {})),
                goods == null ? new GoodsForm(List.of()) : goods);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化政策内容", error);
        }
    }

    private <T> T decode(String value, TypeReference<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法读取已保存的政策内容", error);
        }
    }

    // 对外单号使用已有申请 ID，最少六位；不截断大 ID，也不改变内部创建幂等标识。
    static String policyNumber(long id) {
        return String.format(Locale.ROOT, "ZC-%06d", id);
    }

    private static String sourcePolicyCode(PolicyApply entry) {
        return entry.getExtensionSourceId() == null
                ? null
                : policyNumber(entry.getExtensionSourceId());
    }

    static Snapshot displaySnapshot(Snapshot snapshot) {
        return new Snapshot(
                snapshot.id(),
                policyNumber(Long.parseLong(snapshot.id())),
                snapshot.applicantId(),
                snapshot.applicantName(),
                snapshot.submissionVersion(),
                snapshot.form(),
                snapshot.goods(),
                snapshot.consent(),
                snapshot.sourcePolicyCode());
    }

    private static String creationCode(UserInfo owner, String key) {
        UUID token = UUID.randomUUID();
        if (key != null) {
            try {
                if (!UUID.fromString(key).toString().equals(key)) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException error) {
                throw invalid("创建请求标识须为标准 UUID。");
            }
            // 本地用户 ID 全局唯一，重放标识只影响同一用户的建单。
            token =
                    UUID.nameUUIDFromBytes(
                            (owner.getId() + ":" + key).getBytes(StandardCharsets.UTF_8));
        }
        return token.toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    static String formatTime(LocalDateTime value) {
        return value == null ? null : value.format(PolicyFormValidation.TIME);
    }

    static LocalDateTime now() {
        return LocalDateTime.now(SHANGHAI).truncatedTo(ChronoUnit.SECONDS);
    }

    private static PolicyResponse response(PolicyApply entry) {
        return response(entry, null);
    }

    private static PolicyResponse response(PolicyApply entry, String reviewStage) {
        return new PolicyResponse(
                entry.getId().toString(),
                policyNumber(entry.getId()),
                entry.getStatus(),
                entry.getRecordVersion(),
                entry.getCurrentVersion(),
                reviewStage);
    }

    private static void checkUpdated(int rows) {
        if (rows != 1) {
            throw versionConflict();
        }
    }

    private static ApiException versionConflict() {
        return conflict("POLICY_VERSION_CONFLICT", "申请已被修改，请保留输入并核对最新版本。");
    }

    private static ApiException goodsChanged() {
        return conflict("POLICY_GOODS_CHANGED", "所选商品已下架、删除或配置已变更，请重新核对选品。");
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
