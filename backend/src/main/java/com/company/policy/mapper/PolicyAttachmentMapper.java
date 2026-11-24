package com.company.policy.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PolicyAttachmentMapper {
    record FileReference(String file, String adjustment) {}

    String FORM_FILE =
            "COALESCE(JSON_CONTAINS(JSON_EXTRACT(a.snapshot_data, '$.form.attachmentInfo'), #{reference.file}), 0)";
    String TIME_CHANGE_FILE =
            "COALESCE(JSON_CONTAINS(JSON_EXTRACT(a.snapshot_data, '$.form.goodsForm.timeAdjustment'), #{reference.adjustment}), 0)";
    String DRAFT_FILE =
            "COALESCE(JSON_CONTAINS(p.attachment_urls, #{reference.file}), 0) OR COALESCE(JSON_CONTAINS(JSON_EXTRACT(p.goods_form_json, '$.timeAdjustment'), #{reference.adjustment}), 0)";
    String ADJUSTMENT_FILE =
            "COALESCE(JSON_CONTAINS(JSON_EXTRACT(a.snapshot_data, '$.consent.endAdjustments'), #{reference.adjustment}), 0)";
    String HISTORICAL_FILE = FORM_FILE + " OR " + TIME_CHANGE_FILE + " OR " + ADJUSTMENT_FILE;

    // 保留父申请行，供服务区分申请不可见、轮次不存在和该轮没有此文件。
    String HISTORY =
            """
            SELECT p.status, a.id AS audit_id,
            """
                    + HISTORICAL_FILE
                    + """
              AS attached
            FROM policy_apply p LEFT JOIN policy_audit_log a
              ON a.policy_id = p.id AND a.tenant_key = p.tenant_key AND a.id = #{logId}
            WHERE p.id = #{policyId} AND p.tenant_key = #{tenantKey} AND p.is_deleted = 0
            """;

    @Select(
            """
            SELECT p.status, a.id AS audit_id, CASE WHEN p.status IN ('DRAFT', 'REJECTED') THEN
            """
                    + DRAFT_FILE
                    + " ELSE "
                    + FORM_FILE
                    + " OR "
                    + TIME_CHANGE_FILE
                    + " END OR "
                    + ADJUSTMENT_FILE
                    + """
              AS attached
            FROM policy_apply p LEFT JOIN policy_audit_log a
              ON a.policy_id = p.id AND a.tenant_key = p.tenant_key AND a.version = p.current_version
            WHERE p.id = #{policyId} AND p.tenant_key = #{tenantKey}
              AND p.applicant_id = #{applicantId} AND p.is_deleted = 0
            """)
    Membership current(long policyId, String tenantKey, long applicantId, FileReference reference);

    @Select(HISTORY + " AND p.applicant_id = #{applicantId}")
    Membership applicantHistory(
            long policyId, long logId, String tenantKey, long applicantId, FileReference reference);

    @Select(
            HISTORY
                    + """
            AND p.status IN ('PENDING', 'APPROVED', 'REJECTED')
            AND EXISTS (SELECT 1 FROM policy_audit_log latest WHERE latest.policy_id = p.id
                AND latest.tenant_key = p.tenant_key AND latest.version = p.current_version)
            """)
    Membership adminHistory(long policyId, long logId, String tenantKey, FileReference reference);

    @Select(
            "SELECT p.status, a.id AS audit_id, "
                    + HISTORICAL_FILE
                    + " AS attached "
                    + PolicyReviewRoundMapper.VISIBLE_ROUND)
    Membership reviewRound(
            String tenant, long userId, long id, int version, FileReference reference);

    record Membership(String status, Long auditId, boolean attached) {}

    // 已删除申请和历轮快照仍保留附件引用，物理删除必须同时排除两类引用。
    @Select(
            "SELECT EXISTS(SELECT 1 FROM policy_apply p WHERE "
                    + DRAFT_FILE
                    + ") OR EXISTS(SELECT 1 FROM policy_audit_log a WHERE "
                    + HISTORICAL_FILE
                    + ")")
    boolean attachmentReferenced(@Param("reference") FileReference reference);
}
