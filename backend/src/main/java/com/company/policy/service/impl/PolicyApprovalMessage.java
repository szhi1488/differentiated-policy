package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.policyNumber;
import static com.company.policy.support.policy.PolicyChangeDescription.platformLabel;

import com.company.policy.dto.PolicyDraftRequest.Card;
import com.company.policy.dto.PolicyDraftRequest.Link;
import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.dto.PolicyDraftRequest.Target;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.entity.PolicyTimeChange;
import com.company.policy.mapper.PolicyDeliveryAudienceMapper;
import com.company.policy.support.policy.PolicyChangeDescription;
import com.company.policy.support.policy.PolicyExecutionChange;
import com.company.policy.support.policy.PolicyFormValidation;
import com.company.policy.support.policy.PolicyRouteSummary;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.vo.ApplicantCatalogResponse;
import com.company.policy.vo.ApplicantCatalogResponse.Configuration;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 按获批轮次快照生成群公告，超长内容按完整条目截断并注明数量。 */
@Component
@RequiredArgsConstructor
class PolicyApprovalMessage {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final PolicyDeliveryAudienceMapper audience;
    private final PolicySnapshotCodec snapshots;
    private final ObjectMapper json;

    String build(
            String tenant,
            long policyId,
            int version,
            String signer,
            String executionText,
            Predicate<String> fitsMessage) {
        var round = audience.approvedRound(tenant, policyId, version);
        if (round == null) throw new IllegalStateException("未找到群公告对应的获批轮次。");
        String summary =
                format(snapshots.read(round.snapshotData()), round.productReviewer(), signer);
        if (StringUtils.hasText(executionText)) summary += "\n" + executionText;
        if (round.routeJson() == null) return summary;
        String id = Long.toString(policyId);
        PolicyRouteSummary route;
        try {
            route = json.readValue(round.routeJson(), PolicyRouteSummary.class).forDisplay(id);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("群公告无法读取获批轮次的叠加依据。", invalid);
        }
        if (route.changes() == null) return summary;
        var stacks =
                route.changes().stream().filter(PolicyExecutionChange::stackedSelection).toList();
        if (stacks.isEmpty()) return summary;
        var ambiguousGoodsIds = PolicyChangeDescription.ambiguousCardGoodsIds(stacks);
        var groups = PolicyCardConfigurationGroups.group(stacks);
        String prefix = summary + "\n\n【叠加政策】\n";
        String fallback = prefix + omittedGroups(groups.size());
        if (!fitsMessage.test(fallback)) return summary;
        for (int i = 0; i < groups.size(); i++) {
            var group = stackedGroup(groups.get(i), id, version, ambiguousGoodsIds);
            String heading = prefix + group.heading();
            int remaining = groups.size() - i - 1;
            String rest = remaining == 0 ? "" : "\n\n" + omittedGroups(remaining);
            String complete = heading + "\n" + String.join("\n", group.terms());
            if (!fitsMessage.test(complete + rest)) {
                // 只裁减新增区块，始终保留完整的原单条款和未列出的数量。
                for (int j = 0; j < group.terms().size(); j++) {
                    String candidate =
                            heading + "\n另有 " + (group.terms().size() - j) + " 张政策的补贴条款未列出。" + rest;
                    if (!fitsMessage.test(candidate)) break;
                    fallback = candidate;
                    heading += "\n" + group.terms().get(j);
                }
                return fallback;
            }
            fallback = complete + rest;
            prefix = complete + "\n\n";
        }
        return fallback;
    }

    private static String omittedGroups(int count) {
        return "另有 " + count + " 组叠加政策未列出。";
    }

    private record StackedGroup(String heading, List<String> terms) {}

