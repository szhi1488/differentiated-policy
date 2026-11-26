package com.company.policy.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PolicyDeliveryMapper {
    String DUE =
            """
             ((d.delivery_status IN ('PENDING','RETRY') AND (d.next_attempt_at IS NULL OR d.next_attempt_at<=#{now}))
              OR (d.delivery_status='UNKNOWN' AND d.next_attempt_at<=#{now})
              OR (d.delivery_status='SENDING' AND d.lease_until<=#{now}))
            """;

    // 只锁一条投递登记。结办依赖本人的创建结果，其他成员或群的失败不阻塞它。
    @Select(
            "SELECT d.id FROM policy_review_delivery d WHERE d.tenant_key=#{tenant} AND "
                    + DUE
                    + """
             AND (d.notice_key IS NULL OR d.notice_key NOT LIKE 'PL/%' OR NOT EXISTS (
               SELECT 1 FROM policy_review_delivery former WHERE former.tenant_key=d.tenant_key AND former.id<d.id
                 AND former.notice_key LIKE CONCAT(LEFT(d.notice_key,CHAR_LENGTH(d.notice_key)-36),'%')
                 AND former.delivery_status IN ('PENDING','RETRY','SENDING','UNKNOWN')))
             AND (d.action_type NOT IN ('UPDATE_CARD','CLOSE_TASK') OR NOT EXISTS (
               SELECT 1 FROM policy_review_delivery prior
               WHERE prior.tenant_key=d.tenant_key AND prior.recipient_key=d.recipient_key
                 AND prior.action_type=CASE d.action_type WHEN 'UPDATE_CARD' THEN 'SEND_CARD' ELSE 'CREATE_TASK' END
                 AND prior.delivery_status IN ('PENDING','RETRY','SENDING','UNKNOWN')))
             ORDER BY d.id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    Long next(String tenant, LocalDateTime now);

    @Select(
            """
            SELECT d.id FROM policy_review_delivery d WHERE d.tenant_key=#{tenant} AND d.id=#{target.id}
              AND d.policy_id=#{target.policyId} AND d.version=#{target.version}
              AND d.event_key=#{target.eventKey} AND d.recipient_key=#{target.recipientKey}
              AND d.action_type='SEND_CARD' AND
            """
                    + DUE
                    + " FOR UPDATE SKIP LOCKED")
    Long nextTrialCard(String tenant, PolicyDeliveryAdminMapper.Row target, LocalDateTime now);

    record Item(
            long id,
            String tenantKey,
            long policyId,
            int version,
            String eventKey,
            String actionType,
            long recipientKey,
            String payloadJson,
            String deliveryStatus,
            int attempts,
            LocalDateTime createdAt,
            String groupCode,
            String openId,
            String roleType,
            String memberStatus,
            int currentVersion,
            int recordVersion,
            String status,
            String stage,
            String taskGuid,
            int deleted) {}

    @Select(
            """
            SELECT d.id,d.tenant_key,COALESCE(d.policy_id,0) AS policy_id,COALESCE(d.version,0) AS version,d.event_key,d.action_type,d.recipient_key,
              d.payload_json,d.delivery_status,d.attempts,d.created_at,m.group_code,u.open_id,u.role_type,
              m.member_status,COALESCE(p.current_version,0) AS current_version,COALESCE(p.record_version,0) AS record_version,p.status,r.stage,m.task_guid,COALESCE(p.is_deleted,0) AS deleted
            FROM policy_review_delivery d
            LEFT JOIN policy_apply p ON p.id=d.policy_id AND p.tenant_key=d.tenant_key
            LEFT JOIN policy_review_round r ON r.policy_id=d.policy_id AND r.version=d.version AND r.tenant_key=d.tenant_key
            LEFT JOIN policy_review_member m ON m.id=d.recipient_member_id AND m.tenant_key=d.tenant_key
            LEFT JOIN user_info u ON u.id=m.user_id AND u.tenant_key=m.tenant_key AND u.is_deleted=0
            WHERE d.id=#{id}
            """)
    Item item(long id);

    @Select(
            """
            SELECT receipt_id FROM policy_review_delivery
            WHERE tenant_key=#{tenant} AND recipient_key=#{member} AND action_type=#{action}
              AND delivery_status='SENT' ORDER BY id LIMIT 1
            """)
    String receipt(String tenant, long member, String action);

    @Update(
            """
            UPDATE policy_review_delivery SET delivery_status='SENDING', lease_owner=#{owner},lease_until=#{until},
              attempts=attempts+1, next_attempt_at=NULL,payload_json=#{payload} WHERE id=#{id}
            """)
    int claim(long id, String owner, LocalDateTime until, String payload);

    @Update(
            """
            UPDATE policy_review_delivery SET delivery_status=#{status}, last_error=#{reason},
              lease_owner=NULL, lease_until=NULL, next_attempt_at=NULL WHERE id=#{id}
            """)
    int stopLocked(long id, String status, String reason);

    @Update(
            """
            UPDATE policy_review_delivery SET delivery_status=#{status},receipt_id=#{receipt},delivered_at=#{sentAt},
              lease_owner=NULL,lease_until=NULL,next_attempt_at=#{next},last_error=#{error}
            WHERE id=#{id} AND delivery_status='SENDING' AND lease_owner=#{owner}
            """)
    int finish(
            long id,
            String owner,
            String status,
            String receipt,
            LocalDateTime sentAt,
            LocalDateTime next,
            String error);

    record Attempt(String deliveryStatus, String payloadJson, String leaseOwner) {}

    @Select(
            """
            SELECT delivery_status,payload_json,lease_owner FROM policy_review_delivery WHERE id=#{id}
             AND delivery_status IN ('UNKNOWN','SENDING') FOR UPDATE
            """)
    Attempt lockAttempt(long id);

    @Select(
            """
            SELECT payload_json FROM policy_review_delivery WHERE id=#{id} AND delivery_status='SENDING'
              AND lease_owner=#{owner} AND lease_until>#{at} FOR UPDATE
            """)
    String lockBeforeSend(long id, String owner, LocalDateTime at);

    @Update("UPDATE policy_review_delivery SET payload_json=#{payload} WHERE id=#{id}")
    int updateLockedPayload(long id, String payload);

    @Update(
            """
            UPDATE policy_review_delivery SET delivery_status='SENT',receipt_id=#{receipt},delivered_at=#{at},
              lease_owner=NULL,lease_until=NULL,next_attempt_at=NULL,last_error=NULL WHERE id=#{id}
            """)
    int recordLateReceipt(long id, String receipt, LocalDateTime at);

    @Update(
            """
            UPDATE policy_review_member SET task_guid=#{guid} WHERE tenant_key=#{tenant} AND id=#{member}
              AND (task_guid IS NULL OR task_guid=#{guid})
            """)
    int saveTaskGuid(String tenant, long member, String guid);
}
