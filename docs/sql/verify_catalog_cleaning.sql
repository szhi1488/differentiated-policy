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

-- 清洗后检查最近任务的本轮结果，人工审核结果另保存在来源行。
SELECT job.id, job.brand_name, job.status, COUNT(item.source_id) AS total,
       COUNT(CASE WHEN item.outcome = 'AUTO_PASSED' THEN 1 END) AS auto_passed,
       COUNT(CASE WHEN item.outcome = 'REVIEW_REQUIRED' THEN 1 END) AS review_required,
       COUNT(CASE WHEN item.outcome = 'ERROR' THEN 1 END) AS errors,
       COUNT(CASE WHEN item.outcome = 'SKIPPED' THEN 1 END) AS skipped
FROM (SELECT id, brand_name, status FROM catalog_clean_job ORDER BY id DESC LIMIT 20) job
LEFT JOIN catalog_clean_job_item item ON item.job_id = job.id
GROUP BY job.id, job.brand_name, job.status
ORDER BY job.id DESC;

-- 对照来源、最近审核与商品中心。多条来源可指向同一个 goods_id。
SELECT item.id AS source_id, item.sku_id AS source_sku, item.goods_no AS source_goods_no,
       item.clean_status, item.reviewed_by, item.reviewed_at, item.review_reason,
       g.id AS goods_id, g.sku_id AS catalog_sku, g.product_no, g.goods_name,
       b.name AS brand_name, s.name AS series_name, p.name AS model_name, p.edition,
       g.ram, g.rom, c.color_name, g.catalog_status, g.status AS sales_status
FROM source_goods_item item
JOIN goods g ON g.id = item.goods_id AND g.brand_id = item.catalog_brand_id
JOIN brand b ON b.id = g.brand_id
JOIN series s ON s.id = g.series_id
JOIN spu p ON p.id = g.spu_id
JOIN color_dict c ON c.id = g.color_id
ORDER BY item.id DESC
LIMIT 30;

-- 核对活动唯一索引、幂等唯一索引和明细执行索引。
SHOW INDEX FROM catalog_clean_job WHERE Key_name IN ('uk_clean_job_active', 'uk_clean_job_request');
SHOW INDEX FROM catalog_clean_job_item WHERE Key_name = 'idx_clean_item_pending';

-- 将示例替换为实际任务 ID 或账套，检查清洗任务和待执行明细的执行计划。
EXPLAIN SELECT * FROM catalog_clean_job
WHERE source_key = '示例账套' AND active_slot = 1;
EXPLAIN SELECT source_id, source_version FROM catalog_clean_job_item
WHERE job_id = 1 AND outcome = 'PENDING' ORDER BY source_id LIMIT 100;