    private static StackedGroup stackedGroup(
            PolicyCardConfigurationGroups.Group group,
            String id,
            int version,
            Set<String> ambiguousGoodsIds) {
        var change = group.change();
        var versions = PolicyChangeDescription.policyVersions(change);
        var names = new ArrayList<String>();
        var originals = new ArrayList<String>();
        boolean includesApplication = false;
        for (var policy : change.after()) {
            String name = PolicyChangeDescription.policyName(policy, versions);
            names.add(name);
            if (policy.isRound(id, version)) {
                includesApplication = true;
            } else {
                String role = policy.proposed() ? "本轮随单延期" : "原政策";
                String terms =
                        PolicyChangeDescription.cardTerms(
                                policy,
                                "RETAIL".equals(change.perspective()),
                                group.wholeConfiguration(),
                                ambiguousGoodsIds);
                originals.add(name + "（" + role + "）\n" + terms);
            }
        }
        var lines = new ArrayList<String>();
        lines.add("叠加执行 · " + String.join(" ＋ ", names));
        lines.add(
                PolicyChangeDescription.cardScope(
                        change, group.wholeConfiguration(), ambiguousGoodsIds));
        lines.add(PolicyChangeDescription.cardPeriod(change, group.from(), group.through()));
        if (!includesApplication) lines.add("本次申请未在本段采用");
        return new StackedGroup(String.join("\n", lines), originals);
    }

    String adjusted(PolicyTimeChange change) {
        var round =
                audience.approvedRound(
                        change.getTenantKey(), change.getPolicyId(), change.getPolicyVersion());
        if (round == null) throw new IllegalStateException("未找到原单的获批快照。");
        var original = snapshots.read(round.snapshotData());
        String heading =
                ("EXTEND".equals(change.getDirection()) ? "政策已延期" : "政策截止已提前")
                        + " · "
                        + original.policyCode()
                        + "\n操作人："
                        + change.getActorName()
                        + "　办理时间："
                        + TIME.format(change.getChangedAt());
        if (change.getSourcePolicyId() != null)
            heading += "　发起单：" + policyNumber(change.getSourcePolicyId());
        return formDetails(original, heading, change);
    }

    static String format(Snapshot snapshot, String productReviewer, String signer) {
        String heading =
                "采购终审通过 · "
                        + snapshot.policyCode()
                        + " · 第 "
                        + snapshot.submissionVersion()
                        + " 轮\n申请人："
                        + snapshot.applicantName()
                        + (productReviewer == null ? "" : "　运营初审人：" + productReviewer)
                        + "　终审人："
                        + signer;
        return formDetails(snapshot, heading, null);
    }

    private static String formDetails(Snapshot snapshot, String heading, PolicyTimeChange change) {
        var form = snapshot.form();
        var sections = new ArrayList<String>();
        sections.add(heading);
        boolean retail = "RETAIL".equals(form.perspective());
        var basic = new ArrayList<String>();
        basic.add("【基础信息】");
        basic.add(
                "渠道："
                        + (retail ? "零售渠道" : "百亿渠道")
                        + "　平台："
                        + form.selectedPlatforms().stream()
                                .map(PolicyChangeDescription::platformLabel)
                                .collect(Collectors.joining("、")));
        if (retail) {
            basic.add(
                    "核销类型："
                            + PolicyChangeDescription.verifyLabel(form.verifyType())
                            + "　涉及场景："
                            + form.sceneTypes().stream()
                                    .map(PolicyChangeDescription::sceneLabel)
                                    .collect(Collectors.joining("、")));
            basic.add("是否为预估政策：" + (Boolean.TRUE.equals(form.estimated()) ? "是" : "否"));
        }
        sections.add(String.join("\n", basic));
        var time = new ArrayList<String>();
        time.add("【政策时间与详情】");
        if (change == null) {
            time.add(
                    "申报时段（北京时间）："
                            + displayTime(form.startTime())
                            + " 至 "
                            + displayTime(form.endTime()));
            if (snapshot.consent() != null && !snapshot.consent().endAdjustments().isEmpty())
                time.add(
                        limited(
                                snapshot.consent().endAdjustments().stream()
                                        .map(PolicyApprovalMessage::adjustment)
                                        .toList(),
                                1400,
                                "项调整"));
        } else {
            if ("SHORTEN".equals(change.getDirection()))
                time.add(
                        "调整前政策时段（北京时间）："
                                + displayTime(form.startTime())
                                + " 至 "
                                + TIME.format(change.getPreviousEnd()));
            time.add(
                    "截止变更（北京时间）："
                            + TIME.format(change.getPreviousEnd())
                            + " → "
                            + TIME.format(change.getRequestedEnd()));
            if (!change.getActualEnd().equals(change.getRequestedEnd()))
                time.add("实际截止（北京时间）：" + TIME.format(change.getActualEnd()));
            if ("EXTEND".equals(change.getDirection()))
                time.add("接续起点（北京时间）：" + TIME.format(change.getEffectiveAt()));
            time.add("原因：" + change.getReason());
        }
        if (StringUtils.hasText(form.specialRemark())) time.add("特别备注：" + form.specialRemark());
        sections.add(String.join("\n", time));
        var goods =
                snapshot.goods().stream()
                        .collect(
                                Collectors.toMap(
                                        ApplicantCatalogResponse::goodsId, Function.identity()));
        var ambiguous = ambiguousColors(snapshot.goods());
        var cardLines =
                form.goodsForm().cards().stream()
                        .sorted(
                                Comparator.comparing(
                                        card ->
                                                snapshot.wholeConfigurationSelection()
                                                        && "SPECIFIED".equals(card.cardType())))
                        .map(
                                card ->
                                        cardText(
                                                card,
                                                goods,
                                                ambiguous,
                                                snapshot.wholeConfigurationSelection()))
                        .toList();
        sections.add("【商品及补贴信息】\n" + limited(cardLines, 3000, "张商品卡"));
        return String.join("\n\n", sections);
    }

