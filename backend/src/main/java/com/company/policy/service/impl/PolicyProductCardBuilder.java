package com.company.policy.service.impl;

import com.company.policy.mapper.PolicyProductCardMapper;
import com.company.policy.mapper.PolicyProductCardMapper.Content;
import com.company.policy.service.PolicyFeishuSender;
import com.company.policy.support.policy.PolicyChangeDescription;
import com.company.policy.support.policy.PolicyExecutionChange;
import com.company.policy.support.policy.PolicyRouteSummary;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 用已冻结的本轮依据构建飞书 2.0 私聊卡，按消息所属指派决定能否显示操作。 */
@Component
@RequiredArgsConstructor
public class PolicyProductCardBuilder {
    // 30 KB 上限含 HTTP 请求 JSON。收件人、UUID 和两枚飞书回执核对标识在传输层写入。
    private static final int MAX_CONTENT_BYTES = 29_000;
    private static final int PREVIEW_ITEMS = 3;
    private final PolicyProductCardMapper cards;
    private final ObjectMapper json;
    private final PolicySnapshotCodec snapshots;

    public Map<String, Object> build(
            String tenant, long policyId, int version, long memberId, String detailUrl) {
        var card = cards.content(tenant, policyId, version, memberId);
        if (card == null) throw new IllegalStateException("未找到初审卡所属轮次与指派。");
        var elements = new ArrayList<Map<String, Object>>();
        if (card.actionable()) {
            addEvidence(elements, card);
        } else {
            elements.add(paragraph(result(card)));
        }
        elements.add(
                Map.of(
                        "tag",
                        "button",
                        "text",
                        plain("查看本轮详情"),
                        "behaviors",
                        List.of(Map.of("type", "open_url", "default_url", detailUrl))));
        if (card.actionable()) {
            elements.add(
                    Map.of(
                            "tag",
                            "button",
                            "type",
                            "primary",
                            "text",
                            plain("通过"),
                            "behaviors",
                            callback(policyId, version, memberId, card, "APPROVE")));
            elements.add(
                    Map.of(
                            "tag",
                            "form",
                            "name",
                            "rejection",
                            "elements",
                            List.of(
                                    Map.of(
                                            "tag",
                                            "input",
                                            "name",
                                            "reason",
                                            "required",
                                            true,
                                            "max_length",
                                            512,
                                            "placeholder",
                                            plain("请填写拒绝原因（最多 512 字）")),
                                    Map.of(
                                            "tag",
                                            "button",
                                            "name",
                                            "reject",
                                            "form_action_type",
                                            "submit",
                                            "type",
                                            "danger",
                                            "text",
                                            plain("拒绝"),
                                            "behaviors",
                                            callback(
                                                    policyId, version, memberId, card,
                                                    "REJECT")))));
        }
        String title =
                "政策初审 · "
                        + PolicyApplyServiceImpl.policyNumber(policyId)
                        + " · 第 "
                        + version
                        + " 轮";
        Map<String, Object> result =
                Map.of(
                        "schema",
                        "2.0",
                        "config",
                        Map.of("update_multi", true, "enable_forward", false),
                        "header",
                        Map.of(
                                "title",
                                plain(title),
                                "template",
                                card.actionable() ? "blue" : "grey"),
                        "body",
                        Map.of("elements", elements));
        if (wireBytes(result) > MAX_CONTENT_BYTES)
            throw new IllegalStateException("飞书卡片超出消息长度，请核对 ERP 详情地址。");
        return result;
    }

    private void addEvidence(List<Map<String, Object>> elements, Content card) {
        PolicyRouteSummary route;
        try {
            route = json.readValue(card.routeJson(), PolicyRouteSummary.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("初审卡无法读取当轮分流依据。", error);
        }
        var segments =
                route.changes() == null
                        ? List.<PolicyExecutionChange>of()
                        : route.changes().stream()
                                .filter(PolicyExecutionChange::requiresProductReview)
                                .toList();
        // 控制卡片体积，并为长名单、长原因保留按轮查看的入口。
        for (var change : segments.stream().limit(PREVIEW_ITEMS).toList()) {
            elements.add(
                    paragraph(
                            shorten(PolicyChangeDescription.cardScope(change), 900)
                                    + "\n原采用："
                                    + shorten(
                                            PolicyChangeDescription.cardPolicies(
                                                    change.before(), change),
                                            1500)
                                    + "\n拟采用："
                                    + shorten(
                                            PolicyChangeDescription.cardPolicies(
                                                    change.after(), change),
                                            1500)));
        }
        if (segments.size() > PREVIEW_ITEMS)
            elements.add(paragraph("其余 " + (segments.size() - PREVIEW_ITEMS) + " 段见本轮详情。"));
        if (route.adjustments() != null && !route.adjustments().isEmpty()) {
            var snapshot = snapshots.read(card.snapshotData());
            var accompanying = route.adjustments().stream().filter(a -> a.accompanying()).toList();
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
                elements.add(
                        paragraph("其余 " + (accompanying.size() - PREVIEW_ITEMS) + " 项调整见本轮详情。"));
        }
        if (elements.isEmpty())
            throw new PolicyFeishuSender.Failure(
                    PolicyFeishuSender.Kind.PERMANENT, "本轮缺少需要运营确认的政策交叉或随单调整依据。");
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
        String suffix = "…（详见本轮详情）";
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
        if ("REMOVED".equals(card.memberStatus())) return "本次指派已撤销";
        if (card.decision() == null) return "本卡已停止办理";
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

    private static Map<String, String> plain(String value) {
        return Map.of("tag", "plain_text", "content", value);
    }
}
