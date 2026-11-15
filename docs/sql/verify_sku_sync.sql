-- 在 Navicat 选中目标库后运行。建表后和首次导入后均可执行。
-- 仅查询，不插入、不更新业务数据。

SELECT VERSION() AS mysql_version, DATABASE() AS selected_database;

-- 预期十四张业务表。
SELECT TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN (
    'user_info', 'brand', 'series', 'spu', 'color_dict', 'goods', 'goods_sync_job',
    'source_goods_item', 'catalog_clean_job', 'catalog_clean_job_item', 'catalog_operation_log', 'policy_apply', 'policy_goods_detail', 'policy_audit_log'
  )
ORDER BY TABLE_NAME;

-- 旧目录应无结果。规格组合只建普通索引，允许不同原始 SKU 有相同配置和颜色。
SELECT TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('catalog_variant', 'catalog_variant_color');

SELECT SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'goods' AND INDEX_NAME = 'idx_goods_spu_specs'
ORDER BY SEQ_IN_INDEX;

-- 公司商品相关表和政策明细的租户列检查预期无结果。
SELECT TABLE_NAME, COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'tenant_key'
  AND TABLE_NAME IN ('brand', 'series', 'spu', 'color_dict', 'goods', 'goods_sync_job',
                     'source_goods_item', 'catalog_clean_job', 'catalog_clean_job_item',
                     'catalog_operation_log', 'policy_goods_detail');

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
  'fk_policy_goods_spu', 'fk_policy_goods_sku'
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
FROM policy_goods_detail d
LEFT JOIN goods g ON g.id = d.goods_id
  AND g.spu_id = d.spu_id AND g.ram = d.ram AND g.rom = d.rom
WHERE d.goods_id IS NOT NULL AND g.id IS NULL;

-- 有数据时替换示例，检查按配置查找颜色商品的访问路径。
EXPLAIN SELECT id, color_id
FROM goods
WHERE spu_id = 1 AND ram = '16GB'
  AND rom = '512GB';

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

-- 原始 SKU 契约：sku_id 是可空 VARCHAR(19) ASCII，非空值唯一，来源 goods_id 也唯一。
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, CHARACTER_SET_NAME, COLLATION_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goods' AND COLUMN_NAME = 'sku_id';

SELECT TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'goods' AND INDEX_NAME IN ('uk_goods_sku', 'uk_goods_spu_specs'))
    OR (TABLE_NAME = 'source_goods_item' AND INDEX_NAME = 'uk_source_goods'))
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;
-- uk_goods_sku / uk_source_goods 各一行、NON_UNIQUE=0；旧 uk_goods_spu_specs 不应存在。

-- 下列四项预期无结果。检查一对一、原始 SKU 相同、格式及无来源商品不伪造 SKU。
SELECT goods_id, COUNT(*) AS sources FROM source_goods_item
WHERE goods_id IS NOT NULL GROUP BY goods_id HAVING COUNT(*) > 1;

SELECT s.id, s.sku_id AS source_sku, g.id AS goods_id, g.sku_id AS goods_sku
FROM source_goods_item s JOIN goods g ON g.id = s.goods_id
WHERE NOT (s.sku_id <=> g.sku_id);

SELECT id, sku_id FROM goods
WHERE sku_id IS NOT NULL AND sku_id NOT REGEXP '^[1-9][0-9]{0,18}$';

SELECT g.id, g.sku_id FROM goods g
WHERE g.sku_id IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM source_goods_item s WHERE s.goods_id = g.id AND s.sku_id = g.sku_id
);

EXPLAIN SELECT id FROM goods WHERE sku_id = '123' FOR UPDATE;
EXPLAIN SELECT sku_id FROM source_goods_item WHERE goods_id = 1;
-- 预期分别使用 uk_goods_sku、uk_source_goods（空库可能被优化为常量或空结果）。

-- 并发验证由用户在隔离测试库的两个 Navicat 连接分段执行；替换为实际已映射来源 ID。
-- 连接 A：START TRANSACTION;
-- SELECT id, goods_id FROM source_goods_item WHERE id = <实际来源ID> FOR UPDATE;
-- 连接 B：START TRANSACTION;
-- SELECT id, goods_id FROM source_goods_item WHERE id = <同一来源ID> FOR UPDATE;
-- 预期 B 等待 A。A 执行 ROLLBACK 后 B 才取得行锁，随后 B 也执行 ROLLBACK。
-- API 另核对两次处理同一 SKU 后 goods 仍只有一条；不同 SKU 同规格处理后分别生成商品。
