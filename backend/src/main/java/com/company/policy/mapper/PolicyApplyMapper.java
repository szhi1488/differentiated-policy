package com.company.policy.mapper;

import com.company.policy.dto.PolicyListQuery;
import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyAuditLog;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PolicyApplyMapper {
    String COLUMNS =
            """
        id, policy_no, tenant_key, applicant_id, applicant_name, channel_type, platforms, write_off_type,
        scene, is_estimated, start_time, end_time, remark, other_desc, goods_form_json, attachment_urls,
        status, current_version, record_version
        """;
    String OWNED =
            """
        tenant_key = #{tenantKey} AND applicant_id = #{applicantId} AND is_deleted = 0
        """;
    String MINE_FILTERS =
            """
        <if test="query.status != null">AND status = #{query.status}</if>
        <if test="query.perspective != null">AND channel_type = #{query.perspective}</if>
        <if test="query.platformCode != null">
            AND JSON_CONTAINS(platforms, JSON_QUOTE(#{query.platformCode}))
        </if>
        """;
    String AUDIT_COLUMNS =
            "id, version, audit_status, auditor_name, audit_comment, audit_time, created_at";
    String AUDIT_SCOPE = "policy_id = #{id} AND tenant_key = #{tenantKey}";

    @Select("<script>SELECT COUNT(*) FROM policy_apply WHERE " + OWNED + MINE_FILTERS + "</script>")
    long countMine(String tenantKey, long applicantId, PolicyListQuery query);

    @Select(
            "<script>SELECT id, policy_no, status, record_version, current_version, channel_type,"
                    + " platforms, created_at, updated_at FROM policy_apply WHERE "
                    + OWNED
                    + MINE_FILTERS
                    + " ORDER BY created_at DESC, id DESC LIMIT #{query.pageSize} OFFSET #{offset}</script>")
    List<PolicyApply> listMine(
            String tenantKey, long applicantId, PolicyListQuery query, long offset);

    @Select("SELECT " + COLUMNS + " FROM policy_apply WHERE id = #{id} AND " + OWNED)
    PolicyApply find(long id, String tenantKey, long applicantId);

    @Select("SELECT " + COLUMNS + " FROM policy_apply WHERE policy_no = #{code} AND " + OWNED)
    PolicyApply findByCode(String code, String tenantKey, long applicantId);

    @Insert(
            """
        INSERT INTO policy_apply (
            policy_no, tenant_key, applicant_id, applicant_name, channel_type, platforms,
            write_off_type, scene, is_estimated, start_time, end_time, remark, other_desc,
            goods_form_json, attachment_urls, created_at, updated_at
        ) VALUES (
            #{entry.policyNo}, #{entry.tenantKey}, #{entry.applicantId}, #{entry.applicantName},
            #{entry.channelType}, #{entry.platforms}, #{entry.writeOffType}, #{entry.scene},
            #{entry.isEstimated}, #{entry.startTime}, #{entry.endTime}, #{entry.remark},
            #{entry.otherDesc}, #{entry.goodsFormJson}, #{entry.attachmentUrls}, #{now}, #{now}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "entry.id", keyColumn = "id")
    int insert(@Param("entry") PolicyApply entry, java.time.LocalDateTime now);

    @Update(
            """
        UPDATE policy_apply SET channel_type = #{entry.channelType}, platforms = #{entry.platforms},
            write_off_type = #{entry.writeOffType}, scene = #{entry.scene}, is_estimated = #{entry.isEstimated},
            start_time = #{entry.startTime}, end_time = #{entry.endTime}, remark = #{entry.remark},
            other_desc = #{entry.otherDesc}, goods_form_json = #{entry.goodsFormJson},
            attachment_urls = #{entry.attachmentUrls}, record_version = record_version + 1, updated_at = #{now}
        WHERE id = #{entry.id} AND tenant_key = #{entry.tenantKey} AND applicant_id = #{entry.applicantId}
            AND is_deleted = 0 AND record_version = #{entry.recordVersion} AND status IN ('DRAFT', 'REJECTED')
        """)
    int updateDraft(@Param("entry") PolicyApply entry, java.time.LocalDateTime now);

    @Update(
            """
        UPDATE policy_apply SET status = 'PENDING', record_version = record_version + 1,
            current_version = current_version + 1, updated_at = #{now}
        WHERE id = #{entry.id} AND tenant_key = #{entry.tenantKey} AND applicant_id = #{entry.applicantId}
            AND is_deleted = 0 AND record_version = #{entry.recordVersion} AND status IN ('DRAFT', 'REJECTED')
        """)
    int markSubmitted(@Param("entry") PolicyApply entry, java.time.LocalDateTime now);

    @Insert(
            """
        INSERT INTO policy_audit_log (policy_id, tenant_key, version, snapshot_data, audit_status, created_at)
        VALUES (#{id}, #{tenantKey}, #{version}, #{snapshot}, 'SUBMITTED', #{now})
        """)
    int insertAudit(
            long id, String tenantKey, int version, String snapshot, java.time.LocalDateTime now);

    @Select(
            """
        SELECT snapshot_data FROM policy_audit_log WHERE policy_id = #{id}
            AND tenant_key = #{tenantKey} AND version = #{version}
        """)
    String findSnapshot(long id, String tenantKey, int version);

    @Select("SELECT COUNT(*) FROM policy_audit_log WHERE " + AUDIT_SCOPE)
    long countAuditLogs(long id, String tenantKey);

    @Select(
            "SELECT "
                    + AUDIT_COLUMNS
                    + " FROM policy_audit_log WHERE "
                    + AUDIT_SCOPE
                    + " ORDER BY version DESC LIMIT #{pageSize} OFFSET #{offset}")
    List<PolicyAuditLog> listAuditLogs(long id, String tenantKey, int pageSize, long offset);

    @Select(
            "SELECT "
                    + AUDIT_COLUMNS
                    + ", snapshot_data FROM policy_audit_log WHERE "
                    + AUDIT_SCOPE
                    + " AND id = #{logId}")
    PolicyAuditLog findAuditLog(long id, String tenantKey, long logId);

    @Insert(
            """
        <script>
        INSERT INTO policy_goods_detail (policy_id, version, platform_code, goods_id, spu_id, ram, rom, created_at)
        VALUES
        <foreach collection="lines" item="line" separator=",">
            (#{id}, #{version}, #{line.platform}, #{line.goodsId}, #{line.spuId}, #{line.ram}, #{line.rom}, #{now})
        </foreach>
        </script>
        """)
    int insertDetails(
            long id,
            int version,
            @Param("lines") List<DetailLine> lines,
            java.time.LocalDateTime now);

    record DetailLine(String platform, long goodsId, long spuId, String ram, String rom) {}
}
