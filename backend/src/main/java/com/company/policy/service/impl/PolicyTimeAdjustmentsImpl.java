package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.now;
import static com.company.policy.support.policy.PolicyFormValidation.invalid;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyDraftRequest.Choice;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyConsent;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.entity.PolicyConsent.Target;
import com.company.policy.entity.PolicyTimeChange;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.PolicyTimeChangeMapper;
import com.company.policy.mapper.UserPlatformMapper;
import com.company.policy.service.AttachmentService;
import com.company.policy.service.CatalogAccess;
import com.company.policy.service.ExecutionNotices;
import com.company.policy.service.PolicyCoverageService;
import com.company.policy.service.PolicyTimeAdjustments;
import com.company.policy.support.policy.PolicyBatchExtensions;
import com.company.policy.support.policy.PolicyConfirmation;
import com.company.policy.support.policy.PolicyEndTimes;
import com.company.policy.support.policy.PolicyFormValidation;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyApplicability.AdjustmentOption;
import com.company.policy.vo.PolicyResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class PolicyTimeAdjustmentsImpl implements PolicyTimeAdjustments {
    private final CatalogAccess access;
    private final PolicyCoverageService coverage;
    private final PolicyBatchExtensions adjustments;
    private final PolicyTimeChangeMapper history;
    private final UserPlatformMapper platforms;
    private final AttachmentService attachments;
    private final ExecutionNotices notices;
    private final ObjectMapper json;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<AdjustmentOption> options(CurrentUserResponse session) {
        var actor = access.requireApplicant(session);
        var at = now();
        return coverage.adjustableFor(applicant(actor), at);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(CurrentUserResponse session, List<EndAdjustment> changes) {
        var actor = access.requireApplicant(session);
        validateTargets(changes);
        return prepare(applicant(actor), changes, session, now()).preview();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<PolicyResponse> apply(
            CurrentUserResponse session,
            List<EndAdjustment> changes,
            String confirmationToken,
            List<Choice> decisions,
            String requestKey) {
        var actor = access.requireApplicant(session);
        validateTargets(changes);
        validateKey(requestKey);
        if (confirmationToken == null || confirmationToken.isBlank()) throw invalid("请先核对本次时间调整。");
        var requestHash =
                PolicyConfirmation.fingerprint(
                        json,
                        List.of(
                                changes,
                                confirmationToken,
                                decisions == null ? List.of() : decisions));
        if (platforms.lockApplicant(actor.getTenantKey(), actor.getId()) == null)
            throw new ApiException(HttpStatus.FORBIDDEN, "POLICY_PLATFORM_FORBIDDEN", "当前账号无申请权限。");
        var replay = replay(actor, requestKey, requestHash, changes.size());
        if (!replay.isEmpty()) return replay;
        var applicant = applicant(actor);
        coverage.lockAdjustmentScope(applicant, changes);
        var at = now();
        var batch = prepare(applicant, changes, session, at);
        if (!confirmationToken.equals(batch.preview().confirmationToken())) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "POLICY_COVERAGE_CHANGED", "政策时段、商品或执行名单已变化，请重新核对本次调整。");
        }
        var confirmed = PolicyConfirmation.confirm(batch.preview().applicability(), decisions);
        var saved =
                adjustments.saveApproved(
                        applicant, batch.changes(), confirmed, actor, at, requestKey, requestHash);
        saved.forEach(notices::timeAdjusted);
        return saved.stream().map(PolicyTimeAdjustmentsImpl::response).toList();
    }

    private record Batch(Preview preview, List<EndAdjustment> changes) {}

    private Batch prepare(
            PolicyApply applicant,
            List<EndAdjustment> input,
            CurrentUserResponse session,
            LocalDateTime at) {
        var options = new LinkedHashMap<Target, AdjustmentOption>();
        coverage.adjustableFor(applicant, at)
                .forEach(option -> options.put(option.target(), option));
        var changes = new ArrayList<EndAdjustment>();
        for (var item : input) {
            var reason = item.reason() == null ? "" : item.reason().strip();
            changes.add(
                    new EndAdjustment(
                            item.target(),
                            item.endTime(),
                            reason,
                            attachments.resolveChangeFiles(item.attachmentInfo(), session)));
        }
        PolicyEndTimes.validate(changes, options);
        var extensions =
                adjustments.extensionProposals(
                        applicant, new PolicyConsent(4, List.of(), List.of(), changes));
        adjustments.verifyGoods(extensions);
        var scope = coverage.adjustmentCoverage(applicant, extensions, options, at);
        var token =
                PolicyConfirmation.fingerprint(
                        json,
                        List.of(
                                applicant.getTenantKey(),
                                applicant.getApplicantId(),
                                changes,
                                scope.status(),
                                PolicyConfirmation.comparedRounds(scope),
                                PolicyConfirmation.tokenRanges(scope)));
        return new Batch(new Preview(token, scope), changes);
    }

    /** 即使这张政策又被调整过，重试也返回本次成功时的版本。 */
    private List<PolicyResponse> replay(UserInfo actor, String key, String hash, int count) {
        var saved = history.byRequest(actor.getTenantKey(), actor.getId(), key);
        if (saved.isEmpty()) return List.of();
        if (saved.size() != count || saved.stream().anyMatch(c -> !hash.equals(c.getRequestHash())))
            throw reusedKey();
        return saved.stream().map(PolicyTimeAdjustmentsImpl::response).toList();
    }

    private static PolicyResponse response(PolicyTimeChange item) {
        return new PolicyResponse(
                item.getPolicyId().toString(),
                PolicyApplyServiceImpl.policyNumber(item.getPolicyId()),
                "APPROVED",
                item.getAfterRecordVersion(),
                item.getPolicyVersion(),
                null);
    }

    private static ApiException reusedKey() {
        return new ApiException(HttpStatus.CONFLICT, "POLICY_CREATE_CONFLICT", "此提交标识已用于另一批时间调整。");
    }

    private static void validateTargets(List<EndAdjustment> changes) {
        if (changes == null || changes.isEmpty()) throw invalid("请至少选择一张要调整的已通过政策。");
        var seen = new HashSet<String>();
        for (var item : changes) {
            if (item == null
                    || item.target() == null
                    || item.endTime() == null
                    || item.target().previousEnd() == null
                    || !seen.add(item.target().policyId()))
                throw invalid("请核对原单和新截止，每张政策在同一批中只能出现一次。");
            PolicyFormValidation.id(item.target().policyId());
            PolicyFormValidation.id(item.target().auditLogId());
            PolicyFormValidation.time(item.endTime());
            PolicyFormValidation.time(item.target().previousEnd());
        }
    }

    private static void validateKey(String key) {
        try {
            if (key != null && UUID.fromString(key).toString().equals(key)) return;
        } catch (IllegalArgumentException ignored) {
            // 非标准格式统一按无效创建标识处理。
        }
        throw invalid("时间调整提交标识须为标准 UUID。");
    }

    private static PolicyApply applicant(UserInfo actor) {
        var entry = new PolicyApply();
        // 独立批次预览没有发起主单，0 不与任何真实流水号或随单延期别名重复。
        entry.setId(0L);
        entry.setTenantKey(actor.getTenantKey());
        entry.setApplicantId(actor.getId());
        entry.setApplicantName(actor.getName());
        return entry;
    }
}
