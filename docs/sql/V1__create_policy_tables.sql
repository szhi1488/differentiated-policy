-- 差异化政策管理：公司共用商品目录十四表，MySQL 8.0.16+，2026-11-14。
-- 仅供空库首次建表；在Navicat选中已存在的目标库后整份执行。
-- 不创建数据库、不删除或清空表、不插入真实或示例业务数据。
-- 不使用IF NOT EXISTS掩盖结构差异；如已存在同名表，请停止并核对。
-- MySQL DDL隐式提交，失败后先检查已成功部分，不要直接重跑整份脚本。

SET NAMES utf8mb4;
SET time_zone = '+08:00';

CREATE TABLE `user_info` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '本地用户主键',
  `tenant_key` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '飞书租户标识',
  `open_id` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '飞书应用内用户标识',
  `union_id` VARCHAR(64) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '飞书同一应用开发商下的用户标识',
  `name` VARCHAR(64) NOT NULL COMMENT '用户姓名',
  `department` VARCHAR(128) DEFAULT NULL COMMENT '所属部门',
  `role_type` VARCHAR(32) NOT NULL DEFAULT 'APPLICANT' COMMENT 'APPLICANT-申请人，ADMIN-管理员',
  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0-未删除，1-已删除',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id_tenant` (`id`, `tenant_key`),
  UNIQUE KEY `uk_user_tenant_open_id` (`tenant_key`, `open_id`),

  CONSTRAINT `ck_user_role`
    CHECK (`role_type` IN ('APPLICANT', 'ADMIN')),
  CONSTRAINT `ck_user_deleted`
    CHECK (`is_deleted` IN (0, 1))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='用户与角色映射表';

