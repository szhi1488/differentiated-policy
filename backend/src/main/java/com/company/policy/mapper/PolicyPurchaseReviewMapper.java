package com.company.policy.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PolicyPurchaseReviewMapper {
    record Permission(long policyId, long memberId) {}

    // 预指派在首提时已经冻结。采购只能读实际转入终审的轮次，已结束轮以采购签署记录为准。
    String VISIBLE_ASSIGNMENTS =
            """
            FROM policy_review_member m JOIN policy_review_round pr ON pr.policy_id=m.policy_id
              AND pr.tenant_key=m.tenant_key AND pr.version=m.version
            WHERE m.group_code='PURCHASE' AND m.member_status IN ('ACTIVE','HANDLED') AND (
              (pr.stage='PURCHASE_REVIEW' AND (
                (JSON_CONTAINS(pr.route_json, '{"requiresProductReview":false}') AND NOT EXISTS (
                  SELECT 1 FROM policy_review_member pm WHERE pm.policy_id=pr.policy_id
                    AND pm.tenant_key=pr.tenant_key AND pm.version=pr.version AND pm.group_code='PRODUCT'))
                OR EXISTS (SELECT 1 FROM policy_review_decision pd WHERE pd.policy_id=pr.policy_id
                  AND pd.tenant_key=pr.tenant_key AND pd.version=pr.version AND pd.group_code='PRODUCT' AND pd.decision='APPROVE')))
              OR (pr.stage='FINISHED' AND EXISTS (SELECT 1 FROM policy_review_decision bd
                WHERE bd.policy_id=pr.policy_id AND bd.tenant_key=pr.tenant_key AND bd.version=pr.version AND bd.group_code='PURCHASE')))
            """;

    String DECISION_JOINS =
            """
        LEFT JOIN policy_review_decision product ON product.policy_id=r.policy_id AND product.version=r.version
          AND product.tenant_key=r.tenant_key AND product.group_code='PRODUCT'
        LEFT JOIN policy_review_member signer ON signer.id=product.member_id
        LEFT JOIN policy_review_decision purchase ON purchase.policy_id=r.policy_id AND purchase.version=r.version
          AND purchase.tenant_key=r.tenant_key AND purchase.group_code='PURCHASE'
        """;

    String ASSIGNED_PURCHASE =
            """
               (r.stage='PURCHASE_REVIEW' AND m.id IS NOT NULL AND purchase.member_id IS NULL
                AND ((JSON_CONTAINS(r.route_json, '{"requiresProductReview":false}')
                      AND product.member_id IS NULL AND NOT EXISTS (
                        SELECT 1 FROM policy_review_member prior WHERE prior.policy_id=r.policy_id
                          AND prior.version=r.version AND prior.tenant_key=r.tenant_key AND prior.group_code='PRODUCT'))
                     OR (product.decision='APPROVE' AND signer.member_status='HANDLED'
                         AND signer.user_id != u.id AND signer.user_id != p.applicant_id)))
        """;

    // 列表、详情与写入都按本轮采购指派授权。
    @Select(
            """
        <script>
        SELECT p.id AS policy_id, m.id AS member_id
        FROM policy_apply p
        JOIN policy_audit_log a ON a.policy_id=p.id AND a.tenant_key=p.tenant_key
          AND a.version=p.current_version AND a.audit_status='SUBMITTED'
        JOIN user_info u ON u.id=#{userId} AND u.tenant_key=p.tenant_key
          AND u.is_deleted=0
        JOIN policy_review_round r ON r.policy_id=p.id AND r.version=a.version AND r.tenant_key=p.tenant_key
        JOIN policy_review_member m ON m.policy_id=r.policy_id AND m.version=r.version
          AND m.tenant_key=r.tenant_key AND m.group_code='PURCHASE' AND m.user_id=u.id AND m.member_status='ACTIVE'
        """
                    + DECISION_JOINS
                    + """
        WHERE p.tenant_key=#{tenant} AND p.status='PENDING' AND p.is_deleted=0 AND p.applicant_id != u.id
          AND p.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
          AND
        """
                    + ASSIGNED_PURCHASE
                    + " AND "
                    + ReviewPermissions.PURCHASE
                    + " </script>")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<Permission> permissions(String tenant, long userId, List<Long> ids);

    @Select(
            """
        SELECT id FROM policy_apply WHERE id=#{id} AND tenant_key=#{tenant} AND is_deleted=0
          AND status='PENDING' AND record_version=#{recordVersion} AND current_version=#{version} FOR UPDATE
        """)
    Long lockPending(String tenant, long id, int version, int recordVersion);

    @Select(
            """
        SELECT stage FROM policy_review_round WHERE policy_id=#{id} AND version=#{version}
          AND tenant_key=#{tenant} FOR UPDATE
        """)
    String lockStage(String tenant, long id, int version);

    @Insert(
            """
        INSERT INTO policy_review_decision (policy_id,version,group_code,tenant_key,member_id,decision,reason,decided_at)
        VALUES (#{id},#{version},#{group},#{tenant},#{memberId},#{decision},#{reason},#{at})
        """)
    int decide(
            String tenant,
            long id,
            int version,
            String group,
            long memberId,
            String decision,
            String reason,
            LocalDateTime at);

    @Update(
            """
        UPDATE policy_review_member SET member_status=CASE WHEN id=#{memberId} THEN 'HANDLED' ELSE 'PEER_HANDLED' END
        WHERE policy_id=#{id} AND version=#{version} AND tenant_key=#{tenant} AND group_code=#{group}
          AND member_status='ACTIVE'
        """)
    int closeMembers(String tenant, long id, int version, String group, long memberId);

    @Update(
            """
        UPDATE policy_review_round SET stage=#{nextStage}
        WHERE policy_id=#{id} AND version=#{version} AND tenant_key=#{tenant} AND stage=#{stage}
        """)
    int advance(String tenant, long id, int version, String stage, String nextStage);

    @Insert(
            """
        INSERT INTO policy_review_member (policy_id,version,tenant_key,group_code,user_id,display_name,department)
        SELECT p.id,#{version},p.tenant_key,'REVISION',u.id,u.name,u.department
        FROM policy_apply p JOIN user_info u ON u.id=p.applicant_id AND u.tenant_key=p.tenant_key
        WHERE p.id=#{id} AND p.tenant_key=#{tenant} AND p.current_version=#{version}
          AND p.status='REJECTED' AND p.is_deleted=0
        """)
    int assignRevision(String tenant, long id, int version);
}
