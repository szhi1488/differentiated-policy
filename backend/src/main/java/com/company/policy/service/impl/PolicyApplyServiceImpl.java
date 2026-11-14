package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyFormValidation.entries;
import static com.company.policy.service.impl.PolicyFormValidation.invalid;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyDraftRequest.GoodsForm;
import com.company.policy.dto.PolicyDraftRequest.Link;
import com.company.policy.dto.PolicyDraftRequest.Target;
import com.company.policy.dto.PolicyListQuery;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.CatalogGoodsMapper;
import com.company.policy.mapper.PolicyApplyMapper;
import com.company.policy.mapper.PolicyApplyMapper.DetailLine;
import com.company.policy.service.PolicyApplyService;
import com.company.policy.vo.ApplicantCatalogResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyResponse;
import com.company.policy.vo.PolicyResponse.AuditDetail;
import com.company.policy.vo.PolicyResponse.AuditSummary;
import com.company.policy.vo.PolicyResponse.Detail;
import com.company.policy.vo.PolicyResponse.Page;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.company.policy.vo.PolicyResponse.Summary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final CatalogAccess access;
    private final PolicyApplyMapper policies;
    private final CatalogGoodsMapper catalog;
    private final ObjectMapper json;

    PolicyApplyServiceImpl(
            CatalogAccess access,
            PolicyApplyMapper policies,
            CatalogGoodsMapper catalog,
            ObjectMapper json) {
        this.access = access;
        this.policies = policies;
        this.catalog = catalog;
        this.json = json;
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
        var entry = new PolicyApply();
        entry.setPolicyNo(policyCode(owner, requestKey));
        entry.setTenantKey(owner.getTenantKey());
        entry.setApplicantId(owner.getId());
        entry.setApplicantName(owner.getName());
        entry.setStatus("DRAFT");
        entry.setRecordVersion(1);
        writeForm(entry, form);
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
                    && form.equals(readForm(saved))) {
                return response(saved);
            }
            throw conflict("POLICY_CREATE_CONFLICT", "创建标识已使用，请核对原申请后继续。");
        }
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PolicyResponse save(CurrentUserResponse session, long id, PolicyDraftRequest request) {
        var entry = owned(access.requireApplicant(session), id);
        checkEditable(entry, request.recordVersion());
        writeForm(entry, PolicyFormValidation.validate(request, false));
        checkUpdated(policies.updateDraft(entry, now()));
        entry.setRecordVersion(entry.getRecordVersion() + 1);
        return response(entry);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PolicyResponse submit(CurrentUserResponse session, long id, Integer recordVersion) {
        var entry = owned(access.requireApplicant(session), id);
        checkEditable(entry, recordVersion);
        var form = PolicyFormValidation.validate(readForm(entry), true);
        var ids = targetIds(form);

        // 与商品编辑和删除使用同一组行锁，先按商品 ID 顺序锁定，再读取最新配置。
        for (int offset = 0; offset < ids.size(); offset += 500) {
            var batch = ids.subList(offset, Math.min(offset + 500, ids.size()));
            if (catalog.lockSelected(batch).size() != batch.size()) {
                throw goodsChanged();
            }
        }
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

        var now = now();
        int version = entry.getCurrentVersion() + 1;
        var snapshot =
                new Snapshot(
                        Long.toString(id),
                        entry.getPolicyNo(),
                        entry.getApplicantId().toString(),
                        entry.getApplicantName(),
                        version,
                        form,
                        ids.stream().map(goods::get).toList());
        checkUpdated(policies.markSubmitted(entry, now));
        checkUpdated(
                policies.insertAudit(id, entry.getTenantKey(), version, encode(snapshot), now));
        // 分批写入同一事务，避免单条 SQL 的参数数随商品数和平台数相乘。
        for (int offset = 0; offset < lines.size(); offset += 500) {
            var batch = lines.subList(offset, Math.min(offset + 500, lines.size()));
            if (policies.insertDetails(id, version, batch, now) != batch.size()) {
                throw new IllegalStateException("政策商品明细写入数量不一致");
            }
        }
        entry.setStatus("PENDING");
        entry.setRecordVersion(entry.getRecordVersion() + 1);
        entry.setCurrentVersion(version);
        return response(entry);
    }

    @Override
    @Transactional(readOnly = true)
    public Detail detail(CurrentUserResponse session, long id) {
        var entry = owned(access.requireApplicant(session), id);
        PolicyDraftRequest form;
        List<ApplicantCatalogResponse> goods;
        if (editable(entry)) {
            form = readForm(entry);
            var ids = targetIds(form);
            var current = readGoods(ids);
            goods = ids.stream().filter(current::containsKey).map(current::get).toList();
        } else {
            var snapshot =
                    decode(
                            policies.findSnapshot(
                                    id, entry.getTenantKey(), entry.getCurrentVersion()),
                            new TypeReference<Snapshot>() {});
            if (snapshot == null) {
                throw new IllegalStateException("政策提交快照缺失");
            }
            form = snapshot.form();
            goods = snapshot.goods();
        }
        return new Detail(
                Long.toString(id),
                entry.getPolicyNo(),
                entry.getStatus(),
                entry.getRecordVersion(),
                entry.getCurrentVersion(),
                entry.getApplicantName(),
                form,
                goods);
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
        var items =
                offset >= total
                        ? List.<Summary>of()
                        : policies
                                .listMine(owner.getTenantKey(), owner.getId(), query, offset)
                                .stream()
                                .map(this::summary)
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
                auditSummary(log), decode(log.getSnapshotData(), new TypeReference<Snapshot>() {}));
    }

    private Summary summary(PolicyApply entry) {
        return new Summary(
                entry.getId().toString(),
                entry.getPolicyNo(),
                entry.getStatus(),
                entry.getRecordVersion(),
                entry.getCurrentVersion(),
                entry.getChannelType(),
                entries(decode(entry.getPlatforms(), new TypeReference<List<String>>() {})),
                formatTime(entry.getCreatedAt()),
                formatTime(entry.getUpdatedAt()));
    }

    private static AuditSummary auditSummary(PolicyAuditLog log) {
        return new AuditSummary(
                log.getId().toString(),
                log.getVersion(),
                log.getAuditStatus(),
                log.getAuditorName(),
                log.getAuditComment(),
                formatTime(log.getAuditTime()),
                formatTime(log.getCreatedAt()));
    }

    private static long offset(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw invalid("页码须大于等于 1，每页条数须在 1 至 100 之间。");
        }
        return (page - 1L) * pageSize;
    }

    private static void validateFilter(String value, Set<String> allowed, String field) {
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
        if (version == null || version < 1) {
            throw invalid("请提供有效的记录版本。");
        }
        if (!editable(entry)) {
            throw conflict("POLICY_STATE_CONFLICT", "当前申请状态不允许保存或提交。");
        }
        if (entry.getRecordVersion() != version) {
            throw versionConflict();
        }
    }

    private void writeForm(PolicyApply entry, PolicyDraftRequest form) {
        entry.setChannelType(form.perspective());
        entry.setPlatforms(encode(form.selectedPlatforms()));
        entry.setWriteOffType(form.verifyType());
        entry.setScene(form.sceneType());
        entry.setIsEstimated(form.estimated());
        entry.setStartTime(PolicyFormValidation.time(form.startTime()));
        entry.setEndTime(PolicyFormValidation.time(form.endTime()));
        entry.setRemark(form.specialRemark());
        entry.setOtherDesc(form.otherExplanation());
        entry.setAttachmentUrls(encode(form.attachmentInfo()));
        entry.setGoodsFormJson(encode(form.goodsForm()));
    }

    private PolicyDraftRequest readForm(PolicyApply entry) {
        var goods = decode(entry.getGoodsFormJson(), new TypeReference<GoodsForm>() {});
        return new PolicyDraftRequest(
                null,
                entry.getChannelType(),
                entries(decode(entry.getPlatforms(), new TypeReference<List<String>>() {})),
                entry.getWriteOffType(),
                entry.getScene(),
                entry.getIsEstimated(),
                formatTime(entry.getStartTime()),
                formatTime(entry.getEndTime()),
                entry.getRemark(),
                entries(decode(entry.getAttachmentUrls(), new TypeReference<List<Link>>() {})),
                entry.getOtherDesc(),
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

    private static String policyCode(UserInfo owner, String key) {
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

    private static String formatTime(LocalDateTime value) {
        return value == null ? null : value.format(PolicyFormValidation.TIME);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(SHANGHAI).truncatedTo(ChronoUnit.SECONDS);
    }

    private static PolicyResponse response(PolicyApply entry) {
        return new PolicyResponse(
                entry.getId().toString(),
                entry.getPolicyNo(),
                entry.getStatus(),
                entry.getRecordVersion(),
                entry.getCurrentVersion());
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