CREATE TABLE `brand` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '品牌主键',
  `name` VARCHAR(50) NOT NULL COMMENT '清洗后的品牌名称，直接对应吉客云 brandName',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，北京时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_brand_name` (`name`),
  UNIQUE KEY `uk_brand_name_binding` (`id`, `name`),
  CONSTRAINT `ck_brand_name` CHECK (CHAR_LENGTH(TRIM(`name`)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='标准品牌字典';


CREATE TABLE `series` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '系列主键',
  `brand_id` BIGINT NOT NULL COMMENT '所属品牌',
  `name` VARCHAR(64) NOT NULL COMMENT '标准系列名称',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，北京时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_series_binding` (`id`, `brand_id`),
  UNIQUE KEY `uk_series_name` (`brand_id`, `name`),
  CONSTRAINT `fk_series_brand` FOREIGN KEY (`brand_id`) REFERENCES `brand` (`id`),
  CONSTRAINT `ck_series_name` CHECK (CHAR_LENGTH(TRIM(`name`)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='品牌下的标准系列';


CREATE TABLE `spu` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '标准机型主键',
  `brand_id` BIGINT NOT NULL COMMENT '所属品牌',
  `series_id` BIGINT NOT NULL COMMENT '所属系列',
  `name` VARCHAR(100) NOT NULL COMMENT '标准机型名，含常规机型后缀如 Pro',
  `edition` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '另行确认的版本标识，空字符串表示无独立版本',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，北京时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_spu_binding` (`id`, `brand_id`, `series_id`),
  UNIQUE KEY `uk_spu_identity` (`brand_id`, `name`, `edition`),
  KEY `idx_spu_series` (`series_id`, `brand_id`),
  CONSTRAINT `fk_spu_series` FOREIGN KEY (`series_id`, `brand_id`) REFERENCES `series` (`id`, `brand_id`),
  CONSTRAINT `ck_spu_name` CHECK (CHAR_LENGTH(TRIM(`name`)) > 0),
  CONSTRAINT `ck_spu_edition` CHECK (CHAR_LENGTH(`edition`) = CHAR_LENGTH(TRIM(`edition`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='标准机型及独立版本';


CREATE TABLE `color_dict` (
  `id` INT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '颜色主键',
  `color_name` VARCHAR(64) NOT NULL COMMENT '颜色名称',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_color_dict_name` (`color_name`),
  CONSTRAINT `ck_color_dict_name` CHECK (CHAR_LENGTH(TRIM(`color_name`)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='独立颜色表';


CREATE TABLE `goods` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '标准商品主键',
  `sku_id` VARCHAR(64) NOT NULL COMMENT '公司内稳定的本地标准 SKU 编号',
  `product_no` VARCHAR(128) DEFAULT NULL COMMENT '货品编号，对应来源 goodsNo，可重复',
  `brand_id` BIGINT NOT NULL COMMENT '与机型一致的品牌',
  `series_id` BIGINT NOT NULL COMMENT '与机型一致的系列',
  `spu_id` BIGINT NOT NULL COMMENT '所属机型及版本',
  `goods_name` VARCHAR(384) NOT NULL COMMENT '清洗落库后的商品名称',
  `ram` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范运行内存，如16GB',
  `rom` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范机身存储，统一GB，如1TB记为1024GB',
  `color_id` INT UNSIGNED NOT NULL COMMENT '关联独立颜色表的主键',
  `status` TINYINT UNSIGNED DEFAULT NULL COMMENT '销售状态：1-在售，2-待上架，3-已下架，4-已停产，NULL-未知',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，北京时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间，北京时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_goods_brand_binding` (`id`, `brand_id`),
  UNIQUE KEY `uk_goods_policy_binding` (`id`, `spu_id`, `ram`, `rom`),
  UNIQUE KEY `uk_goods_sku` (`sku_id`),
  UNIQUE KEY `uk_goods_spu_specs` (`spu_id`, `ram`, `rom`, `color_id`),
  KEY `idx_goods_spu` (`spu_id`, `brand_id`, `series_id`),
  KEY `idx_goods_color` (`color_id`),
  KEY `idx_goods_catalog` (`brand_id`, `series_id`, `id`),
  KEY `idx_goods_product_no` (`product_no`),
  CONSTRAINT `fk_goods_spu` FOREIGN KEY (`spu_id`, `brand_id`, `series_id`) REFERENCES `spu` (`id`, `brand_id`, `series_id`),
  CONSTRAINT `fk_goods_color` FOREIGN KEY (`color_id`) REFERENCES `color_dict` (`id`),
  CONSTRAINT `ck_goods_names` CHECK (CHAR_LENGTH(TRIM(`sku_id`)) > 0 AND CHAR_LENGTH(TRIM(`goods_name`)) > 0 AND (`product_no` IS NULL OR CHAR_LENGTH(TRIM(`product_no`)) > 0)),
  CONSTRAINT `ck_goods_memory` CHECK (
    REGEXP_LIKE(`ram`, '^[1-9][0-9]{0,7}GB$', 'c') AND RIGHT(`ram`, 2) = 'GB'
    AND REGEXP_LIKE(`rom`, '^[1-9][0-9]{0,7}GB$', 'c') AND RIGHT(`rom`, 2) = 'GB'
  ),
  CONSTRAINT `ck_goods_status` CHECK (`status` IN (1, 2, 3, 4))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='具体机型版本、配置及颜色的标准商品';


CREATE TABLE `goods_sync_job` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '同步任务主键，一个任务只导入一个品牌',
  `source_key` VARCHAR(64) NOT NULL COMMENT '服务端受控账套标识，不保存凭证',
  `brand_name` VARCHAR(50) NOT NULL COMMENT '任务品牌：OPPO手机或一加手机',
  `requester_id` BIGINT NOT NULL COMMENT '发起管理员',
  `idempotency_key` VARCHAR(64) NOT NULL COMMENT '任务创建幂等键',
  `status` VARCHAR(16) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED、RUNNING、PAUSED、SUCCEEDED、CANCELLED',
  `sync_mode` VARCHAR(16) NOT NULL DEFAULT 'FULL' COMMENT 'FULL 全量、CREATED 按建档日、MODIFIED 按变更日',
  `sync_date` DATE DEFAULT NULL COMMENT '按日导入的北京时间日期，全量为空',
  `sync_phase` VARCHAR(16) NOT NULL DEFAULT 'FULL' COMMENT '当前查询阶段，变更日先查货品再查规格',
  `last_max_id` VARCHAR(32) NOT NULL DEFAULT '0' COMMENT '当前品牌及阶段的续拉位置',
  `page_size` INT NOT NULL DEFAULT 50 COMMENT '联调确认范围内的单批数量',
  `include_blockup` TINYINT NOT NULL COMMENT '是否查询停用来源；当前导入固定为 1',
  `page_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已提交页数，含确认结束的空页',
  `read_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计接收条次，重放可重复，非真实SKU总数',
  `content_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前任务已提交的不同 SKU 数',
  `failure_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '失败尝试次数',
  `error_code` VARCHAR(64) DEFAULT NULL COMMENT '脱敏错误类别，不存原始响应或凭证',
  `worker_token` VARCHAR(64) DEFAULT NULL COMMENT '当前执行者租约标识，不是上游凭证',
  `lease_until` DATETIME DEFAULT NULL COMMENT '租约到期时间',
  `next_request_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最早可再次查询的时间，包含上游限流冷却',
  `fencing_token` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '接管代次，旧执行者必须条件写入',
  `record_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '并发版本',
  `started_at` DATETIME DEFAULT NULL COMMENT '开始时间',
  `finished_at` DATETIME DEFAULT NULL COMMENT '结束时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，北京时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间，北京时间',
  `active_slot` TINYINT GENERATED ALWAYS AS (CASE WHEN `status` IN ('QUEUED', 'RUNNING', 'PAUSED') THEN 1 ELSE NULL END) STORED COMMENT '同一账套只保留一个活动任务，暂停也占位',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_job_id_scope` (`id`, `source_key`, `brand_name`),
  UNIQUE KEY `uk_job_idempotency` (`source_key`, `idempotency_key`),
  UNIQUE KEY `uk_job_active` (`source_key`, `active_slot`),
  KEY `idx_job_requester` (`requester_id`),
  KEY `idx_job_brand_latest` (`source_key`, `brand_name`, `id`),
  KEY `idx_job_cooldown` (`source_key`, `status`, `next_request_at`),
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='单品牌原始导入任务及最新断点';


CREATE TABLE `catalog_clean_job` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '独立清洗任务主键',
  `source_key` VARCHAR(64) NOT NULL,
  `brand_name` VARCHAR(50) NOT NULL,
  `created_date` DATE DEFAULT NULL COMMENT '清洗范围的建档日，空表示品牌全部',
  `include_reviews` TINYINT NOT NULL DEFAULT 0 COMMENT '是否同时重新识别未映射的待审商品',
  `target_source_id` BIGINT DEFAULT NULL COMMENT '单条技术重试时的来源商品 ID',
  `request_key` VARCHAR(64) NOT NULL COMMENT '确认提交的幂等键',
  `requester_id` BIGINT NOT NULL,
  `requester_name` VARCHAR(64) DEFAULT NULL COMMENT '发起人姓名快照',
  `status` VARCHAR(16) NOT NULL COMMENT 'RUNNING、COMPLETED、PARTIAL、FAILED',
  `worker_token` VARCHAR(64) DEFAULT NULL,
  `lease_until` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `active_slot` TINYINT GENERATED ALWAYS AS (CASE WHEN `status` = 'RUNNING' THEN 1 ELSE NULL END) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_clean_job_scope` (`id`, `source_key`, `brand_name`),
  UNIQUE KEY `uk_clean_job_request` (`source_key`, `request_key`),
  UNIQUE KEY `uk_clean_job_active` (`source_key`, `active_slot`),
  KEY `idx_clean_job_history` (`source_key`, `id`),
  KEY `idx_clean_job_requester` (`requester_id`),
  CONSTRAINT `fk_clean_job_requester` FOREIGN KEY (`requester_id`) REFERENCES `user_info` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_clean_job_brand` CHECK (`brand_name` IN ('OPPO手机', '一加手机')),
  CONSTRAINT `ck_clean_job_status` CHECK (`status` IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
  CONSTRAINT `ck_clean_job_scope` CHECK (`include_reviews` IN (0, 1) AND (`target_source_id` IS NULL OR (`created_date` IS NULL AND `include_reviews` = 0))),
  CONSTRAINT `ck_clean_job_lease` CHECK ((`worker_token` IS NULL AND `lease_until` IS NULL) OR (`status` = 'RUNNING' AND `worker_token` IS NOT NULL AND `lease_until` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='按本地商品范围手动启动的清洗任务';


CREATE TABLE `source_goods_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '本地来源商品稳定主键',
  `source_key` VARCHAR(64) NOT NULL COMMENT '受控账套标识',
  `brand_name` VARCHAR(50) NOT NULL COMMENT '品牌查询分区，不取上游返回字段',
  `goods_name` VARCHAR(512) DEFAULT NULL COMMENT '来源goodsName原文',
  `goods_no` VARCHAR(128) DEFAULT NULL COMMENT '来源goodsNo原文，不作SKU唯一键',
  `sku_name` VARCHAR(256) DEFAULT NULL COMMENT '来源skuName原文',
  `cate_name` VARCHAR(256) DEFAULT NULL COMMENT '来源cateName原文',
  `goods_field4` VARCHAR(1024) DEFAULT NULL COMMENT '来源goodsField4原文',
  `goods_field1` VARCHAR(1024) DEFAULT NULL COMMENT '来源goodsField1原文',
  `goods_field5` VARCHAR(1024) DEFAULT NULL COMMENT '来源goodsField5原文',
  `sku_id` VARCHAR(19) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '吉客云 SKU ID，公司源商品库唯一',
  `cate_full_name` VARCHAR(1024) DEFAULT NULL COMMENT '吉客云完整分类路径原文',
  `gmt_modified` BIGINT UNSIGNED DEFAULT NULL COMMENT '规格修改时间，Unix 毫秒',
  `goods_gmt_modified` BIGINT UNSIGNED DEFAULT NULL COMMENT '货品修改时间，Unix 毫秒',
  `is_blockup` TINYINT UNSIGNED DEFAULT NULL COMMENT '吉客云停用标记，NULL 表示未知',
  `is_package_good` TINYINT UNSIGNED DEFAULT NULL COMMENT '吉客云组合装标记，NULL 表示未知',
  `first_seen_job_id` BIGINT NOT NULL COMMENT '首次写入的同步任务',
  `last_seen_job_id` BIGINT NOT NULL COMMENT '最近出现的同步任务，仅当前信息不作批次历史',
  `mapping_status` VARCHAR(16) NOT NULL DEFAULT 'UNMAPPED' COMMENT 'UNMAPPED-待归类，MAPPED-已确认，EXCLUDED-排除',
  `catalog_brand_id` BIGINT DEFAULT NULL COMMENT '与来源品牌名称相同的标准品牌',
  `goods_id` BIGINT DEFAULT NULL COMMENT '自动清洗或人工审核确认的标准商品',
  `mapping_remark` VARCHAR(512) DEFAULT NULL COMMENT '归类或排除原因',
  `mapping_origin` VARCHAR(16) DEFAULT NULL COMMENT '生效映射来源：AUTO-自动清洗，MANUAL-人工审核',
  `clean_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING、PROCESSING、AUTO_PASSED、REVIEW_REQUIRED、MANUAL_PASSED、ERROR、EXCLUDED',
  `clean_job_id` BIGINT DEFAULT NULL COMMENT '最近生成清洗或审核结果的任务',
  `clean_candidate` JSON DEFAULT NULL COMMENT '本地候选及白名单字段证据，禁止完整响应',
  `clean_error_code` VARCHAR(64) DEFAULT NULL COMMENT '缺失、歧义、冲突或技术错误类别',
  `clean_attempts` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '清洗尝试次数',
  `cleaned_at` DATETIME DEFAULT NULL COMMENT '最近一次清洗或人工审核结束时间',
  `reviewed_by` BIGINT DEFAULT NULL COMMENT '必要时人工审核人，自动通过必须为空',
  `reviewed_at` DATETIME DEFAULT NULL COMMENT '人工审核时间',
  `review_reason` VARCHAR(512) DEFAULT NULL COMMENT '人工纠正或排除的原因',
  `record_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '防止清洗或审核使用已变化的来源记录',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，北京时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '来源或清洗结果的更新时间，由应用在实际变更时写入',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_sku` (`sku_id`),
  KEY `idx_source_brand_binding` (`catalog_brand_id`, `brand_name`),
  KEY `idx_source_goods` (`goods_id`, `catalog_brand_id`),
  KEY `idx_source_first_job` (`first_seen_job_id`, `source_key`, `brand_name`),
  KEY `idx_source_last_job` (`last_seen_job_id`, `source_key`, `brand_name`),
  KEY `idx_source_scope_list` (`source_key`, `id`),
  KEY `idx_source_brand_list` (`source_key`, `brand_name`, `id`),
  KEY `idx_source_work` (`brand_name`, `mapping_status`),
  KEY `idx_source_goods_no` (`goods_no`),
  KEY `idx_source_clean_queue` (`clean_status`, `id`),
  KEY `idx_source_clean_job` (`clean_job_id`, `source_key`, `brand_name`),
  KEY `idx_source_reviewer` (`reviewed_by`),
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
  CONSTRAINT `ck_source_candidate` CHECK (`clean_candidate` IS NULL OR JSON_TYPE(`clean_candidate`) = 'OBJECT'),
  CONSTRAINT `ck_source_auto_pass` CHECK (`clean_status` <> 'AUTO_PASSED' OR (`mapping_status` = 'MAPPED' AND `mapping_origin` IS NOT NULL AND `mapping_origin` = 'AUTO' AND `clean_job_id` IS NOT NULL AND `clean_candidate` IS NOT NULL AND `cleaned_at` IS NOT NULL AND `clean_error_code` IS NULL AND `reviewed_by` IS NULL AND `reviewed_at` IS NULL AND `review_reason` IS NULL)),
  CONSTRAINT `ck_source_manual_pass` CHECK (`clean_status` <> 'MANUAL_PASSED' OR (`mapping_status` = 'MAPPED' AND `mapping_origin` IS NOT NULL AND `mapping_origin` = 'MANUAL' AND `reviewed_by` IS NOT NULL AND `reviewed_at` IS NOT NULL AND `review_reason` IS NOT NULL AND CHAR_LENGTH(TRIM(`review_reason`)) > 0 AND `cleaned_at` IS NOT NULL AND `clean_error_code` IS NULL)),
  CONSTRAINT `ck_source_clean_issue` CHECK (`clean_status` NOT IN ('REVIEW_REQUIRED', 'ERROR') OR (`clean_error_code` IS NOT NULL AND CHAR_LENGTH(TRIM(`clean_error_code`)) > 0)),
  CONSTRAINT `ck_source_clean_excluded` CHECK ((`clean_status` = 'EXCLUDED' AND `mapping_status` = 'EXCLUDED' AND `reviewed_by` IS NOT NULL AND `reviewed_at` IS NOT NULL AND `review_reason` IS NOT NULL AND CHAR_LENGTH(TRIM(`review_reason`)) > 0 AND `cleaned_at` IS NOT NULL) OR (`clean_status` <> 'EXCLUDED' AND `mapping_status` <> 'EXCLUDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='当前 SKU 来源字段及内联标准映射';


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


CREATE TABLE `catalog_operation_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '商品导入记录主键',
  `actor_type` VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT 'USER-人工操作，SYSTEM-后台导入',
  `operator_id` BIGINT DEFAULT NULL COMMENT '人工操作用户，SYSTEM必须为空，不冒用发起人',
  `operator_name` VARCHAR(64) DEFAULT NULL COMMENT '操作人姓名快照',
  `entity_type` VARCHAR(32) NOT NULL COMMENT 'SOURCE_GOODS_ITEM-来源商品，SYNC_JOB-导入任务',
  `entity_id` BIGINT NOT NULL COMMENT '对应本地对象主键，由服务端校验类型和归属',
  `action_type` VARCHAR(32) NOT NULL COMMENT '来源建档日、来源更新或导入任务操作',
  `batch_key` VARCHAR(64) DEFAULT NULL COMMENT '本次批量操作关联号，不是外部商品ID',
  `before_data` JSON DEFAULT NULL COMMENT '本地字段变更前对象，不存完整上游响应或外部SKU标识',
  `after_data` JSON DEFAULT NULL COMMENT '本地字段变更后对象，任务快照须剔除游标及执行令牌',
  `reason` VARCHAR(512) DEFAULT NULL COMMENT '导入或变更说明',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_catalog_log_entity` (`entity_type`, `entity_id`, `id`),
  KEY `idx_catalog_log_operator` (`operator_id`, `created_at`),
  KEY `idx_catalog_log_batch` (`batch_key`),
  CONSTRAINT `fk_catalog_log_operator` FOREIGN KEY (`operator_id`) REFERENCES `user_info` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_catalog_log_actor` CHECK ((`actor_type` = 'USER' AND `operator_id` IS NOT NULL) OR (`actor_type` = 'SYSTEM' AND `operator_id` IS NULL AND `operator_name` IS NULL)),
  CONSTRAINT `ck_catalog_log_action` CHECK (
    (`entity_type` = 'SOURCE_GOODS_ITEM' AND `actor_type` = 'SYSTEM' AND `action_type` IN ('CREATE', 'UPDATE'))
    OR (`entity_type` = 'SYNC_JOB' AND `actor_type` = 'USER' AND `action_type` IN ('START', 'PAUSE', 'RESUME', 'CANCEL'))
  ),
  CONSTRAINT `ck_catalog_log_before` CHECK (`before_data` IS NULL OR JSON_TYPE(`before_data`) = 'OBJECT'),
  CONSTRAINT `ck_catalog_log_after` CHECK (`after_data` IS NULL OR JSON_TYPE(`after_data`) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='商品导入记录与来源建档日证据';


CREATE TABLE `policy_master` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '政策主键',
  `tenant_key` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '申请所属租户，由会话确定',
  `policy_code` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '政策单号，创建草稿时生成',

  `applicant_id` BIGINT NOT NULL COMMENT '申请人，关联 user_info.id',
  `applicant_name` VARCHAR(64) DEFAULT NULL COMMENT '申请人姓名快照',

  `perspective` VARCHAR(32) NOT NULL COMMENT 'RETAIL-零售，BILLION-百亿',
  `channel_type` VARCHAR(32) NOT NULL COMMENT 'RETAIL_CHANNEL-零售渠道，BILLION_CHANNEL-百亿渠道',
  `selected_platforms` JSON DEFAULT NULL COMMENT '已选具体平台编码数组，不保存 ALL',

  `verify_type` VARCHAR(32) DEFAULT NULL COMMENT 'SALES-销售维度，ACTIVATION-激活维度；百亿为空',
  `scene_type` VARCHAR(32) DEFAULT NULL COMMENT 'NATIONAL_SUB-国补，LOCAL_SUB-地补，SELF_SUB-自补；百亿为空',
  `is_estimated` TINYINT DEFAULT NULL COMMENT '0-否，1-是；百亿及未填写的草稿为空',

  `start_time` DATETIME DEFAULT NULL COMMENT '政策开始时间，草稿可为空',
  `end_time` DATETIME DEFAULT NULL COMMENT '政策结束时间，草稿可为空',
  `special_remark` TEXT COMMENT '特别备注',
  `attachment_info` JSON DEFAULT NULL COMMENT '附件及链接信息数组，不保存文件二进制内容',

  `status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT-草稿，PENDING-待审核，APPROVED-已通过，REJECTED-已拒绝',
  `submission_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '提交版本，未提交为0，每次提交或重提递增',
  `record_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '并发控制版本号，每次修改递增',

  `reviewer_id` BIGINT DEFAULT NULL COMMENT '最近一次审核人，关联 user_info.id',
  `reviewer_name` VARCHAR(64) DEFAULT NULL COMMENT '最近一次审核人姓名快照',
  `review_remark` VARCHAR(512) DEFAULT NULL COMMENT '最近一次审核意见，完整历史保存在日志表',
  `review_time` DATETIME DEFAULT NULL COMMENT '最近一次审核时间',

  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '0-未删除，1-已删除',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_policy_id_tenant` (`id`, `tenant_key`),
  UNIQUE KEY `uk_policy_code` (`policy_code`),
  KEY `idx_policy_applicant_status` (`applicant_id`, `tenant_key`, `status`, `created_at`),
  KEY `idx_policy_perspective_status` (`tenant_key`, `perspective`, `status`, `created_at`),
  KEY `idx_policy_time_range` (`start_time`, `end_time`),
  KEY `idx_policy_reviewer` (`reviewer_id`, `tenant_key`),

  CONSTRAINT `fk_policy_applicant`
    FOREIGN KEY (`applicant_id`, `tenant_key`) REFERENCES `user_info` (`id`, `tenant_key`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_policy_reviewer`
    FOREIGN KEY (`reviewer_id`, `tenant_key`) REFERENCES `user_info` (`id`, `tenant_key`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,

  CONSTRAINT `ck_policy_channel`
    CHECK (
      (`perspective` = 'RETAIL' AND `channel_type` = 'RETAIL_CHANNEL')
      OR
      (`perspective` = 'BILLION' AND `channel_type` = 'BILLION_CHANNEL')
    ),

  CONSTRAINT `ck_policy_platform_array`
    CHECK (
      `selected_platforms` IS NULL
      OR JSON_TYPE(`selected_platforms`) = 'ARRAY'
    ),

  CONSTRAINT `ck_policy_attachment_array`
    CHECK (
      `attachment_info` IS NULL
      OR JSON_TYPE(`attachment_info`) = 'ARRAY'
    ),

  CONSTRAINT `ck_policy_verify_type`
    CHECK (
      `verify_type` IS NULL
      OR `verify_type` IN ('SALES', 'ACTIVATION')
    ),

  CONSTRAINT `ck_policy_scene_type`
    CHECK (
      `scene_type` IS NULL
      OR `scene_type` IN ('NATIONAL_SUB', 'LOCAL_SUB', 'SELF_SUB')
    ),

  CONSTRAINT `ck_policy_estimated`
    CHECK (`is_estimated` IS NULL OR `is_estimated` IN (0, 1)),

  CONSTRAINT `ck_policy_billion_fields`
    CHECK (
      `perspective` <> 'BILLION'
      OR (
        `verify_type` IS NULL
        AND `scene_type` IS NULL
        AND `is_estimated` IS NULL
      )
    ),

  CONSTRAINT `ck_policy_time_range`
    CHECK (
      `start_time` IS NULL
      OR `end_time` IS NULL
      OR `start_time` < `end_time`
    ),

  CONSTRAINT `ck_policy_status`
    CHECK (`status` IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED')),

  CONSTRAINT `ck_policy_deleted`
    CHECK (`is_deleted` IN (0, 1))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='政策申请主表';

CREATE TABLE `policy_detail` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '明细主键',
  `policy_id` BIGINT NOT NULL COMMENT '关联 policy_master.id',

  `platform_code` VARCHAR(32) NOT NULL COMMENT '具体平台编码，不保存 ALL',

  `spu_id` BIGINT DEFAULT NULL COMMENT '标准机型及版本，草稿可暂未选',
  `ram` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '配置身份的运行内存，统一GB',
  `rom` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '配置身份的机身存储，统一GB',
  `brand_name_snapshot` VARCHAR(50) DEFAULT NULL COMMENT '提交时品牌快照',
  `series_name_snapshot` VARCHAR(64) DEFAULT NULL COMMENT '提交时系列快照',
  `color_scope` VARCHAR(32) DEFAULT NULL COMMENT 'ALL_COLORS-不限颜色，SPECIFIC_COLOR-指定颜色；NULL仅为草稿未选择',
  `goods_id` BIGINT DEFAULT NULL COMMENT '指定颜色时引用当前配置下的标准商品',
  `model_full_name` VARCHAR(384) DEFAULT NULL COMMENT '涉及型号全称快照，草稿可为空',
  `single_model` VARCHAR(100) DEFAULT NULL COMMENT '单型号快照，草稿可为空',
  `spec` VARCHAR(64) DEFAULT NULL COMMENT '配置快照，草稿可为空',
  `color` VARCHAR(64) DEFAULT NULL COMMENT '指定颜色的名称快照，不限颜色时为NULL',
  `quantity` INT DEFAULT NULL COMMENT '该平台该机型的数量；未填写为NULL，不自动记为0',

  `subsidy_mode` VARCHAR(32) DEFAULT NULL COMMENT 'FIXED-固定补贴，RATIO-比例补贴；草稿可为空',
  `fixed_amount` DECIMAL(10,2) DEFAULT NULL COMMENT '固定补贴金额',
  `max_subsidy_amount` DECIMAL(10,2) DEFAULT NULL COMMENT '固定补贴上限金额，具体作用范围待确认',
  `subsidy_ratio` DECIMAL(5,2) DEFAULT NULL COMMENT '补贴比例，百分数；10.00表示10%',
  `base_price_type` VARCHAR(32) DEFAULT NULL COMMENT 'RETAIL_PRICE-零售价，POLICY_PRICE-政策价，HAND_PRICE-到手价，LOCAL_ACTUAL-地补实际，MERCHANT_CONTROL-商家配控',

  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

  PRIMARY KEY (`id`),
  KEY `idx_detail_policy_platform` (`policy_id`, `platform_code`),
  KEY `idx_detail_scope` (`platform_code`, `spu_id`, `ram`, `rom`, `color_scope`, `goods_id`),
  KEY `idx_detail_spu` (`spu_id`),
  KEY `idx_detail_goods` (`goods_id`, `spu_id`, `ram`, `rom`),

  CONSTRAINT `fk_detail_policy`
    FOREIGN KEY (`policy_id`) REFERENCES `policy_master` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,

  CONSTRAINT `fk_detail_spu` FOREIGN KEY (`spu_id`) REFERENCES `spu` (`id`),
  CONSTRAINT `fk_detail_goods` FOREIGN KEY (`goods_id`, `spu_id`, `ram`, `rom`) REFERENCES `goods` (`id`, `spu_id`, `ram`, `rom`),
  CONSTRAINT `ck_detail_memory` CHECK (
    (`ram` IS NULL AND `rom` IS NULL)
    OR (`spu_id` IS NOT NULL AND `ram` IS NOT NULL AND `rom` IS NOT NULL
      AND REGEXP_LIKE(`ram`, '^[1-9][0-9]{0,7}GB$', 'c') AND RIGHT(`ram`, 2) = 'GB'
      AND REGEXP_LIKE(`rom`, '^[1-9][0-9]{0,7}GB$', 'c') AND RIGHT(`rom`, 2) = 'GB')
  ),
  CONSTRAINT `ck_detail_color_scope` CHECK (
    ((`color_scope` IS NULL OR `color_scope` = 'ALL_COLORS') AND `goods_id` IS NULL AND `color` IS NULL)
    OR (`color_scope` IS NOT NULL AND `color_scope` = 'SPECIFIC_COLOR'
      AND `spu_id` IS NOT NULL AND `ram` IS NOT NULL AND `rom` IS NOT NULL
      AND ((`goods_id` IS NULL AND `color` IS NULL)
        OR (`goods_id` IS NOT NULL AND `color` IS NOT NULL AND CHAR_LENGTH(TRIM(`color`)) > 0)))
  ),

  CONSTRAINT `ck_detail_platform`
    CHECK (
      `platform_code` IN (
        'KUAISHOU', 'PDD', 'JD', 'DOUYIN', 'TMALL',
        'TAOBAO', 'ZHAOSHANG', 'DEWU', 'UNICOM', 'GOV_ENTERPRISE'
      )
    ),

  CONSTRAINT `ck_detail_quantity`
    CHECK (`quantity` IS NULL OR `quantity` >= 0),

  CONSTRAINT `ck_detail_subsidy_mode`
    CHECK (
      `subsidy_mode` IS NULL
      OR `subsidy_mode` IN ('FIXED', 'RATIO')
    ),

  CONSTRAINT `ck_detail_fixed_amount`
    CHECK (`fixed_amount` IS NULL OR `fixed_amount` >= 0),

  CONSTRAINT `ck_detail_max_amount`
    CHECK (`max_subsidy_amount` IS NULL OR `max_subsidy_amount` >= 0),

  CONSTRAINT `ck_detail_ratio`
    CHECK (`subsidy_ratio` IS NULL OR `subsidy_ratio` >= 0),

  CONSTRAINT `ck_detail_base_price`
    CHECK (
      `base_price_type` IS NULL
      OR `base_price_type` IN (
        'RETAIL_PRICE', 'POLICY_PRICE', 'HAND_PRICE',
        'LOCAL_ACTUAL', 'MERCHANT_CONTROL'
      )
    ),

  CONSTRAINT `ck_detail_subsidy_exclusive`
    CHECK (
      (
        `subsidy_mode` IS NOT NULL
        AND `subsidy_mode` = 'FIXED'
        AND `subsidy_ratio` IS NULL
        AND `base_price_type` IS NULL
      )
      OR (
        `subsidy_mode` IS NOT NULL
        AND `subsidy_mode` = 'RATIO'
        AND `fixed_amount` IS NULL
        AND `max_subsidy_amount` IS NULL
      )
      OR (
        `subsidy_mode` IS NULL
        AND `fixed_amount` IS NULL
        AND `max_subsidy_amount` IS NULL
        AND `subsidy_ratio` IS NULL
        AND `base_price_type` IS NULL
      )
    )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='政策明细：平台、机型、数量与补贴规则';

CREATE TABLE `policy_audit_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '日志主键',
  `tenant_key` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '与政策和操作人相同的租户',
  `policy_id` BIGINT NOT NULL COMMENT '关联 policy_master.id',
  `submission_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '对应的提交版本，首次提交前为0',

  `operator_id` BIGINT NOT NULL COMMENT '操作人，关联 user_info.id',
  `operator_name` VARCHAR(64) DEFAULT NULL COMMENT '操作人姓名快照',

  `action_type` VARCHAR(32) NOT NULL COMMENT 'CREATE_DRAFT、UPDATE_DRAFT、SUBMIT、APPROVE、REJECT、RESUBMIT',
  `from_status` VARCHAR(32) DEFAULT NULL COMMENT '操作前状态，创建时为空',
  `to_status` VARCHAR(32) NOT NULL COMMENT '操作后状态',
  `action_desc` VARCHAR(255) DEFAULT NULL COMMENT '操作描述',
  `review_remark` VARCHAR(512) DEFAULT NULL COMMENT '本次审核意见，不随后续重提覆盖',
  `submission_snapshot` JSON DEFAULT NULL COMMENT '提交或重提时的完整主表及明细快照',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',

  PRIMARY KEY (`id`),
  KEY `idx_log_policy_version` (`policy_id`, `tenant_key`, `submission_version`, `id`),
  KEY `idx_log_operator_time` (`operator_id`, `tenant_key`, `created_at`),

  CONSTRAINT `fk_log_policy`
    FOREIGN KEY (`policy_id`, `tenant_key`) REFERENCES `policy_master` (`id`, `tenant_key`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_log_operator`
    FOREIGN KEY (`operator_id`, `tenant_key`) REFERENCES `user_info` (`id`, `tenant_key`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,

  CONSTRAINT `ck_log_action`
    CHECK (
      `action_type` IN (
        'CREATE_DRAFT', 'UPDATE_DRAFT', 'SUBMIT',
        'APPROVE', 'REJECT', 'RESUBMIT'
      )
    ),

  CONSTRAINT `ck_log_from_status`
    CHECK (
      `from_status` IS NULL
      OR `from_status` IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED')
    ),

  CONSTRAINT `ck_log_to_status`
    CHECK (`to_status` IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED')),

  CONSTRAINT `ck_log_submission_snapshot`
    CHECK (
      `action_type` NOT IN ('SUBMIT', 'RESUBMIT')
      OR (
        `submission_version` > 0
        AND `submission_snapshot` IS NOT NULL
        AND JSON_TYPE(`submission_snapshot`) = 'OBJECT'
      )
    ),

  CONSTRAINT `ck_log_review_version`
    CHECK (
      `action_type` NOT IN ('APPROVE', 'REJECT')
      OR `submission_version` > 0
    )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='政策操作、提交快照与审核历史';
