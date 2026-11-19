-- 商品清洗直接排除，适用于项目十四表基线，MySQL 8.0.16+。
-- 已有库请停止后端、备份，在 Navicat 选中目标库后按段执行。空库使用最新建表脚本。
-- 两条 ALTER 各自提交。若第二条失败，核实第一条的结果，仅继续未完成的步骤。
SET NAMES utf8mb4;

-- 1. 核对库名与现有约束。旧约束要求排除必须有审核人，任务成员 outcome 尚不含 EXCLUDED。
SELECT DATABASE() AS target_database, VERSION() AS mysql_version;
SHOW CREATE TABLE source_goods_item;
SHOW CREATE TABLE catalog_clean_job_item;

-- 2. 自动排除保存任务、命中证据、原因与清洗时间，人工排除继续保存实际审核人。
ALTER TABLE source_goods_item
  DROP CHECK ck_source_clean_excluded,
  ADD CONSTRAINT `ck_source_clean_excluded` CHECK (
    (`clean_status` <> 'EXCLUDED' AND `mapping_status` <> 'EXCLUDED') OR
    (`clean_status` = 'EXCLUDED' AND `mapping_status` = 'EXCLUDED' AND `cleaned_at` IS NOT NULL AND (
      (`reviewed_by` IS NOT NULL AND `reviewed_at` IS NOT NULL AND `review_reason` IS NOT NULL AND CHAR_LENGTH(TRIM(`review_reason`)) > 0) OR
      (`reviewed_by` IS NULL AND `reviewed_at` IS NULL AND `review_reason` IS NULL AND `clean_job_id` IS NOT NULL
       AND `clean_candidate` IS NOT NULL AND `clean_error_code` IS NULL AND `mapping_remark` IS NOT NULL AND CHAR_LENGTH(TRIM(`mapping_remark`)) > 0)
    ))
  );

-- 3. 允许本轮任务成员记录自动排除。
ALTER TABLE catalog_clean_job_item
  DROP CHECK ck_clean_item_outcome,
  ADD CONSTRAINT `ck_clean_item_outcome` CHECK (`outcome` IN ('PENDING', 'AUTO_PASSED', 'MANUAL_PASSED', 'REVIEW_REQUIRED', 'EXCLUDED', 'ERROR', 'SKIPPED'));

-- 4. 预期返回两项已启用的约束。核对表达式后启动新版后端并发起清洗。
SELECT t.TABLE_NAME, t.CONSTRAINT_NAME, t.ENFORCED, c.CHECK_CLAUSE
FROM information_schema.TABLE_CONSTRAINTS AS t
JOIN information_schema.CHECK_CONSTRAINTS AS c
  ON c.CONSTRAINT_SCHEMA = t.CONSTRAINT_SCHEMA AND c.CONSTRAINT_NAME = t.CONSTRAINT_NAME
WHERE t.CONSTRAINT_SCHEMA = DATABASE() AND t.CONSTRAINT_TYPE = 'CHECK'
  AND ((t.TABLE_NAME = 'source_goods_item' AND t.CONSTRAINT_NAME = 'ck_source_clean_excluded')
    OR (t.TABLE_NAME = 'catalog_clean_job_item' AND t.CONSTRAINT_NAME = 'ck_clean_item_outcome'));

-- 5. 发起清洗后按任务核对自动排除数，具体来源在清洗页「已排除」查看。
SELECT job_id, COUNT(*) AS auto_excluded
FROM catalog_clean_job_item WHERE outcome = 'EXCLUDED' GROUP BY job_id ORDER BY job_id DESC;

-- 升级不回写已有业务数据。需要整体回退时在停写期间恢复升级前备份，配套使用旧版后端。
