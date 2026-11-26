package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.policyNumber;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.mapper.PolicyDeliveryAudienceMapper;
import com.company.policy.service.ExecutionNotices;
import com.company.policy.service.FeishuWorkflowClient;
import com.company.policy.service.PolicyDeliveryAttemptGuard;
import com.company.policy.service.PolicyFeishuSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
class PolicyFeishuSenderImpl implements PolicyFeishuSender {
    private final FeishuWorkflowClient client;
    private final FeishuProperties feishu;
    private final PolicyProductCardBuilder cards;
    private final PolicyDeliveryAudienceMapper audience;
    private final PolicyDeliveryAttemptGuard attempts;
    private final ObjectMapper json;
    private final ExecutionNotices notices;
    private final PolicyApprovalMessage approvals;

    @Override
    public String send(Delivery item) {
        requireOwnTenant(item);
        return switch (PolicyFeishuSender.deliveryAction(
                item.policyId(), item.action(), item.payload())) {
            case "SEND_CARD" ->
                    item.policyId() == 0
                            ? sendExpiryReminder(item)
                            : sendCard(
                                    item,
                                    "PRODUCT".equals(item.group())
                                            ? card(item)
                                            : personalNotice(item));
            case "UPDATE_CARD" -> client.updateCard(item.priorReceipt(), card(item));
            case "GROUP_MESSAGE" -> sendGroup(item);
            case "CREATE_TASK" -> {
                String title =
                        switch (item.payload().path("stage").asText("")) {
                            case "REVISION" -> "退回修改";
                            case "PURCHASE_REVIEW" -> "采购终审";
                            default -> throw failed("无法识别个人待办的业务节点。");
                        };
                String summary = policyNumber(item.policyId()) + " " + title;
                yield client.createTask(
                        item.openId(),
                        summary,
                        item.idempotencyKey(),
                        () -> prepare(item, item.openId(), summary));
            }
            case "CLOSE_TASK" ->
                    client.closeTask(
                            item.priorReceipt(),
                            item.payload().path("reason").asText("ERP 已结办"),
                            item.createdAt().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            default -> throw failed("无法识别飞书投递动作。");
        };
    }

    private void requireOwnTenant(Delivery item) {
        if (!feishu.hasCompanyTenant() || !feishu.getTenantKey().equals(item.tenant()))
            throw failed("投递企业与本应用配置不符。");
    }

    private String sendCard(Delivery item, JsonNode body) {
        return client.sendCard(
                item.openId(),
                body,
                item.idempotencyKey(),
                () -> prepare(item, item.openId(), body));
    }

    private JsonNode card(Delivery item) {
        return json.valueToTree(
                cards.build(item.tenant(), item.policyId(), item.version(), item.memberId()));
    }

    private JsonNode personalNotice(Delivery item) {
        boolean revision = "REVISION".equals(item.group());
        boolean removed = "REMOVED".equals(item.payload().path("outcome").asText());
        boolean productDecided = "PRODUCT_DECIDED".equals(item.event());
        boolean reassigned = item.event().startsWith("REASSIGNED_");
        String policyCode = policyNumber(item.policyId());
        String title;
        String text;
        if (revision) {
            title = (productDecided ? "产品运营初审" : "采购终审") + "退回";
            text =
                    "办理人："
                            + signer(item)
                            + "\n退回原因："
                            + item.payload().path("reason").asText("审核人未填写驳回原因");
        } else if (removed) {
            title = "采购终审已改派";
            text = "你原先的这次采购指派已撤销。";
        } else {
            title = "待你采购终审";
            text = "请打开飞书工作台里的「差异化政策管理系统」，进入「政策管理」→「差异化政策」，找到 " + policyCode + "，点击「审核」，及时完成终审。";
            if (productDecided) {
                text += "\n" + signer(item) + " 已完成产品运营初审。";
            } else if (reassigned) {
                text += "\n本轮采购终审已改派给你。";
            }
        }
        if (reassigned && item.payload().hasNonNull("reason"))
            text += "\n改派原因：" + item.payload().get("reason").asText();
        return readOnlyCard(
                item,
                item.openId(),
                title + " · " + policyCode + " · 第 " + item.version() + " 轮",
                text,
                revision ? "orange" : "blue");
    }

    private JsonNode readOnlyCard(
            Delivery item, String openId, String title, String text, String template) {
        var heading = Map.of("tag", "plain_text", "content", title);
        String marker =
                PolicyFeishuSender.noticeElementId(
                        openId,
                        item.idempotencyKey(),
                        item.memberId(),
                        item.policyId(),
                        item.version());
        var paragraph =
                Map.of(
                        "tag",
                        "div",
                        "element_id",
                        marker,
                        "text",
                        Map.of("tag", "plain_text", "content", text));
        return json.valueToTree(
                Map.of(
                        "schema",
                        "2.0",
                        "config",
                        Map.of("enable_forward", false),
                        "header",
                        Map.of("template", template, "title", heading),
                        "body",
                        Map.of("elements", List.of(paragraph))));
    }

    private String signer(Delivery item) {
        String name =
                audience.signer(
                        item.tenant(),
                        item.policyId(),
                        item.version(),
                        "PRODUCT_DECIDED".equals(item.event()) ? "PRODUCT" : "PURCHASE",
                        item.payload().path("actorId").asLong());
        if (!StringUtils.hasText(name)) throw failed("未能核对此轮实际办理人。");
        return name;
    }

    private String sendGroup(Delivery item) {
        if (!StringUtils.hasText(feishu.getPolicyChatId()))
            throw failed("请配置 FEISHU_POLICY_CHAT_ID 后补发本条群消息。");
        if (item.policyId() == 0) return sendNotice(item);
        if (!"PURCHASE_DECIDED".equals(item.event())
                || !"APPROVE".equals(item.payload().path("decision").asText()))
            throw new Failure(Kind.OBSOLETE, "此审核节点仅通过机器人私聊通知。");
        var execution = notices.atApproval(item.tenant(), item.policyId());
        var userIds = new LinkedHashSet<Long>(execution.users());
        addIds(userIds, item.payload().path("informedUserIds"));
        var recipients =
                userIds.isEmpty()
                        ? List.<PolicyDeliveryAudienceMapper.Recipient>of()
                        : audience.recipients(item.tenant(), List.copyOf(userIds));
        if (recipients.size() != userIds.size()
                || recipients.stream().anyMatch(u -> !StringUtils.hasText(u.openId())))
            throw failed("终审群提醒收件人的飞书身份发生变化，请核对。");
        var mentions =
                recipients.stream()
                        .map(PolicyDeliveryAudienceMapper.Recipient::openId)
                        .distinct()
                        .toList();
        String chatId = feishu.getPolicyChatId();
        String summary =
                approvals.build(
                        item.tenant(),
                        item.policyId(),
                        item.version(),
                        signer(item),
                        execution.text(),
                        text ->
                                FeishuGroupPost.fits(
                                        json, chatId, text, mentions, item.idempotencyKey()));
        return client.sendGroup(
                chatId,
                summary,
                mentions,
                item.idempotencyKey(),
                () -> prepare(item, chatId, List.of(summary, mentions, "")));
    }

    private String sendNotice(Delivery item) {
        var content = noticeContent(item);
        String chat = feishu.getPolicyChatId();
        return client.sendGroup(
                chat,
                content.summary(),
                content.openIds(),
                item.idempotencyKey(),
                () -> {
                    if (!content.equals(noticeContent(item)))
                        throw new Failure(Kind.RETRYABLE, "业务情况或收件人已变化，重新核对。");
                    prepare(item, chat, List.of(content.summary(), content.openIds(), ""));
                });
    }

    private String sendExpiryReminder(Delivery item) {
        var content = noticeContent(item);
        String applicant = content.openIds().get(0);
        var card = readOnlyCard(item, applicant, "政策临期提醒", content.summary(), "orange");
        return client.sendCard(
                applicant,
                card,
                item.idempotencyKey(),
                () -> {
                    if (!content.equals(noticeContent(item)))
                        throw new Failure(Kind.RETRYABLE, "政策截止或原申请人身份已变化，重新核对。");
                    prepare(item, applicant, card);
                });
    }

    private record NoticeContent(String summary, List<String> openIds) {}

    private NoticeContent noticeContent(Delivery item) {
        var notice = notices.message(item.tenant(), item.payload(), PolicyApplyServiceImpl.now());
        var people =
                notice.users().isEmpty()
                        ? List.<PolicyDeliveryAudienceMapper.Recipient>of()
                        : audience.recipients(item.tenant(), notice.users());
        boolean catalog = item.payload().has("requester");
        var valid =
                people.stream()
                        .filter(
                                u ->
                                        StringUtils.hasText(u.openId())
                                                && (!catalog || "ADMIN".equals(u.roleType())))
                        .toList();
        if (ExecutionNotices.EXPIRY_REMINDER.equals(item.payload().path("kind").asText())
                && (notice.users().size() != 1 || valid.size() != 1))
            throw failed("原申请人缺少有效飞书身份，无法私发临期提醒。");
        if (valid.isEmpty())
            throw failed("本项缺少有效的" + (catalog ? "批次商品管理员" : "执行负责人或申请人") + "，请核对身份和分工。");
        var missing = new LinkedHashSet<>(notice.users());
        valid.forEach(u -> missing.remove(u.id()));
        String summary = notice.text() + (missing.isEmpty() ? "" : " 未能点名的账号：" + missing + "。");
        var body = (ObjectNode) item.payload();
        body.remove("unreachableUserIds");
        if (!missing.isEmpty()) body.set("unreachableUserIds", json.valueToTree(missing));
        var mentions =
                valid.stream()
                        .map(PolicyDeliveryAudienceMapper.Recipient::openId)
                        .distinct()
                        .toList();
        return new NoticeContent(summary, mentions);
    }

    private void prepare(Delivery item, String recipient, Object content) {
        attempts.prepare(
                item,
                recipient,
                PolicyDeliveryFingerprint.compute(
                        json,
                        feishu.getAppId(),
                        PolicyFeishuSender.deliveryAction(
                                item.policyId(), item.action(), item.payload()),
                        recipient,
                        content));
    }

    private static void addIds(LinkedHashSet<Long> target, JsonNode ids) {
        ids.forEach(id -> target.add(id.longValue()));
    }

    private static Failure failed(String message) {
        return new Failure(Kind.PERMANENT, message);
    }
}
