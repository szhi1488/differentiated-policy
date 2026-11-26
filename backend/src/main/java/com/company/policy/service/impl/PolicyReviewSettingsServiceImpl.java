package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyReviewAccounts.lockUsers;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyReviewSettingsUpdate;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.PolicyReviewSettingsMapper;
import com.company.policy.service.CatalogAccess;
import com.company.policy.service.PolicyReviewSettingsService;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyReviewSettings;
import com.company.policy.vo.PolicyReviewerCandidate;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class PolicyReviewSettingsServiceImpl implements PolicyReviewSettingsService {
    private final CatalogAccess access;
    private final PolicyReviewSettingsMapper settings;
    private final FeishuProperties feishu;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PolicyReviewSettings read(CurrentUserResponse session) {
        String tenant = access.requirePermission(session, "review.manage").getTenantKey();
        return response(tenant);
    }

    private static PolicyReviewerCandidate candidate(Long id, String name, String department) {
        return id == null ? null : new PolicyReviewerCandidate(id.toString(), name, department);
    }

    private PolicyReviewSettings response(String tenant) {
        var series =
                settings.series(tenant).stream()
                        .collect(
                                Collectors.groupingBy(
                                        PolicyReviewSettingsMapper.Series::brandId,
                                        Collectors.mapping(
                                                s ->
                                                        new PolicyReviewSettings.Series(
                                                                Long.toString(s.id()),
                                                                s.name(),
                                                                candidate(
                                                                        s.productId(),
                                                                        s.productName(),
                                                                        s.productDepartment())),
                                                Collectors.toList())));
        var brands =
                settings.brands(tenant).stream()
                        .map(b -> brandResponse(b, series.getOrDefault(b.id(), List.of())))
                        .toList();
        return new PolicyReviewSettings(
                Objects.requireNonNullElse(settings.version(tenant), 0),
                brands,
                StringUtils.hasText(feishu.getPolicyChatId()));
    }

    private static PolicyReviewSettings.Brand brandResponse(
            PolicyReviewSettingsMapper.Brand brand, List<PolicyReviewSettings.Series> series) {
        String mode = productMode(brand.name(), brand.productMode());
        return new PolicyReviewSettings.Brand(
                Long.toString(brand.id()),
                brand.name(),
                mode,
                candidate(brand.purchaseId(), brand.purchaseName(), brand.purchaseDepartment()),
                "BY_BRAND".equals(mode)
                        ? candidate(
                                brand.productId(), brand.productName(), brand.productDepartment())
                        : null,
                series);
    }

    private static String productMode(String brandName, String configuredMode) {
        if ("OPPO手机".equals(brandName) || "一加手机".equals(brandName)) return "BY_SERIES";
        return Objects.requireNonNullElse(configuredMode, "BY_SERIES");
    }

    private static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid("请选择有效的目录或人员编号。");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalid("编号超出范围。");
        }
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PolicyReviewSettings save(
            CurrentUserResponse session, PolicyReviewSettingsUpdate update) {
        var admin = access.requirePermission(session, "review.manage");
        String tenant = admin.getTenantKey();
        if (update == null || update.expectedVersion() == null || update.brands() == null)
            throw invalid("请提供已读版本和品牌分工。");
        int version = lockVersion(tenant);
        if (version != update.expectedVersion())
            throw new ApiException(
                    HttpStatus.CONFLICT, "POLICY_REVIEW_SETTINGS_CONFLICT", "审核设置已变化，请重新读取。");
        var catalog =
                settings.brands(tenant).stream()
                        .collect(
                                Collectors.toMap(
                                        PolicyReviewSettingsMapper.Brand::id,
                                        PolicyReviewSettingsMapper.Brand::name));
        var series =
                settings.series(tenant).stream()
                        .collect(
                                Collectors.toMap(
                                        PolicyReviewSettingsMapper.Series::id,
                                        PolicyReviewSettingsMapper.Series::brandId));
        var purchaseBrands = new HashMap<Long, String>();
        var products = new TreeSet<Long>();
        var configured = new HashSet<Long>();
        var usedSeries = new HashSet<Long>();
        for (var b : update.brands()) {
            if (b == null || b.series() == null) throw invalid("请提供完整的品牌和系列分工。");
            long brand = id(b.brandId());
            String name = catalog.get(brand);
            if (name == null || !configured.add(brand)) throw invalid("品牌无效或重复。");
            if (!Set.of("BY_BRAND", "BY_SERIES").contains(Objects.toString(b.productMode(), "")))
                throw invalid("请选择初审分工方式。");
            if (!productMode(name, b.productMode()).equals(b.productMode()))
                throw invalid("OPPO 和一加均须按系列指定初审人。");
            String otherBrand = purchaseBrands.putIfAbsent(id(b.purchaseUserId()), name);
            if (otherBrand != null)
                throw invalid("每名采购只能负责一个品牌，请为「" + otherBrand + "」和「" + name + "」选择不同的采购。");
            if ("BY_BRAND".equals(b.productMode())) {
                if (!b.series().isEmpty()) throw invalid("按品牌初审时请只指定一名产品运营。");
                products.add(id(b.brandProductUserId()));
            } else if (b.brandProductUserId() != null) throw invalid("按系列初审时请在对应系列设置人员。");
            for (var s : b.series()) {
                long sid = id(s.seriesId());
                if (!Objects.equals(series.get(sid), brand) || !usedSeries.add(sid))
                    throw invalid("系列与品牌不符或重复。");
                products.add(id(s.productUserId()));
            }
        }
        var ids = new TreeSet<>(purchaseBrands.keySet());
        ids.addAll(products);
        ids.add(admin.getId());
        var accounts = lockUsers(settings, tenant, ids);
        access.requirePermission(session, "review.manage");
        for (var person : purchaseBrands.keySet())
            if (!eligible(accounts.get(person), "PURCHASE"))
                throw invalid("请选择本企业采购岗位中具备终审权限的有效账号。");
        for (var person : products)
            if (!eligible(accounts.get(person), "PRODUCT"))
                throw invalid("请选择本企业产品运营岗位中具备初审权限的有效账号。");
        settings.clearSeries(tenant);
        settings.clearBrands(tenant);
        for (var b : update.brands()) {
            long brand = id(b.brandId());
            settings.insertBrand(
                    tenant,
                    brand,
                    id(b.purchaseUserId()),
                    b.productMode(),
                    b.brandProductUserId() == null ? null : id(b.brandProductUserId()));
            for (var s : b.series())
                settings.insertSeries(tenant, brand, id(s.seriesId()), id(s.productUserId()));
        }
        if (settings.advance(tenant, admin.getId(), version) != 1)
            throw new IllegalStateException("无法保存审核设置版本。");
        return response(tenant);
    }

    private boolean eligible(UserInfo user, String group) {
        return PolicyReviewAccounts.eligible(settings, user, group);
    }

    private int lockVersion(String tenant) {
        settings.ensureSettings(tenant);
        return settings.lockVersion(tenant);
    }

    private record Preset(long purchaseId, String productMode, Long productId) {}

    private Preset preset(String tenant, long brand, long series) {
        var owners = settings.owners(tenant, brand, series);
        if (owners == null) return null;
        String mode = productMode(owners.brandName(), owners.productMode());
        Long reviewer =
                "BY_BRAND".equals(mode) ? owners.brandProductId() : owners.seriesProductId();
        return new Preset(owners.purchaseId(), mode, reviewer);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int lockForSubmission(String tenant, long applicantId, Long brandId, Long seriesId) {
        int version = lockVersion(tenant);
        var ids = new TreeSet<Long>();
        ids.add(applicantId);
        var owners = brandId == null || seriesId == null ? null : preset(tenant, brandId, seriesId);
        if (owners != null) {
            ids.add(owners.purchaseId());
            if (owners.productId() != null) ids.add(owners.productId());
        }
        lockUsers(settings, tenant, ids);
        return version;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Assignment assign(
            String tenant, long applicant, Long brand, Long series, boolean needsProduct) {
        int version = lockVersion(tenant);
        if (brand == null || series == null) throw missing("请先选择同一品牌、同一系列的商品。");
        var owners = preset(tenant, brand, series);
        if (owners == null) throw missing("请管理员提前为本品牌指定终审采购。");
        var ids = new TreeSet<Long>();
        ids.add(applicant);
        ids.add(owners.purchaseId());
        if (needsProduct && owners.productId() != null) ids.add(owners.productId());
        var accounts = lockUsers(settings, tenant, ids);
        var owner = accounts.get(applicant);
        if (owner == null
                || !Integer.valueOf(0).equals(owner.getIsDeleted())
                || !access.hasPermission(owner, "policy.apply"))
            throw new ApiException(HttpStatus.FORBIDDEN, "POLICY_ACCESS_DENIED", "当前运营已无提交权限。");
        var buyer = accounts.get(owners.purchaseId());
        if (owners.purchaseId() == applicant || !eligible(buyer, "PURCHASE"))
            throw missing("本品牌预设采购不能办理本单，请管理员修改分工。");
        if (!needsProduct) return new Assignment(version, null, buyer, owners.productMode());
        if (owners.productId() == null) throw missing("请管理员提前为本系列指定产品运营初审人。");
        var reviewer = accounts.get(owners.productId());
        if (!eligible(reviewer, "PRODUCT")
                || reviewer.getId() == applicant
                || reviewer.getId().equals(buyer.getId()))
            throw missing("本单需要合格且与申请人、终审人不同的预设初审人，请管理员修改分工。");
        return new Assignment(version, reviewer, buyer, owners.productMode());
    }

    private static ApiException invalid(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, "POLICY_REVIEW_SETTINGS_INVALID", msg);
    }

    private static ApiException missing(String msg) {
        return new ApiException(HttpStatus.CONFLICT, "POLICY_REVIEW_ASSIGNEES_MISSING", msg);
    }
}