    private static String cardText(
            Card card,
            Map<String, ApplicantCatalogResponse> goods,
            Set<String> ambiguousColors,
            boolean confirmedWhole) {
        boolean specified = "SPECIFIED".equals(card.cardType());
        var products = productLines(card, goods, ambiguousColors, confirmedWhole);
        var plans =
                card.subsidyPlans().stream()
                        .collect(Collectors.toMap(SubsidyPlan::key, Function.identity()));
        var rows = new ArrayList<String>();
        rows.add((specified ? "指定适用机型" : "手机型号") + "（" + card.targets().size() + " 件商品）");
        rows.add(limited(products, 560, specified ? "件商品" : "项商品范围"));
        for (var assignment : card.platformAssignments()) {
            var plan = plans.get(assignment.planKey());
            String amount =
                    "FIXED".equals(plan.subsidyMode())
                            ? "固定补贴 " + plan.fixedAmount() + " 元"
                            : "补贴比例 "
                                    + plan.ratio()
                                    + "%（计算基数："
                                    + PolicyChangeDescription.priceBaseLabel(plan.priceBase())
                                    + "）";
            if (plan.maxSubsidyAmount() != null)
                amount += "，补贴上限 " + plan.maxSubsidyAmount() + " 元";
            if (specified)
                amount +=
                        assignment.quantity() == null
                                ? "，数量未填"
                                : "，数量 " + assignment.quantity() + " 台";
            rows.add(platformLabel(assignment.platformCode()) + "：" + amount);
        }
        return String.join("\n", rows);
    }

    private record ConfigurationColor(Configuration configuration, String color) {}

    private static Set<String> ambiguousColors(List<ApplicantCatalogResponse> goods) {
        var first = new HashMap<ConfigurationColor, String>();
        var ambiguous = new HashSet<String>();
        for (var item : goods) {
            if (!StringUtils.hasText(item.colorName())) continue;
            var key =
                    new ConfigurationColor(
                            new Configuration(item.spuId(), item.ram(), item.rom()),
                            item.colorName());
            String other = first.putIfAbsent(key, item.goodsId());
            if (other != null && !other.equals(item.goodsId())) {
                ambiguous.add(other);
                ambiguous.add(item.goodsId());
            }
        }
        return ambiguous;
    }

