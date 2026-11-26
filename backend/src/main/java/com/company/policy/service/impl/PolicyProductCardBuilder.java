package com.company.policy.service.impl;

import static com.company.policy.support.policy.PolicyChangeDescription.policyName;

import com.company.policy.mapper.PolicyProductCardMapper;
import com.company.policy.mapper.PolicyProductCardMapper.Content;
import com.company.policy.service.PolicyFeishuSender;
import com.company.policy.support.policy.PolicyChangeDescription;
import com.company.policy.support.policy.PolicyExecutionChange;
import com.company.policy.support.policy.PolicyRouteSummary;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.vo.PolicyApplicability.Policy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 用已冻结的本轮依据构建飞书 2.0 私聊卡，按消息所属指派决定能否显示操作。 */
@Component
@RequiredArgsConstructor
public class PolicyProductCardBuilder {
    // 30 KB 上限含 HTTP 请求 JSON。收件人、UUID 和两枚飞书回执核对标识在传输层写入。
    private static final int MAX_CONTENT_BYTES = 29_000;
    private static final int PREVIEW_ITEMS = 3;
    // 每张政策两行各含 div 和 plain_text，留出组头、调整、表单及长名单提示的元素额度。
    private static final int MAX_POLICY_ROWS = 27;
    private final PolicyProductCardMapper cards;
    private final ObjectMapper json;
    private final PolicySnapshotCodec snapshots;

    public Map<String, Object> build(String tenant, long policyId, int version, long memberId) {
        var card = cards.content(tenant, policyId, version, memberId);
        if (card == null) throw new IllegalStateException("未找到初审卡所属轮次与指派。");
        var elements = new ArrayList<Map<String, Object>>();
        if (card.actionable()) {
            addEvidence(elements, card, Long.toString(policyId), version);
            elements.add(Map.of("tag", "hr"));
            elements.add(decisionForm(card, policyId, version, memberId));
        } else {
            elements.add(paragraph(result(card)));
        }
        String subtitle = PolicyApplyServiceImpl.policyNumber(policyId) + " · 第 " + version + " 轮";
        Map<String, Object> result =
                Map.of(
                        "schema",
                        "2.0",
                        "config",
                        Map.of("update_multi", true, "enable_forward", false),
                        "header",
                        Map.of(
                                "title",
                                plain(card.actionable() ? "政策叠加初审" : "政策初审"),
                                "subtitle",
                                plain(subtitle),
                                "template",
                                card.actionable() ? "blue" : "grey"),
                        "body",
                        Map.of("vertical_spacing", "8px", "elements", elements));
        if (wireBytes(result) > MAX_CONTENT_BYTES) throw new IllegalStateException("飞书初审卡超出消息长度。");
        return result;
    }

