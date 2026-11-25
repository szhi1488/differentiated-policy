-- 二十二表基线中的商品、政策与按轮审核表，用于隔离 H2 验证。账号与审核名单见 user-info-schema.sql。

CREATE TABLE `brand` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(50) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_brand_name` (`name`),
  UNIQUE KEY `uk_brand_name_binding` (`id`, `name`),
  CONSTRAINT `ck_brand_name` CHECK (CHAR_LENGTH(TRIM(`name`)) > 0)
);

CREATE TABLE `series` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `brand_id` BIGINT NOT NULL,
  `name` VARCHAR(64) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_series_binding` (`id`, `brand_id`),
  UNIQUE KEY `uk_series_name` (`brand_id`, `name`),
  CONSTRAINT `fk_series_brand` FOREIGN KEY (`brand_id`) REFERENCES `brand` (`id`),
  CONSTRAINT `ck_series_name` CHECK (CHAR_LENGTH(TRIM(`name`)) > 0)
);

CREATE TABLE `spu` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `brand_id` BIGINT NOT NULL,
  `series_id` BIGINT NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `edition` VARCHAR(64) NOT NULL DEFAULT '',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_spu_binding` (`id`, `brand_id`, `series_id`),
  UNIQUE KEY `uk_spu_identity` (`brand_id`, `name`, `edition`),
  CONSTRAINT `fk_spu_series` FOREIGN KEY (`series_id`, `brand_id`) REFERENCES `series` (`id`, `brand_id`),
  CONSTRAINT `ck_spu_name` CHECK (CHAR_LENGTH(TRIM(`name`)) > 0),
  CONSTRAINT `ck_spu_edition` CHECK (CHAR_LENGTH(`edition`) = CHAR_LENGTH(TRIM(`edition`)))
);

CREATE TABLE `color_dict` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `color_name` VARCHAR(64) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_color_dict_name` (`color_name`),
  CONSTRAINT `ck_color_dict_name` CHECK (CHAR_LENGTH(TRIM(`color_name`)) > 0)
);

