package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.now;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.policyNumber;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.dto.ChannelPlatform;
import com.company.policy.entity.CatalogCleaningJob;
import com.company.policy.entity.GoodsSyncJob;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyTimeChange;
import com.company.policy.mapper.CatalogCleaningMapper.JobCount;
import com.company.policy.mapper.ExecutionNoticeMapper;
import com.company.policy.mapper.ExecutionNoticeMapper.Policy;
import com.company.policy.mapper.PolicyTimeChangeMapper;
import com.company.policy.mapper.UserPlatformMapper;
import com.company.policy.service.ExecutionNotices;
import com.company.policy.service.PolicyCoverageService;
import com.company.policy.service.PolicyFeishuSender.Failure;
import com.company.policy.service.PolicyFeishuSender.Kind;
import com.company.policy.support.policy.PolicyChangeDescription;
import com.company.policy.support.policy.PolicyEndTimes;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final PolicyTimeChangeMapper changes;
    private final UserPlatformMapper platforms;
    private final PolicyCoverageService coverage;
    private final PolicySnapshotCodec codec;
    private final PolicyApprovalMessage approvals;
    private final FeishuProperties config;
    private final ObjectMapper json;

    @Value("${policy.review-delivery-enabled:false}")
    private boolean enabled;

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final LocalTime REMINDER_TIME = LocalTime.of(17, 0);

    @Override
    public void initialize(LocalDateTime at) {
        String tenant = config.getTenantKey();
        if (!config.hasCompanyTenant()) return;
        long after = 0;
        while (true) {
            var page = notices.activePage(tenant, after, at);
            for (var p : page) {
                plan(tenant, p, at, false);
                after = p.id();
            }
            if (page.size() < 200) return;
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void approved(PolicyApply entry, Snapshot snapshot, LocalDateTime at) {
        scheduleCurrent(
                entry.getTenantKey(), notices.approved(entry.getTenantKey(), entry.getId()), at);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void timeAdjusted(PolicyTimeChange change) {
        var tenant = change.getTenantKey();
        var p = notices.approved(tenant, change.getPolicyId());
        if (p == null || p.auditId() != change.getAuditLogId())
            throw new IllegalStateException("无法核对改期原单的获批轮次。");
        var at = change.getChangedAt();
        var kind = "EXTEND".equals(change.getDirection()) ? "EXTENDED" : "SHORTENED";
        var payload =
                policyPayload(kind, p)
                        .put("changeId", change.getId())
                        .put("actor", change.getActorId());
        register(tenant, "P/" + p.id() + "/" + kind + "/" + change.getId(), payload, at, at);
        scheduleCurrent(tenant, p, at);
    }

    private void scheduleCurrent(String tenant, Policy p, LocalDateTime at) {
        if (p == null) return;
        if (!enabled
                && notices.hasSchedule(tenant, "P/" + p.id() + "/" + p.version() + "/") == 0
                && notices.hasSchedule(tenant, reminderPrefix(p)) == 0) return;
        plan(tenant, p, at, true);
    }

    private void plan(String tenant, Policy p, LocalDateTime at, boolean changed) {
        var end = p.endTime();
        // 截止变更前后若共用某一提醒日，沿用该日登记和回执，到期天数在发送时重算。
        var reminderPrefix = reminderPrefix(p);
        var lastReminder = end.toLocalDate().minusDays(1);
        var firstReminder = lastReminder.minusDays(1);
        if (changed)
            notices.cancelOtherReminderDates(
                    tenant,
                    reminderPrefix,
                    reminderPrefix + firstReminder,
                    reminderPrefix + lastReminder);
        for (var date : List.of(firstReminder, lastReminder)) {
            if (!date.isBefore(at.toLocalDate()))
                registerSchedule(
                        tenant,
                        reminderPrefix + date,
                        policyPayload(EXPIRY_REMINDER, p).put("reminderDate", date.toString()),
                        at,
                        date.atTime(REMINDER_TIME));
        }
        String chat = config.getPolicyChatId();
        if (!StringUtils.hasText(chat)) return;
        String prefix = groupPrefix(p, chat);
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
        String endKey = prefix + "END/" + end;
        if (changed) notices.cancelOld(tenant, prefix + "END/", endKey);
        if (!end.equals(LocalDateTime.of(9999, 12, 31, 23, 59, 59)))
            registerSchedule(
                    tenant,
                    endKey,
                    policyPayload("END", p).put("end", end.toString()).put("chat", chat),
                    at,
                    end.plusSeconds(1));
    }

    private void registerSchedule(
            String tenant, String key, ObjectNode payload, LocalDateTime at, LocalDateTime due) {
        register(tenant, key, payload, at, due);
        notices.resumeDeadlinePlan(tenant, key, due);
    }

    private static String groupPrefix(Policy p, String chat) {
        return "P/"
                + p.id()
                + "/"
                + p.version()
                + "/"
                + UUID.nameUUIDFromBytes(chat.getBytes(StandardCharsets.UTF_8))
                + "/";
    }

    private static String reminderPrefix(Policy p) {
        return "P/" + p.id() + "/" + EXPIRY_REMINDER + "/";
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
        if (kind.equals("EXTENDED") || kind.equals("SHORTENED")) {
            var change = changes.byId(tenant, p.id(), body.path("changeId").asLong());
            if (change == null || change.getAuditLogId() != p.auditId()) throw stale("找不到该次改期记录。");
            return new Message(approvals.adjusted(change), changeRecipients(tenant, p, body));
        }
        var end = p.endTime();
        if (kind.equals(EXPIRY_REMINDER)) {
            var date = LocalDate.parse(body.path("reminderDate").asText());
            long days = ChronoUnit.DAYS.between(date, end.toLocalDate());
            if (days != 1 && days != 2) throw stale("政策实际截止已变更，本日无需提醒。");
            if (!date.equals(at.toLocalDate()) || at.toLocalTime().isBefore(REMINDER_TIME))
                throw stale("不在本次到期提醒当日 17:00 起的发送时段。");
            return new Message(
                    policyNumber(p.id())
                            + " 将于"
                            + (days == 1 ? "明天" : "后天")
                            + "到期，实际截止 "
                            + DISPLAY.format(end)
                            + "（北京时间）。",
                    List.of(p.applicantId()));
        }
        if (kind.equals("START")) {
            if (at.isBefore(p.startTime()) || at.isAfter(end)) throw stale("已离开本单执行窗口。");
            var adopted = adopted(tenant, p, p.startTime(), at);
            if (adopted.text().isEmpty()) throw stale("当前范围未采用本单。");
            return new Message(policyNumber(p.id()) + " " + adopted.text(), adopted.users());
        }
        if (!kind.equals("END")) throw new Failure(Kind.PERMANENT, "无法识别业务提醒类型。");
        var registeredEnd = LocalDateTime.parse(body.path("end").asText());
        if (!end.equals(registeredEnd)) throw stale("政策实际截止已变更。");
        if (!at.isAfter(end)) throw stale("尚未到达截止的下一秒。");
        var detail = "SHORTEN".equals(p.lastChange()) ? "政策已按获批调整提前结束。" : "政策自然结束。";
        return new Message(
                policyNumber(p.id())
                        + " "
                        + detail
                        + extensionText(tenant, p)
                        + " 实际截止 "
                        + DISPLAY.format(end)
                        + "。",
                changeRecipients(tenant, p, body));
    }

    private List<Long> changeRecipients(String tenant, Policy p, JsonNode body) {
        var scopes = Set.copyOf(notices.usedPlatforms(tenant, p.id()));
        var users = new LinkedHashSet<Long>();
        users.add(p.applicantId());
        users.addAll(owners(tenant, p.channel(), scopes));
        if (body.has("actor")) users.add(body.path("actor").asLong());
        return List.copyOf(users);
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
                                + " 项。"
                        : "导入批次 " + id + " 异常暂停（" + body.path("error").asText() + "）。";
        return new Message(
                body.path("brand").asText() + " " + action,
                List.of(body.path("requester").asLong()));
    }

    private String extensionText(String tenant, Policy p) {
        boolean pending =
                notices.pendingChanges(tenant, p.id(), p.auditId()).stream()
                        .flatMap(s -> codec.readEndAdjustments(s, p.id(), p.auditId()).stream())
                        .anyMatch(PolicyEndTimes::isExtension);
        return pending ? "延期仍待审核，原单按当前截止执行。" : "";
    }

    private static Failure stale(String reason) {
        return new Failure(Kind.OBSOLETE, reason);
    }
}
