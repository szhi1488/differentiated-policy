package com.company.policy.service.impl;

import com.company.policy.mapper.PolicyReviewWorkflowMapper;
import com.company.policy.mapper.PolicyReviewWorkflowMapper.Member;
import com.company.policy.service.PolicyReviewNotifications;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
class PolicyReviewNotificationsImpl implements PolicyReviewNotifications {
    private final PolicyReviewWorkflowMapper workflow;
    private final ObjectMapper json;

    PolicyReviewNotificationsImpl(PolicyReviewWorkflowMapper workflow, ObjectMapper json) {
        this.workflow = workflow;
        this.json = json;
    }

    @Override
    public void submitted(
            String tenant,
            long id,
            int version,
            boolean productReview,
            String summary,
            LocalDateTime at) {
        // 整批登记同事务提交。节点已办结或后来改派时，重放仍对应首次提交的收件人。
        if (workflow.eventRegistered(tenant, id, version, "SUBMITTED") != 0) return;
        var members = workflow.members(tenant, id, version);
        var product = active(members, "PRODUCT");
        var purchase = active(members, "PURCHASE");
        var current = productReview ? product : purchase;
        if (purchase.isEmpty() || current.isEmpty())
            throw new IllegalStateException("本轮缺少已冻结的审核人。");
        var buyers = purchase.stream().map(Member::userId).toList();
        var stage = productReview ? "PRODUCT_REVIEW" : "PURCHASE_REVIEW";
        if (!productReview)
            workflow.register(
                    tenant,
                    id,
                    version,
                    "SUBMITTED",
                    "GROUP_MESSAGE",
                    0,
                    encode(
                            Map.of(
                                    "stage",
                                    stage,
                                    "summary",
                                    summary,
                                    "instruction",
                                    "请采购办理终审。",
                                    "actingUserIds",
                                    buyers,
                                    "informedUserIds",
                                    List.of(),
                                    "purchaseReviewerUserIds",
                                    buyers)),
                    at);
        for (var member : current) {
            workflow.register(
                    tenant,
                    id,
                    version,
                    "SUBMITTED",
                    productReview ? "SEND_CARD" : "CREATE_TASK",
                    member.id(),
                    encode(Map.of("stage", stage, "userId", member.userId(), "summary", summary)),
                    at);
        }
    }

    @Override
    public void revisionSubmitted(
            String tenant,
            long id,
            int previousVersion,
            long memberId,
            long applicantId,
            LocalDateTime at) {
        workflow.register(
                tenant,
                id,
                previousVersion,
                "RESUBMITTED",
                "CLOSE_TASK",
                memberId,
                encode(
                        Map.of(
                                "stage",
                                "REVISION",
                                "userId",
                                applicantId,
                                "outcome",
                                "HANDLED",
                                "reason",
                                "原申请人已重新提交",
                                "submissionVersion",
                                previousVersion + 1)),
                at);
    }

    private static List<Member> active(List<Member> members, String group) {
        return members.stream()
                .filter(m -> group.equals(m.groupCode()) && "ACTIVE".equals(m.memberStatus()))
                .toList();
    }