CREATE TABLE `goods` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `sku_id` VARCHAR(19) DEFAULT NULL,
  `product_no` VARCHAR(128) DEFAULT NULL,
  `brand_id` BIGINT NOT NULL,
  `series_id` BIGINT NOT NULL,
  `spu_id` BIGINT NOT NULL,
  `goods_name` VARCHAR(512) NOT NULL,
  `policy_name` VARCHAR(1024) DEFAULT NULL,
  `ram` VARCHAR(20) NOT NULL,
  `rom` VARCHAR(20) NOT NULL,
  `color_id` BIGINT NOT NULL,
  `status` TINYINT DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_goods_brand_binding` (`id`, `brand_id`),
  UNIQUE KEY `uk_goods_policy_binding` (`id`, `spu_id`, `ram`, `rom`),
  UNIQUE KEY `uk_goods_sku` (`sku_id`),
  KEY `idx_goods_spu_specs` (`spu_id`, `ram`, `rom`, `color_id`),
  CONSTRAINT `fk_goods_spu` FOREIGN KEY (`spu_id`, `brand_id`, `series_id`) REFERENCES `spu` (`id`, `brand_id`, `series_id`),
  CONSTRAINT `fk_goods_color` FOREIGN KEY (`color_id`) REFERENCES `color_dict` (`id`),
  CONSTRAINT `ck_goods_names` CHECK (CHAR_LENGTH(TRIM(`goods_name`)) > 0 AND (`product_no` IS NULL OR CHAR_LENGTH(TRIM(`product_no`)) > 0)),
  CONSTRAINT `ck_goods_sku_id` CHECK (`sku_id` IS NULL OR REGEXP_LIKE(`sku_id`, '^[1-9][0-9]{0,18}$')),
  CONSTRAINT `ck_goods_memory` CHECK (
    REGEXP_LIKE(`ram`, '^[1-9][0-9]{0,7}GB$', 'c') AND RIGHT(`ram`, 2) = 'GB'
    AND REGEXP_LIKE(`rom`, '^[1-9][0-9]{0,7}GB$', 'c') AND RIGHT(`rom`, 2) = 'GB'
  ),
  CONSTRAINT `ck_goods_status` CHECK (`status` IN (1, 2, 3, 4))
);

CREATE ALIAS IF NOT EXISTS JSON_QUOTE FOR "com.company.policy.controller.PolicyJsonFunctions.quote";
CREATE ALIAS IF NOT EXISTS JSON_CONTAINS FOR "com.company.policy.controller.PolicyJsonFunctions.contains";
CREATE ALIAS IF NOT EXISTS JSON_SEARCH FOR "com.company.policy.controller.PolicyJsonFunctions.search";
CREATE ALIAS IF NOT EXISTS JSON_EXTRACT FOR "com.company.policy.controller.PolicyJsonFunctions.extract";
CREATE ALIAS IF NOT EXISTS JSON_UNQUOTE FOR "com.company.policy.controller.PolicyJsonFunctions.unquote";

CREATE TABLE `policy_apply` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `policy_no` VARCHAR(32) NOT NULL,
  `extension_source_id` BIGINT DEFAULT NULL,
  `tenant_key` VARCHAR(64) NOT NULL,
  `applicant_id` BIGINT NOT NULL,
  `applicant_name` VARCHAR(64) NOT NULL,
  `channel_type` VARCHAR(20) NOT NULL,
  `platforms` TEXT DEFAULT NULL,
  `write_off_type` VARCHAR(20) DEFAULT NULL,
  `scene_types` TEXT DEFAULT NULL,
  `is_estimated` TINYINT DEFAULT NULL,
  `start_time` DATETIME DEFAULT NULL,
  `end_time` DATETIME DEFAULT NULL,
  `remark` TEXT,
  `goods_form_json` TEXT DEFAULT NULL,
  `attachment_urls` TEXT DEFAULT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  `current_version` INT NOT NULL DEFAULT 0,
  `record_version` INT NOT NULL DEFAULT 1,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_apply_policy_no` (`policy_no`),
  UNIQUE KEY `uk_apply_extension_source` (`extension_source_id`, `tenant_key`),
  UNIQUE KEY `uk_apply_tenant_binding` (`id`, `tenant_key`),
  KEY `idx_apply_mine` (`applicant_id`, `tenant_key`, `is_deleted`, `created_at`, `id`),
  KEY `idx_apply_status` (`tenant_key`, `is_deleted`, `status`, `created_at`, `id`),

  CONSTRAINT `fk_apply_applicant`
    FOREIGN KEY (`applicant_id`, `tenant_key`) REFERENCES `user_info` (`id`, `tenant_key`),
  CONSTRAINT `fk_apply_extension_source`
    FOREIGN KEY (`extension_source_id`, `tenant_key`) REFERENCES `policy_apply` (`id`, `tenant_key`),
  CONSTRAINT `ck_apply_channel` CHECK (`channel_type` IN ('RETAIL', 'BILLION')),
  CONSTRAINT `ck_apply_retail_basics` CHECK (
    (`write_off_type` IS NULL OR `write_off_type` IN ('SALES', 'ACTIVATION'))
    AND (`is_estimated` IS NULL OR `is_estimated` IN (0, 1))
    AND (`channel_type` = 'RETAIL' OR (`write_off_type` IS NULL AND `scene_types` IS NULL AND `is_estimated` IS NULL))
  ),
  CONSTRAINT `ck_apply_time` CHECK (`start_time` IS NULL OR `end_time` IS NULL OR `start_time` < `end_time`
    OR (`status` = 'APPROVED' AND TIMESTAMPDIFF(SECOND, `end_time`, `start_time`) <= 1)),
  CONSTRAINT `ck_apply_status_version` CHECK (
    (`status` = 'DRAFT' AND `current_version` = 0)
    OR (`status` IN ('PENDING', 'APPROVED', 'REJECTED') AND `current_version` > 0)
  ),
  CONSTRAINT `ck_apply_deleted` CHECK (`is_deleted` IN (0, 1))
);

CREATE TABLE `policy_audit_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `policy_id` BIGINT NOT NULL,
  `tenant_key` VARCHAR(64) NOT NULL,
  `version` INT NOT NULL,
  `snapshot_data` TEXT NOT NULL,
  `workflow_version` TINYINT NOT NULL DEFAULT 1 CHECK (workflow_version IN (0,1)),
  `audit_status` VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
  `auditor_id` BIGINT DEFAULT NULL,
  `auditor_name` VARCHAR(64) DEFAULT NULL,
  `audit_comment` VARCHAR(512) DEFAULT NULL,
  `audit_time` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_policy_audit_round` (`policy_id`, `version`),
  KEY `idx_policy_audit_tenant` (`policy_id`, `tenant_key`),
  KEY `idx_policy_audit_auditor` (`auditor_id`, `tenant_key`),

  CONSTRAINT `fk_policy_audit_apply`
    FOREIGN KEY (`policy_id`, `tenant_key`) REFERENCES `policy_apply` (`id`, `tenant_key`),
  CONSTRAINT `fk_policy_audit_user`
    FOREIGN KEY (`auditor_id`, `tenant_key`) REFERENCES `user_info` (`id`, `tenant_key`),
  CONSTRAINT `ck_policy_audit_snapshot` CHECK (`version` > 0),
  CONSTRAINT `ck_policy_audit_result` CHECK (
    (`audit_status` = 'SUBMITTED' AND `auditor_id` IS NULL AND `auditor_name` IS NULL
      AND `audit_comment` IS NULL AND `audit_time` IS NULL)
    OR (`audit_status` IN ('APPROVED', 'REJECTED') AND `auditor_id` IS NOT NULL
      AND `auditor_name` IS NOT NULL AND `audit_time` IS NOT NULL)
  )
);

