package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.now;
import static com.company.policy.service.impl.PolicyReviewAccounts.*;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyReviewReassignmentRequest;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.PolicyPurchaseReviewMapper;
import com.company.policy.mapper.PolicyReviewReassignmentMapper;
import com.company.policy.mapper.PolicyReviewSettingsMapper;
import com.company.policy.mapper.PolicyReviewWorkflowMapper;
import com.company.policy.mapper.PolicyReviewWorkflowMapper.Member;
import com.company.policy.service.CatalogAccess;
import com.company.policy.service.PolicyReviewNotifications;
import com.company.policy.service.PolicyReviewNotifications.Assignee;
import com.company.policy.service.PolicyReviewReassignmentService;
import com.company.policy.support.policy.PolicyFormValidation;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyReviewReassignmentResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class PolicyReviewReassignmentServiceImpl implements PolicyReviewReassignmentService {
    private static final String PRODUCT_STAGE = "PRODUCT_REVIEW";
    private static final String PURCHASE_STAGE = "PURCHASE_REVIEW";
    private static final String INVALID = "POLICY_REASSIGNMENT_INVALID";
    private final CatalogAccess access;
    private final PolicyReviewReassignmentMapper reassignments;
    private final PolicyPurchaseReviewMapper purchase;
    private final PolicyReviewWorkflowMapper workflow;
    private final PolicyReviewSettingsMapper settings;
    private final PolicyReviewNotifications notifications;
    private final ObjectMapper json;

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PolicyReviewReassignmentResult reassign(
            CurrentUserResponse session, long id, PolicyReviewReassignmentRequest request) {
        var admin = access.requireUserAdmin(session);
        var tenant = admin.getTenantKey();
        var policy = reassignments.find(tenant, id);
        if (policy == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "找不到本企业的已提交申请。");
        var reason = PolicyFormValidation.trimReviewText(request.reason());
        if (reason.isEmpty() || reason.codePointCount(0, reason.length()) > 512)
            throw invalid("请填写改派原因，最多 512 字。");
        var target = request.targetStage();
        var expected = request.expectedStage();
        var stages = Set.of(PRODUCT_STAGE, PURCHASE_STAGE);
        if (!stages.contains(target) || !stages.contains(expected))
            throw invalid("请选择产品运营初审或采购终审。");
        var group = PRODUCT_STAGE.equals(target) ? "PRODUCT" : "PURCHASE";
        var desired = distinctIds(request.assigneeUserIds(), INVALID);
        if (desired.isEmpty()) throw invalid("请至少保留一名有效审核人。");

        int version = request.submissionVersion();
        int record = request.recordVersion();
        if (version != policy.currentVersion()
                || record != policy.recordVersion()
                || !"PENDING".equals(policy.status())
                || !Integer.valueOf(1).equals(policy.workflowVersion())) throw stale();

        // 同轮改派和决定都递增主单版本，因此可先按预读名单升序锁人员，再锁申请和本轮。
        var members = workflow.members(tenant, id, version);
        var ids = new TreeSet<>(desired);
        ids.add(admin.getId());
        ids.add(policy.applicantId());
        members.stream()
                .filter(m -> "ACTIVE".equals(m.memberStatus()))
                .map(Member::userId)
                .forEach(ids::add);
        var accounts = lockUsers(settings, tenant, ids);
        var operator = accounts.get(admin.getId());
        if (!eligible(operator, "PURCHASE")
                || !Objects.equals(operator.getOpenId(), session.openId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_ADMIN_REQUIRED", "当前账号已失去改派权限。");
        if (purchase.lockPending(tenant, id, version, record) == null) throw stale();
        var stage = purchase.lockStage(tenant, id, version);
        if (!expected.equals(stage) || (PURCHASE_STAGE.equals(stage) && !target.equals(stage)))
            throw stale();
        validateMembers(
                tenant,
                id,
                version,
                stage,
                group,
                policy.applicantId(),
                desired,
                accounts,
                members);

        var before = active(members, group);
        var beforeUsers = before.stream().map(Assignee::userId).collect(Collectors.toSet());
        var desiredUsers = Set.copyOf(desired);
        var removed = before.stream().filter(m -> !desiredUsers.contains(m.userId())).toList();
        var newUsers = desired.stream().filter(user -> !beforeUsers.contains(user)).toList();
        if (removed.isEmpty() && newUsers.isEmpty())
            return new PolicyReviewReassignmentResult(Long.toString(id), version, record, stage);

        var at = now();
        if (reassignments.advance(tenant, id, version, record, at) != 1) throw stale();
        if (!removed.isEmpty()
                && reassignments.remove(
                                tenant,
                                id,
                                version,
                                group,
                                removed.stream().map(Assignee::memberId).toList())
                        != removed.size()) throw stale();
        if (!newUsers.isEmpty()
                && reassignments.add(tenant, id, version, group, newUsers) != newUsers.size())
            throw stale();
        var after = active(workflow.members(tenant, id, version), group);
        var added = after.stream().filter(m -> !beforeUsers.contains(m.userId())).toList();
        if (reassignments.audit(
                        tenant,
                        id,
                        version,
                        group,
                        admin.getId(),
                        reason,
                        encode(before),
                        encode(after),
                        at)
                != 1) throw stale();
        notifications.reassigned(
                tenant,
                id,
                version,
                record + 1,
                stage,
                target,
                admin.getId(),
                reason,
                added,
                removed,
                at);
        return new PolicyReviewReassignmentResult(Long.toString(id), version, record + 1, stage);
    }

    private void validateMembers(
            String tenant,
            long id,
            int version,
            String stage,
            String group,
            long applicant,
            List<Long> desired,
            Map<Long, UserInfo> accounts,
            List<Member> members) {
        if (desired.contains(applicant)) throw invalid("申请人不能审核本人提交的申请。");
        if (desired.stream().anyMatch(user -> !eligible(accounts.get(user), group)))
            throw invalid("PRODUCT".equals(group) ? "产品运营须为本企业启用的账号。" : "采购须为本企业启用的管理员。");
        if (PURCHASE_STAGE.equals(stage)) {
            var signer = reassignments.productSigner(tenant, id, version);
            if (signer != null && desired.contains(signer)) throw invalid("本轮实际初审人不能再担任采购终审。");
            return;
        }
        // 维持首提的两关分签条件；任一有效初审成员同意后均应有其他人可签终审。
        var otherGroup = "PRODUCT".equals(group) ? "PURCHASE" : "PRODUCT";
        var others =
                active(members, otherGroup).stream()
                        .map(Assignee::userId)
                        .filter(user -> eligible(accounts.get(user), otherGroup))
                        .toList();
        var buyers = "PURCHASE".equals(group) ? desired : others;
        var products = "PRODUCT".equals(group) ? desired : others;
        if (buyers.size() == 1 && products.contains(buyers.get(0)))
            throw invalid("请为终审保留不同于初审签署人的合格采购。");
    }

    private static List<Assignee> active(List<Member> members, String group) {
        return members.stream()
                .filter(m -> group.equals(m.groupCode()) && "ACTIVE".equals(m.memberStatus()))
                .map(m -> new Assignee(m.id(), m.userId()))
                .toList();
    }

    private String encode(List<Assignee> members) {
        try {
            return json.writeValueAsString(members);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法记录改派前后的审核人员。", error);
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, INVALID, message);
    }

    private static ApiException stale() {
        return new ApiException(
                HttpStatus.CONFLICT, "POLICY_REASSIGNMENT_CONFLICT", "申请版本或审核阶段已变化，请重新读取本轮详情。");
    }
}
