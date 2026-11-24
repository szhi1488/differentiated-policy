package com.company.policy.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExecutionNoticeMapper {
    @Insert(
            """
        INSERT INTO policy_review_delivery
          (tenant_key,event_key,action_type,recipient_key,payload_json,created_at,next_attempt_at)
        VALUES (#{tenant},#{key},'GROUP_MESSAGE',0,#{payload},#{at},#{due})
        ON DUPLICATE KEY UPDATE id=id
        """)
    void register(String tenant, String key, String payload, LocalDateTime at, LocalDateTime due);

    record Policy(
            long id,
            int version,
            long auditId,
            long applicantId,
            String channel,
            LocalDateTime startTime,
            LocalDateTime endTime,
            LocalDateTime approvedAt,
            Long extensionSourceId) {}

    String APPROVED =
            """
        SELECT p.id,p.current_version AS version,a.id AS audit_id,p.applicant_id,p.channel_type AS channel,
          p.start_time,p.end_time,a.audit_time AS approved_at,p.extension_source_id
        FROM policy_apply p JOIN policy_audit_log a ON a.policy_id=p.id AND a.tenant_key=p.tenant_key
          AND a.version=p.current_version AND a.audit_status='APPROVED'
        WHERE p.tenant_key=#{tenant} AND p.is_deleted=0 AND p.status='APPROVED'
          AND EXISTS (SELECT 1 FROM policy_goods_detail d WHERE d.policy_id=p.id AND d.version=p.current_version)
        """;

    @Select(APPROVED + " AND p.id=#{id}")
    Policy approved(String tenant, long id);

    @Select(APPROVED + " AND p.id>#{after} AND p.end_time>=#{at} ORDER BY p.id LIMIT 200")
    List<Policy> activePage(String tenant, long after, LocalDateTime at);

    String DETAIL_SCOPE =
            """
        FROM policy_goods_detail d JOIN policy_apply p ON p.id=d.policy_id AND p.current_version=d.version
        WHERE p.tenant_key=#{tenant} AND p.is_deleted=0 AND p.id=#{id}
        """;

    @Select(
            """
        SELECT DISTINCT d.platform_code,
          CASE WHEN p.channel_type='RETAIL' THEN d.goods_id END AS goods_id,
          CASE WHEN p.channel_type='BILLION' THEN d.spu_id END AS spu_id,
          CASE WHEN p.channel_type='BILLION' THEN d.ram END AS ram,
          CASE WHEN p.channel_type='BILLION' THEN d.rom END AS rom
        """
                    + DETAIL_SCOPE
                    + " ORDER BY platform_code,goods_id,spu_id,ram,rom")
    List<com.company.policy.vo.PolicyApplicability.Scope> scopes(String tenant, long id);

    @Select("SELECT DISTINCT d.platform_code " + DETAIL_SCOPE + " ORDER BY d.platform_code")
    List<String> usedPlatforms(String tenant, long id);

    record Extension(long id, String status, LocalDateTime startTime, LocalDateTime approvedAt) {}

    @Select(
            """
        SELECT p.id,p.status,p.start_time,a.audit_time AS approved_at FROM policy_apply p
        JOIN policy_audit_log a ON a.policy_id=p.id AND a.tenant_key=p.tenant_key AND a.version=p.current_version
        WHERE p.tenant_key=#{tenant} AND p.extension_source_id=#{id} AND p.is_deleted=0
          AND ((p.status='APPROVED' AND a.audit_status='APPROVED') OR (p.status='PENDING' AND a.audit_status='SUBMITTED'))
        ORDER BY p.id DESC LIMIT 1
        """)
    Extension extension(String tenant, long id);

    @Select(
            """
        SELECT JSON_EXTRACT(a.snapshot_data,'$.consent.endAdjustments')
        FROM policy_audit_log a JOIN policy_apply p ON p.id=a.policy_id AND p.tenant_key=a.tenant_key AND p.current_version=a.version
        WHERE p.tenant_key=#{tenant} AND p.is_deleted=0 AND p.status='PENDING' AND a.audit_status='SUBMITTED'
          AND JSON_CONTAINS(JSON_EXTRACT(a.snapshot_data,'$.consent.endAdjustments'),
              CONCAT('{"target":{"policyId":"',#{id},'","auditLogId":"',#{auditId},'"}}'))
        """)
    List<String> pendingChanges(String tenant, long id, long auditId);

    @Select("SELECT name FROM user_info WHERE tenant_key=#{tenant} AND id=#{id}")
    String accountName(String tenant, long id);

    @Select("SELECT tenant_key FROM user_info WHERE id=#{id}")
    String requesterTenant(long id);

    @Select(
            "SELECT COUNT(*) FROM goods_sync_job WHERE id=#{id} AND status='PAUSED' AND record_version=#{version}")
    int stillPaused(long id, int version);

    @Select(
            """
        SELECT payload_json FROM policy_review_delivery WHERE tenant_key=#{tenant}
          AND notice_key LIKE CONCAT(#{prefix},'%') ORDER BY id DESC LIMIT 1
        """)
    String lastPlatformChange(String tenant, String prefix);

    @Select(
            "SELECT COUNT(*) FROM policy_review_delivery WHERE tenant_key=#{tenant} AND notice_key LIKE CONCAT(#{prefix},'%')")
    int hasSchedule(String tenant, String prefix);

    @Update(
            """
        UPDATE policy_review_delivery SET delivery_status='CANCELLED',next_attempt_at=NULL,last_error='政策实际截止已变更'
        WHERE tenant_key=#{tenant} AND notice_key LIKE CONCAT(#{prefix},'%')
          AND notice_key<>#{current} AND delivery_status IN ('PENDING','RETRY')
        """)
    void cancelOld(String tenant, String prefix, String current);
}
