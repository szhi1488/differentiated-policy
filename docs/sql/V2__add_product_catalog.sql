-- 商品目录与管理员手动同步：已有四表数据库的增量迁移
-- 适用 MySQL 8.0.16+；执行前选择目标库并备份、核对 SHOW CREATE TABLE。
-- 前置：user_info、policy_master、policy_detail、policy_audit_log 与原四表设计一致。
-- 先确认两张新表及 policy_detail 的新增字段、索引、约束均不存在；仅执行一次。
-- 不重跑 schema.sql/V1，不删除、重建或按名称自动关联历史明细。
-- MySQL DDL 会隐式提交；中途失败先核对已完成步骤，再仅补齐剩余部分。

SET NAMES utf8mb4;
SET time_zone = '+08:00';

CREATE TABLE `product_catalog` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '本地商品主键，政策明细引用',
  `tenant_key` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属飞书租户',
  `source_base_token` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '源多维表格资源标识，不是访问凭证',
  `source_table_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '源数据表标识',
  `source_record_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '源记录稳定标识，不使用行号或名称',
  `model_full_name` VARCHAR(128) NOT NULL COMMENT '型号，对应表单涉及型号',
  `single_model` VARCHAR(64) NOT NULL COMMENT '单型号',
  `spec` VARCHAR(64) NOT NULL COMMENT '配置，完整保留简装及版本等信息',
  `is_available` TINYINT NOT NULL DEFAULT 1 COMMENT '1-可选，0-源记录缺失后停用；保留历史关联',
  `record_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '商品字段或可选状态变化时递增，无变化同步不递增',
  `last_synced_at` DATETIME NOT NULL COMMENT '最近一次成功同步且在源表中读取到此记录的时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP COMMENT '本地记录更新时间，不作为业务版本',

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_source` (`tenant_key`, `source_base_token`, `source_table_id`, `source_record_id`),
  KEY `idx_product_tenant_available` (`tenant_key`, `is_available`, `id`),
  KEY `idx_product_tenant_model` (`tenant_key`, `single_model`, `spec`),
  CONSTRAINT `ck_product_available` CHECK (`is_available` IN (0, 1)),
  CONSTRAINT `ck_product_version` CHECK (`record_version` > 0),
  CONSTRAINT `ck_product_fields` CHECK (
    CHAR_LENGTH(TRIM(`tenant_key`)) > 0
    AND CHAR_LENGTH(TRIM(`source_base_token`)) > 0
    AND CHAR_LENGTH(TRIM(`source_table_id`)) > 0
    AND CHAR_LENGTH(TRIM(`source_record_id`)) > 0
    AND CHAR_LENGTH(TRIM(`model_full_name`)) > 0
    AND CHAR_LENGTH(TRIM(`single_model`)) > 0
    AND CHAR_LENGTH(TRIM(`spec`)) > 0
  )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='飞书商品目录本地镜像，管理员手动更新';

CREATE TABLE `product_sync_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '同步批次主键',
  `tenant_key` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '同步所属租户',
  `source_base_token` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '本次源多维表格资源标识',
  `source_table_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '本次源数据表标识',
  `operator_id` BIGINT NOT NULL COMMENT '发起管理员，关联 user_info.id',
  `operator_name` VARCHAR(64) DEFAULT NULL COMMENT '发起管理员姓名快照',
  `status` VARCHAR(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING-进行中，SUCCESS-成功，FAILED-失败',
  `fetched_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已完整解析的源记录数，失败时不代表源表总数',
  `created_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '成功新增数量',
  `updated_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '成功修改数量，含恢复可选的记录',
  `disabled_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '由可选变为停用的数量',
  `unchanged_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '源表存在且字段及状态未变的数量',
  `error_code` VARCHAR(64) DEFAULT NULL COMMENT '失败稳定代码，不保存飞书原始响应或凭证',
  `started_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间，北京时间秒级',
  `finished_at` DATETIME DEFAULT NULL COMMENT '成功或失败的结束时间，进行中为空',

  PRIMARY KEY (`id`),
  KEY `idx_product_sync_source_time` (`tenant_key`, `source_base_token`, `source_table_id`, `id`),
  KEY `idx_product_sync_operator` (`operator_id`),
  CONSTRAINT `fk_product_sync_operator`
    FOREIGN KEY (`operator_id`) REFERENCES `user_info` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_product_sync_status` CHECK (`status` IN ('RUNNING', 'SUCCESS', 'FAILED')),
  CONSTRAINT `ck_product_sync_terminal` CHECK (
    (`status` = 'RUNNING' AND `finished_at` IS NULL AND `error_code` IS NULL)
    OR (`status` = 'SUCCESS' AND `finished_at` IS NOT NULL AND `error_code` IS NULL)
    OR (`status` = 'FAILED' AND `finished_at` IS NOT NULL AND `error_code` IS NOT NULL)
  ),
  CONSTRAINT `ck_product_sync_time` CHECK (`finished_at` IS NULL OR `finished_at` >= `started_at`),
  CONSTRAINT `ck_product_sync_counts` CHECK (
    (`status` = 'SUCCESS' AND `fetched_count` = `created_count` + `updated_count` + `unchanged_count`)
    OR (`status` <> 'SUCCESS' AND `created_count` = 0 AND `updated_count` = 0
      AND `disabled_count` = 0 AND `unchanged_count` = 0)
  )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='商品目录同步批次及结果，不写入政策审核日志';

ALTER TABLE `policy_detail`
  ADD COLUMN `product_id` BIGINT DEFAULT NULL COMMENT '本地商品ID，历史未关联及未选商品草稿为空' AFTER `platform_code`,
  ADD COLUMN `product_version` INT UNSIGNED DEFAULT NULL COMMENT '选中商品时的版本，用于草稿提交前核验' AFTER `product_id`,
  ADD KEY `idx_detail_product` (`product_id`),
  ADD CONSTRAINT `fk_detail_product`
    FOREIGN KEY (`product_id`) REFERENCES `product_catalog` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `ck_detail_product_reference` CHECK (
    (`product_id` IS NULL AND `product_version` IS NULL)
    OR (`product_id` IS NOT NULL AND `product_version` IS NOT NULL AND `product_version` > 0)
  );