CREATE TABLE `policy_time_change` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  policy_id BIGINT NOT NULL,
  tenant_key VARCHAR(64) NOT NULL,
  policy_version INT NOT NULL,
  audit_log_id BIGINT NOT NULL,
  after_record_version INT NOT NULL,
  direction VARCHAR(8) NOT NULL,
  previous_end DATETIME NOT NULL,
  requested_end DATETIME NOT NULL,
  actual_end DATETIME NOT NULL,
  effective_at DATETIME NOT NULL,
  changed_at DATETIME NOT NULL,
  reason VARCHAR(512) NOT NULL,
  actor_id BIGINT NOT NULL,
  actor_name VARCHAR(64) NOT NULL,
  attachment_json TEXT NOT NULL,
  consent_json TEXT NOT NULL,
  request_key VARCHAR(36) DEFAULT NULL,
  request_hash VARCHAR(64) DEFAULT NULL,
  source_policy_id BIGINT DEFAULT NULL,
  source_version INT DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_time_change_version (policy_id,after_record_version),
  UNIQUE KEY uk_time_change_request (tenant_key,actor_id,request_key,policy_id),
  UNIQUE KEY uk_time_change_source (source_policy_id,source_version,policy_id),
  KEY idx_time_change_period (tenant_key,changed_at,id),
  KEY idx_time_change_policy (policy_id,changed_at,id),
  CONSTRAINT fk_time_change_policy FOREIGN KEY (policy_id,tenant_key) REFERENCES policy_apply(id,tenant_key),
  CONSTRAINT fk_time_change_round FOREIGN KEY (policy_id,policy_version) REFERENCES policy_audit_log(policy_id,version),
  CONSTRAINT fk_time_change_audit FOREIGN KEY (audit_log_id) REFERENCES policy_audit_log(id),
  CONSTRAINT fk_time_change_source FOREIGN KEY (source_policy_id,source_version) REFERENCES policy_audit_log(policy_id,version),
  CONSTRAINT fk_time_change_source_tenant FOREIGN KEY (source_policy_id,tenant_key) REFERENCES policy_apply(id,tenant_key),
  CONSTRAINT fk_time_change_actor FOREIGN KEY (actor_id,tenant_key) REFERENCES user_info(id,tenant_key),
  CONSTRAINT ck_time_change_direction CHECK ((direction='EXTEND' AND requested_end>previous_end AND actual_end=requested_end)
    OR (direction='SHORTEN' AND requested_end<previous_end AND actual_end BETWEEN requested_end AND previous_end)),
  CONSTRAINT ck_time_change_source CHECK ((request_key IS NOT NULL AND request_hash IS NOT NULL AND source_policy_id IS NULL AND source_version IS NULL)
    OR (request_key IS NULL AND request_hash IS NULL AND source_policy_id IS NOT NULL AND source_version IS NOT NULL)),
  CONSTRAINT ck_time_change_reason CHECK (CHAR_LENGTH(TRIM(reason))>0)
);

