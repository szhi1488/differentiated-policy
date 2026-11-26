package com.company.policy.support.policy;

import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.entity.PolicyConsent.Target;
import com.company.policy.vo.PolicyResponse.Snapshot;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** 预览和审核分流共用本轮的商品范围，时间调整按目标原单单列。 */
public final class PolicyWorkflowProposals {
    private PolicyWorkflowProposals() {}

    /** 同渠道所有新增段一起比较，使不同原单的延期即使与本单无交集也能互相参与选单。 */
    public static List<List<Snapshot>> groups(Snapshot parent, List<Snapshot> extensions) {
        var channels = new LinkedHashMap<String, List<Snapshot>>();
        channels.computeIfAbsent(parent.form().perspective(), key -> new ArrayList<>()).add(parent);
        for (var extension : extensions) {
            channels.computeIfAbsent(extension.form().perspective(), key -> new ArrayList<>())
                    .add(extension);
        }
        return channels.values().stream().map(List::copyOf).toList();
    }

    public record Adjustment(
            Target target,
            String policyCode,
            String endTime,
            boolean extension,
            boolean accompanying) {
        private static final DateTimeFormatter DISPLAY_TIME =
                DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

        public String previousEnd() {
            return target.previousEnd();
        }

        public String summary() {
            return policyCode
                    + " 拟将截止从 "
                    + displayTime(previousEnd())
                    + (extension ? " 延长至 " : " 提前至 ")
                    + displayTime(endTime)
                    + "（北京时间）";
        }

        private static String displayTime(String value) {
            return PolicyFormValidation.time(value).format(DISPLAY_TIME);
        }
    }

    /** 按提交顺序展示本轮确认的随单调整。 */
    public static List<Adjustment> adjustments(Snapshot parent) {
        if (parent.consent() == null) return List.of();
        return parent.consent().endAdjustments().stream()
                .map(PolicyWorkflowProposals::adjustment)
                .toList();
    }

    private static Adjustment adjustment(EndAdjustment change) {
        var target = change.target();
        return new Adjustment(
                target,
                PolicyBatchExtensions.policyCode(PolicyFormValidation.id(target.policyId())),
                change.endTime(),
                PolicyEndTimes.isExtension(change),
                true);
    }
}
