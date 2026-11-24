-- 已有 B 阶段二十二表库，MySQL 8.0.16+。停止所有后端写入并备份，在 Navicat 同一连接分段执行。
-- 新空库已包含本约束。先核对选库、现有约束与记录数量；如已有下方五种动作，跳到核对步骤。
SELECT DATABASE() AS target_database, VERSION() AS mysql_version;
SHOW CREATE TABLE policy_review_delivery;
SELECT COUNT(*) INTO @delivery_before FROM policy_review_delivery;

-- 一条 ALTER 原子替换同一检查约束；旧动作和收件人键均合法。
ALTER TABLE policy_review_delivery
  DROP CHECK ck_review_delivery_recipient,
  ADD CONSTRAINT ck_review_delivery_recipient CHECK ((action_type = 'GROUP_MESSAGE' AND recipient_key = 0)
    OR (action_type IN ('SEND_CARD','UPDATE_CARD','CREATE_TASK','CLOSE_TASK') AND recipient_key > 0));

-- 核对记录数相等、约束 ENFORCED 为 YES，且包含两种个人卡片动作。
SELECT @delivery_before AS deliveries_before, COUNT(*) AS deliveries_after FROM policy_review_delivery;
SELECT t.ENFORCED, c.CHECK_CLAUSE
FROM information_schema.TABLE_CONSTRAINTS t
JOIN information_schema.CHECK_CONSTRAINTS c USING (CONSTRAINT_SCHEMA, CONSTRAINT_NAME)
WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME = 'policy_review_delivery'
  AND t.CONSTRAINT_NAME = 'ck_review_delivery_recipient' AND t.CONSTRAINT_TYPE = 'CHECK';