CREATE TABLE `policy_goods_detail` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `policy_id` BIGINT NOT NULL,
  `version` INT NOT NULL,
  `platform_code` VARCHAR(32) NOT NULL,
  `spu_id` BIGINT NOT NULL,
  `ram` VARCHAR(20) NOT NULL,
  `rom` VARCHAR(20) NOT NULL,
  `goods_id` BIGINT NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_policy_goods_round_platform` (`policy_id`, `version`, `platform_code`, `goods_id`),
  KEY `idx_policy_goods_config` (`spu_id`, `ram`, `rom`, `platform_code`, `goods_id`),
  KEY `idx_policy_goods_binding` (`goods_id`, `spu_id`, `ram`, `rom`),

  CONSTRAINT `fk_policy_goods_round`
    FOREIGN KEY (`policy_id`, `version`) REFERENCES `policy_audit_log` (`policy_id`, `version`),
  CONSTRAINT `fk_policy_goods_spu` FOREIGN KEY (`spu_id`) REFERENCES `spu` (`id`),
  CONSTRAINT `fk_policy_goods_sku`
    FOREIGN KEY (`goods_id`, `spu_id`, `ram`, `rom`) REFERENCES `goods` (`id`, `spu_id`, `ram`, `rom`),
  CONSTRAINT `ck_policy_goods_platform` CHECK (
    `platform_code` IN ('KUAISHOU', 'PDD', 'JD', 'DOUYIN', 'TMALL', 'TAOBAO', 'ZHAOSHANG', 'DEWU', 'UNICOM', 'GOV_ENTERPRISE')
  ),
  CONSTRAINT `ck_policy_goods_memory` CHECK (
    REGEXP_LIKE(`ram`, '^[1-9][0-9]{0,7}GB$', 'c') AND RIGHT(`ram`, 2) = 'GB'
    AND REGEXP_LIKE(`rom`, '^[1-9][0-9]{0,7}GB$', 'c') AND RIGHT(`rom`, 2) = 'GB'
  )
);

CREATE TABLE `goods_sync_job` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `source_key` VARCHAR(64) NOT NULL,
  `brand_name` VARCHAR(50) NOT NULL,
  `requester_id` BIGINT NOT NULL,
  `idempotency_key` VARCHAR(64) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
  `sync_mode` VARCHAR(16) NOT NULL DEFAULT 'FULL',
  `sync_date` DATE DEFAULT NULL,
  `sync_phase` VARCHAR(16) NOT NULL DEFAULT 'FULL',
  `last_max_id` VARCHAR(32) NOT NULL DEFAULT '0',
  `page_size` INT NOT NULL DEFAULT 50,
  `include_blockup` TINYINT NOT NULL,
  `page_count` INT NOT NULL DEFAULT 0,
  `read_count` BIGINT NOT NULL DEFAULT 0,
  `content_count` BIGINT NOT NULL DEFAULT 0,
  `failure_count` INT NOT NULL DEFAULT 0,
  `error_code` VARCHAR(64) DEFAULT NULL,
  `worker_token` VARCHAR(64) DEFAULT NULL,
  `lease_until` DATETIME DEFAULT NULL,
  `next_request_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `fencing_token` BIGINT NOT NULL DEFAULT 0,
  `record_version` INT NOT NULL DEFAULT 0,
  `started_at` DATETIME DEFAULT NULL,
  `finished_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `active_slot` TINYINT GENERATED ALWAYS AS (CASE WHEN `status` IN ('QUEUED', 'RUNNING', 'PAUSED') THEN 1 ELSE NULL END),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_job_id_scope` (`id`, `source_key`, `brand_name`),
  UNIQUE KEY `uk_job_idempotency` (`source_key`, `idempotency_key`),
  UNIQUE KEY `uk_job_active` (`source_key`, `active_slot`),
  CONSTRAINT `fk_job_requester` FOREIGN KEY (`requester_id`) REFERENCES `user_info` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_job_status` CHECK (`status` IN ('QUEUED', 'RUNNING', 'PAUSED', 'SUCCEEDED', 'CANCELLED')),
  CONSTRAINT `ck_job_brand` CHECK (`brand_name` IN ('OPPO手机', '一加手机')),
  CONSTRAINT `ck_job_sync_scope` CHECK (
    (`sync_mode` = 'FULL' AND `sync_date` IS NULL AND `sync_phase` = 'FULL')
    OR (`sync_mode` = 'CREATED' AND `sync_date` IS NOT NULL AND `sync_phase` = 'CREATED')
    OR (`sync_mode` = 'MODIFIED' AND `sync_date` IS NOT NULL AND `sync_phase` IN ('GOODS_MODIFIED', 'SKU_MODIFIED'))
  ),
  CONSTRAINT `ck_job_page` CHECK (`page_size` BETWEEN 1 AND 50),
  CONSTRAINT `ck_job_blockup` CHECK (`include_blockup` IN (0, 1))
);

CREATE TABLE `catalog_clean_job` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `source_key` VARCHAR(64) NOT NULL,
  `brand_name` VARCHAR(50) NOT NULL,
  `created_date` DATE DEFAULT NULL,
  `include_reviews` TINYINT NOT NULL DEFAULT 0,
  `target_source_id` BIGINT DEFAULT NULL,
  `request_key` VARCHAR(64) NOT NULL,
  `requester_id` BIGINT NOT NULL,
  `requester_name` VARCHAR(64) DEFAULT NULL,
  `status` VARCHAR(16) NOT NULL,
  `worker_token` VARCHAR(64) DEFAULT NULL,
  `lease_until` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `active_slot` TINYINT GENERATED ALWAYS AS (CASE WHEN `status` = 'RUNNING' THEN 1 ELSE NULL END),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_clean_job_scope` (`id`, `source_key`, `brand_name`),
  UNIQUE KEY `uk_clean_job_request` (`source_key`, `request_key`),
  UNIQUE KEY `uk_clean_job_active` (`source_key`, `active_slot`),
  CONSTRAINT `fk_clean_job_requester` FOREIGN KEY (`requester_id`) REFERENCES `user_info` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_clean_job_brand` CHECK (`brand_name` IN ('OPPO手机', '一加手机')),
  CONSTRAINT `ck_clean_job_status` CHECK (`status` IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
  CONSTRAINT `ck_clean_job_scope` CHECK (`include_reviews` IN (0, 1) AND (`target_source_id` IS NULL OR (`created_date` IS NULL AND `include_reviews` = 0))),
  CONSTRAINT `ck_clean_job_lease` CHECK ((`worker_token` IS NULL AND `lease_until` IS NULL) OR (`status` = 'RUNNING' AND `worker_token` IS NOT NULL AND `lease_until` IS NOT NULL))
);

CREATE TABLE `source_goods_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `source_key` VARCHAR(64) NOT NULL,
  `brand_name` VARCHAR(50) NOT NULL,
  `goods_name` VARCHAR(512) DEFAULT NULL,
  `goods_no` VARCHAR(128) DEFAULT NULL,
  `sku_name` VARCHAR(256) DEFAULT NULL,
  `cate_name` VARCHAR(256) DEFAULT NULL,
  `goods_field4` VARCHAR(1024) DEFAULT NULL,
  `goods_field1` VARCHAR(1024) DEFAULT NULL,
  `goods_field5` VARCHAR(1024) DEFAULT NULL,
  `sku_id` VARCHAR(19) NOT NULL,
  `cate_full_name` VARCHAR(1024) DEFAULT NULL,
  `gmt_modified` BIGINT DEFAULT NULL,
  `goods_gmt_modified` BIGINT DEFAULT NULL,
  `is_blockup` TINYINT DEFAULT NULL,
  `is_package_good` TINYINT DEFAULT NULL,
  `first_seen_job_id` BIGINT NOT NULL,
  `last_seen_job_id` BIGINT NOT NULL,
  `mapping_status` VARCHAR(16) NOT NULL DEFAULT 'UNMAPPED',
  `catalog_brand_id` BIGINT DEFAULT NULL,
  `goods_id` BIGINT DEFAULT NULL,
  `mapping_remark` VARCHAR(512) DEFAULT NULL,
  `mapping_origin` VARCHAR(16) DEFAULT NULL,
  `clean_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `clean_job_id` BIGINT DEFAULT NULL,
  `clean_candidate` VARCHAR DEFAULT NULL,
  `clean_error_code` VARCHAR(64) DEFAULT NULL,
  `clean_attempts` INT NOT NULL DEFAULT 0,
  `cleaned_at` DATETIME DEFAULT NULL,
  `reviewed_by` BIGINT DEFAULT NULL,
  `reviewed_at` DATETIME DEFAULT NULL,
  `review_reason` VARCHAR(512) DEFAULT NULL,
  `record_version` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_sku` (`sku_id`),
  UNIQUE KEY `uk_source_goods` (`goods_id`),
  CONSTRAINT `fk_source_first_job` FOREIGN KEY (`first_seen_job_id`, `source_key`, `brand_name`) REFERENCES `goods_sync_job` (`id`, `source_key`, `brand_name`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_source_last_job` FOREIGN KEY (`last_seen_job_id`, `source_key`, `brand_name`) REFERENCES `goods_sync_job` (`id`, `source_key`, `brand_name`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_source_catalog_brand` FOREIGN KEY (`catalog_brand_id`, `brand_name`) REFERENCES `brand` (`id`, `name`),
  CONSTRAINT `fk_source_goods` FOREIGN KEY (`goods_id`, `catalog_brand_id`) REFERENCES `goods` (`id`, `brand_id`),
  CONSTRAINT `fk_source_clean_job` FOREIGN KEY (`clean_job_id`, `source_key`, `brand_name`) REFERENCES `catalog_clean_job` (`id`, `source_key`, `brand_name`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_source_reviewer` FOREIGN KEY (`reviewed_by`) REFERENCES `user_info` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_source_brand` CHECK (`brand_name` IN ('一加手机', 'OPPO手机')),
  CONSTRAINT `ck_source_sku_id` CHECK (CHAR_LENGTH(TRIM(`sku_id`)) BETWEEN 1 AND 19),
  CONSTRAINT `ck_source_flags` CHECK (
    (`is_blockup` IS NULL OR `is_blockup` IN (0, 1))
    AND (`is_package_good` IS NULL OR `is_package_good` IN (0, 1))
  ),
  CONSTRAINT `ck_source_mapping` CHECK ((`mapping_status` = 'MAPPED' AND `catalog_brand_id` IS NOT NULL AND `goods_id` IS NOT NULL AND `mapping_origin` IS NOT NULL AND `mapping_origin` IN ('AUTO', 'MANUAL')) OR (`mapping_status` IN ('UNMAPPED', 'EXCLUDED') AND `catalog_brand_id` IS NULL AND `goods_id` IS NULL AND `mapping_origin` IS NULL)),
  CONSTRAINT `ck_source_clean_enum` CHECK (`clean_status` IN ('PENDING', 'PROCESSING', 'AUTO_PASSED', 'REVIEW_REQUIRED', 'MANUAL_PASSED', 'ERROR', 'EXCLUDED')),
  CONSTRAINT `ck_source_candidate` CHECK (`clean_candidate` IS NULL OR `clean_candidate` IS JSON OBJECT),
  CONSTRAINT `ck_source_auto_pass` CHECK (`clean_status` <> 'AUTO_PASSED' OR (`mapping_status` = 'MAPPED' AND `mapping_origin` IS NOT NULL AND `mapping_origin` = 'AUTO' AND `clean_job_id` IS NOT NULL AND `clean_candidate` IS NOT NULL AND `cleaned_at` IS NOT NULL AND `clean_error_code` IS NULL AND `reviewed_by` IS NULL AND `reviewed_at` IS NULL AND `review_reason` IS NULL)),
  CONSTRAINT `ck_source_manual_pass` CHECK (`clean_status` <> 'MANUAL_PASSED' OR (`mapping_status` = 'MAPPED' AND `mapping_origin` IS NOT NULL AND `mapping_origin` = 'MANUAL' AND `reviewed_by` IS NOT NULL AND `reviewed_at` IS NOT NULL AND `review_reason` IS NOT NULL AND CHAR_LENGTH(TRIM(`review_reason`)) > 0 AND `cleaned_at` IS NOT NULL AND `clean_error_code` IS NULL)),
  CONSTRAINT `ck_source_clean_issue` CHECK (`clean_status` NOT IN ('REVIEW_REQUIRED', 'ERROR') OR (`clean_error_code` IS NOT NULL AND CHAR_LENGTH(TRIM(`clean_error_code`)) > 0)),
  CONSTRAINT `ck_source_clean_excluded` CHECK (
    (`clean_status` <> 'EXCLUDED' AND `mapping_status` <> 'EXCLUDED') OR
    (`clean_status` = 'EXCLUDED' AND `mapping_status` = 'EXCLUDED' AND `cleaned_at` IS NOT NULL AND (
      (`reviewed_by` IS NOT NULL AND `reviewed_at` IS NOT NULL AND `review_reason` IS NOT NULL AND CHAR_LENGTH(TRIM(`review_reason`)) > 0) OR
      (`reviewed_by` IS NULL AND `reviewed_at` IS NULL AND `review_reason` IS NULL AND `clean_job_id` IS NOT NULL
       AND `clean_candidate` IS NOT NULL AND `clean_error_code` IS NULL AND `mapping_remark` IS NOT NULL AND CHAR_LENGTH(TRIM(`mapping_remark`)) > 0)
    ))
  )
);

CREATE TABLE `catalog_clean_job_item` (
  `job_id` BIGINT NOT NULL,
  `source_id` BIGINT NOT NULL,
  `source_version` INT NOT NULL,
  `outcome` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  PRIMARY KEY (`job_id`, `source_id`),
  CONSTRAINT `fk_clean_item_job` FOREIGN KEY (`job_id`) REFERENCES `catalog_clean_job` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_clean_item_source` FOREIGN KEY (`source_id`) REFERENCES `source_goods_item` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_clean_item_outcome` CHECK (`outcome` IN ('PENDING', 'AUTO_PASSED', 'MANUAL_PASSED', 'REVIEW_REQUIRED', 'EXCLUDED', 'ERROR', 'SKIPPED'))
);

CREATE TABLE `catalog_operation_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_type` VARCHAR(16) NOT NULL DEFAULT 'USER',
  `operator_id` BIGINT DEFAULT NULL,
  `operator_name` VARCHAR(64) DEFAULT NULL,
  `entity_type` VARCHAR(32) NOT NULL,
  `entity_id` BIGINT NOT NULL,
  `action_type` VARCHAR(32) NOT NULL,
  `batch_key` VARCHAR(64) DEFAULT NULL,
  `before_data` VARCHAR DEFAULT NULL,
  `after_data` VARCHAR DEFAULT NULL,
  `reason` VARCHAR(512) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_catalog_log_operator` FOREIGN KEY (`operator_id`) REFERENCES `user_info` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_catalog_log_actor` CHECK ((`actor_type` = 'USER' AND `operator_id` IS NOT NULL) OR (`actor_type` = 'SYSTEM' AND `operator_id` IS NULL AND `operator_name` IS NULL)),
  CONSTRAINT `ck_catalog_log_action` CHECK (
    (`entity_type` = 'SOURCE_GOODS_ITEM' AND `actor_type` = 'SYSTEM' AND `action_type` IN ('CREATE', 'UPDATE'))
    OR (`entity_type` = 'SYNC_JOB' AND `actor_type` = 'USER' AND `action_type` IN ('START', 'PAUSE', 'RESUME', 'CANCEL'))
  ),
  CONSTRAINT `ck_catalog_log_before` CHECK (`before_data` IS NULL OR `before_data` IS JSON OBJECT),
  CONSTRAINT `ck_catalog_log_after` CHECK (`after_data` IS NULL OR `after_data` IS JSON OBJECT)
);

