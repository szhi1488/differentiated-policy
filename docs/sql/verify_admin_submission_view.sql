-- 用途：在 Navicat 核对管理员查询始终使用最近正式提交，以及相应的 MySQL 查询计划。
-- 前置：最新十四表基线；填写实际企业标识和已有正式提交的申请 ID。
-- 本脚本只读业务数据，仅设置当前会话变量，不修改表结构或业务记录。
-- 顺序：1. 填参数；2. 查看主表与快照；3. 执行计数和 EXPLAIN。
-- 在“驳回后修改保存、尚未重提”和“重提成功后”分别执行。
SET @policy_id = 0;
SET @tenant_key = '请填写企业租户标识';
SET @channel_type = 'RETAIL';
SET @platform_code = 'PDD';

-- 保存后主表可以与快照不同，这是合法编辑状态；管理员应展示 submitted_* 的值。
-- 重提后 current_version 增加，submitted_* 应切换到新的正式提交。
SELECT p.id, p.status, p.record_version, p.current_version,
       p.channel_type AS editing_channel, p.platforms AS editing_platforms,
       p.write_off_type AS editing_verify_type, p.scene AS editing_scene,
       a.id AS audit_id, a.created_at AS submitted_at,
       JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_data, '$.form.perspective')) AS submitted_channel,
       JSON_EXTRACT(a.snapshot_data, '$.form.selectedPlatforms') AS submitted_platforms,
       NULLIF(JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_data, '$.form.verifyType')), 'null') AS submitted_verify_type,
       NULLIF(JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_data, '$.form.sceneType')), 'null') AS submitted_scene
FROM policy_apply p
JOIN policy_audit_log a
  ON a.policy_id = p.id AND a.tenant_key = p.tenant_key AND a.version = p.current_version
WHERE p.id = @policy_id AND p.tenant_key = @tenant_key AND p.is_deleted = 0
  AND p.status IN ('PENDING', 'APPROVED', 'REJECTED');

-- 对该申请按原提交渠道及平台筛选：重提前应命中 REJECTED=1；重提改平台后原条件应无结果。
-- 换为重提后的渠道及平台，应命中 PENDING=1。
SELECT p.status, COUNT(*) AS total
FROM policy_apply p
JOIN policy_audit_log a
  ON a.policy_id = p.id AND a.tenant_key = p.tenant_key AND a.version = p.current_version
WHERE p.id = @policy_id AND p.tenant_key = @tenant_key AND p.is_deleted = 0
  AND p.status IN ('PENDING', 'APPROVED', 'REJECTED')
  AND JSON_UNQUOTE(JSON_EXTRACT(a.snapshot_data, '$.form.perspective')) = @channel_type
  AND JSON_CONTAINS(JSON_EXTRACT(a.snapshot_data, '$.form.selectedPlatforms'), JSON_QUOTE(@platform_code))
GROUP BY p.status;

-- 管理员列表代表查询。核对实际行数、按租户和状态限制主表、按每单每轮唯一键关联日志。
-- JSON 业务条件需从快照计算；是否排序及扫描成本以目标库真实 EXPLAIN 为准。
EXPLAIN
SELECT p.id, p.status, p.record_version, p.current_version, a.applicant_name,
       a.channel_type, a.platforms, a.write_off_type, a.scene, a.created_at
FROM policy_apply p
JOIN (
  SELECT id, policy_id, tenant_key, version, created_at,
         NULLIF(JSON_UNQUOTE(JSON_EXTRACT(snapshot_data, '$.applicantName')), 'null') AS applicant_name,
         NULLIF(JSON_UNQUOTE(JSON_EXTRACT(snapshot_data, '$.form.perspective')), 'null') AS channel_type,
         JSON_EXTRACT(snapshot_data, '$.form.selectedPlatforms') AS platforms,
         NULLIF(JSON_UNQUOTE(JSON_EXTRACT(snapshot_data, '$.form.verifyType')), 'null') AS write_off_type,
         NULLIF(JSON_UNQUOTE(JSON_EXTRACT(snapshot_data, '$.form.sceneType')), 'null') AS scene
  FROM policy_audit_log
) a ON a.policy_id = p.id AND a.tenant_key = p.tenant_key AND a.version = p.current_version
WHERE p.tenant_key = @tenant_key AND p.is_deleted = 0
  AND p.status IN ('PENDING', 'APPROVED', 'REJECTED')
  AND a.channel_type = @channel_type
  AND JSON_CONTAINS(a.platforms, JSON_QUOTE(@platform_code))
ORDER BY a.created_at DESC, a.id DESC
LIMIT 10 OFFSET 0;
