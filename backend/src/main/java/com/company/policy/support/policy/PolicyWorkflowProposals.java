package com.company.policy.support.policy;

import com.company.policy.common.exception.ApiException;
import com.company.policy.entity.PolicyConsent.EndAdjustment;
import com.company.policy.entity.PolicyConsent.Target;
import com.company.policy.vo.PolicyResponse.Snapshot;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 预览和审核分流共用本轮的商品范围，时间调整按目标原单单列。 */
public final class PolicyWorkflowProposals {
    private PolicyWorkflowProposals() {}

    public static boolean onlyShortens(Snapshot parent) {
        return parent.sourcePolicyCode() == null
                && parent.form().goodsForm().timeAdjustment() != null;
    }

    /** 同渠道所有新增段一起比较，使不同原单的延期即使与本单无交集也能互相参与选单。 */
    public static List<List<Snapshot>> groups(Snapshot parent, List<Snapshot> extensions) {
        if (onlyShortens(parent)) return List.of();
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

    /** 独立调整从表单取，随单调整从确认内容取。独立缩短会同时保存在这两个位置。 */
    public static List<Adjustment> adjustments(Snapshot parent) {
        var result = new LinkedHashMap<String, Adjustment>();
        var own = parent.form().goodsForm().timeAdjustment();
        if (own != null) addAdjustment(result, own, false);
        if (parent.consent() != null) {
            for (var change : parent.consent().endAdjustments())
                addAdjustment(result, change, own == null || !own.target().equals(change.target()));
        }
        return List.copyOf(result.values());
    }

    private static void addAdjustment(
            Map<String, Adjustment> result, EndAdjustment change, boolean accompanying) {
        var target = change.target();
        var item =
                new Adjustment(
                        target,
                        PolicyBatchExtensions.policyCode(
                                PolicyFormValidation.id(target.policyId())),
                        change.endTime(),
                        PolicyEndTimes.isExtension(change),
                        accompanying);
        var previous = result.putIfAbsent(target.policyId(), item);
        if (previous != null && !previous.equals(item)) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "POLICY_RECONFIRM_REQUIRED", "同一原单的调整依据不一致，请重新核对。");
        }
    }
}