CREATE TABLE policy_review_round (
  policy_id BIGINT NOT NULL,
  version INT NOT NULL,
  tenant_key VARCHAR(64) NOT NULL,
  settings_version INT NOT NULL,
  stage VARCHAR(24) NOT NULL,
  route_json VARCHAR NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (policy_id, version),
  UNIQUE KEY uk_review_round_tenant (policy_id, version, tenant_key),
  KEY idx_review_round_stage (tenant_key, stage, created_at),
  CONSTRAINT ck_review_round_settings CHECK (settings_version > 0),
  CONSTRAINT ck_review_round_stage CHECK (stage IN ('PRODUCT_REVIEW','PURCHASE_REVIEW','FINISHED')),
  CONSTRAINT ck_review_round_route CHECK (route_json IS JSON OBJECT),
  CONSTRAINT fk_review_round_audit FOREIGN KEY (policy_id, version) REFERENCES policy_audit_log (policy_id, version),
  CONSTRAINT fk_review_round_tenant FOREIGN KEY (policy_id, tenant_key) REFERENCES policy_apply (id, tenant_key)
);

CREATE TABLE policy_review_member (
  id BIGINT NOT NULL AUTO_INCREMENT,
  policy_id BIGINT NOT NULL,
  version INT NOT NULL,
  tenant_key VARCHAR(64) NOT NULL,
  group_code VARCHAR(16) NOT NULL,
  user_id BIGINT NOT NULL,
  display_name VARCHAR(64) NOT NULL,
  department VARCHAR(128) DEFAULT NULL,
  assignment_no INT NOT NULL DEFAULT 1,
  member_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  task_guid VARCHAR(128) DEFAULT NULL,
  active_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN member_status = 'ACTIVE' THEN user_id ELSE NULL END),
  PRIMARY KEY (id),
  UNIQUE KEY uk_review_member_assignment (policy_id, version, group_code, user_id, assignment_no),
  UNIQUE KEY uk_review_member_active (policy_id, version, group_code, active_user_id),
  UNIQUE KEY uk_review_member_group (id, policy_id, version, tenant_key, group_code),
  UNIQUE KEY uk_review_member_round (id, policy_id, version, tenant_key),
  UNIQUE KEY uk_review_member_task (tenant_key, task_guid),
  KEY idx_review_person_work (tenant_key, user_id, member_status, group_code, policy_id, version),
  CONSTRAINT ck_review_member_group CHECK (group_code IN ('PRODUCT','PURCHASE','REVISION')),
  CONSTRAINT ck_review_member_status CHECK (member_status IN ('ACTIVE','REMOVED','HANDLED','PEER_HANDLED')),
  CONSTRAINT ck_review_member_sequence CHECK (assignment_no > 0),
  CONSTRAINT fk_review_member_round FOREIGN KEY (policy_id, version, tenant_key) REFERENCES policy_review_round (policy_id, version, tenant_key),
  CONSTRAINT fk_review_member_user FOREIGN KEY (user_id, tenant_key) REFERENCES user_info (id, tenant_key)
);

