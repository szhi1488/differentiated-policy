-- 在 Navicat 选中目标库后运行。建表后和首次导入后均可执行。
-- 仅查询，不插入、不更新业务数据。

SELECT VERSION() AS mysql_version, DATABASE() AS selected_database;

-- 预期九张业务表。
SELECT TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN (
    'user_info', 'catalog_variant', 'catalog_variant_color', 'goods_sync_job',
    'source_goods_item', 'catalog_operation_log', 'policy_master', 'policy_detail', 'policy_audit_log'
  )
ORDER BY TABLE_NAME;

-- 预期三行，依次为 tenant_key、source_key、sku_id，non_unique 均为 0。
SELECT SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'source_goods_item' AND INDEX_NAME = 'uk_source_sku'
ORDER BY SEQ_IN_INDEX;

-- 同步按租户、账套、SKU 精确锁定的访问路径，预期可用 uk_source_sku。
-- 有数据时可将下列示例替换为实际租户、账套、SKU。
EXPLAIN SELECT id
FROM source_goods_item
WHERE tenant_key = '示例租户' AND source_key = '示例账套' AND sku_id = '123';

-- 预期无结果。检查 SKU 格式、重复及任务范围。
SELECT id FROM source_goods_item WHERE sku_id NOT REGEXP '^[1-9][0-9]{0,18}$';

SELECT tenant_key, source_key, sku_id, COUNT(*) AS copies
FROM source_goods_item
GROUP BY tenant_key, source_key, sku_id
HAVING COUNT(*) > 1;

-- 并发导入后预期无结果：暂停也占活动槽。
SELECT tenant_key, source_key, COUNT(*) AS active_jobs
FROM goods_sync_job
WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED')
GROUP BY tenant_key, source_key
HAVING COUNT(*) > 1;

SELECT id FROM goods_sync_job
WHERE NOT (
     (sync_mode = 'FULL' AND sync_date IS NULL AND sync_phase = 'FULL')
  OR (sync_mode = 'CREATED' AND sync_date IS NOT NULL AND sync_phase = 'CREATED')
  OR (sync_mode = 'MODIFIED' AND sync_date IS NOT NULL AND sync_phase IN ('GOODS_MODIFIED', 'SKU_MODIFIED'))
) OR content_count > read_count;

-- 导入完成后可核对两个品牌的来源及新字段覆盖情况。
SELECT brand_name, COUNT(*) AS sku_count,
       COUNT(NULLIF(TRIM(cate_full_name), '')) AS with_category_path,
       COUNT(gmt_modified) AS with_sku_modified_at,
       COUNT(goods_gmt_modified) AS with_goods_modified_at,
       SUM(CASE WHEN is_blockup = 1 THEN 1 ELSE 0 END) AS blocked_skus,
       SUM(CASE WHEN is_package_good = 1 THEN 1 ELSE 0 END) AS package_skus
FROM source_goods_item
GROUP BY brand_name;

SELECT id, brand_name, sync_mode, sync_date, sync_phase, status, clean_status, read_count, content_count
FROM goods_sync_job
ORDER BY id DESC
LIMIT 20;
