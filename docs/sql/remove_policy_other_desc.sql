-- 移除政策申请的「其他说明」，保留 remark（特别备注）。
-- 用户已于 2026-11-15 确认删除 other_desc 列及该列已有内容。
-- 适用 MySQL 8.0.16+，已有十四表基线，policy_apply.other_desc 当前仍存在。
-- 先更新并重启本次后端代码，再由用户在 Navicat 选中目标库执行本脚本。
-- 新版后端兼容迁移前后的表结构，旧版后端依赖 other_desc，删除列后须运行新版。
-- MySQL DDL 隐式提交，DROP COLUMN 无法通过 ROLLBACK 恢复列中内容。
-- 空库首次建表使用 schema.sql 或 V1__create_policy_tables.sql 二选一。

-- 1. 核对当前数据库和已有列。预期 remark、other_desc 均为可空 TEXT。
SELECT DATABASE() AS target_database;

SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'policy_apply'
  AND COLUMN_NAME IN ('remark', 'other_desc')
ORDER BY ORDINAL_POSITION;

-- 2. 核对受影响记录数量。这里只统计行数，不展示字段正文。
SELECT COUNT(*) AS policy_rows,
       COALESCE(SUM(other_desc IS NOT NULL AND CHAR_LENGTH(TRIM(other_desc)) > 0), 0) AS rows_with_other_desc
FROM policy_apply;

-- 3. 永久移除该列及其内容。
ALTER TABLE policy_apply DROP COLUMN other_desc;

-- 4. 验证结果。预期查询仅返回 remark；申请行数与步骤 2 一致。
SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'policy_apply'
  AND COLUMN_NAME IN ('remark', 'other_desc')
ORDER BY ORDINAL_POSITION;

SELECT COUNT(*) AS policy_rows FROM policy_apply;
