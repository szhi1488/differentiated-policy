-- MySQL 8.0.16+。只读查询，每行对应发起单某轮正式提交的一项目标调整。
-- 填入本企业 tenant_key。按发起单查填 20、NULL，按目标旧单反查填 NULL、17。
WITH filters AS (
    SELECT '替换为本企业的 tenant_key' AS tenant_key,
           20 AS request_id,
           CAST(NULL AS UNSIGNED) AS target_id
),
rounds AS (
    SELECT p.id AS request_id, p.tenant_key, p.status, p.current_version,
           p.extension_source_id, a.id AS log_id, a.version, a.audit_status,
           a.created_at, a.audit_time, a.snapshot_data
    FROM filters AS f
    JOIN policy_apply AS p ON p.tenant_key = f.tenant_key AND p.is_deleted = 0
    JOIN policy_audit_log AS a ON a.policy_id = p.id AND a.tenant_key = p.tenant_key
    WHERE f.request_id IS NULL OR p.id = f.request_id
),
change_lists AS (
    -- 随单调整和独立缩短在发起单的确认清单内，每个目标只展开一次。
    SELECT log_id, JSON_EXTRACT(snapshot_data, '$.consent.endAdjustments') AS items
    FROM rounds
    WHERE JSON_TYPE(JSON_EXTRACT(snapshot_data, '$.consent.endAdjustments')) = 'ARRAY'
    UNION ALL
    -- 独立延期的自身调整在表单内。批量产生的子单已在发起单清单中列过。
    SELECT log_id, JSON_ARRAY(JSON_EXTRACT(snapshot_data, '$.form.goodsForm.timeAdjustment')) AS items
    FROM rounds
    WHERE extension_source_id IS NOT NULL
      AND JSON_TYPE(JSON_EXTRACT(snapshot_data, '$.form.goodsForm.timeAdjustment')) = 'OBJECT'
      AND JSON_EXTRACT(snapshot_data, '$.consent.batchPolicyKey') IS NULL
)
SELECT r.request_id, r.version AS request_version, r.audit_status,
       r.created_at AS submitted_at, r.audit_time AS reviewed_at,
       c.target_id, c.target_audit_id, c.previous_end, c.requested_end,
       CASE WHEN c.requested_end < c.previous_end THEN 'SHORTEN'
            WHEN c.requested_end > c.previous_end THEN 'EXTEND'
       END AS adjustment_direction,
       c.reason,
       CASE WHEN c.requested_end < c.previous_end
                  AND r.status = 'APPROVED' AND r.version = r.current_version
                  AND r.audit_status = 'APPROVED'
                  AND r.audit_time <= DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)
            THEN GREATEST(STR_TO_DATE(LEFT(c.requested_end, 19), '%Y-%m-%dT%H:%i:%s'),
                          DATE_SUB(r.audit_time, INTERVAL 1 SECOND))
       END AS approved_through
FROM filters AS f
JOIN rounds AS r ON r.tenant_key = f.tenant_key
JOIN change_lists AS cl ON cl.log_id = r.log_id
CROSS JOIN JSON_TABLE(cl.items, '$[*]' COLUMNS (
    target_id BIGINT PATH '$.target.policyId',
    target_audit_id BIGINT PATH '$.target.auditLogId',
    previous_end VARCHAR(32) PATH '$.target.previousEnd',
    requested_end VARCHAR(32) PATH '$.endTime',
    reason VARCHAR(512) PATH '$.reason'
)) AS c
JOIN policy_apply AS old_policy ON old_policy.id = c.target_id
    AND old_policy.tenant_key = r.tenant_key AND old_policy.is_deleted = 0
WHERE f.target_id IS NULL OR c.target_id = f.target_id
ORDER BY r.created_at DESC, r.log_id DESC, c.target_id;
