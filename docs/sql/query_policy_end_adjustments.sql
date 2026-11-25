-- MySQL 8.0.16+。只读核对；在 Navicat 填企业与原单 ID，例如 ZC-000024 填 24。
SET @policy_tenant = '替换为本企业的 tenant_key';
SET @original_policy = 24;

SELECT p.id, p.current_version, p.record_version, p.end_time AS current_end,
       JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_data, '$.form.endTime')) AS first_approved_end
FROM policy_apply p JOIN policy_audit_log a ON a.policy_id=p.id AND a.tenant_key=p.tenant_key AND a.version=p.current_version
WHERE p.id=@original_policy AND p.tenant_key=@policy_tenant AND p.is_deleted=0 AND a.audit_status='APPROVED';

SELECT c.id,c.direction,c.previous_end,c.requested_end,c.actual_end,c.effective_at,c.changed_at,
       c.reason,c.actor_name,c.attachment_json,c.source_policy_id,c.source_version,
       c.after_record_version,c.consent_json,d.delivery_status
FROM policy_time_change c JOIN policy_apply p ON p.id=c.policy_id AND p.tenant_key=c.tenant_key AND p.is_deleted=0
LEFT JOIN policy_review_delivery d ON d.tenant_key=c.tenant_key
  AND d.notice_key=CONCAT('P/',c.policy_id,'/',IF(c.direction='EXTEND','EXTENDED','SHORTENED'),'/',c.id)
WHERE c.tenant_key=@policy_tenant AND c.policy_id=@original_policy ORDER BY c.id;

-- 每条记录衔接上一条的实际截止。最新记录与主表一致，目标审核记录与原单、获批轮次一致。均预期 0 行。
WITH events AS (
  SELECT c.*, LAG(c.actual_end) OVER (PARTITION BY c.policy_id ORDER BY c.after_record_version) AS prior_end,
         ROW_NUMBER() OVER (PARTITION BY c.policy_id ORDER BY c.after_record_version DESC) AS newest
  FROM policy_time_change c WHERE c.tenant_key=@policy_tenant
)
SELECT c.id,c.policy_id FROM events c
JOIN policy_apply p ON p.id=c.policy_id AND p.tenant_key=c.tenant_key AND p.is_deleted=0
JOIN policy_audit_log a ON a.id=c.audit_log_id
WHERE (c.prior_end IS NOT NULL AND c.prior_end<>c.previous_end)
  OR (c.newest=1 AND p.end_time<>c.actual_end) OR a.policy_id<>p.id OR a.version<>c.policy_version;

-- 目标库的行数和分布影响实际执行计划。单号查历史可使用带 policy_id 前缀的索引。
EXPLAIN SELECT id, previous_end, actual_end FROM policy_time_change
WHERE policy_id=@original_policy AND changed_at<=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR) ORDER BY changed_at,id;