CREATE TABLE policy_review_decision (
  policy_id BIGINT NOT NULL,
  version INT NOT NULL,
  group_code VARCHAR(16) NOT NULL,
  tenant_key VARCHAR(64) NOT NULL,
  member_id BIGINT NOT NULL,
  decision VARCHAR(16) NOT NULL,
  reason VARCHAR(512) DEFAULT NULL,
  decided_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (policy_id, version, group_code),
  CONSTRAINT ck_review_decision_group CHECK (group_code IN ('PRODUCT','PURCHASE')),
  CONSTRAINT ck_review_decision CHECK (decision IN ('APPROVE','REJECT')),
  CONSTRAINT ck_review_rejection_reason CHECK (group_code <> 'PRODUCT' OR decision <> 'REJECT'
    OR (reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0)),
  CONSTRAINT fk_review_decision_member FOREIGN KEY (member_id, policy_id, version, tenant_key, group_code)
    REFERENCES policy_review_member (id, policy_id, version, tenant_key, group_code)
);

CREATE TABLE policy_review_reassignment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  policy_id BIGINT NOT NULL,
  version INT NOT NULL,
  tenant_key VARCHAR(64) NOT NULL,
  group_code VARCHAR(16) NOT NULL,
  operator_id BIGINT NOT NULL,
  reason VARCHAR(512) NOT NULL,
  before_members VARCHAR NOT NULL,
  after_members VARCHAR NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_review_reassign_round (policy_id, version, tenant_key, group_code, id),
  CONSTRAINT fk_review_reassign_round FOREIGN KEY (policy_id, version, tenant_key) REFERENCES policy_review_round (policy_id, version, tenant_key),
  CONSTRAINT fk_review_reassign_operator FOREIGN KEY (operator_id, tenant_key) REFERENCES user_info (id, tenant_key),
  CONSTRAINT ck_review_reassign_group CHECK (group_code IN ('PRODUCT','PURCHASE')),
  CONSTRAINT ck_review_reassign_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0),
  CONSTRAINT ck_review_reassign_members CHECK (before_members IS JSON ARRAY AND after_members IS JSON ARRAY)
);

