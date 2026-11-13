-- 适用于已有旧版九表的 MySQL 8.0.16+ 库，仅在 Navicat 对备份后的目标库执行一次。
-- 执行前停止旧版后端，暂停导入和管理员写入。DDL 隐式提交，任一步失败时停止并核对已执行步骤。
-- 空库直接运行更新后的 schema.sql 或 V1，不运行本文件。
SET NAMES utf8mb4;
SET time_zone = '+08:00';

CREATE TABLE `catalog_clean_job` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '独立清洗任务主键',
  `tenant_key` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  `source_key` VARCHAR(64) NOT NULL,
  `brand_name` VARCHAR(32) NOT NULL,
  `created_date` DATE DEFAULT NULL COMMENT '清洗范围的建档日，空表示品牌全部',
  `include_reviews` TINYINT NOT NULL DEFAULT 0 COMMENT '是否同时重新识别未映射的待审商品',
  `target_source_id` BIGINT DEFAULT NULL COMMENT '单条技术重试时的来源商品 ID',
  `request_key` VARCHAR(64) NOT NULL COMMENT '确认提交的幂等键',
  `requester_id` BIGINT NOT NULL,
  `requester_name` VARCHAR(64) DEFAULT NULL COMMENT '发起人姓名快照',
  `status` VARCHAR(16) NOT NULL COMMENT 'RUNNING、COMPLETED、PARTIAL、FAILED、INTERRUPTED',
  `legacy` TINYINT NOT NULL DEFAULT 0 COMMENT '由旧导入清洗记录迁入，仅可追溯日志',
  `worker_token` VARCHAR(64) DEFAULT NULL,
  `lease_until` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `active_slot` TINYINT GENERATED ALWAYS AS (CASE WHEN `status` = 'RUNNING' THEN 1 ELSE NULL END) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_clean_job_scope` (`id`, `tenant_key`, `source_key`, `brand_name`),
  UNIQUE KEY `uk_clean_job_request` (`tenant_key`, `source_key`, `request_key`),
  UNIQUE KEY `uk_clean_job_active` (`tenant_key`, `source_key`, `active_slot`),
  KEY `idx_clean_job_history` (`tenant_key`, `source_key`, `id`),
  KEY `idx_clean_job_requester` (`requester_id`, `tenant_key`),
  CONSTRAINT `fk_clean_job_requester` FOREIGN KEY (`requester_id`, `tenant_key`) REFERENCES `user_info` (`id`, `tenant_key`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_clean_job_brand` CHECK (`brand_name` IN ('OPPO手机', '一加手机')),
  CONSTRAINT `ck_clean_job_status` CHECK (`status` IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'INTERRUPTED')),
  CONSTRAINT `ck_clean_job_scope` CHECK (`legacy` IN (0, 1) AND `include_reviews` IN (0, 1) AND (`target_source_id` IS NULL OR (`created_date` IS NULL AND `include_reviews` = 0))),
  CONSTRAINT `ck_clean_job_lease` CHECK ((`worker_token` IS NULL AND `lease_until` IS NULL) OR (`status` = 'RUNNING' AND `worker_token` IS NOT NULL AND `lease_until` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='按本地商品范围手动启动的清洗任务';

-- 先固化旧清洗关联携带的建档日证据，后续该关联改指独立清洗任务。
INSERT INTO catalog_operation_log
  (tenant_key, actor_type, rule_version, entity_type, entity_id, action_type, batch_key, reason)
SELECT item.tenant_key, 'SYSTEM', 'SOURCE_CREATED_DATE_V1', 'SOURCE_GOODS_ITEM',
       item.id, 'CREATE', CONCAT('CREATED-DATE-', dated.sync_date), '迁移保留既有建档日证据。'
FROM source_goods_item item
JOIN goods_sync_job dated
  ON dated.id = item.clean_job_id AND dated.tenant_key = item.tenant_key
  AND dated.source_key = item.source_key AND dated.brand_name = item.brand_name
  AND dated.sync_mode = 'CREATED' AND dated.sync_date IS NOT NULL
WHERE NOT EXISTS (
  SELECT 1 FROM catalog_operation_log marker
  WHERE marker.tenant_key = item.tenant_key AND marker.entity_type = 'SOURCE_GOODS_ITEM'
    AND marker.entity_id = item.id AND marker.actor_type = 'SYSTEM' AND marker.action_type = 'CREATE'
    AND marker.batch_key = CONCAT('CREATED-DATE-', dated.sync_date)
);

-- 旧批次没有固定 SKU 成员，保留已有任务和日志关联。旧运行中任务归档为中断。
INSERT INTO catalog_clean_job
  (id, tenant_key, source_key, brand_name, created_date, request_key, requester_id,
   requester_name, status, legacy, created_at)
SELECT job.id, job.tenant_key, job.source_key, job.brand_name,
       CASE WHEN job.sync_mode = 'CREATED' THEN job.sync_date ELSE NULL END,
       CONCAT('legacy-', job.id), job.requester_id, requester.name,
       CASE WHEN job.clean_status IN ('COMPLETED', 'PARTIAL', 'FAILED')
            THEN job.clean_status ELSE 'INTERRUPTED' END,
       1, job.created_at
FROM goods_sync_job job
JOIN user_info requester ON requester.id = job.requester_id AND requester.tenant_key = job.tenant_key
WHERE job.clean_status <> 'NOT_STARTED'
   OR EXISTS (SELECT 1 FROM source_goods_item item WHERE item.clean_job_id = job.id);

ALTER TABLE source_goods_item
  DROP FOREIGN KEY fk_source_clean_job,
  DROP CHECK ck_source_auto_pass,
  DROP CHECK ck_source_clean_processing;

UPDATE source_goods_item
SET clean_status = 'PENDING', record_version = record_version + 1
WHERE clean_status = 'PROCESSING';

ALTER TABLE source_goods_item
  DROP COLUMN clean_rule_version,
  DROP COLUMN clean_run_key,
  DROP COLUMN clean_lease_until,
  MODIFY COLUMN clean_job_id BIGINT DEFAULT NULL COMMENT '最近生成清洗或审核结果的任务',
  ADD CONSTRAINT fk_source_clean_job
    FOREIGN KEY (clean_job_id, tenant_key, source_key, brand_name)
    REFERENCES catalog_clean_job (id, tenant_key, source_key, brand_name) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `ck_source_auto_pass` CHECK (`clean_status` <> 'AUTO_PASSED' OR (`mapping_status` = 'MAPPED' AND `mapping_origin` IS NOT NULL AND `mapping_origin` = 'AUTO' AND `variant_color_id` IS NOT NULL AND `clean_job_id` IS NOT NULL AND `clean_candidate` IS NOT NULL AND `cleaned_at` IS NOT NULL AND `clean_error_code` IS NULL AND `reviewed_by` IS NULL AND `reviewed_at` IS NULL AND `review_reason` IS NULL));

CREATE TABLE `catalog_clean_job_item` (
  `job_id` BIGINT NOT NULL COMMENT '清洗任务',
  `source_id` BIGINT NOT NULL COMMENT '本地来源商品',
  `source_version` INT UNSIGNED NOT NULL COMMENT '确认时的来源记录版本',
  `outcome` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '本轮结果快照',
  PRIMARY KEY (`job_id`, `source_id`),
  KEY `idx_clean_item_pending` (`job_id`, `outcome`, `source_id`),
  KEY `idx_clean_item_source` (`source_id`),
  CONSTRAINT `fk_clean_item_job` FOREIGN KEY (`job_id`) REFERENCES `catalog_clean_job` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_clean_item_source` FOREIGN KEY (`source_id`) REFERENCES `source_goods_item` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_clean_item_outcome` CHECK (`outcome` IN ('PENDING', 'AUTO_PASSED', 'MANUAL_PASSED', 'REVIEW_REQUIRED', 'ERROR', 'SKIPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='清洗确认时的 SKU 范围与逐条结果';

ALTER TABLE goods_sync_job
  DROP CHECK ck_job_clean_ready,
  DROP CHECK ck_job_clean_status;

ALTER TABLE goods_sync_job
  DROP COLUMN clean_status,
  DROP COLUMN clean_rule_version;

ALTER TABLE catalog_operation_log
  DROP CHECK ck_catalog_log_actor,
  DROP CHECK ck_catalog_log_entity,
  DROP CHECK ck_catalog_log_action;

ALTER TABLE catalog_operation_log
  DROP COLUMN rule_version,
  MODIFY COLUMN entity_type VARCHAR(32) NOT NULL COMMENT 'VARIANT、VARIANT_COLOR、SOURCE_GOODS_ITEM、SYNC_JOB、CLEAN_JOB',
  ADD CONSTRAINT `ck_catalog_log_actor` CHECK ((`actor_type` = 'USER' AND `operator_id` IS NOT NULL) OR (`actor_type` = 'SYSTEM' AND `operator_id` IS NULL AND `operator_name` IS NULL)),
  ADD CONSTRAINT `ck_catalog_log_entity` CHECK (`entity_type` IN ('VARIANT', 'VARIANT_COLOR', 'SOURCE_GOODS_ITEM', 'SYNC_JOB', 'CLEAN_JOB')),
  ADD CONSTRAINT `ck_catalog_log_action` CHECK (`action_type` IN ('CREATE', 'UPDATE', 'PUBLISH', 'DISABLE', 'MAP', 'UNMAP', 'EXCLUDE', 'CORRECT', 'START', 'PAUSE', 'RESUME', 'CANCEL', 'CLEAN_AUTO', 'CLEAN_REVIEW', 'CLEAN_ERROR', 'CLEAN_RETRY', 'CLEAN_SKIP'));
