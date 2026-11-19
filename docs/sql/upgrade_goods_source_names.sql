-- 已有十四表基线升级：标准商品原名与政策名（MySQL 8.0.16+）。
-- 在 Navicat 选中目标库，停止后端写入，备份现有库后依次执行各段。
-- 空库直接使用最新建表基线。回退时在停写期间恢复升级前备份，配套使用原版后端。

SET NAMES utf8mb4;
SET time_zone = '+08:00';

-- 1. 核对库名和原结构：goods_name 应为 VARCHAR(384)，尚无 policy_name。
SELECT DATABASE() AS target_database, VERSION() AS mysql_version;
SHOW CREATE TABLE goods;

-- 预期返回零行。原名缺失时先在吉客云补全并重新导入，再停写并复核。
SELECT id AS source_id, sku_id, goods_id
FROM source_goods_item
WHERE goods_id IS NOT NULL
  AND (goods_name IS NULL OR NOT REGEXP_LIKE(goods_name, '[^[:space:]]'));

-- 2. 在同一条 ALTER 中重建原有名称与货号约束。此段只执行一次。
ALTER TABLE goods
  DROP CHECK ck_goods_names,
  MODIFY COLUMN goods_name VARCHAR(512) NOT NULL COMMENT '商品名称，清洗或审核通过时取来源 goodsName 原文',
  ADD COLUMN policy_name VARCHAR(1024) DEFAULT NULL COMMENT '政策名' AFTER goods_name,
  ADD CONSTRAINT ck_goods_names CHECK (
    CHAR_LENGTH(TRIM(goods_name)) > 0 AND (product_no IS NULL OR CHAR_LENGTH(TRIM(product_no)) > 0)
  );

-- 3. 仅回填当前已关联的商品。字段按字节比较，包含尾部空格，并区分 NULL 与空串。
-- 原名缺失时由非空名称约束阻止整条 UPDATE，避免部分商品已回填。
-- 若第 2 段已成功而回填失败，处理报错后从本段继续。
START TRANSACTION;
UPDATE goods AS g
JOIN source_goods_item AS s ON s.goods_id = g.id
SET g.goods_name = CASE WHEN REGEXP_LIKE(s.goods_name, '[^[:space:]]') THEN s.goods_name ELSE '' END,
    g.policy_name = s.goods_field1
WHERE NOT (CAST(g.goods_name AS BINARY) <=> CAST(s.goods_name AS BINARY))
   OR NOT (CAST(g.policy_name AS BINARY) <=> CAST(s.goods_field1 AS BINARY))
   OR NOT REGEXP_LIKE(s.goods_name, '[^[:space:]]');
COMMIT;

-- 4. matched_goods 为已关联商品数，两个差异数均应为 0，核对后启动新版后端。
SELECT COUNT(*) AS matched_goods,
       COUNT(CASE WHEN NOT (CAST(g.goods_name AS BINARY) <=> CAST(s.goods_name AS BINARY))
                  THEN 1 END) AS goods_name_differences,
       COUNT(CASE WHEN NOT (CAST(g.policy_name AS BINARY) <=> CAST(s.goods_field1 AS BINARY))
                  THEN 1 END) AS policy_name_differences
FROM goods AS g
JOIN source_goods_item AS s ON s.goods_id = g.id;