    @Override
    public void decided(
            String tenant,
            long id,
            int version,
            String group,
            long actorId,
            String decision,
            String reason,
            LocalDateTime at) {
        var event = group + "_DECIDED";
        // 群消息和每份个人动作同事务落库，重放须保留第一次决定时的指派。
        if (workflow.eventRegistered(tenant, id, version, event) != 0) return;
        var members = workflow.members(tenant, id, version);
        for (var member : members) {
            if (!group.equals(member.groupCode())) continue;
            var outcome = member.memberStatus();
            if ("HANDLED".equals(outcome) || "PEER_HANDLED".equals(outcome)) {
                close(
                        tenant,
                        id,
                        version,
                        event,
                        group,
                        member.id(),
                        encode(
                                Map.of(
                                        "userId",
                                        member.userId(),
                                        "outcome",
                                        outcome,
                                        "decision",
                                        decision,
                                        "actorId",
                                        actorId,
                                        "reason",
                                        "HANDLED".equals(outcome) ? "本人已处理" : "同组他人已处理")),
                        at);
            }
        }
        var applicant = workflow.applicantId(tenant, id);
        boolean rejected = "REJECT".equals(decision);
        boolean transferred = "PRODUCT".equals(group) && !rejected;
        var stage = rejected ? "REVISION" : transferred ? "PURCHASE_REVIEW" : "APPROVED";
        var next =
                rejected
                        ? active(members, "REVISION")
                        : transferred ? active(members, "PURCHASE") : List.<Member>of();
        if ((rejected && (next.size() != 1 || next.get(0).userId() != applicant))
                || (transferred && next.isEmpty()))
            throw new IllegalStateException("无法确定本轮下一步办理人。");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("stage", stage);
        payload.put("decision", decision);
        payload.put("reason", reason);
        payload.put("actorId", actorId);
        payload.put("applicantId", applicant);
        payload.put("actingUserIds", next.stream().map(Member::userId).toList());
        payload.put("informedUserIds", stage.equals("APPROVED") ? List.of(applicant) : List.of());
        workflow.register(tenant, id, version, event, "GROUP_MESSAGE", 0, encode(payload), at);
        var task = new LinkedHashMap<String, Object>();
        task.put("stage", stage);
        task.put("reason", reason);
        for (var assignee : next) {
            task.put("userId", assignee.userId());
            workflow.register(
                    tenant, id, version, event, "CREATE_TASK", assignee.id(), encode(task), at);
        }
    }

    @Override
    public void reassigned(
            String tenant,
            long id,
            int version,
            int recordVersion,
            String currentStage,
            String targetStage,
            long actorId,
            String reason,
            List<Assignee> added,
            List<Assignee> removed,
            LocalDateTime at) {
        var event = "REASSIGNED_" + recordVersion;
        if (workflow.eventRegistered(tenant, id, version, event) != 0) return;
        if (!"PRODUCT_REVIEW".equals(currentStage))
            workflow.register(
                    tenant,
                    id,
                    version,
                    event,
                    "GROUP_MESSAGE",
                    0,
                    encode(
                            Map.of(
                                    "stage",
                                    currentStage,
                                    "targetStage",
                                    targetStage,
                                    "actorId",
                                    actorId,
                                    "addedUserIds",
                                    added.stream().map(Assignee::userId).toList(),
                                    "removedUserIds",
                                    removed.stream().map(Assignee::userId).toList(),
                                    "reason",
                                    reason)),
                    at);
        if (!targetStage.equals(currentStage)) return;
        for (var member : added)
            workflow.register(
                    tenant,
                    id,
                    version,
                    event,
                    "PRODUCT_REVIEW".equals(targetStage) ? "SEND_CARD" : "CREATE_TASK",
                    member.memberId(),
                    encode(Map.of("stage", targetStage, "userId", member.userId())),
                    at);
        for (var member : removed)
            close(
                    tenant,
                    id,
                    version,
                    event,
                    "PRODUCT_REVIEW".equals(targetStage) ? "PRODUCT" : "PURCHASE",
                    member.memberId(),
                    encode(
                            Map.of(
                                    "stage",
                                    targetStage,
                                    "userId",
                                    member.userId(),
                                    "actorId",
                                    actorId,
                                    "outcome",
                                    "REMOVED",
                                    "reason",
                                    "已改派")),
                    at);
    }

    private void close(
            String tenant,
            long id,
            int version,
            String event,
            String group,
            long member,
            String payload,
            LocalDateTime at) {
        var action = "PRODUCT".equals(group) ? "UPDATE_CARD" : "CLOSE_TASK";
        workflow.register(tenant, id, version, event, action, member, payload, at);
    }

    private String encode(Map<String, ?> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法保存审核通知内容。", error);
        }
    }
}
