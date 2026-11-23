package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyReviewAccounts.*;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyReviewSettingsUpdate;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.PolicyReviewSettingsMapper;
import com.company.policy.mapper.PolicyReviewSettingsMapper.Member;
import com.company.policy.service.CatalogAccess;
import com.company.policy.service.PolicyReviewSettingsService;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyReviewSettings;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PolicyReviewSettingsServiceImpl implements PolicyReviewSettingsService {
    private static final String PRODUCT = "PRODUCT";
    private static final String PURCHASE = "PURCHASE";
    private final CatalogAccess access;
    private final PolicyReviewSettingsMapper settings;
    private final FeishuProperties feishu;

    public PolicyReviewSettingsServiceImpl(
            CatalogAccess access, PolicyReviewSettingsMapper settings, FeishuProperties feishu) {
        this.access = access;
        this.settings = settings;
        this.feishu = feishu;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PolicyReviewSettings read(CurrentUserResponse session) {
        String tenant = access.requireUserAdmin(session).getTenantKey();
        var version = settings.version(tenant);
        return version == null
                ? response(0, List.of())
                : response(version, settings.members(tenant));
    }

    private PolicyReviewSettings response(int version, List<Member> members) {
        return new PolicyReviewSettings(
                version,
                members.stream()
                        .filter(m -> PRODUCT.equals(m.groupCode()))
                        .map(Member::candidate)
                        .toList(),
                members.stream()
                        .filter(m -> PURCHASE.equals(m.groupCode()))
                        .map(Member::candidate)
                        .toList(),
                StringUtils.hasText(feishu.getPolicyChatId()));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PolicyReviewSettings save(
            CurrentUserResponse session, PolicyReviewSettingsUpdate update) {
        var admin = access.requireUserAdmin(session);
        String tenant = admin.getTenantKey();
        if (update == null || update.expectedVersion() == null || update.expectedVersion() < 0)
            throw invalid("请提供已读取的设置版本。");
        var product =
                distinctIds(update.productReviewerUserIds(), "POLICY_REVIEW_SETTINGS_INVALID");
        var purchase =
                distinctIds(update.purchaseReviewerUserIds(), "POLICY_REVIEW_SETTINGS_INVALID");
        int version = lockSettings(tenant);
        if (version != update.expectedVersion()) throw stale();
        var ids = new TreeSet<Long>(product);
        ids.addAll(purchase);
        ids.add(admin.getId());
        var accounts = lockUsers(settings, tenant, ids);
        var operator = accounts.get(admin.getId());
        if (!eligible(operator, PURCHASE)
                || !Objects.equals(operator.getOpenId(), session.openId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_ADMIN_REQUIRED", "当前账号已失去设置权限。");
        validateMembers(product, accounts, PRODUCT);
        validateMembers(purchase, accounts, PURCHASE);
        if (settings.advance(tenant, admin.getId(), version) != 1) throw stale();
        settings.removeMembers(tenant);
        insert(tenant, PRODUCT, product);
        insert(tenant, PURCHASE, purchase);
        return response(version + 1, settings.members(tenant));
    }

    private int lockSettings(String tenant) {
        settings.ensureSettings(tenant);
        return settings.lockVersion(tenant);
    }

    private static void validateMembers(
            List<Long> ids, Map<Long, UserInfo> accounts, String group) {
        if (ids.stream().anyMatch(id -> !eligible(accounts.get(id), group)))
            throw invalid(PRODUCT.equals(group) ? "产品运营须选本企业启用账号，请重新选择。" : "采购须选本企业启用的管理员，请重新选择。");
    }

    private void insert(String tenant, String group, List<Long> ids) {
        for (int i = 0; i < ids.size(); i += 500) {
            var batch = ids.subList(i, Math.min(i + 500, ids.size()));
            if (settings.insertMembers(tenant, group, batch) != batch.size()) throw stale();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int lockForSubmission(String tenantKey, long applicantId) {
        return lockSubmission(tenantKey, applicantId).version();
    }

    private record Locked(int version, List<Member> members, Map<Long, UserInfo> accounts) {}

    private Locked lockSubmission(String tenant, long applicantId) {
        var version = lockSettings(tenant);
        var members = settings.members(tenant);
        var ids = new TreeSet<Long>();
        ids.add(applicantId);
        members.forEach(member -> ids.add(member.id()));
        var accounts = lockUsers(settings, tenant, ids);
        var owner = accounts.get(applicantId);
        if (!eligible(owner, PRODUCT) || !"APPLICANT".equals(owner.getRoleType()))
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "POLICY_ACCESS_DENIED", "只有本企业启用的申请人可正式提交。");
        return new Locked(version, members, accounts);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Assignment assign(String tenantKey, long applicantId, boolean needsProductReview) {
        var locked = lockSubmission(tenantKey, applicantId);
        if (locked.version() == 0) return new Assignment(0, List.of(), List.of());
        var purchase = assigned(locked, applicantId, PURCHASE);
        if (purchase.isEmpty()) throw missing("本轮没有合格的采购审核人，请管理员更新采购名单。");
        if (!needsProductReview) return new Assignment(locked.version(), List.of(), purchase);
        var product = assigned(locked, applicantId, PRODUCT);
        if (product.isEmpty()) throw missing("本轮没有合格的产品运营审核人，请管理员更新产品运营名单。");
        // 采购只剩一人时，预留该人终审，确保初审的任何一个有效签名都能继续转采购。
        if (purchase.size() == 1) {
            product =
                    product.stream()
                            .filter(user -> !user.getId().equals(purchase.get(0).getId()))
                            .toList();
            if (product.isEmpty()) throw missing("产品运营和采购需要由不同人员签署，请为其中一个岗位补充审核人。");
        }
        return new Assignment(locked.version(), product, purchase);
    }

    private static List<UserInfo> assigned(Locked locked, long owner, String group) {
        return locked.members().stream()
                .filter(m -> group.equals(m.groupCode()) && m.id() != owner)
                .map(m -> locked.accounts().get(m.id()))
                .filter(u -> eligible(u, group))
                .toList();
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "POLICY_REVIEW_SETTINGS_INVALID", message);
    }

    private static ApiException stale() {
        return new ApiException(
                HttpStatus.CONFLICT, "POLICY_REVIEW_SETTINGS_CONFLICT", "审核设置已变化，请重新读取后再保存。");
    }

    private static ApiException missing(String message) {
        return new ApiException(HttpStatus.CONFLICT, "POLICY_REVIEW_ASSIGNEES_MISSING", message);
    }
}
