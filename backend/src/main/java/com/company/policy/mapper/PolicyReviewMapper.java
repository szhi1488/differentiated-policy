package com.company.policy.mapper;

import com.company.policy.dto.ChannelPlatform;
import com.company.policy.dto.PolicyReviewQuery;
import com.company.policy.dto.PolicyTimelineQuery;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import com.company.policy.vo.PolicyApplicability.Scope;
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
          SELECT id, policy_id, tenant_key, version, created_at, snapshot_data, audit_status, workflow_version,
            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(snapshot_data, '$.applicantName')), 'null') AS applicant_name,
            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(snapshot_data, '$.form.perspective')), 'null') AS channel_type,
            JSON_EXTRACT(snapshot_data, '$.form.selectedPlatforms') AS platforms,
            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(snapshot_data, '$.form.verifyType')), 'null') AS write_off_type,
            JSON_EXTRACT(snapshot_data, '$.form.sceneTypes') AS scene_types
          FROM policy_audit_log
        ) a
          ON a.policy_id = p.id AND a.tenant_key = p.tenant_key AND a.version = p.current_version
        """
                    + PolicyReviewWorkflowMapper.ROUND_JOIN;
    String PRODUCT_SIGNER =
            """
            LEFT JOIN policy_review_decision d ON d.policy_id=r.policy_id AND d.version=r.version
              AND d.tenant_key=r.tenant_key AND d.group_code='PRODUCT'
            LEFT JOIN policy_review_member signer ON signer.id=d.member_id AND signer.tenant_key=d.tenant_key
              AND signer.policy_id=d.policy_id AND signer.version=d.version AND signer.group_code=d.group_code
            """;
    String COLUMNS =
            """
        p.id, p.policy_no, p.tenant_key, p.applicant_id, a.applicant_name,
        a.channel_type, a.platforms, a.write_off_type, a.scene_types,
        p.status, p.record_version, p.current_version, a.created_at AS created_at,
        signer.display_name AS product_reviewer_name, d.decided_at AS product_reviewed_at,
        """
                    + PolicyReviewWorkflowMapper.CURRENT_STAGE
                    + " AS review_stage ";
    String SUBMITTED =
            """
        WHERE p.tenant_key = #{tenantKey} AND p.is_deleted = 0
          AND p.status IN ('PENDING', 'APPROVED', 'REJECTED')
        """;
    String FILTERS =
            "<if test='q.reviewStage != null'>AND p.status='PENDING' AND "
                    + PolicyReviewWorkflowMapper.CURRENT_STAGE
                    + " = #{q.reviewStage}</if>"
                    + """
        <if test="q.perspective != null">AND a.channel_type = #{q.perspective}</if>
        <if test="q.platformCode != null">AND JSON_CONTAINS(a.platforms, JSON_QUOTE(#{q.platformCode}))</if>
        <if test="q.verifyType != null">AND a.write_off_type = #{q.verifyType}</if>
        <if test="q.sceneType != null">AND JSON_CONTAINS(a.scene_types, JSON_QUOTE(#{q.sceneType}))</if>
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
                    + PRODUCT_SIGNER
                    + SUBMITTED
                    + FILTERS
                    + "<if test='q.status != null'> AND p.status = #{q.status}</if>"
                    + " ORDER BY a.created_at DESC, a.id DESC LIMIT #{q.pageSize} OFFSET #{offset}</script>")
    List<PolicyApply> list(String tenantKey, PolicyReviewQuery q, long offset);

    @Select(
            "SELECT "
                    + COLUMNS
                    + ", p.start_time, p.extension_source_id "
                    + FROM
                    + PRODUCT_SIGNER
                    + SUBMITTED
                    + " AND p.id = #{id}")
    PolicyApply find(long id, String tenantKey);

    @Select(
            "SELECT "
                    + PolicyApplyMapper.AUDIT_COLUMNS
                    + ", a.snapshot_data FROM policy_audit_log a WHERE "
                    + PolicyApplyMapper.AUDIT_SCOPE
                    + " AND a.version=#{version}")
    PolicyAuditLog current(long id, String tenantKey, int version);

    @Select(
            """
        <script>
        SELECT a.id, a.policy_id, a.version, a.snapshot_data, a.created_at, a.audit_time
        FROM policy_audit_log a
        JOIN policy_apply p ON p.id = a.policy_id AND p.tenant_key = a.tenant_key
        WHERE p.tenant_key = #{tenantKey} AND p.is_deleted = 0
          AND p.status = 'APPROVED' AND a.audit_status = 'APPROVED'
          AND a.id IN
          <foreach collection="auditIds" item="auditId" open="(" separator="," close=")">#{auditId}</foreach>
        ORDER BY a.created_at, a.id
        </script>
        """)
    List<PolicyAuditLog> coverageSnapshots(String tenantKey, List<Long> auditIds);

    // 调整适用于整张旧单，查询独立于发起调整的新单商品、平台及自身有效期。
    @Select(
            """
            <script>
            SELECT a.policy_id AS request_id, a.audit_time, JSON_EXTRACT(a.snapshot_data, '$.consent.endAdjustments') AS changes,
              <choose><when test="targetId != null">target_audit.id</when><otherwise>NULL</otherwise></choose> AS target_audit_id
            FROM policy_audit_log a JOIN policy_apply p ON p.id=a.policy_id AND p.tenant_key=a.tenant_key AND p.current_version=a.version
            <if test="targetId != null">
                JOIN policy_apply target ON target.id=#{targetId} AND target.tenant_key=p.tenant_key
                  AND target.is_deleted=0 AND target.status='APPROVED'
                JOIN policy_audit_log target_audit ON target_audit.policy_id=target.id AND target_audit.tenant_key=target.tenant_key
                  AND target_audit.version=target.current_version AND target_audit.audit_status='APPROVED'
                  <if test="targetAuditId != null">AND target_audit.id=#{targetAuditId}</if>
            </if>
            WHERE p.tenant_key=#{tenant} AND p.is_deleted=0 AND p.status='APPROVED' AND a.audit_status='APPROVED'
              AND a.audit_time &lt;= #{asOf} AND JSON_EXTRACT(a.snapshot_data, '$.consent.endAdjustments') IS NOT NULL
              <if test="targetId != null">
                AND JSON_CONTAINS(JSON_EXTRACT(a.snapshot_data, '$.consent.endAdjustments'),
                    CONCAT('{"target":{"policyId":"', target.id, '","auditLogId":"', target_audit.id, '"}}'))
              </if>
            ORDER BY a.audit_time,a.id
            </script>
            """)
    List<ApprovedChanges> approvedChanges(
            String tenant, LocalDateTime asOf, Long targetId, Long targetAuditId);

    record ApprovedChanges(
            long requestId, LocalDateTime auditTime, String changes, Long targetAuditId) {}

    // 负责一个实际使用的平台即可发起整单调整。旧单的分工、日期和渠道独立于本次表单。
    String ADJUSTABLE =
            """
            FROM policy_apply p
            JOIN policy_audit_log a ON a.policy_id=p.id AND a.tenant_key=p.tenant_key AND a.version=p.current_version
            WHERE p.tenant_key=#{tenant} AND p.is_deleted=0 AND p.status='APPROVED' AND a.audit_status='APPROVED'
              AND EXISTS (
                SELECT 1 FROM user_platform_assignment u
                JOIN user_info owner ON owner.id=u.user_id AND owner.tenant_key=u.tenant_key
                JOIN policy_goods_detail d ON d.platform_code=u.platform_code
                WHERE d.policy_id=p.id AND d.version=a.version AND u.tenant_key=p.tenant_key
                  AND u.perspective=p.channel_type AND u.user_id=#{applicantId}
                  AND owner.is_deleted=0 AND owner.role_type='APPLICANT')
            """;

    @Select(
            """
            SELECT p.id AS policy_id, a.id AS audit_id, p.channel_type AS perspective, a.audit_time,
                   p.applicant_id, p.platforms,
                   EXISTS(SELECT 1 FROM policy_apply child WHERE child.tenant_key=p.tenant_key
                     AND child.extension_source_id=p.id AND child.is_deleted=0) AS has_extension,
                   JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_data, '$.form.startTime')) AS start_time,
                   JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_data, '$.form.endTime')) AS end_time,
                   NULLIF(JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_data, '$.sourcePolicyCode')), 'null') AS source_policy_code
            """
                    + ADJUSTABLE
                    + """
                       AND a.audit_time <= #{asOf}
                       AND (p.applicant_id=#{applicantId}
                         OR LEFT(JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_data, '$.form.endTime')), 10) >= #{today})
                     ORDER BY p.id DESC
                     """)
    List<AdjustmentCandidate> adjustmentCandidates(
            String tenant, long applicantId, LocalDateTime asOf, String today);

    record AdjustmentCandidate(
            long policyId,
            long auditId,
            String perspective,
            LocalDateTime auditTime,
            long applicantId,
            String platforms,
            boolean hasExtension,
            String startTime,
            String endTime,
            String sourcePolicyCode) {}

    @Select(
            """
            <script>SELECT DISTINCT detail.spu_id FROM policy_goods_detail detail
            JOIN (SELECT p.id, a.version
            """
                    + ADJUSTABLE
                    + """
                      AND a.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
                    ) eligible ON detail.policy_id=eligible.id AND detail.version=eligible.version
                    </script>
                    """)
    List<Long> adjustmentModels(String tenant, long applicantId, List<Long> ids);

    @Select(
            """
        <script>SELECT DISTINCT detail.goods_id FROM policy_goods_detail detail
        JOIN (SELECT p.id, a.version
        """
                    + ADJUSTABLE
                    + """
              AND p.applicant_id=#{applicantId}
              AND a.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ) eligible ON detail.policy_id=eligible.id AND detail.version=eligible.version
        </script>
        """)
    List<Long> extensionGoods(String tenant, long applicantId, List<Long> ids);

    // 限定公司、当前已通过轮次、交叉日期与本次实际选品范围。不同颜色的百亿商品按配置命中。
    @Select(
            """
        <script>
        SELECT DISTINCT a.id
        FROM policy_apply p
        JOIN policy_audit_log a ON a.policy_id = p.id AND a.version = p.current_version AND a.tenant_key = p.tenant_key
        JOIN policy_goods_detail d ON d.policy_id = p.id AND d.version = a.version
        WHERE p.tenant_key = #{tenantKey} AND p.channel_type = #{channel} AND p.is_deleted = 0
          AND p.id != #{draftId} AND p.status = 'APPROVED' AND a.audit_status = 'APPROVED'
          AND a.audit_time &lt;= #{asOf}
          AND ((LEFT(JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_data, '$.form.startTime')), 10) &lt;= #{lastDate}
          AND LEFT(JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_data, '$.form.endTime')), 10) &gt;= #{firstDate})
          <if test="workflow">
              OR ((p.start_time IS NULL OR LEFT(p.start_time, 10) &lt;= #{lastDate})
                  AND (p.end_time IS NULL OR LEFT(p.end_time, 10) &gt;= #{firstDate}))
          </if>)
        <choose>
          <when test="channel == 'RETAIL'">
            AND (d.goods_id, d.platform_code) IN
            <foreach collection="scopes" item="s" open="(" separator="," close=")">(#{s.goodsId}, #{s.platformCode})</foreach>
          </when>
          <otherwise>
            AND (d.spu_id, d.ram, d.rom, d.platform_code) IN
            <foreach collection="scopes" item="s" open="(" separator="," close=")">(#{s.spuId}, #{s.ram}, #{s.rom}, #{s.platformCode})</foreach>
          </otherwise>
        </choose>
        </script>
        """)
    List<Long> overlappingAuditIds(
            String tenantKey,
            long draftId,
            String channel,
            LocalDateTime asOf,
            String firstDate,
            String lastDate,
            List<Scope> scopes,
            boolean workflow);

    @Select(
            """
        <script>SELECT id FROM spu WHERE id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY id FOR UPDATE</script>
        """)
    List<Long> lockScopeModels(List<Long> ids);

    @Select("SELECT spu_id, ram, rom FROM goods WHERE id = #{goodsId}")
    Configuration configuration(long goodsId);

    record Configuration(long spuId, String ram, String rom) {}

    @Select(
            "SELECT id FROM goods WHERE spu_id = #{spuId} AND ram = #{ram} AND rom = #{rom} ORDER BY id")
    List<Long> configurationGoods(long spuId, String ram, String rom);

    // 只取原单日期和轮次索引，完整快照按命中的展示窗口分批读取。
    @Select(
            """
        <script>
        SELECT a.id,
            LEFT(JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_data, '$.form.startTime')), 10) AS first_date,
            LEFT(JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_data, '$.form.endTime')), 10) AS last_date
        FROM policy_apply p
        JOIN policy_audit_log a ON a.policy_id = p.id AND a.version = p.current_version AND a.tenant_key = p.tenant_key
        WHERE p.tenant_key = #{tenant} AND p.is_deleted = 0 AND p.status = 'APPROVED'
          AND a.audit_status = 'APPROVED' AND a.audit_time &lt;= #{asOf}
          AND EXISTS (SELECT 1 FROM policy_goods_detail d
              WHERE d.policy_id = p.id AND d.version = p.current_version
                AND d.spu_id = #{query.spuId} AND d.ram = #{query.ram} AND d.rom = #{query.rom}
                AND (p.channel_type, d.platform_code) IN
                <foreach collection="scopes" item="scope" open="(" separator="," close=")">
                  (#{scope.perspective}, #{scope.platformCode})
                </foreach>)
        ORDER BY a.created_at, a.id
        </script>
        """)
    List<TimelineIndex> timelineIndex(
            String tenant,
            PolicyTimelineQuery query,
            List<ChannelPlatform> scopes,
            LocalDateTime asOf);

    record TimelineIndex(long id, String firstDate, String lastDate) {}

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

    record StatusCount(String status, long total) {}
}
