-- 在 Navicat 选中已执行旧版九表 SQL 的库，使用“运行 SQL 文件”执行整份脚本。
-- 执行前停止后端。该脚本仅适用于没有导入任务、来源商品及商品操作日志的旧库。
-- MySQL 的 ALTER TABLE 会隐式提交。出现错误时保留错误信息，先核对结构再继续。
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS `upgrade_empty_goods_sync_to_single_brand`;

DELIMITER $$

CREATE PROCEDURE `upgrade_empty_goods_sync_to_single_brand`()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goods_sync_job' AND COLUMN_NAME = 'oppo_status'
  ) OR EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'goods_sync_job' AND COLUMN_NAME = 'brand_name'
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '当前任务表不是待升级的旧版本，请检查所选库和字段。';
  END IF;

  IF EXISTS (SELECT 1 FROM `goods_sync_job`)
    OR EXISTS (SELECT 1 FROM `source_goods_item`)
    OR EXISTS (SELECT 1 FROM `catalog_operation_log`) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '存在商品导入数据或日志，请使用保留历史的迁移方案。';
  END IF;

  -- 先解除来源表对旧任务联合键的引用。
  ALTER TABLE `source_goods_item`
    DROP FOREIGN KEY `fk_source_first_job`,
    DROP FOREIGN KEY `fk_source_last_job`,
    DROP FOREIGN KEY `fk_source_clean_job`;

  ALTER TABLE `goods_sync_job`
    DROP CHECK `ck_job_oppo`,
    DROP CHECK `ck_job_oneplus`,
    DROP CHECK `ck_job_success`,
    DROP CHECK `ck_job_status`,
    DROP CHECK `ck_job_page`,
    DROP COLUMN `oppo_status`,
    DROP COLUMN `oneplus_status`,
    DROP COLUMN `oppo_last_max_id`,
    DROP COLUMN `oneplus_last_max_id`,
    ADD COLUMN `brand_name` VARCHAR(32) NOT NULL COMMENT '任务品牌：OPPO手机或一加手机' AFTER `source_key`,
    ADD COLUMN `last_max_id` VARCHAR(32) NOT NULL DEFAULT '0' COMMENT '本任务品牌的最新续拉位置，不保存逐商品标识' AFTER `status`,
    ADD COLUMN `page_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已提交页数，含确认结束的空页' AFTER `include_blockup`,
    ADD COLUMN `next_request_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最早可再次查询的时间，包含上游限流冷却' AFTER `lease_until`,
    MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '同步任务主键，一个任务只导入一个品牌',
    MODIFY COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED、RUNNING、PAUSED、SUCCEEDED、CANCELLED',
    MODIFY COLUMN `include_blockup` TINYINT NOT NULL COMMENT '本任务是否包含停用来源，0或1；当前导入固定为0',
    MODIFY COLUMN `active_slot` TINYINT GENERATED ALWAYS AS (CASE WHEN `status` IN ('QUEUED', 'RUNNING', 'PAUSED') THEN 1 ELSE NULL END) STORED COMMENT '同租户账套只保留一个活动任务，暂停也占位',
    DROP INDEX `uk_job_id_scope`,
    ADD UNIQUE KEY `uk_job_id_scope` (`id`, `tenant_key`, `source_key`, `brand_name`),
    DROP INDEX `idx_job_status`,
    ADD KEY `idx_job_brand_latest` (`tenant_key`, `source_key`, `brand_name`, `id`),
    ADD KEY `idx_job_cooldown` (`tenant_key`, `source_key`, `status`, `next_request_at`),
    ADD CONSTRAINT `ck_job_status` CHECK (`status` IN ('QUEUED', 'RUNNING', 'PAUSED', 'SUCCEEDED', 'CANCELLED')),
    ADD CONSTRAINT `ck_job_brand` CHECK (`brand_name` IN ('OPPO手机', '一加手机')),
    ADD CONSTRAINT `ck_job_page` CHECK (`page_size` BETWEEN 1 AND 50),
    COMMENT = '单品牌原始导入任务及最新断点';

  -- 按品牌重建关联，保留租户及账套约束。
  ALTER TABLE `source_goods_item`
    DROP INDEX `idx_source_first_job`,
    DROP INDEX `idx_source_last_job`,
    DROP INDEX `idx_source_clean_job`,
    ADD KEY `idx_source_first_job` (`first_seen_job_id`, `tenant_key`, `source_key`, `brand_name`),
    ADD KEY `idx_source_last_job` (`last_seen_job_id`, `tenant_key`, `source_key`, `brand_name`),
    ADD KEY `idx_source_clean_job` (`clean_job_id`, `tenant_key`, `source_key`, `brand_name`),
    ADD KEY `idx_source_scope_list` (`tenant_key`, `source_key`, `id`),
    ADD KEY `idx_source_brand_list` (`tenant_key`, `source_key`, `brand_name`, `id`),
    ADD CONSTRAINT `fk_source_first_job` FOREIGN KEY (`first_seen_job_id`, `tenant_key`, `source_key`, `brand_name`) REFERENCES `goods_sync_job` (`id`, `tenant_key`, `source_key`, `brand_name`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT `fk_source_last_job` FOREIGN KEY (`last_seen_job_id`, `tenant_key`, `source_key`, `brand_name`) REFERENCES `goods_sync_job` (`id`, `tenant_key`, `source_key`, `brand_name`) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT `fk_source_clean_job` FOREIGN KEY (`clean_job_id`, `tenant_key`, `source_key`, `brand_name`) REFERENCES `goods_sync_job` (`id`, `tenant_key`, `source_key`, `brand_name`) ON DELETE RESTRICT ON UPDATE RESTRICT;

  SELECT '单品牌商品导入表已升级' AS `结果`;
END$$

DELIMITER ;

CALL `upgrade_empty_goods_sync_to_single_brand`();
DROP PROCEDURE `upgrade_empty_goods_sync_to_single_brand`;