    private static List<String> productLines(
            Card card,
            Map<String, ApplicantCatalogResponse> goods,
            Set<String> ambiguousColors,
            boolean confirmedWhole) {
        var byConfiguration = new LinkedHashMap<Configuration, List<Target>>();
        var other = new ArrayList<String>();
        for (var target : card.targets()) {
            var item = goods.get(target.goodsId());
            var configuration =
                    new Configuration(
                            valueOr(target.spuId(), item == null ? null : item.spuId()),
                            valueOr(target.ram(), item == null ? null : item.ram()),
                            valueOr(target.rom(), item == null ? null : item.rom()));
            if (!"COMMON".equals(card.cardType())
                    || !StringUtils.hasText(configuration.spuId())
                    || !StringUtils.hasText(configuration.ram())
                    || !StringUtils.hasText(configuration.rom())) {
                other.add(
                        (productName(item, configuration)
                                        + " "
                                        + selectedColor(target, item, ambiguousColors))
                                .strip());
            } else {
                byConfiguration
                        .computeIfAbsent(configuration, key -> new ArrayList<>())
                        .add(target);
            }
        }
        var whole = new ArrayList<String>();
        for (var entry : byConfiguration.entrySet()) {
            var items = entry.getValue();
            var model =
                    items.stream()
                            .map(t -> goods.get(t.goodsId()))
                            .filter(item -> item != null && StringUtils.hasText(item.modelName()))
                            .findFirst()
                            .orElse(null);
            String name = productName(model, entry.getKey());
            if (confirmedWhole
                    && items.stream()
                            .map(t -> goods.get(t.goodsId()))
                            .allMatch(
                                    item ->
                                            item != null
                                                    && StringUtils.hasText(item.modelName()))) {
                whole.add(name + " 整组配置");
            } else {
                String colors =
                        items.stream()
                                .map(t -> selectedColor(t, goods.get(t.goodsId()), ambiguousColors))
                                .collect(Collectors.joining("、"));
                other.add(name + " " + colors);
            }
        }
        whole.addAll(other);
        return whole;
    }

    private static String valueOr(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static String productName(ApplicantCatalogResponse item, Configuration configuration) {
        String capacity =
                Stream.of(
                                configuration.ram(),
                                "1024GB".equals(configuration.rom()) ? "1T" : configuration.rom())
                        .filter(StringUtils::hasText)
                        .collect(Collectors.joining("+"));
        if (item == null || !StringUtils.hasText(item.modelName())) {
            String model =
                    StringUtils.hasText(configuration.spuId()) ? "机型 " + configuration.spuId() : "";
            return (model + " " + capacity).strip();
        }
        return Stream.of(
                        item.brandName(),
                        item.seriesName(),
                        item.modelName(),
                        item.edition(),
                        capacity)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(" "));
    }

    private static String selectedColor(
            Target target, ApplicantCatalogResponse item, Set<String> ambiguous) {
        String id = "商品 ID " + target.goodsId();
        if (item == null || !StringUtils.hasText(item.colorName())) return id;
        return ambiguous.contains(target.goodsId()) || !StringUtils.hasText(item.modelName())
                ? item.colorName() + "（" + id + "）"
                : item.colorName();
    }

    private static String adjustment(EndAdjustment change) {
        return "调整 "
                + policyNumber(Long.parseLong(change.target().policyId()))
                + "："
                + displayTime(change.target().previousEnd())
                + " → "
                + displayTime(change.endTime())
                + "（北京时间）\n原因："
                + change.reason()
                + (change.attachmentInfo().isEmpty()
                        ? ""
                        : "\n证明文件：" + attachments(change.attachmentInfo()));
    }

    private static String displayTime(String value) {
        return TIME.format(PolicyFormValidation.time(value));
    }

    private static String attachments(List<Link> links) {
        var names =
                links.stream()
                        .map(link -> "FILE".equals(link.type()) ? link.originalName() : "外部链接")
                        .toList();
        return limited(names, 600, "项附件或链接");
    }

    // 以完整条目截断，避免只显示半个金额、比例或商品范围。
    private static String limited(List<String> items, int maxLength, String unit) {
        var out = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            String item = items.get(i);
            if (out.length() + item.length() > maxLength) {
                out.append("另有 ").append(items.size() - i).append(unit).append("未列出。");
                break;
            }
            out.append(item);
            if (i + 1 < items.size()) out.append('\n');
        }
        return out.toString();
    }
}