CREATE TABLE policy_review_delivery (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_key VARCHAR(64) NOT NULL,
  policy_id BIGINT DEFAULT NULL,
  version INT DEFAULT NULL,
  event_key VARCHAR(128) NOT NULL,
  action_type VARCHAR(16) NOT NULL,
  recipient_key BIGINT NOT NULL,
  recipient_member_id BIGINT GENERATED ALWAYS AS (NULLIF(recipient_key, 0)),
  payload_json VARCHAR NOT NULL,
  delivery_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME DEFAULT NULL,
  lease_owner VARCHAR(128) DEFAULT NULL,
  lease_until DATETIME DEFAULT NULL,
  receipt_id VARCHAR(128) DEFAULT NULL,
  delivered_at DATETIME DEFAULT NULL,
  last_error VARCHAR(512) DEFAULT NULL,
  notice_key VARCHAR(128) GENERATED ALWAYS AS (CASE WHEN policy_id IS NULL THEN event_key ELSE NULL END),
  UNIQUE KEY uk_delivery_notice (tenant_key, notice_key),
  CONSTRAINT ck_delivery_subject CHECK ((policy_id IS NOT NULL AND version IS NOT NULL) OR (policy_id IS NULL AND version IS NULL AND action_type='GROUP_MESSAGE' AND recipient_key=0)),
  PRIMARY KEY (id),
  UNIQUE KEY uk_review_delivery_event (tenant_key, policy_id, version, event_key, action_type, recipient_key),
  KEY idx_review_delivery_due (delivery_status, next_attempt_at, lease_until, id),
  CONSTRAINT fk_review_delivery_round FOREIGN KEY (policy_id, version, tenant_key) REFERENCES policy_review_round (policy_id, version, tenant_key),
  CONSTRAINT fk_review_delivery_member FOREIGN KEY (recipient_member_id, policy_id, version, tenant_key)
    REFERENCES policy_review_member (id, policy_id, version, tenant_key),
  CONSTRAINT ck_review_delivery_event CHECK (CHAR_LENGTH(TRIM(event_key)) > 0),
  CONSTRAINT ck_review_delivery_recipient CHECK ((action_type = 'GROUP_MESSAGE' AND recipient_key = 0)
    OR (action_type IN ('CREATE_TASK','CLOSE_TASK','SEND_CARD','UPDATE_CARD') AND recipient_key > 0)),
  CONSTRAINT ck_review_delivery_status CHECK (delivery_status IN ('PENDING','SENDING','SENT','RETRY','UNKNOWN','FAILED','CANCELLED')),
  CONSTRAINT ck_review_delivery_attempts CHECK (attempts >= 0),
  CONSTRAINT ck_review_delivery_payload CHECK (payload_json IS JSON OBJECT)
);
