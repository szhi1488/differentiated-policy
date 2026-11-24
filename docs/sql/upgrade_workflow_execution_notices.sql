-- F 阶段已有库增量升级。先停写、备份，在 Navicat 同一连接执行。
-- 前置：已有 E 阶段投递表，核对表定义包含 SEND_CARD、UPDATE_CARD 和两条轮次外键。
SELECT DATABASE() AS target_database;
SHOW CREATE TABLE policy_review_delivery;
CREATE TEMPORARY TABLE before_execution_delivery AS
SELECT id,tenant_key,policy_id,version,event_key,action_type,recipient_key,payload_json,
       delivery_status,receipt_id,attempts,next_attempt_at,lease_owner,lease_until,created_at,delivered_at,last_error
FROM policy_review_delivery;
SELECT COUNT(*) INTO @execution_delivery_rows_before FROM before_execution_delivery;

ALTER TABLE policy_review_delivery
  DROP FOREIGN KEY fk_review_delivery_round, DROP FOREIGN KEY fk_review_delivery_member;
ALTER TABLE policy_review_delivery
  MODIFY policy_id BIGINT DEFAULT NULL COMMENT '审核投递所属申请，业务提醒为空',
  MODIFY version INT UNSIGNED DEFAULT NULL COMMENT '审核提交轮次，业务提醒为空',
  ADD notice_key VARCHAR(128) GENERATED ALWAYS AS (CASE WHEN policy_id IS NULL THEN event_key ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_delivery_notice (tenant_key, notice_key),
  ADD CONSTRAINT ck_delivery_subject CHECK ((policy_id IS NOT NULL AND version IS NOT NULL)
    OR (policy_id IS NULL AND version IS NULL AND action_type='GROUP_MESSAGE' AND recipient_key=0)),
  ADD CONSTRAINT fk_review_delivery_round FOREIGN KEY (policy_id, version, tenant_key)
    REFERENCES policy_review_round (policy_id, version, tenant_key),
  ADD CONSTRAINT fk_review_delivery_member FOREIGN KEY (recipient_member_id, policy_id, version, tenant_key)
    REFERENCES policy_review_member (id, policy_id, version, tenant_key);

-- 预期两项为 0；旧消息、待办、指派、租约及人工核对记录均保留。
SELECT (SELECT CAST(COUNT(*) AS SIGNED) FROM policy_review_delivery)-CAST(@execution_delivery_rows_before AS SIGNED) AS row_difference,
  (SELECT COUNT(*) FROM before_execution_delivery b LEFT JOIN policy_review_delivery d ON d.id=b.id
   WHERE d.id IS NULL OR d.notice_key IS NOT NULL OR NOT (
     d.policy_id <=> b.policy_id AND d.version <=> b.version AND d.tenant_key=b.tenant_key AND d.event_key=b.event_key
     AND d.action_type=b.action_type AND d.recipient_key=b.recipient_key AND d.payload_json=b.payload_json
     AND d.delivery_status=b.delivery_status AND d.receipt_id <=> b.receipt_id AND d.attempts=b.attempts
     AND d.next_attempt_at <=> b.next_attempt_at AND d.lease_owner <=> b.lease_owner AND d.lease_until <=> b.lease_until
     AND d.created_at=b.created_at AND d.delivered_at <=> b.delivered_at AND d.last_error <=> b.last_error)) AS changed_rows;
SHOW CREATE TABLE policy_review_delivery;
