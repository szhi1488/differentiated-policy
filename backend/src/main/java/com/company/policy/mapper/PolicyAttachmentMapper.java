package com.company.policy.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PolicyAttachmentMapper {
    // 保留父申请行，供服务区分申请不可见、轮次不存在和该轮没有此文件。
    String HISTORY =
            """
            SELECT p.status, a.id AS audit_id,
                COALESCE(JSON_CONTAINS(JSON_EXTRACT(a.snapshot_data, '$.form.attachmentInfo'), #{file}), 0) AS attached
            FROM policy_apply p LEFT JOIN policy_audit_log a
              ON a.policy_id = p.id AND a.tenant_key = p.tenant_key AND a.id = #{logId}
            WHERE p.id = #{policyId} AND p.tenant_key = #{tenantKey} AND p.is_deleted = 0
            """;

    @Select(
            """
            SELECT p.status, a.id AS audit_id, COALESCE(JSON_CONTAINS(
                CASE WHEN p.status IN ('DRAFT', 'REJECTED') THEN p.attachment_urls
                     ELSE JSON_EXTRACT(a.snapshot_data, '$.form.attachmentInfo') END, #{file}), 0) AS attached
            FROM policy_apply p LEFT JOIN policy_audit_log a
              ON a.policy_id = p.id AND a.tenant_key = p.tenant_key AND a.version = p.current_version
            WHERE p.id = #{policyId} AND p.tenant_key = #{tenantKey}
              AND p.applicant_id = #{applicantId} AND p.is_deleted = 0
            """)
    Membership current(long policyId, String tenantKey, long applicantId, String file);

    @Select(HISTORY + " AND p.applicant_id = #{applicantId}")
    Membership applicantHistory(
            long policyId, long logId, String tenantKey, long applicantId, String file);

    @Select(
            HISTORY
                    + """
            AND p.status IN ('PENDING', 'APPROVED', 'REJECTED')
            AND EXISTS (SELECT 1 FROM policy_audit_log latest WHERE latest.policy_id = p.id
                AND latest.tenant_key = p.tenant_key AND latest.version = p.current_version)
            """)
    Membership adminHistory(long policyId, long logId, String tenantKey, String file);

    record Membership(String status, Long auditId, boolean attached) {}

    // 已删除申请和历轮快照仍保留附件引用，物理删除必须同时排除两类引用。
    @Select(
            """
            SELECT EXISTS(SELECT 1 FROM policy_apply WHERE JSON_CONTAINS(attachment_urls, #{file}))
                OR EXISTS(SELECT 1 FROM policy_audit_log WHERE JSON_CONTAINS(snapshot_data, #{snapshot}))
            """)
    boolean attachmentReferenced(String file, String snapshot);
}
