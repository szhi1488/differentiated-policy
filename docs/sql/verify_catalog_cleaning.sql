-- 在 Navicat 选中已建立十四表基线的数据库执行。以下查询只读。
SELECT VERSION() AS mysql_version, DATABASE() AS selected_database;

-- 应有两张独立清洗表。以下旧版规则和清洗历史字段应无结果。
SELECT table_name FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name IN ('catalog_clean_job', 'catalog_clean_job_item');
SELECT table_name, column_name FROM information_schema.columns
WHERE table_schema = DATABASE() AND (
  (table_name = 'goods_sync_job' AND column_name IN ('clean_status', 'clean_rule_version'))
  OR (table_name = 'source_goods_item' AND column_name = 'clean_rule_version')
  OR (table_name = 'catalog_operation_log' AND column_name = 'rule_version')
  OR (table_name = 'catalog_clean_job' AND column_name = 'legacy')
);

-- 商品操作记录仅用于导入和建档日。预期无清洗、审核或目录操作记录。
SELECT id FROM catalog_operation_log
WHERE NOT (
  (entity_type = 'SOURCE_GOODS_ITEM' AND actor_type = 'SYSTEM' AND action_type IN ('CREATE', 'UPDATE'))
  OR (entity_type = 'SYNC_JOB' AND actor_type = 'USER' AND action_type IN ('START', 'PAUSE', 'RESUME', 'CANCEL'))
);

-- 下列完整性检查预期均无结果。
SELECT item.id FROM source_goods_item item
LEFT JOIN catalog_clean_job job ON job.id = item.clean_job_id
  AND job.source_key = item.source_key AND job.brand_name = item.brand_name
WHERE item.clean_job_id IS NOT NULL AND job.id IS NULL;

SELECT clean_item.job_id, clean_item.source_id FROM catalog_clean_job_item clean_item
JOIN catalog_clean_job job ON job.id = clean_item.job_id
JOIN source_goods_item item ON item.id = clean_item.source_id
WHERE job.source_key <> item.source_key OR job.brand_name <> item.brand_name
   OR (job.target_source_id IS NOT NULL AND job.target_source_id <> clean_item.source_id)
   OR (job.status <> 'RUNNING' AND clean_item.outcome = 'PENDING');

SELECT source_key, COUNT(*) AS active_jobs
FROM catalog_clean_job WHERE status = 'RUNNING'
GROUP BY source_key HAVING COUNT(*) > 1;

-- 核对活动唯一索引、幂等唯一索引和明细执行索引。
SHOW INDEX FROM catalog_clean_job WHERE Key_name IN ('uk_clean_job_active', 'uk_clean_job_request');
SHOW INDEX FROM catalog_clean_job_item WHERE Key_name = 'idx_clean_item_pending';

-- 将示例替换为实际任务 ID 或账套，检查清洗任务和待执行明细的执行计划。
EXPLAIN SELECT * FROM catalog_clean_job
WHERE source_key = '示例账套' AND active_slot = 1;
EXPLAIN SELECT source_id, source_version FROM catalog_clean_job_item
WHERE job_id = 1 AND outcome = 'PENDING' ORDER BY source_id LIMIT 100;
