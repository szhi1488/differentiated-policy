package com.company.policy.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PolicyReviewRoundMapper {
    @Select(
            """
            SELECT m.id
            FROM policy_review_member m
            JOIN policy_review_round r ON r.policy_id=m.policy_id AND r.version=m.version AND r.tenant_key=m.tenant_key
            JOIN policy_apply p ON p.id=r.policy_id AND p.current_version=r.version AND p.tenant_key=r.tenant_key
            JOIN policy_audit_log a ON a.policy_id=r.policy_id AND a.version=r.version AND a.tenant_key=r.tenant_key
            JOIN user_info u ON u.id=m.user_id AND u.tenant_key=m.tenant_key AND u.is_deleted=0
            """
                    + PolicyPurchaseReviewMapper.DECISION_JOINS
                    + """
            WHERE m.tenant_key=#{tenant} AND m.user_id=#{userId} AND m.member_status='ACTIVE'
              AND p.status='PENDING' AND p.is_deleted=0 AND p.applicant_id != u.id
              AND a.workflow_version=1 AND a.audit_status='SUBMITTED'
              AND m.group_code='PRODUCT' AND r.stage='PRODUCT_REVIEW'
              AND JSON_CONTAINS(r.route_json, '{"requiresProductReview":true}')
              AND product.member_id IS NULL AND purchase.member_id IS NULL
              AND p.id=#{id} AND r.version=#{version}
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Long productPermission(String tenant, long userId, long id, int version);

    // 详情与附件共用同一份轮次授权。被移除的产品运营不再以该指派读取本轮。
    String VISIBLE_ROUND =
            """
            FROM policy_apply p
            JOIN policy_audit_log a ON a.policy_id=p.id AND a.tenant_key=p.tenant_key AND a.version=#{version}
            JOIN user_info u ON u.id=#{userId} AND u.tenant_key=p.tenant_key AND u.is_deleted=0
            LEFT JOIN policy_review_round r ON r.policy_id=p.id AND r.tenant_key=p.tenant_key AND r.version=a.version
            WHERE p.id=#{id} AND p.tenant_key=#{tenant} AND p.is_deleted=0
              AND p.status IN ('PENDING','APPROVED','REJECTED') AND a.version <= p.current_version
              AND (u.role_type='ADMIN' OR u.id=p.applicant_id OR
                   (a.workflow_version=1 AND EXISTS (
                      SELECT 1 FROM policy_review_member m
                      WHERE m.policy_id=r.policy_id AND m.tenant_key=r.tenant_key AND m.version=r.version
                        AND m.group_code='PRODUCT' AND m.user_id=u.id
                        AND m.member_status IN ('ACTIVE','HANDLED','PEER_HANDLED'))))
            """;

    record Round(
            int currentVersion,
            int recordVersion,
            int workflowVersion,
            Integer settingsVersion,
            String stage,
            String routeJson,
            String auditStatus,
            String snapshotData) {}

    @Select(
            """
            SELECT p.current_version, p.record_version, a.workflow_version, r.settings_version,
                   r.stage, r.route_json, a.audit_status, a.snapshot_data
            """
                    + VISIBLE_ROUND)
    Round visible(String tenant, long userId, long id, int version);

    @Select("SELECT COUNT(*) " + VISIBLE_ROUND)
    int visibleCount(String tenant, long userId, long id, int version);

    record Member(
            long id,
            long userId,
            String groupCode,
            String displayName,
            String department,
            int assignmentNo,
            String memberStatus,
            String decision,
            String reason,
            LocalDateTime decidedAt) {}

    @Select(
            """
            SELECT m.id, m.user_id, m.group_code, m.display_name, m.department, m.assignment_no, m.member_status,
                   d.decision, d.reason, d.decided_at
            FROM policy_review_member m LEFT JOIN policy_review_decision d
              ON d.member_id=m.id AND d.tenant_key=m.tenant_key AND d.policy_id=m.policy_id
                AND d.version=m.version AND d.group_code=m.group_code
            WHERE m.tenant_key=#{tenant} AND m.policy_id=#{id} AND m.version=#{version}
              AND m.group_code IN ('PRODUCT','PURCHASE')
            ORDER BY m.group_code, m.user_id, m.assignment_no
            """)
    List<Member> members(String tenant, long id, int version);
}
