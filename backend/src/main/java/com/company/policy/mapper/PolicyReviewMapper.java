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
    // 管理员业务字段只读最近正式提交的快照，驳回后的暂存修改不能提前进入列表和筛选。
    String FROM =
            """
        FROM policy_apply p JOIN (
          SELECT id, policy_id, tenant_key, version, created_at, snapshot_data,
            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(snapshot_data, '$.applicantName')), 'null') AS applicant_name,
            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(snapshot_data, '$.form.perspective')), 'null') AS channel_type,
            JSON_EXTRACT(snapshot_data, '$.form.selectedPlatforms') AS platforms,
            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(snapshot_data, '$.form.verifyType')), 'null') AS write_off_type,
            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(snapshot_data, '$.form.sceneType')), 'null') AS scene
          FROM policy_audit_log
        ) a
          ON a.policy_id = p.id AND a.tenant_key = p.tenant_key AND a.version = p.current_version
        """;
    String COLUMNS =
            """
        p.id, p.policy_no, p.tenant_key, p.applicant_id, a.applicant_name,
        a.channel_type, a.platforms, a.write_off_type, a.scene,
        p.status, p.record_version, p.current_version, a.created_at AS created_at
        """;
    String SUBMITTED =
            """
        WHERE p.tenant_key = #{tenantKey} AND p.is_deleted = 0
          AND p.status IN ('PENDING', 'APPROVED', 'REJECTED')
        """;
    String FILTERS =
            """
        <if test="q.perspective != null">AND a.channel_type = #{q.perspective}</if>
        <if test="q.platformCode != null">AND JSON_CONTAINS(a.platforms, JSON_QUOTE(#{q.platformCode}))</if>
        <if test="q.verifyType != null">AND a.write_off_type = #{q.verifyType}</if>
        <if test="q.sceneType != null">AND a.scene = #{q.sceneType}</if>
        <if test="q.applicant != null">AND LOWER(a.applicant_name) LIKE LOWER(#{q.applicant}) ESCAPE '!'</if>
        <if test="q.submittedFrom != null">AND a.created_at &gt;= #{q.submittedFrom}</if>
        <if test="q.submittedBefore != null">AND a.created_at &lt; #{q.submittedBefore}</if>
        <if test="q.keyword != null">
          AND (LOWER(p.policy_no) LIKE LOWER(#{q.keyword}) ESCAPE '!'
            OR LOWER(CONCAT('ZC-', CASE WHEN p.id &lt; 1000000
              THEN LPAD(TRIM(CAST(p.id AS CHAR(20))), 6, '0')
              ELSE TRIM(CAST(p.id AS CHAR(20))) END)) LIKE LOWER(#{q.keyword}) ESCAPE '!'
            OR LOWER(a.applicant_name) LIKE LOWER(#{q.keyword}) ESCAPE '!'
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
            "<script>SELECT "
                    + COLUMNS
                    + FROM
                    + SUBMITTED
                    + FILTERS
                    + "<if test='q.status != null'> AND p.status = #{q.status}</if>"
                    + " ORDER BY a.created_at DESC, a.id DESC LIMIT #{q.pageSize} OFFSET #{offset}</script>")
    List<PolicyApply> list(String tenantKey, PolicyReviewQuery q, long offset);

    @Select("SELECT " + COLUMNS + ", p.start_time " + FROM + SUBMITTED + " AND p.id = #{id}")
    PolicyApply find(long id, String tenantKey);

    @Select(
            """
        SELECT id, version, audit_status, auditor_name, audit_comment, audit_time, created_at, snapshot_data
        FROM policy_audit_log WHERE policy_id = #{id} AND tenant_key = #{tenantKey} AND version = #{version}
        """)
    PolicyAuditLog current(long id, String tenantKey, int version);

    @Select(
            """
        <script>
        SELECT a.id, a.snapshot_data
        FROM policy_audit_log a
        JOIN policy_apply p ON p.id = a.policy_id AND p.tenant_key = a.tenant_key
        WHERE p.tenant_key = #{tenantKey} AND p.is_deleted = 0
          AND p.status = 'APPROVED' AND a.audit_status = 'APPROVED'
          AND a.id IN
          <foreach collection="auditIds" item="auditId" open="(" separator="," close=")">#{auditId}</foreach>
        </script>
        """)
    List<PolicyAuditLog> coverageSnapshots(String tenantKey, List<Long> auditIds);

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
            <when test="draftScope != null">
              AND p.applicant_id = #{draftScope.applicantId}
              AND (d.platform_code, d.goods_id) IN
              <foreach collection="draftScope.targets" item="line" open="(" separator="," close=")">(#{line.platform}, #{line.goodsId})</foreach>
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
            Long goodsId,
            DraftScope draftScope);

    record DraftScope(long applicantId, List<PolicyApplyMapper.DetailLine> targets) {}

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
