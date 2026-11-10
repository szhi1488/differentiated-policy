-- 差异化政策管理：四张业务表的新建脚本
-- 适用 MySQL 8.0.16 及以上；执行前在 Navicat 选中目标数据库。
-- 仅供新建空表；不含删表、清空或真实业务初始化数据。
-- 如存在同名表，应核对现有结构并另写增量迁移，不要直接重跑本脚本。

SET NAMES utf8mb4;
SET time_zone = '+08:00';


-- ========================================
-- 1. 用户与角色映射表
-- ========================================
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
  UNIQUE KEY `uk_user_tenant_open_id` (`tenant_key`, `open_id`),

  CONSTRAINT `ck_user_role`
    CHECK (`role_type` IN ('APPLICANT', 'ADMIN')),
  CONSTRAINT `ck_user_deleted`
    CHECK (`is_deleted` IN (0, 1))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='用户与角色映射表';


-- ========================================
-- 2. 政策申请主表
-- ========================================
CREATE TABLE `policy_master` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '政策主键',
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
  UNIQUE KEY `uk_policy_code` (`policy_code`),
  KEY `idx_policy_applicant_status` (`applicant_id`, `status`, `created_at`),
  KEY `idx_policy_perspective_status` (`perspective`, `status`, `created_at`),
  KEY `idx_policy_time_range` (`start_time`, `end_time`),
  KEY `idx_policy_reviewer` (`reviewer_id`),

  CONSTRAINT `fk_policy_applicant`
    FOREIGN KEY (`applicant_id`) REFERENCES `user_info` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_policy_reviewer`
    FOREIGN KEY (`reviewer_id`) REFERENCES `user_info` (`id`)
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


-- ========================================
-- 3. 政策明细表
-- 每行：一个平台下的一个机型、数量及补贴
-- ========================================
CREATE TABLE `policy_detail` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '明细主键',
  `policy_id` BIGINT NOT NULL COMMENT '关联 policy_master.id',

  `platform_code` VARCHAR(32) NOT NULL COMMENT '具体平台编码，不保存 ALL',

  `model_full_name` VARCHAR(128) DEFAULT NULL COMMENT '涉及型号全称快照，草稿可为空',
  `single_model` VARCHAR(64) DEFAULT NULL COMMENT '单型号快照，草稿可为空',
  `spec` VARCHAR(64) DEFAULT NULL COMMENT '配置快照，草稿可为空',
  `color` VARCHAR(32) DEFAULT NULL COMMENT '颜色，非必填',
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
  KEY `idx_detail_platform_model` (`platform_code`, `single_model`, `spec`),

  CONSTRAINT `fk_detail_policy`
    FOREIGN KEY (`policy_id`) REFERENCES `policy_master` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,

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


-- ========================================
-- 4. 政策操作与审核日志表
-- ========================================
CREATE TABLE `policy_audit_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '日志主键',
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
  KEY `idx_log_policy_version` (`policy_id`, `submission_version`, `id`),
  KEY `idx_log_operator_time` (`operator_id`, `created_at`),

  CONSTRAINT `fk_log_policy`
    FOREIGN KEY (`policy_id`) REFERENCES `policy_master` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_log_operator`
    FOREIGN KEY (`operator_id`) REFERENCES `user_info` (`id`)
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
