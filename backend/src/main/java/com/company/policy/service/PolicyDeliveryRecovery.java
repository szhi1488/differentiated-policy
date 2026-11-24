package com.company.policy.service;

import com.company.policy.vo.CurrentUserResponse;
import java.util.List;

/** 供公司管理员从 ERP 逐项核对外部回执及补发。 */
public interface PolicyDeliveryRecovery {
    record Resolution(
            String operation,
            String expectedStatus,
            Integer expectedAttempts,
            Integer expectedRevision,
            String receiptId,
            String reason) {}

    record Item(
            String id,
            String deliveryKey,
            String event,
            String action,
            String assignmentId,
            String recipient,
            String status,
            int attempts,
            String receiptId,
            String error,
            String nextAttemptAt,
            int revision,
            boolean canResolve) {}

    List<Item> list(CurrentUserResponse session, long policyId, int version);

    /** 管理员按游标查看公司执行期、平台交接及商品批次投递。 */
    List<Item> notices(CurrentUserResponse session, long before);

    Item resolve(CurrentUserResponse session, long deliveryId, Resolution request);
}
