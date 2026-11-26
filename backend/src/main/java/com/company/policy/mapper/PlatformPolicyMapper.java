package com.company.policy.mapper;

import com.company.policy.dto.PolicyListQuery;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 同平台共同成员只读最新正式轮次。申请人正在编辑的主表内容不进入共享列表。 */
@Mapper
public interface PlatformPolicyMapper {
    String SCOPE =
            """
        FROM policy_apply p
        JOIN policy_audit_log a ON a.policy_id=p.id AND a.tenant_key=p.tenant_key AND a.version=p.current_version
        JOIN policy_review_round r ON r.policy_id=a.policy_id AND r.tenant_key=a.tenant_key AND r.version=a.version
        WHERE p.tenant_key=#{tenant} AND p.is_deleted=0 AND p.status IN ('PENDING','REJECTED','APPROVED')
          AND EXISTS (SELECT 1 FROM policy_round_platform rp
            JOIN user_platform_assignment m ON m.tenant_key=rp.tenant_key AND m.perspective=rp.perspective
              AND m.platform_code=rp.platform_code
            WHERE rp.policy_id=a.policy_id AND rp.version=a.version AND rp.tenant_key=p.tenant_key AND m.user_id=#{userId})
        """;
    String FILTERS =
            """
        <if test="query.status != null"> AND p.status=#{query.status}</if>
        <if test="query.perspective != null"> AND a.perspective=#{query.perspective}</if>
        <if test="query.platformCode != null"> AND EXISTS (SELECT 1 FROM policy_round_platform rp
          WHERE rp.policy_id=a.policy_id AND rp.version=a.version AND rp.platform_code=#{query.platformCode})</if>
        """;

    record ListRow(
            long id,
            String status,
            int recordVersion,
            int version,
            String perspective,
            String platforms,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            String submittedEnd,
            LocalDateTime effectiveEnd,
            String stage) {}

    record Submitted(
            String status, int recordVersion, int version, String stage, String snapshotData) {}

    @Select("<script>SELECT COUNT(*) " + SCOPE + FILTERS + "</script>")
    long count(String tenant, long userId, PolicyListQuery query);

    @Select(
            "<script>"
                    + """
        SELECT p.id,p.status,p.record_version,a.version,a.perspective,
          JSON_EXTRACT(a.snapshot_data,'$.form.selectedPlatforms') AS platforms,
          p.created_at, COALESCE(a.audit_time,a.created_at) AS updated_at,
          JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_data,'$.form.endTime')) AS submitted_end,
          CASE WHEN p.status='APPROVED' THEN p.end_time END AS effective_end,r.stage
        """
                    + SCOPE
                    + FILTERS
                    + " ORDER BY a.created_at DESC,a.id DESC LIMIT #{query.pageSize} OFFSET #{offset}</script>")
    List<ListRow> list(String tenant, long userId, PolicyListQuery query, long offset);

    @Select(
            "SELECT p.status,p.record_version,a.version,r.stage,a.snapshot_data "
                    + SCOPE
                    + " AND p.id=#{id}")
    Submitted current(String tenant, long userId, long id);
}
