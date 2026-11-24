package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.now;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.policyNumber;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.dto.ChannelPlatform;
import com.company.policy.entity.CatalogCleaningJob;
import com.company.policy.entity.GoodsSyncJob;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyConsent;
import com.company.policy.mapper.CatalogCleaningMapper.JobCount;
import com.company.policy.mapper.ExecutionNoticeMapper;
import com.company.policy.mapper.ExecutionNoticeMapper.Policy;
import com.company.policy.mapper.UserPlatformMapper;
import com.company.policy.service.ExecutionNotices;
import com.company.policy.service.PolicyCoverageService;
import com.company.policy.service.PolicyFeishuSender.Failure;
import com.company.policy.service.PolicyFeishuSender.Kind;
import com.company.policy.support.policy.PolicyApplicabilityEvaluator;
import com.company.policy.support.policy.PolicyChangeDescription;
import com.company.policy.support.policy.PolicyEndTimes;
import com.company.policy.support.policy.PolicyFormValidation;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.vo.PolicyResponse.ApprovedEnd;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
class ExecutionNoticesImpl implements ExecutionNotices {
    private final ExecutionNoticeMapper notices;
    private final UserPlatformMapper platforms;
    private final PolicyCoverageService coverage;
    private final PolicySnapshotCodec codec;
    private final FeishuProperties config;
    private final ObjectMapper json;

