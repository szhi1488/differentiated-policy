package com.company.policy.mapper;

import com.company.policy.dto.PolicyReviewQuery;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PolicyReviewMapper {
    String FROM =
            """
        FROM policy_apply p JOIN policy_audit_log a
          ON a.policy_id = p.id AND a.tenant_key = p.tenant_key AND a.version = p.current_version
        """;
    String SUBMITTED =
            """
        WHERE p.tenant_key = #{tenantKey} AND p.is_deleted = 0
          AND p.status IN ('PENDING', 'APPROVED', 'REJECTED')
        """;
    String FILTERS =
            """
        <if test="q.perspective != null">AND p.channel_type = #{q.perspective}</if>
        <if test="q.platformCode != null">AND JSON_CONTAINS(p.platforms, JSON_QUOTE(#{q.platformCode}))</if>
        <if test="q.verifyType != null">AND p.write_off_type = #{q.verifyType}</if>
        <if test="q.sceneType != null">AND p.scene = #{q.sceneType}</if>
        <if test="q.applicant != null">AND LOWER(p.applicant_name) LIKE LOWER(#{q.applicant}) ESCAPE '!'</if>
        <if test="q.submittedFrom != null">AND a.created_at &gt;= #{q.submittedFrom}</if>
        <if test="q.submittedBefore != null">AND a.created_at &lt; #{q.submittedBefore}</if>
        <if test="q.keyword != null">
          AND (LOWER(p.policy_no) LIKE LOWER(#{q.keyword}) ESCAPE '!'
            OR LOWER(CONCAT('ZC-', CASE WHEN p.id &lt; 1000000
              THEN LPAD(TRIM(CAST(p.id AS CHAR(20))), 6, '0')
              ELSE TRIM(CAST(p.id AS CHAR(20))) END)) LIKE LOWER(#{q.keyword}) ESCAPE '!'
            OR LOWER(p.applicant_name) LIKE LOWER(#{q.keyword}) ESCAPE '!'
            OR JSON_SEARCH(LOWER(a.snapshot_data), 'one', LOWER(#{q.keyword}), '!', '$.goods[*].modelname') IS NOT NULL)
        </if>
        """;

    @Select(
            "<script>SELECT p.status, COUNT(*) AS total "
                    + FROM
                    + SUBMITTED
                    + FILTERS
                    + " GROUP BY p.status</script>")
    List<StatusCount> counts(String tenantKey, PolicyReviewQuery q);

    @Select(
            "<script>SELECT p.id, p.policy_no, p.tenant_key, p.applicant_id, p.applicant_name,"
                    + " p.channel_type, p.platforms, p.write_off_type, p.scene, p.status, p.record_version, p.current_version,"
                    + " a.created_at AS created_at "
                    + FROM
                    + SUBMITTED
                    + FILTERS
                    + "<if test='q.status != null'> AND p.status = #{q.status}</if>"
                    + " ORDER BY a.created_at DESC, a.id DESC LIMIT #{q.pageSize} OFFSET #{offset}</script>")
    List<PolicyApply> list(String tenantKey, PolicyReviewQuery q, long offset);

    @Select(
            """
        SELECT id, policy_no, tenant_key, applicant_id, applicant_name, channel_type, platforms,
          write_off_type, scene, start_time, status, record_version, current_version
        FROM policy_apply WHERE id = #{id} AND tenant_key = #{tenantKey}
          AND is_deleted = 0 AND status IN ('PENDING', 'APPROVED', 'REJECTED')
        """)
    PolicyApply find(long id, String tenantKey);

    @Select(
            """
        SELECT id, version, audit_status, auditor_name, audit_comment, audit_time, created_at, snapshot_data
        FROM policy_audit_log WHERE policy_id = #{id} AND tenant_key = #{tenantKey} AND version = #{version}
        """)
    PolicyAuditLog current(long id, String tenantKey, int version);

    @Update(
            """
        UPDATE policy_apply SET status = #{status}, record_version = record_version + 1, updated_at = #{now}
        WHERE id = #{id} AND tenant_key = #{tenantKey} AND is_deleted = 0 AND status = 'PENDING'
          AND record_version = #{recordVersion} AND current_version = #{version}
        """)
    int review(
            long id,
            String tenantKey,
            int recordVersion,
            int version,
            String status,
            LocalDateTime now);

    @Update(
            """
        UPDATE policy_audit_log SET audit_status = #{status}, auditor_id = #{auditorId},
          auditor_name = #{auditorName}, audit_comment = #{remark}, audit_time = #{now}
        WHERE policy_id = #{id} AND tenant_key = #{tenantKey} AND version = #{version}
          AND audit_status = 'SUBMITTED'
        """)
    int reviewAudit(
            long id,
            String tenantKey,
            int version,
            String status,
            long auditorId,
            String auditorName,
            String remark,
            LocalDateTime now);

    @Select(
            """
        <script>
        SELECT platform_code, goods_id, policy_id, policy_no, version, audit_id, submitted_at, effective_from
        FROM (
          SELECT d.platform_code, d.goods_id, p.id AS policy_id, p.policy_no, a.version,
            a.id AS audit_id, a.created_at AS submitted_at,
            CASE WHEN p.start_time &gt; a.audit_time THEN p.start_time ELSE a.audit_time END AS effective_from,
            ROW_NUMBER() OVER (PARTITION BY d.platform_code, d.goods_id
              ORDER BY a.created_at DESC, a.id DESC) AS position_no
          FROM policy_goods_detail d
          JOIN policy_apply p ON p.id = d.policy_id AND p.current_version = d.version
          JOIN policy_audit_log a ON a.policy_id = p.id AND a.version = d.version AND a.tenant_key = p.tenant_key
          WHERE p.tenant_key = #{tenantKey} AND p.channel_type = #{perspective} AND p.is_deleted = 0
            AND p.status = 'APPROVED' AND a.audit_status = 'APPROVED'
            AND p.start_time &lt;= #{at} AND a.audit_time &lt;= #{at}
          <choose>
            <when test="policyId != null">
              AND EXISTS (
                SELECT 1 FROM policy_goods_detail targets
                WHERE targets.policy_id = #{policyId} AND targets.version = #{version}
                  AND targets.platform_code = d.platform_code AND targets.goods_id = d.goods_id
              )
            </when>
            <otherwise>AND d.platform_code = #{platformCode} AND d.goods_id = #{goodsId}</otherwise>
          </choose>
        ) ranked WHERE position_no = 1
        </script>
        """)
    List<WinnerRow> winners(
            String tenantKey,
            String perspective,
            LocalDateTime at,
            Long policyId,
            Integer version,
            String platformCode,
            Long goodsId);

    record StatusCount(String status, long total) {}

    record WinnerRow(
            String platformCode,
            long goodsId,
            long policyId,
            String policyNo,
            int version,
            long auditId,
            LocalDateTime submittedAt,
            LocalDateTime effectiveFrom) {}
}