    private void addEvidence(
            List<Map<String, Object>> elements, Content card, String policyId, int version) {
        PolicyRouteSummary route;
        try {
            route = json.readValue(card.routeJson(), PolicyRouteSummary.class).forDisplay(policyId);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("初审卡无法读取当轮分流依据。", error);
        }
        var segments =
                route.changes() == null
                        ? List.<PolicyExecutionChange>of()
                        : route.changes().stream()
                                .filter(PolicyExecutionChange::requiresProductReview)
                                .toList();
        if (segments.isEmpty())
            throw new PolicyFeishuSender.Failure(
                    PolicyFeishuSender.Kind.PERMANENT, "本轮缺少已冻结的产品运营初审依据。");
        var groups = PolicyCardConfigurationGroups.group(segments);
        var ambiguousGoodsIds = PolicyChangeDescription.ambiguousCardGoodsIds(segments);
        var visibleGroups = groups.stream().limit(PREVIEW_ITEMS).toList();
        for (var group : visibleGroups) {
            addSegment(
                    elements,
                    group,
                    policyId,
                    version,
                    ambiguousGoodsIds,
                    MAX_POLICY_ROWS / visibleGroups.size());
        }
        if (groups.size() > PREVIEW_ITEMS)
            elements.add(paragraph("其余 " + (groups.size() - PREVIEW_ITEMS) + " 组未展开。"));
        if (route.adjustments() != null && !route.adjustments().isEmpty()) {
            var snapshot = snapshots.read(card.snapshotData());
            var accompanying = route.adjustments();
            elements.add(styled("随单截止调整", "heading", "default"));
            for (var adjustment : accompanying.stream().limit(PREVIEW_ITEMS).toList()) {
                String reason =
                        snapshot.consent().endAdjustments().stream()
                                .filter(a -> a.target().equals(adjustment.target()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException("本轮缺少随单调整依据。"))
                                .reason();
                elements.add(paragraph(adjustment.summary() + "\n原因：" + shorten(reason, 2000)));
            }
            if (accompanying.size() > PREVIEW_ITEMS)
                elements.add(paragraph("其余 " + (accompanying.size() - PREVIEW_ITEMS) + " 项调整未展开。"));
        }
    }

    private void addSegment(
            List<Map<String, Object>> elements,
            PolicyCardConfigurationGroups.Group group,
            String policyId,
            int version,
            Set<String> ambiguousGoodsIds,
            int maxPolicies) {
        var change = group.change();
        boolean selected =
                change.after().stream().anyMatch(policy -> policy.isRound(policyId, version));
        var versions = PolicyChangeDescription.policyVersions(change);
        String selection =
                PolicyChangeDescription.cardDecision(change)
                        + " · "
                        + limitedPolicyNames(change.after(), versions, " ＋ ", 900);
        String scope =
                shorten(
                        PolicyChangeDescription.cardScope(
                                change, group.wholeConfiguration(), ambiguousGoodsIds),
                        900);
        var summary =
                Map.of(
                        "tag",
                        "column",
                        "width",
                        "weighted",
                        "weight",
                        1,
                        "background_style",
                        "blue-50",
                        "padding",
                        "10px",
                        "vertical_spacing",
                        "4px",
                        "elements",
                        List.of(
                                styled(selection, "heading", "blue"),
                                paragraph("手机配置　" + scope),
                                styled(
                                        PolicyChangeDescription.cardPeriod(
                                                change, group.from(), group.through()),
                                        "notation",
                                        "grey")));
        elements.add(Map.of("tag", "column_set", "columns", List.of(summary)));
        int remainingBytes = 3600;
        int displayed = 0;
        for (var policy : change.after().stream().limit(maxPolicies).toList()) {
            boolean current = policy.isRound(policyId, version);
            String role = current ? "本次申请" : policy.proposed() ? "本轮随单延期" : "原政策";
            String name = policyName(policy, versions) + "（" + role + "）";
            String terms =
                    PolicyChangeDescription.cardTerms(
                            policy,
                            "RETAIL".equals(change.perspective()),
                            group.wholeConfiguration(),
                            ambiguousGoodsIds);
            var identity = styled(name, "normal", current ? "blue" : "default");
            var conditions = paragraph(shorten(terms, 3000));
            int size = wireBytes(identity) + wireBytes(conditions);
            if (size > remainingBytes) break;
            elements.add(identity);
            elements.add(conditions);
            remainingBytes -= size;
            displayed++;
        }
        int omitted = change.after().size() - displayed;
        if (omitted > 0) elements.add(paragraph("另有 " + omitted + " 张拟采用政策未展开。"));
        if (!selected) elements.add(styled("本次申请未在本段采用", "notation", "grey"));
        if (!change.removed().isEmpty()) {
            elements.add(
                    styled(
                            "退出本段：" + limitedPolicyNames(change.removed(), versions, "、", 600),
                            "notation",
                            "grey"));
        }
    }

    private String limitedPolicyNames(
            List<Policy> policies,
            Map<String, Set<Integer>> versions,
            String separator,
            int limit) {
        var names = new ArrayList<String>();
        int bytes = 0;
        for (var policy : policies) {
            String name = policyName(policy, versions);
            bytes += wireBytes(separator + name);
            if (bytes > limit) {
                return String.join(separator, names)
                        + "（另有 "
                        + (policies.size() - names.size())
                        + " 张未展开）";
            }
            names.add(name);
        }
        return String.join(separator, names);
    }

    private static Map<String, Object> decisionForm(
            Content card, long policyId, int version, long memberId) {
        // 两个决定共用输入框。只有拒绝需要原因，服务端按点击的决定验证。
        var reason =
                Map.of(
                        "tag",
                        "input",
                        "name",
                        "reason",
                        "width",
                        "fill",
                        "input_type",
                        "multiline_text",
                        "rows",
                        2,
                        "required",
                        false,
                        "max_length",
                        512,
                        "label",
                        plain("拒绝原因（仅拒绝时必填）"),
                        "placeholder",
                        plain("请填写需要申请人修改的内容，最多 512 字"));
        var buttons = new ArrayList<Map<String, Object>>();
        for (String decision : List.of("APPROVE", "REJECT")) {
            boolean approve = decision.equals("APPROVE");
            var button =
                    Map.of(
                            "tag",
                            "button",
                            "name",
                            approve ? "approve" : "reject",
                            "text",
                            plain(approve ? "通过" : "拒绝"),
                            "type",
                            approve ? "primary_filled" : "danger",
                            "width",
                            "fill",
                            "form_action_type",
                            "submit",
                            "behaviors",
                            callback(policyId, version, memberId, card, decision));
            buttons.add(
                    Map.of(
                            "tag",
                            "column",
                            "width",
                            "weighted",
                            "weight",
                            1,
                            "elements",
                            List.of(button)));
        }
        var actions =
                Map.of(
                        "tag",
                        "column_set",
                        "flex_mode",
                        "bisect",
                        "horizontal_spacing",
                        "8px",
                        "columns",
                        buttons);
        return Map.of(
                "tag",
                "form",
                "name",
                "rejection",
                "vertical_spacing",
                "8px",
                "elements",
                List.of(reason, actions));
    }

    private int wireBytes(Object value) {
        // IM 的 content 是字符串，控制字符、引号和反斜杠会随整张卡再次转义。
        try {
            return json.writeValueAsBytes(json.valueToTree(value).toString()).length;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法编码飞书卡片。", error);
        }
    }

    private String shorten(String value, int maxBytes) {
        if (wireBytes(value) <= maxBytes) return value;
        String suffix = "…（已省略）";
        int low = 0;
        int high = Math.min(value.codePointCount(0, value.length()), maxBytes);
        while (low < high) {
            int mid = (low + high + 1) / 2;
            var prefix = value.substring(0, value.offsetByCodePoints(0, mid));
            if (wireBytes(prefix + suffix) <= maxBytes) low = mid;
            else high = mid - 1;
        }
        return value.substring(0, value.offsetByCodePoints(0, low)) + suffix;
    }

    private static String result(Content card) {
        if (card.decision() == null
                || !("HANDLED".equals(card.memberStatus())
                        || "PEER_HANDLED".equals(card.memberStatus()))) return "本卡已停止办理";
        String decision = "APPROVE".equals(card.decision()) ? "通过" : "拒绝";
        String result = card.signerName() + " 已" + decision;
        if ("PEER_HANDLED".equals(card.memberStatus())) result = "同组他人已处理\n" + result;
        return card.reason() == null ? result : result + "\n审核意见：" + card.reason();
    }

    private static List<Map<String, Object>> callback(
            long policyId, int version, long memberId, Content card, String decision) {
        return List.of(
                Map.of(
                        "type",
                        "callback",
                        "value",
                        Map.of(
                                "policyId",
                                Long.toString(policyId),
                                "round",
                                version,
                                "memberId",
                                Long.toString(memberId),
                                "assignmentNo",
                                card.assignmentNo(),
                                "recordVersion",
                                card.recordVersion(),
                                "node",
                                "PRODUCT_REVIEW",
                                "decision",
                                decision)));
    }

    private static Map<String, Object> paragraph(String value) {
        return Map.of("tag", "div", "text", plain(value));
    }

    private static Map<String, Object> styled(String value, String size, String color) {
        return Map.of(
                "tag",
                "div",
                "text",
                Map.of(
                        "tag",
                        "plain_text",
                        "content",
                        value,
                        "text_size",
                        size,
                        "text_color",
                        color));
    }

    private static Map<String, String> plain(String value) {
        return Map.of("tag", "plain_text", "content", value);
    }
}
