-- 在 Navicat 选中目标库后运行。建表后和首次导入后均可执行。
-- 仅查询，不插入、不更新业务数据。

SELECT VERSION() AS mysql_version, DATABASE() AS selected_database;

-- 预期十四张业务表。
SELECT TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN (
    'user_info', 'brand', 'series', 'spu', 'color_dict', 'goods', 'goods_sync_job',
    'source_goods_item', 'catalog_clean_job', 'catalog_clean_job_item', 'catalog_operation_log', 'policy_master', 'policy_detail', 'policy_audit_log'
  )
ORDER BY TABLE_NAME;

-- 旧目录应无结果。新商品唯一键预期为 spu_id、ram、rom、color_id。
SELECT TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('catalog_variant', 'catalog_variant_color');

SELECT SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'goods' AND INDEX_NAME = 'uk_goods_spu_specs'
ORDER BY SEQ_IN_INDEX;

-- 公司商品相关表和政策明细的租户列检查预期无结果。
SELECT TABLE_NAME, COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'tenant_key'
  AND TABLE_NAME IN ('brand', 'series', 'spu', 'color_dict', 'goods', 'goods_sync_job',
                     'source_goods_item', 'catalog_clean_job', 'catalog_clean_job_item',
                     'catalog_operation_log', 'policy_detail');

-- 旧品牌字段和标准商品编辑版本检查预期无结果。
SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND (
  (TABLE_NAME = 'brand' AND COLUMN_NAME = 'source_brand_name')
  OR (TABLE_NAME = 'goods' AND COLUMN_NAME = 'record_version')
);

-- 颜色表应仅有 id 和 color_name，颜色名称唯一键应仅含 color_name。
SHOW COLUMNS FROM color_dict;
SHOW INDEX FROM color_dict WHERE Key_name = 'uk_color_dict_name';

-- 核对来源品牌、商品颜色及政策配置外键。
SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
FROM information_schema.KEY_COLUMN_USAGE
WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME IN (
  'fk_source_catalog_brand', 'fk_source_goods', 'fk_goods_color',
  'fk_detail_spu', 'fk_detail_goods'
)
ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION;

-- 下列关联检查预期无结果。
SELECT item.id
FROM source_goods_item item
LEFT JOIN brand b ON b.id = item.catalog_brand_id
  AND b.name = item.brand_name
LEFT JOIN goods g ON g.id = item.goods_id
  AND g.brand_id = item.catalog_brand_id
WHERE item.mapping_status = 'MAPPED' AND (b.id IS NULL OR g.id IS NULL);

SELECT g.id
FROM goods g
LEFT JOIN color_dict c ON c.id = g.color_id
WHERE c.id IS NULL;

SELECT d.id
FROM policy_detail d
LEFT JOIN goods g ON g.id = d.goods_id
  AND g.spu_id = d.spu_id AND g.ram = d.ram AND g.rom = d.rom
WHERE d.goods_id IS NOT NULL AND g.id IS NULL;

-- 有数据时替换示例，检查按配置查找颜色商品的访问路径。
EXPLAIN SELECT id, color_id
FROM goods
WHERE spu_id = 1 AND ram = '16GB'
  AND rom = '512GB' AND catalog_status = 'ACTIVE';

-- 预期一行，列为 sku_id，non_unique 为 0。
SELECT SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'source_goods_item' AND INDEX_NAME = 'uk_source_sku'
ORDER BY SEQ_IN_INDEX;

-- 同步按 SKU 精确锁定，预期可用 uk_source_sku。有数据时可替换为实际 SKU。
EXPLAIN SELECT id
FROM source_goods_item
WHERE sku_id = '123';

-- 预期无结果。检查 SKU 格式、重复及任务范围。
SELECT id FROM source_goods_item WHERE sku_id NOT REGEXP '^[1-9][0-9]{0,18}$';

SELECT sku_id, COUNT(*) AS copies
FROM source_goods_item
GROUP BY sku_id
HAVING COUNT(*) > 1;

-- 并发导入后预期无结果：暂停也占活动槽。
SELECT source_key, COUNT(*) AS active_jobs
FROM goods_sync_job
WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED')
GROUP BY source_key
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

SELECT id, brand_name, sync_mode, sync_date, sync_phase, status, read_count, content_count
FROM goods_sync_job
ORDER BY id DESC
LIMIT 20;
