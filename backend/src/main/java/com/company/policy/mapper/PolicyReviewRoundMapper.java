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
              AND a.audit_status='SUBMITTED'
              AND m.group_code='PRODUCT' AND r.stage='PRODUCT_REVIEW'
              AND JSON_CONTAINS(r.route_json, '{"requiresProductReview":true}')
              AND product.member_id IS NULL AND purchase.member_id IS NULL
              AND p.id=#{id} AND r.version=#{version} AND
            """
                    + ReviewPermissions.PRODUCT)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Long productPermission(String tenant, long userId, long id, int version);

    // 详情与附件共用轮次授权，产品运营按本人实际签署的初审记录读取。
    String VISIBLE_ROUNDS =
            """
            FROM policy_apply p
            JOIN policy_audit_log a ON a.policy_id=p.id AND a.tenant_key=p.tenant_key
            JOIN user_info u ON u.id=#{userId} AND u.tenant_key=p.tenant_key AND u.is_deleted=0
            LEFT JOIN policy_review_round r ON r.policy_id=p.id AND r.tenant_key=p.tenant_key AND r.version=a.version
            WHERE p.id=#{id} AND p.tenant_key=#{tenant} AND p.is_deleted=0
              AND p.status IN ('PENDING','APPROVED','REJECTED') AND a.version <= p.current_version
              AND (
            """
                    + ReviewPermissions.COMPANY
                    + " OR ("
                    + ReviewPermissions.APPLY
                    + " AND u.id=p.applicant_id) OR ("
                    + ReviewPermissions.PURCHASE_PERMISSION
                    + " AND EXISTS (SELECT 1 "
                    + PolicyPurchaseReviewMapper.VISIBLE_ASSIGNMENTS
                    + " AND m.policy_id=a.policy_id AND m.tenant_key=a.tenant_key AND m.version=a.version AND m.user_id=u.id)) OR ("
                    + ReviewPermissions.HISTORY
                    + """
                AND EXISTS (SELECT 1 FROM policy_review_decision d JOIN policy_review_member m ON m.id=d.member_id
                WHERE d.policy_id=a.policy_id AND d.tenant_key=a.tenant_key AND d.version=a.version AND d.group_code='PRODUCT' AND m.user_id=u.id)))
            """;
    String VISIBLE_ROUND = VISIBLE_ROUNDS + " AND a.version=#{version}";

    @Select("SELECT MAX(a.version) " + VISIBLE_ROUNDS)
    int latestVisibleVersion(String tenant, long userId, long id);

    record Round(
            int currentVersion,
            int recordVersion,
            Integer settingsVersion,
            String stage,
            String routeJson,
            String auditStatus,
            String snapshotData,
            LocalDateTime submittedAt) {}

    @Select(
            """
            SELECT p.current_version, p.record_version, r.settings_version,
                   r.stage, r.route_json, a.audit_status, a.snapshot_data, a.created_at AS submitted_at
            """
                    + VISIBLE_ROUND)
    Round visible(String tenant, long userId, long id, int version);

    @Select("SELECT COUNT(*) " + VISIBLE_ROUND)
    int visibleCount(String tenant, long userId, long id, int version);

    record ProgressRow(
            String stage,
            String auditStatus,
            String applicantName,
            LocalDateTime submittedAt,
            boolean requiresProductReview) {}

    // 仅由已核对本人或平台可见轮次的详情读取，人员姓名取提交时的快照。
    @Select(
            """
        SELECT r.stage, a.audit_status, JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_data,'$.applicantName')) AS applicant_name,
          a.created_at AS submitted_at, JSON_CONTAINS(r.route_json, '{"requiresProductReview":true}') AS requires_product_review
        FROM policy_audit_log a JOIN policy_review_round r
          ON r.tenant_key=a.tenant_key AND r.policy_id=a.policy_id AND r.version=a.version
        WHERE a.tenant_key=#{tenant} AND a.policy_id=#{id} AND a.version=#{version}
        """)
    ProgressRow progress(String tenant, long id, int version);

    @Select(
            "SELECT EXISTS (SELECT 1 "
                    + PolicyPurchaseReviewMapper.VISIBLE_ASSIGNMENTS
                    + " AND m.tenant_key=#{tenant} AND m.user_id=#{userId} AND m.policy_id=#{id} AND m.version=#{version})")
    boolean purchaseVisible(String tenant, long userId, long id, int version);

    String SIGNED_FROM =
            """
        FROM policy_review_decision d JOIN policy_review_member m ON m.id=d.member_id
        JOIN policy_apply p ON p.id=d.policy_id AND p.tenant_key=d.tenant_key AND p.is_deleted=0
        WHERE d.tenant_key=#{tenant} AND d.group_code='PRODUCT' AND m.user_id=#{userId}
        """;

    record Signed(
            long policyId,
            int submissionVersion,
            String decision,
            String reason,
            LocalDateTime decidedAt) {}

    @Select("SELECT count(*) " + SIGNED_FROM)
    long signedCount(String tenant, long userId);

    @Select(
            "SELECT d.policy_id,d.version AS submission_version,d.decision,d.reason,d.decided_at "
                    + SIGNED_FROM
                    + " ORDER BY d.decided_at DESC,d.policy_id DESC,d.version DESC LIMIT #{size} OFFSET #{offset}")
    List<Signed> signed(String tenant, long userId, int size, long offset);

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
