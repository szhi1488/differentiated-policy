package com.company.policy.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PolicyDeliveryAudienceMapper {
    record Recipient(long id, String openId, String roleType) {}

    @Select(
            """
            <script>SELECT id, open_id, role_type FROM user_info
            WHERE tenant_key=#{tenant} AND is_deleted=0 AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY id</script>
            """)
    List<Recipient> recipients(String tenant, List<Long> ids);

    @Select(
            """
            SELECT m.display_name FROM policy_review_decision d JOIN policy_review_member m
              ON m.id=d.member_id AND m.tenant_key=d.tenant_key AND m.policy_id=d.policy_id AND m.version=d.version
            WHERE d.tenant_key=#{tenant} AND d.policy_id=#{policyId} AND d.version=#{version}
              AND d.group_code=#{group} AND m.user_id=#{actor}
            """)
    String signer(String tenant, long policyId, int version, String group, long actor);

    record ApprovedRound(String snapshotData, String productReviewer, String routeJson) {}

    /** 群公告使用获批轮次的快照；运营署名只取该轮实际通过决策关联的指派姓名。 */
    @Select(
            """
            SELECT a.snapshot_data, m.display_name AS product_reviewer, r.route_json
            FROM policy_audit_log a
            JOIN policy_apply p ON p.id=a.policy_id AND p.tenant_key=a.tenant_key AND p.is_deleted=0
            LEFT JOIN policy_review_round r ON r.tenant_key=a.tenant_key AND r.policy_id=a.policy_id AND r.version=a.version
            LEFT JOIN policy_review_decision d ON d.policy_id=a.policy_id AND d.version=a.version
              AND d.tenant_key=a.tenant_key AND d.group_code='PRODUCT' AND d.decision='APPROVE'
            LEFT JOIN policy_review_member m ON m.id=d.member_id AND m.policy_id=d.policy_id
              AND m.tenant_key=d.tenant_key AND m.version=d.version AND m.group_code=d.group_code
            WHERE a.policy_id=#{policyId} AND a.tenant_key=#{tenant} AND a.version=#{version}
              AND a.audit_status='APPROVED'
            """)
    ApprovedRound approvedRound(String tenant, long policyId, int version);
}
