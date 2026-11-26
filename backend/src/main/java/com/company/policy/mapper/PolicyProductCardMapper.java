package com.company.policy.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 私聊初审卡通过本轮指派标识核验办理人。 */
@Mapper
public interface PolicyProductCardMapper {
    String ASSIGNMENT =
            """
        FROM policy_review_member m
        JOIN policy_review_round r ON r.tenant_key=m.tenant_key AND r.policy_id=m.policy_id AND r.version=m.version
        JOIN policy_apply p ON p.tenant_key=m.tenant_key AND p.id=m.policy_id AND p.is_deleted=0
        JOIN policy_audit_log a ON a.tenant_key=m.tenant_key AND a.policy_id=m.policy_id AND a.version=m.version
        JOIN user_info u ON u.tenant_key=m.tenant_key AND u.id=m.user_id
        LEFT JOIN policy_review_decision d ON d.tenant_key=m.tenant_key AND d.policy_id=m.policy_id
            AND d.version=m.version AND d.group_code='PRODUCT'
        LEFT JOIN policy_review_member signer ON signer.id=d.member_id
        """;

    String MEMBER =
            """
        WHERE m.tenant_key=#{tenant} AND m.policy_id=#{id} AND m.version=#{version}
          AND m.group_code='PRODUCT' AND m.id=#{memberId}
        """;

    record Content(
            String memberStatus,
            String signerName,
            String decision,
            String reason,
            int assignmentNo,
            int recordVersion,
            boolean actionable,
            String routeJson,
            String snapshotData) {}

    @Select(
            """
        SELECT m.member_status,signer.display_name AS signer_name,d.decision,d.reason,
          m.assignment_no,p.record_version,
          (u.is_deleted=0 AND u.id<>p.applicant_id AND m.member_status='ACTIVE' AND d.member_id IS NULL
           AND p.current_version=m.version AND p.status='PENDING' AND r.stage='PRODUCT_REVIEW'
           AND a.audit_status='SUBMITTED' AND
        """
                    + ReviewPermissions.PRODUCT
                    + """
            ) AS actionable,r.route_json,a.snapshot_data
        """
                    + ASSIGNMENT
                    + MEMBER)
    Content content(String tenant, long id, int version, long memberId);

    record Binding(
            long userId,
            String name,
            int assignmentNo,
            int recordVersion,
            int currentVersion,
            String memberStatus,
            String stage,
            String decision) {}

    @Select(
            """
        SELECT u.id AS user_id,u.name,m.assignment_no,p.record_version,
          p.current_version,m.member_status,r.stage,d.decision
        """
                    + ASSIGNMENT
                    + """
        JOIN policy_review_delivery card ON card.tenant_key=m.tenant_key AND card.policy_id=m.policy_id
          AND card.version=m.version AND card.recipient_key=m.id AND card.action_type='SEND_CARD'
          AND card.delivery_status='SENT' AND card.receipt_id=#{messageId}
        """
                    + MEMBER
                    + " AND u.is_deleted=0 AND u.open_id=#{openId} AND "
                    + ReviewPermissions.PRODUCT)
    Binding binding(
            String tenant, long id, int version, long memberId, String openId, String messageId);
}
