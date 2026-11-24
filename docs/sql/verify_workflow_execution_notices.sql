-- 在 Navicat 选择目标测试库。结构检查可在启用前运行，以下业务结果须填写同一测试企业再读取。
SHOW CREATE TABLE policy_review_delivery;
SHOW INDEX FROM policy_review_delivery WHERE Key_name IN ('uk_delivery_notice','uk_review_delivery_event','idx_review_delivery_due');
SET @trial_tenant = NULL; -- 替换为已核对的测试企业标识。
SELECT id,event_key,JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.kind')) AS kind,
       JSON_EXTRACT(payload_json,'$.policy') AS target_policy,JSON_EXTRACT(payload_json,'$.job') AS job_id,
       JSON_EXTRACT(payload_json,'$.unreachableUserIds') AS unreachable_users,
       delivery_status,created_at,next_attempt_at,delivered_at,attempts,receipt_id,last_error
FROM policy_review_delivery WHERE tenant_key=@trial_tenant AND notice_key IS NOT NULL ORDER BY id DESC LIMIT 100;

-- 完整试点时在同一窗口填写申请与轮次，核对两组配置、实际指派、唯一签署人及每项回执。
SET @trial_policy = NULL, @trial_round = NULL;
SELECT s.group_code,u.name,u.department,u.role_type,u.is_deleted
FROM policy_review_setting_member s JOIN user_info u ON u.tenant_key=s.tenant_key AND u.id=s.user_id
WHERE s.tenant_key=@trial_tenant ORDER BY s.group_code,s.user_id;
SELECT r.stage,m.group_code,m.display_name,m.member_status,m.task_guid
FROM policy_review_round r JOIN policy_review_member m
  ON m.policy_id=r.policy_id AND m.version=r.version AND m.tenant_key=r.tenant_key
WHERE r.tenant_key=@trial_tenant AND r.policy_id=@trial_policy AND r.version=@trial_round ORDER BY m.id;
SELECT group_code,decision,member_id FROM policy_review_decision
WHERE tenant_key=@trial_tenant AND policy_id=@trial_policy AND version=@trial_round;
SELECT event_key,action_type,recipient_key,delivery_status,receipt_id,last_error FROM policy_review_delivery
WHERE tenant_key=@trial_tenant AND policy_id=@trial_policy AND version=@trial_round ORDER BY id;

-- 预期零行。所有独立提醒应是群投递，其幂等键在企业内唯一。
SELECT notice_key,COUNT(*) AS row_count
FROM policy_review_delivery WHERE tenant_key=@trial_tenant AND notice_key IS NOT NULL
GROUP BY notice_key HAVING COUNT(*)<>1;
SELECT id FROM policy_review_delivery WHERE tenant_key=@trial_tenant AND notice_key IS NOT NULL
  AND (policy_id IS NOT NULL OR version IS NOT NULL OR recipient_key<>0 OR action_type<>'GROUP_MESSAGE');
SELECT id FROM policy_review_delivery WHERE tenant_key=@trial_tenant AND delivery_status='SENT'
  AND (receipt_id IS NULL OR delivered_at IS NULL);
EXPLAIN SELECT id FROM policy_review_delivery WHERE tenant_key=@trial_tenant AND notice_key='CLEAN/1';
-- 在有到期登记的测试库暂停投递后，可在两个 Navicat 查询窗口分别执行下列事务。
-- 第一个窗口保持事务，两窗口应取得不同 ID，或第二个返回空集。两个窗口最后分别回滚。
-- START TRANSACTION;
-- SELECT id FROM policy_review_delivery WHERE tenant_key=@trial_tenant AND notice_key IS NOT NULL
--   AND delivery_status='PENDING' AND next_attempt_at<=NOW() ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED;
-- ROLLBACK;
