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
            SELECT m.user_id FROM policy_review_member m
            JOIN policy_apply p ON p.id=m.policy_id AND p.tenant_key=m.tenant_key AND p.current_version=m.version
            JOIN policy_review_round r ON r.policy_id=m.policy_id AND r.tenant_key=m.tenant_key AND r.version=m.version
            JOIN user_info u ON u.tenant_key=m.tenant_key AND u.id=m.user_id AND u.is_deleted=0
            WHERE m.tenant_key=#{tenant} AND m.policy_id=#{policyId} AND m.version=#{version}
              AND p.is_deleted=0 AND p.status='PENDING' AND r.stage='PURCHASE_REVIEW'
              AND m.group_code='PURCHASE' AND m.member_status='ACTIVE' AND u.role_type='ADMIN'
            ORDER BY m.user_id
            """)
    List<Long> currentBuyers(String tenant, long policyId, int version);

    @Select(
            """
            SELECT m.display_name FROM policy_review_decision d JOIN policy_review_member m
              ON m.id=d.member_id AND m.tenant_key=d.tenant_key AND m.policy_id=d.policy_id AND m.version=d.version
            WHERE d.tenant_key=#{tenant} AND d.policy_id=#{policyId} AND d.version=#{version}
              AND d.group_code=#{group} AND m.user_id=#{actor}
            """)
    String signer(String tenant, long policyId, int version, String group, long actor);
}
