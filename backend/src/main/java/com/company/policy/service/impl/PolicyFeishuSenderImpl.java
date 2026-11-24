package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.policyNumber;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.mapper.PolicyDeliveryAudienceMapper;
import com.company.policy.service.ExecutionNotices;
import com.company.policy.service.FeishuWorkflowClient;
import com.company.policy.service.JsSdkSignatureService;
import com.company.policy.service.PolicyDeliveryAttemptGuard;
import com.company.policy.service.PolicyFeishuSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final JsSdkSignatureService origins;
    private final ExecutionNotices notices;

    @Override
    public String send(Delivery item) {
        requireOwnTenant(item);
        String stage = item.payload().path("stage").asText("");
        return switch (item.action()) {
            case "SEND_CARD" -> sendCard(item, card(item));
            case "UPDATE_CARD" -> client.updateCard(item.priorReceipt(), card(item));
            case "GROUP_MESSAGE" -> sendGroup(item, stage);
            case "CREATE_TASK" -> {
                String title =
                        switch (stage) {
                            case "REVISION" -> "退回修改";
                            case "PURCHASE_REVIEW" -> "采购终审";
                            default -> throw failed("无法识别个人待办的业务节点。");
                        };
                String summary = policyNumber(item.policyId()) + " " + title;
                String url = link(item, stage);
                yield client.createTask(
                        item.openId(),
                        summary,
                        url,
                        item.idempotencyKey(),
                        () -> prepare(item, item.openId(), List.of(summary, url)));
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
                cards.build(
                        item.tenant(),
                        item.policyId(),
                        item.version(),
                        item.memberId(),
                        link(item, "PRODUCT_REVIEW")));
    }

    private String sendGroup(Delivery item, String stage) {
        if (!StringUtils.hasText(feishu.getPolicyChatId()))
            throw failed("请配置 FEISHU_POLICY_CHAT_ID 后补发本条群消息。");
        if (item.policyId() == 0) return sendNotice(item);
        var payload = item.payload();
        var userIds = new LinkedHashSet<Long>();
        String message;
        if (item.event().endsWith("_DECIDED")) {
            boolean product = item.event().equals("PRODUCT_DECIDED");
            String signer =
                    audience.signer(
                            item.tenant(),
                            item.policyId(),
                            item.version(),
                            product ? "PRODUCT" : "PURCHASE",
                            payload.path("actorId").asLong());
            if (!StringUtils.hasText(signer)) throw failed("未能核对此轮实际办理人。");
            boolean rejected = "REJECT".equals(payload.path("decision").asText());
            message = (product ? "产品运营初审" : "采购终审") + (rejected ? "退回" : "通过") + "，办理人：" + signer;
            if (!product && !rejected) {
                var execution = notices.atApproval(item.tenant(), item.policyId());
                message += "。" + execution.text();
                userIds.addAll(execution.users());
            }
            if (rejected) {
                String reason = payload.path("reason").asText("");
                message += (reason.isBlank() ? "" : "。原因：" + reason) + "。请原申请人修改。";
            }
        } else if (item.event().equals("SUBMITTED")) {
            message = "政策已提交。";
        } else if (item.event().startsWith("REASSIGNED_")) {
            message = "本轮审核人员已改派。";
            addIds(userIds, payload.path("addedUserIds"));
        } else {
            throw failed("无法识别本轮群通知事件。");
        }
        if ("PURCHASE_REVIEW".equals(stage)) {
            var buyers = audience.currentBuyers(item.tenant(), item.policyId(), item.version());
            if (item.event().startsWith("REASSIGNED_")) {
                userIds.retainAll(buyers);
            } else {
                userIds.addAll(buyers);
                if (!buyers.isEmpty()) message += " 请采购办理终审。";
            }
        } else {
            addIds(userIds, payload.path("actingUserIds"));
        }
        addIds(userIds, payload.path("informedUserIds"));
        var recipients =
                userIds.isEmpty()
                        ? List.<PolicyDeliveryAudienceMapper.Recipient>of()
                        : audience.recipients(item.tenant(), List.copyOf(userIds));
        if (recipients.size() != userIds.size()
                || recipients.stream()
                        .anyMatch(
                                u ->
                                        !StringUtils.hasText(u.openId())
                                                || (stage.equals("PURCHASE_REVIEW")
                                                        && !item.event().startsWith("REASSIGNED_")
                                                        && !"ADMIN".equals(u.roleType()))))
            throw failed("本轮群提醒收件人账号、采购资格或飞书身份发生变化，请核对。");
        String summary = policyNumber(item.policyId()) + " " + message;
        var mentions =
                recipients.stream()
                        .map(PolicyDeliveryAudienceMapper.Recipient::openId)
                        .distinct()
                        .toList();
        String url = link(item, stage);
        String chatId = feishu.getPolicyChatId();
        return client.sendGroup(
                chatId,
                summary,
                mentions,
                url,
                item.idempotencyKey(),
                () -> prepare(item, chatId, List.of(summary, mentions, url)));
    }

    private String sendNotice(Delivery item) {
        var content = noticeContent(item);
        String chat = feishu.getPolicyChatId();
        boolean near = "NEAR".equals(item.payload().path("kind").asText());
        String url = near ? erpRoot() + "#/apply/submissions" : null;
        return client.sendGroup(
                chat,
                content.summary(),
                content.mentions(),
                url,
                item.idempotencyKey(),
                () -> {
                    if (!content.equals(noticeContent(item)))
                        throw new Failure(Kind.RETRYABLE, "业务情况或收件人已变化，重新核对。");
                    prepare(
                            item,
                            chat,
                            List.of(content.summary(), content.mentions(), url == null ? "" : url));
                });
    }

    private record NoticeContent(String summary, List<String> mentions) {}

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
                        json, feishu.getAppId(), item.action(), recipient, content));
    }

    private static void addIds(LinkedHashSet<Long> target, JsonNode ids) {
        ids.forEach(id -> target.add(id.longValue()));
    }

    private String erpRoot() {
        String erpRoot = feishu.getPolicyErpUrl();
        boolean allowListedHttp = feishu.usesLongConnection();
        String root = erpRoot == null ? "" : erpRoot.strip();
        try {
            URI uri = URI.create(root);
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) throw new IllegalArgumentException();
            if (allowListedHttp && "http".equalsIgnoreCase(uri.getScheme())) {
                origins.validateAndStripFragment(root);
            } else if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | ApiException e) {
            if (allowListedHttp)
                throw failed("请配置 ERP HTTPS 根地址，或将内网 HTTP 根地址的来源加入 FEISHU_ALLOWED_ORIGINS。");
            throw failed(INVALID_ERP_HTTPS);
        }
        return root.endsWith("/") ? root : root + "/";
    }

    private String link(Delivery item, String stage) {
        String path =
                switch (stage) {
                    case "PURCHASE_REVIEW" -> "#/admin/policies?policyId=" + item.policyId() + "&";
                    case "REVISION" -> "#/apply?policyId=" + item.policyId() + "&";
                    default -> "#/reviews/" + item.policyId() + "?";
                };
        return erpRoot() + path + "round=" + item.version();
    }

    private static Failure failed(String message) {
        return new Failure(Kind.PERMANENT, message);
    }
}
