package com.company.policy.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PolicyDeliveryAdminMapper {
    record Row(
            long id,
            long policyId,
            int version,
            String eventKey,
            String actionType,
            long recipientKey,
            String groupCode,
            String displayName,
            String deliveryStatus,
            int attempts,
            LocalDateTime nextAttemptAt,
            String receiptId,
            String lastError,
            String payloadJson) {}

    String ROWS =
            """
            SELECT d.id,COALESCE(d.policy_id,0) AS policy_id,COALESCE(d.version,0) AS version,d.event_key,d.action_type,d.recipient_key,m.group_code,m.display_name,
                   d.delivery_status,d.attempts,d.next_attempt_at,d.receipt_id,d.last_error,d.payload_json
            FROM policy_review_delivery d
            LEFT JOIN policy_apply p ON p.id=d.policy_id AND p.tenant_key=d.tenant_key AND p.is_deleted=0
            LEFT JOIN policy_review_member m ON m.id=d.recipient_key AND m.tenant_key=d.tenant_key
            WHERE d.tenant_key=#{tenant} AND (d.policy_id IS NULL OR p.id IS NOT NULL)
            """;

    @Select(ROWS + " AND d.policy_id=#{policyId} AND d.version=#{version} ORDER BY d.id")
    List<Row> list(String tenant, long policyId, int version);

    @Select(ROWS + " AND d.policy_id IS NULL AND d.id < #{before} ORDER BY d.id DESC LIMIT 50")
    List<Row> notices(String tenant, long before);

    @Select(ROWS + " AND d.id=#{id}")
    Row read(String tenant, long id);

    // 人工核对只锁投递本身，与后台领取竞争同一行。
    @Select(
            "SELECT id FROM policy_review_delivery WHERE tenant_key=#{tenant} AND id=#{id} FOR UPDATE")
    Long lock(String tenant, long id);

    record ReceiptCandidate(String receiptId, String payloadJson) {}

    // 含已归档的请求证据。先用精确回执或摘要筛选候选，再逐项核对 JSON 中的字段及时间窗。
    @Select(
            """
            SELECT receipt_id,payload_json FROM policy_review_delivery
            WHERE tenant_key=#{tenant} AND id<>#{id} AND
              (receipt_id=#{receipt} OR LOCATE(#{receipt},payload_json)>0 OR
               (action_type='GROUP_MESSAGE' AND LOCATE(#{fingerprint},payload_json)>0))
            """)
    List<ReceiptCandidate> receiptCandidates(
            String tenant, long id, String receipt, String fingerprint);

    @Update(
            """
            UPDATE policy_review_delivery SET delivery_status=#{status},attempts=#{attempts},
              payload_json=#{payload},receipt_id=#{receipt},delivered_at=#{at},next_attempt_at=NULL,
              lease_owner=NULL,lease_until=NULL,last_error=NULL WHERE tenant_key=#{tenant} AND id=#{id}
            """)
    int resolve(
            String tenant,
            long id,
            String status,
            int attempts,
            String payload,
            String receipt,
            LocalDateTime at);

    @Update(
            """
            UPDATE policy_review_member SET task_guid=#{receipt} WHERE tenant_key=#{tenant}
             AND id=#{memberId} AND (task_guid IS NULL OR task_guid=#{receipt})
            """)
    int attachTask(String tenant, long memberId, String receipt);
}
