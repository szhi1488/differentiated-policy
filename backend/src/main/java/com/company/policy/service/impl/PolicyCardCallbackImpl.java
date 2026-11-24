package com.company.policy.service.impl;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyReviewRequest;
import com.company.policy.mapper.PolicyProductCardMapper;
import com.company.policy.mapper.PolicyProductCardMapper.Binding;
import com.company.policy.service.PolicyCardCallback;
import com.company.policy.service.PolicyReviewService;
import com.company.policy.support.feishu.FeishuCallbackVerifier;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class PolicyCardCallbackImpl implements PolicyCardCallback {
    private final FeishuCallbackVerifier verifier;
    private final PolicyProductCardMapper cards;
    private final PolicyReviewService reviews;

    @Override
    public Map<String, Object> receive(
            byte[] body, String timestamp, String nonce, String signature) {
        var envelope = verifier.verify(body, timestamp, nonce, signature);
        if ("url_verification".equals(envelope.path("type").textValue()))
            return Map.of("challenge", envelope.get("challenge").textValue());
        return handleAction(envelope);
    }

    @Override
    public Map<String, Object> handleLongConnection(JsonNode envelope) {
        verifier.verifyCardScope(envelope);
        return handleAction(envelope);
    }

    private Map<String, Object> handleAction(JsonNode envelope) {
        var event = envelope.path("event");
        var action = event.path("action");
        var value = action.path("value");
        String decision = value.path("decision").textValue();
        if (!"im_message".equals(event.path("host").textValue())
                || !"button".equals(action.path("tag").textValue())
                || !"PRODUCT_REVIEW".equals(value.path("node").textValue())
                || !("APPROVE".equals(decision) || "REJECT".equals(decision))) return unavailable();
        String tenant = envelope.at("/header/tenant_key").textValue();
        String openId = event.at("/operator/open_id").textValue();
        String message = event.at("/context/open_message_id").textValue();
        long id = positiveId(value.path("policyId"));
        long member = positiveId(value.path("memberId"));
        int round = positiveInt(value.path("round"));
        int assignment = positiveInt(value.path("assignmentNo"));
        int record = positiveInt(value.path("recordVersion"));
        if (openId == null
                || openId.isBlank()
                || message == null
                || message.isBlank()
                || id < 1
                || member < 1
                || round < 1
                || assignment < 1
                || record < 1) return unavailable();
        var binding = cards.binding(tenant, id, round, member, openId, message);
        if (binding == null
                || binding.assignmentNo() != assignment
                || record > binding.recordVersion()) return unavailable();
        if (!current(binding, round)) return handled(binding);
        // 其他成员改派时保留者仍使用原卡。读取具体指派当下的主单版本，随后在共同事务内加锁复核。
        var actor =
                new CurrentUserResponse(
                        Long.toString(binding.userId()),
                        openId,
                        tenant,
                        binding.name(),
                        binding.roleType(),
                        null);
        String reason =
                "REJECT".equals(decision) ? action.at("/form_value/reason").textValue() : null;
        try {
            reviews.productReview(
                    actor,
                    id,
                    new PolicyReviewRequest(binding.recordVersion(), round, decision, reason));
            return toast("success", "APPROVE".equals(decision) ? "初审已通过" : "已退回原申请人");
        } catch (ApiException e) {
            if ("POLICY_VERSION_CONFLICT".equals(e.getCode())) {
                var latest = cards.binding(tenant, id, round, member, openId, message);
                if (latest != null && !current(latest, round)) return handled(latest);
            }
            return toast("error", e.getMessage());
        }
    }

    private static boolean current(Binding binding, int round) {
        return round == binding.currentVersion()
                && "ACTIVE".equals(binding.memberStatus())
                && binding.decision() == null
                && "PRODUCT_REVIEW".equals(binding.stage());
    }

    private static Map<String, Object> handled(Binding binding) {
        return switch (binding.memberStatus()) {
            case "PEER_HANDLED" -> toast("info", "同组他人已处理");
            case "HANDLED" ->
                    toast("info", "APPROVE".equals(binding.decision()) ? "本轮初审已通过" : "本轮初审已退回");
            default -> unavailable();
        };
    }

    private static long positiveId(JsonNode value) {
        try {
            return value.isTextual() ? Long.parseLong(value.textValue()) : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int positiveInt(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() ? value.intValue() : -1;
    }

    private static Map<String, Object> unavailable() {
        return toast("error", "此卡当前无法办理，请核对最新的飞书审核卡。");
    }

    private static Map<String, Object> toast(String type, String message) {
        return Map.of("toast", Map.of("type", type, "content", message));
    }
}