    @Value("${policy.review-delivery-enabled:false}")
    private boolean enabled;

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void initialize(LocalDateTime at) {
        String tenant = config.getTenantKey();
        if (!config.hasCompanyTenant() || !StringUtils.hasText(config.getPolicyChatId())) return;
        var ends = coverage.approvedEnds(tenant, at);
        long after = 0;
        while (true) {
            var page = notices.activePage(tenant, after, at.toLocalDate().atStartOfDay());
            for (var p : page) {
                var end = actualEnd(p, ends.get(Long.toString(p.id())));
                if (!end.isBefore(at)) plan(tenant, p, end, at, false);
                after = p.id();
            }
            if (page.size() < 200) return;
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void approved(PolicyApply entry, Snapshot snapshot, LocalDateTime at) {
        String tenant = entry.getTenantKey();
        var p = notices.approved(tenant, entry.getId());
        if (p != null) scheduleCurrent(tenant, p, at);
        var changes =
                snapshot.consent() == null
                        ? List.<PolicyConsent.EndAdjustment>of()
                        : snapshot.consent().endAdjustments();
        var targets = new LinkedHashSet<Long>();
        for (var change : changes) targets.add(Long.valueOf(change.target().policyId()));
        if (entry.getExtensionSourceId() != null) targets.add(entry.getExtensionSourceId());
        for (long id : targets) {
            var old = notices.approved(tenant, id);
            if (old == null) throw new IllegalStateException("无法还原已调整的目标政策。");
            boolean extension =
                    Objects.equals(entry.getExtensionSourceId(), id)
                            || changes.stream()
                                    .anyMatch(
                                            c ->
                                                    c.target().policyId().equals(Long.toString(id))
                                                            && PolicyEndTimes.isExtension(c));
            var body = policyPayload(extension ? "EXTENDED" : "SHORTENED", old);
            body.put("request", entry.getId()).put("actor", entry.getApplicantId());
            if (extension) {
                var child = notices.extension(tenant, id);
                if (child == null || !"APPROVED".equals(child.status()))
                    throw new IllegalStateException("找不到对应的已批延期单。");
                body.put("child", child.id());
                if (p == null || child.id() != p.id())
                    scheduleCurrent(tenant, notices.approved(tenant, child.id()), at);
            } else {
                var cutoff = coverage.approvedEnd(tenant, id, old.auditId(), at);
                if (cutoff == null) throw new IllegalStateException("无法还原获批的提前截止。");
                body.put("end", cutoff.actualEnd());
                scheduleCurrent(tenant, old, at);
            }
            register(
                    tenant,
                    "P/"
                            + id
                            + "/"
                            + body.path("kind").asText()
                            + "/"
                            + entry.getId()
                            + "/"
                            + entry.getCurrentVersion(),
                    body,
                    at,
                    at);
        }
    }

    private void scheduleCurrent(String tenant, Policy p, LocalDateTime at) {
        if (!StringUtils.hasText(config.getPolicyChatId()) || p == null) return;
        if (!enabled && notices.hasSchedule(tenant, "P/" + p.id() + "/" + p.version() + "/") == 0)
            return;
        plan(
                tenant,
                p,
                actualEnd(p, coverage.approvedEnd(tenant, p.id(), p.auditId(), at)),
                at,
                true);
    }

    private LocalDateTime actualEnd(Policy p, ApprovedEnd cut) {
        if (cut != null && Long.toString(p.auditId()).equals(cut.targetAuditId()))
            return PolicyFormValidation.time(cut.actualEnd());
        return PolicyApplicabilityEvaluator.originalThrough(
                p.endTime(), p.extensionSourceId() == null ? null : "延期");
    }

    private void plan(
            String tenant, Policy p, LocalDateTime end, LocalDateTime at, boolean changed) {
        String chat = config.getPolicyChatId();
        String prefix =
                "P/"
                        + p.id()
                        + "/"
                        + p.version()
                        + "/"
                        + UUID.nameUUIDFromBytes(chat.getBytes(StandardCharsets.UTF_8))
                        + "/";
        if (p.startTime().isAfter(at)
                && p.startTime().isAfter(p.approvedAt())
                && !p.startTime().isAfter(end)) {
            register(
                    tenant,
                    prefix + "START",
                    policyPayload("START", p).put("chat", chat),
                    at,
                    p.startTime());
        }
        for (String kind : List.of("NEAR", "END")) {
            String key = prefix + kind + "/" + end;
            if (changed) notices.cancelOld(tenant, prefix + kind + "/", key);
            if (kind.equals("NEAR") && end.isBefore(at)) continue;
            if (kind.equals("END") && end.equals(LocalDateTime.of(9999, 12, 31, 23, 59, 59)))
                continue;
            var due = kind.equals("NEAR") ? end.minusDays(3) : end.plusSeconds(1);
            register(
                    tenant,
                    key,
                    policyPayload(kind, p).put("end", end.toString()).put("chat", chat),
                    at,
                    due);
        }
    }

    private ObjectNode policyPayload(String kind, Policy p) {
        return json.createObjectNode()
                .put("kind", kind)
                .put("policy", p.id())
                .put("version", p.version());
    }

    private void register(
            String tenant, String key, ObjectNode body, LocalDateTime at, LocalDateTime due) {
        notices.register(tenant, key, body.toString(), at, due);
    }

    @Override
    public void platformChanged(String tenant, ChannelPlatform scope, long user, boolean assigned) {
        String prefix = "PL/" + scope.perspective() + "/" + scope.platformCode() + "/";
        var body =
                json.createObjectNode()
                        .put("kind", "PLATFORM")
                        .put("channel", scope.perspective())
                        .put("platform", scope.platformCode());
        if (assigned) {
            body.put("new", user);
            var previous = notices.lastPlatformChange(tenant, prefix);
            if (previous != null) {
                try {
                    var old = json.readTree(previous);
                    if (!old.has("new") && old.has("old"))
                        body.put("old", old.path("old").asLong());
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalStateException("无法读取平台交接记录。", e);
                }
            }
        } else body.put("old", user);
        var at = now();
        register(tenant, prefix + UUID.randomUUID(), body, at, at);
    }

    @Override
    public void paused(GoodsSyncJob job, String code) {
        int version = job.getRecordVersion() + 1;
        var body =
                catalog("IMPORT", job.getId(), job.getBrandName(), job.getRequesterId())
                        .put("version", version)
                        .put("error", code);
        catalogEvent(job.getRequesterId(), "IMPORT/" + job.getId() + "/" + version, body);
    }

    @Override
    public void cleaned(CatalogCleaningJob job, List<JobCount> counts) {
        long reviews = count(counts, "REVIEW_REQUIRED"), errors = count(counts, "ERROR");
        if (reviews + errors == 0) return;
        var body =
                catalog("CLEAN", job.getId(), job.getBrandName(), job.getRequesterId())
                        .put("reviews", reviews)
                        .put("errors", errors);
        catalogEvent(job.getRequesterId(), "CLEAN/" + job.getId(), body);
    }

    private static long count(List<JobCount> counts, String status) {
        return counts.stream()
                .filter(c -> status.equals(c.status()))
                .mapToLong(JobCount::total)
                .sum();
    }

    private ObjectNode catalog(String kind, long job, String brand, long requester) {
        return json.createObjectNode()
                .put("kind", kind)
                .put("job", job)
                .put("brand", brand)
                .put("requester", requester);
    }

    private void catalogEvent(long requester, String key, ObjectNode body) {
        // 发起人即本批负责人，停用后仍能保留原企业的处置记录。
        String tenant = notices.requesterTenant(requester);
        if (tenant == null) throw new IllegalStateException("商品批次缺少发起企业。");
        var at = now();
        register(tenant, key, body, at, at);
    }

    @Override
    public Message atApproval(String tenant, long id) {
        var p = notices.approved(tenant, id);
        if (p == null || p.startTime().isAfter(p.approvedAt())) return new Message("", List.of());
        return adopted(tenant, p, p.approvedAt(), p.approvedAt());
    }

    private Message adopted(String tenant, Policy p, LocalDateTime start, LocalDateTime at) {
        var scopes =
                coverage.adoptedScopes(
                        tenant, p.id(), p.channel(), notices.scopes(tenant, p.id()), start);
        if (!start.equals(at))
            scopes = coverage.adoptedScopes(tenant, p.id(), p.channel(), scopes, at);
        var used = new LinkedHashSet<String>();
        scopes.forEach(scope -> used.add(scope.platformCode()));
        if (used.isEmpty()) return new Message("", List.of());
        return new Message(
                DISPLAY.format(start)
                        + " 实际采用本单的平台："
                        + String.join(
                                "、",
                                used.stream().map(PolicyChangeDescription::platformLabel).toList())
                        + "。",
                owners(tenant, p.channel(), used));
    }

    private List<Long> owners(String tenant, String channel, Set<String> scopes) {
        return platforms.owners(tenant).stream()
                .filter(u -> u.perspective().equals(channel) && scopes.contains(u.platformCode()))
                .map(u -> Long.valueOf(u.userId()))
                .distinct()
                .toList();
    }

    @Override
    public Message message(String tenant, JsonNode body, LocalDateTime at) {
        String kind = body.path("kind").asText();
        if (body.has("chat") && !body.path("chat").asText().equals(config.getPolicyChatId()))
            throw stale("目标业务群已更换。");
        if (kind.equals("PLATFORM")) return platformMessage(tenant, body);
        if (kind.equals("CLEAN") || kind.equals("IMPORT")) return catalogMessage(body, kind);
        var p = notices.approved(tenant, body.path("policy").asLong());
        if (p == null || p.version() != body.path("version").asInt()) throw stale("原单已失效。");
        var approvedCut = coverage.approvedEnd(tenant, p.id(), p.auditId(), at);
        var end = actualEnd(p, approvedCut);
        if (kind.equals("START")) {
            if (at.isBefore(p.startTime()) || at.isAfter(end)) throw stale("已离开本单执行窗口。");
            var adopted = adopted(tenant, p, p.startTime(), at);
            if (adopted.text().isEmpty()) throw stale("当前范围未采用本单。");
            return new Message(policyNumber(p.id()) + " " + adopted.text(), adopted.users());
        }
        var scopes = Set.copyOf(notices.usedPlatforms(tenant, p.id()));
        var users = new LinkedHashSet<Long>();
        users.add(p.applicantId());
        users.addAll(owners(tenant, p.channel(), scopes));
        if (body.has("actor")) users.add(body.path("actor").asLong());
        String detail;
        if (kind.equals("SHORTENED")) {
            if (!end.equals(PolicyFormValidation.time(body.path("end").asText())))
                throw stale("此截止变更已被后续批准的截止取代。");
            detail =
                    "截止已变更，核准截止 "
                            + DISPLAY.format(end)
                            + "，发起单 "
                            + policyNumber(body.path("request").asLong())
                            + "。";
        } else if (kind.equals("EXTENDED")) {
            detail = extensionText(tenant, p, end);
        } else {
            if (!end.equals(LocalDateTime.parse(body.path("end").asText())))
                throw stale("政策实际截止已变更。");
            if (kind.equals("NEAR")) {
                if (at.isBefore(end.minusDays(3)) || at.isAfter(end)) throw stale("已离开临期提醒窗口。");
                detail =
                        "已进入临期窗口，实际截止 " + DISPLAY.format(end) + "。" + extensionText(tenant, p, end);
            } else if (kind.equals("END")) {
                if (!at.isAfter(end)) throw stale("尚未到达截止的下一秒。");
                String extension = extensionText(tenant, p, end);
                detail =
                        (approvedCut != null
                                        ? "政策已按获批调整提前结束。" + extension
                                        : extension.isEmpty() ? "政策自然结束。" : "本段已结束。" + extension)
                                + " 实际截止 "
                                + DISPLAY.format(end)
                                + "。";
            } else throw new Failure(Kind.PERMANENT, "无法识别业务提醒类型。");
        }
        return new Message(policyNumber(p.id()) + " " + detail, List.copyOf(users));
    }

    private Message platformMessage(String tenant, JsonNode body) {
        var users = new LinkedHashSet<Long>();
        if (body.has("old")) users.add(body.path("old").asLong());
        if (body.has("new")) users.add(body.path("new").asLong());
        String detail =
                (body.has("old")
                                ? " 原负责人 " + notices.accountName(tenant, body.path("old").asLong())
                                : "")
                        + (body.has("new")
                                ? " 现负责人 " + notices.accountName(tenant, body.path("new").asLong())
                                : "，当前待分配");
        return new Message(
                ("RETAIL".equals(body.path("channel").asText()) ? "零售" : "百亿")
                        + " · "
                        + PolicyChangeDescription.platformLabel(body.path("platform").asText())
                        + " 平台分工变更。"
                        + detail
                        + "。",
                List.copyOf(users));
    }

    private Message catalogMessage(JsonNode body, String kind) {
        long id = body.path("job").asLong();
        if (kind.equals("IMPORT") && notices.stillPaused(id, body.path("version").asInt()) == 0)
            throw stale("该暂停批次已续跑或结束。");
        String action =
                kind.equals("CLEAN")
                        ? "清洗批次 "
                                + id
                                + " 已结束，待人工审核 "
                                + body.path("reviews").asLong()
                                + " 项，技术失败 "
                                + body.path("errors").asLong()
                                + " 项。请在商品清洗记录处理。"
                        : "导入批次 " + id + " 异常暂停（" + body.path("error").asText() + "），请在源商品库核对后续跑。";
        return new Message(
                body.path("brand").asText() + " " + action,
                List.of(body.path("requester").asLong()));
    }

    private String extensionText(String tenant, Policy p, LocalDateTime end) {
        var child = notices.extension(tenant, p.id());
        if (child != null && "APPROVED".equals(child.status())) {
            var start =
                    child.approvedAt().isAfter(child.startTime())
                            ? child.approvedAt()
                            : child.startTime();
            return "延期已批，"
                    + policyNumber(child.id())
                    + " 从 "
                    + DISPLAY.format(start)
                    + " 起获准接续。"
                    + (start.isAfter(end.plusSeconds(1)) ? "原截止与延期起点之间有空档。" : "");
        }
        boolean pending =
                child != null
                        || notices.pendingChanges(tenant, p.id(), p.auditId()).stream()
                                .flatMap(
                                        s ->
                                                codec
                                                        .readEndAdjustments(s, p.id(), p.auditId())
                                                        .stream())
                                .anyMatch(PolicyEndTimes::isExtension);
        return pending ? "延期仍待审核，原单按当前截止执行。" : "";
    }

    private static Failure stale(String reason) {
        return new Failure(Kind.OBSOLETE, reason);
    }
}
