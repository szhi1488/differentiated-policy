# 差异化政策管理数据库设计

更新日期：2026-11-10。适用数据库：MySQL 8.0.16 及以上。

本版采用四张业务表，保存政策申请、平台机型明细、用户角色及审核历史。建表脚本为 [V1__create_policy_tables.sql](sql/V1__create_policy_tables.sql)。文末包含与独立脚本一致的完整 SQL。

当前交付状态：用户最新 Navicat 截图显示四张表均已存在。本次已核对本机 `differentiated policy` 库的 `user_info` 字段并接入飞书登录落库，真实 MySQL 的临时写入验证已通过并回滚，未改表结构。政策主表、明细表和审核日志尚未接入业务持久化；其实际约束未逐项回读核验。SQL 文件名用于版本追踪，不表示启用 Flyway 或其他自动迁移工具；不要对已有表重跑新建脚本。

## 1. 已明确的业务与设计边界

- 录入顺序为“选择零售或百亿 → 选择平台 → 选择该平台下的机型，填写数量和补贴”。一条明细对应一个平台下的一个机型，数量属于这一条明细；跨平台数量分别保存。
- 同一机型在不同平台可以有不同补贴；部分或全部平台共享补贴时，保存为各具体平台的明细，由录入界面提供批量填写能力。不能把各平台数量直接当成同一个共享数量重复填充。
- “全部”是选择操作，保存操作当时明确的平台集合，不保存 `ALL`。历史政策不会因为平台列表变化自动增加适用平台。
- 草稿允许不完整，提交时完整校验；申请被拒绝后可以修改重提，每次提交和审核历史必须保留。
- 用户说明型号、单型号、配置来自同一张机型表，但其主键字段及类型尚不清楚。本版暂存文本快照，不凭空添加关联 ID，也不将名称当作可靠唯一键；正式对接机型库前必须核对稳定标识。
- 本次不定义政策覆盖优先级和旧政策失效逻辑。`APPROVED` 仅表示审核通过，不能直接解释成当前生效。

## 2. 表职责与关联关系

| 表 | 职责 | 关键关联 |
| --- | --- | --- |
| `user_info` | 飞书身份与本地角色映射 | `(tenant_key, open_id)` 唯一 |
| `policy_master` | 申请基本信息、已选平台、当前状态及版本 | `applicant_id`、`reviewer_id` 关联 `user_info.id` |
| `policy_detail` | 平台下的机型、颜色、数量及补贴参数 | `policy_id` 关联 `policy_master.id` |
| `policy_audit_log` | 操作记录、每次提交快照和独立审核意见 | `policy_id` 关联申请，`operator_id` 关联 `user_info.id` |

与原始草案相比，`applicant_id`、`reviewer_id`、`operator_id` 均从飞书 `open_id` 文本改为本地用户 `BIGINT` 主键。姓名字段作为历史快照保留。所有外键使用 `RESTRICT`，不级联删除历史。

明细及日志跟随主表权限读取。主表逻辑删除后，应在业务查询中同步排除其明细；日志不随主表编辑而修改或清空。

## 3. 字段与校验约定

### 平台与渠道

| 平台 | 编码 | 零售 | 百亿 |
| --- | --- | --- | --- |
| 快手 | `KUAISHOU` | 支持 | 不支持 |
| 拼多多 | `PDD` | 支持 | 支持 |
| 京东 | `JD` | 支持 | 不支持 |
| 抖音 | `DOUYIN` | 支持 | 不支持 |
| 天猫 | `TMALL` | 支持 | 不支持 |
| 淘宝 | `TAOBAO` | 支持 | 支持 |
| 招商 | `ZHAOSHANG` | 支持 | 不支持 |
| 得物 | `DEWU` | 支持 | 不支持 |
| 联通 | `UNICOM` | 支持 | 支持 |
| 政企 | `GOV_ENTERPRISE` | 支持 | 不支持 |

`selected_platforms` 保存字符串数组，例如 `["PDD", "TAOBAO"]`；未填写的草稿可为 SQL `NULL` 或空数组。后端必须校验数组元素、去重、渠道可选范围，以及每条明细的平台属于该集合。JSON 类型本身不完成这些业务校验。

百亿的 `verify_type`、`scene_type`、`is_estimated` 必须为 SQL `NULL`；零售提交时三者必填。切换渠道或减少已选平台时，需要同步处理不再适用的明细，不能静默保留无效组合。

### 草稿、机型和补贴

- 草稿也必须有政策单号、申请人和渠道视角；尚未选择平台或机型时，不必创建空明细。创建明细时至少已有所属申请和具体平台。
- 机型名称、单型号、配置、补贴方式、政策时间允许草稿暂缺。后端只允许申请人编辑本人草稿及已拒绝申请；提交必须完成相应渠道的必填校验。
- 颜色和数量可为空，SQL `NULL` 不代表某种默认颜色，也不代表数量为零。本版数量允许非负整数；零值业务含义尚未确认，不自动填零。
- 固定补贴使用 `fixed_amount`、`max_subsidy_amount`，比例补贴使用 `subsidy_ratio`、`base_price_type`。切换方式先清空另一组字段。数据库约束允许当前方式的字段暂缺，完整性由正式提交校验保障。
- 同平台下同机型的重复或冲突明细由后端识别并提示；本版没有基于名称添加唯一索引。实际机型及颜色识别口径须在主数据对接后确定，不能依赖字符串模糊匹配或最后一条记录取胜。
- 金额使用 `DECIMAL(10,2)`，比例沿用原始 SQL 草案的 `DECIMAL(5,2)` 百分数表示法，`10.00` 表示 `10%`。这是本版存储约定，不表示已确认最终精度、业务上限或舍入口径。后端使用 `BigDecimal`，拒绝超出存储精度或范围的输入，不能依赖数据库隐式截断或舍入。
- `DECIMAL(5,2)` 最大可存 `999.99`；脚本只限制非负，不擅自将业务上限设为 `100%`。固定补贴上限的作用范围仍待确认。

### 时间、附件和用户

- 本版时间字段使用 `DATETIME`，精确到秒，按 `Asia/Shanghai` 对应的北京时间解释；`DATETIME` 本身不携带时区。后端、数据库连接及界面需按相同约定转换。脚本的 `SET time_zone = '+08:00'` 仅设置执行脚本的当前连接，不配置后续连接。
- 起止时间同时填写时必须开始早于结束；覆盖检测的端点包含规则仍待确认。
- `attachment_info` 暂存附件或链接的 JSON 数组，仅规定数组外层结构；具体元素字段、存储位置、文件大小和类型、下载鉴权及链接校验待存储方案确定。数据库不保存文件二进制内容、飞书 token 或密钥。
- 登录后按 `(tenant_key, open_id)` 查找或创建本地用户；普通用户默认为 `APPLICANT`。管理员身份由服务端管理，不能接受前端自行设置角色。多租户读取、写入、外键对象归属和操作权限仍需后端校验。
- 逻辑删除用户后原唯一身份仍被保留；同一用户再次启用时应恢复原记录，不依靠新建重复身份绕过唯一键。

## 4. 提交、审核与历史保留

| 字段 | 用途 |
| --- | --- |
| `policy_master.submission_version` | 初始为 0，首次提交为 1，每次重提递增 |
| `policy_master.record_version` | 并发控制版本，每次主表或明细业务修改、提交、审核均递增 |
| `policy_audit_log.submission_version` | 标记日志对应哪一轮提交；首次提交前为 0 |
| `policy_audit_log.submission_snapshot` | `SUBMIT`、`RESUBMIT` 时保存完整主表及全部明细快照 |
| `policy_audit_log.review_remark` | 当前这次审核的意见，后续重提不覆盖 |

- 提交快照包含当轮选定平台、机型信息、数量、补贴参数、时间、备注和附件链接信息等完整业务内容，不含会话凭证或 token。快照提交后不改写。
- 创建或修改草稿、提交、审核均由服务端写操作日志。提交校验、条件更新、版本递增和写入快照在同一事务中完成；通过及拒绝必须针对当前待审核提交版本，并在同一事务内写审核日志。
- 并发控制由后端显式实现：带原 `record_version` 和允许的当前状态条件更新，影响行数必须为 1；只有版本字段本身不会自动阻止并发操作。明细修改也必须参与同一主表版本保护事务。
- 修改已拒绝申请时保留此前审核意见；再次提交后清空主表最近审核字段，旧审核信息仍留在对应日志中。待审核、已通过申请不得按草稿编辑接口修改。
- 本版不开放通过后修改或待审核撤回。覆盖候选检测、最终生效及部分替换须在业务规则确认后实现。

## 5. 在 Navicat 中建立表

1. 选择目标数据库，执行 `SELECT VERSION();`，确认是 MySQL 8.0.16 及以上。
2. 确认目标库没有四张同名表。本脚本使用普通 `CREATE TABLE`，已存在的表会报错；如已存在，应先检查结构并制定增量迁移，不要删除已有数据。
3. 打开 `docs/sql/V1__create_policy_tables.sql`，按文件顺序执行：用户表、主表、明细表、日志表。
4. 查看执行结果，确认四张表、外键和 `CHECK` 约束均创建成功。MySQL DDL 不构成整个文件的原子事务；中途报错时，前面已成功创建的表可能仍存在，先核对结果，不要直接重跑整个文件。

本脚本只新建业务表，不包含建库、真实用户或机型初始化数据。建表成功并不代表后端数据库连接、权限控制或业务流程已经实现。

### 已创建前两张表后的继续执行

用户于 2026-11-10 提供的日志显示 `user_info`、`policy_master` 已成功创建，`policy_detail` 报错：`3812 - An expression of non-boolean type specified to a check constraint 'ck_detail_subsidy_exclusive'.` 原因是原约束以 `CASE` 返回整数形式的真假值，不符合 MySQL 对 CHECK 顶层布尔表达式的要求。现已改为 `AND` / `OR` 组合，并显式处理补贴方式为空的情况，保留草稿允许不完整及两种补贴参数互斥的含义。

在 Navicat 确认前两张表存在、`policy_detail` 与 `policy_audit_log` 均不存在后，执行 [继续创建剩余两张表](sql/continue_create_policy_tables.sql)。该文件只创建明细表及日志表，不删除或修改已存在的表；若日志表已经创建，则仅选择其中缺少的表对应的完整 `CREATE TABLE` 语句执行。原 V1 脚本已同步修正，供新的空库使用；当前库不要从头重跑 V1。

### 验证场景与当前结果

以下为数据库及业务接入后的验证要求，不表示已执行通过：

- 不完整草稿可以保存，颜色和数量为空时回读仍为空。
- 百亿不能保存零售专属字段；未知明细平台被约束拒绝。跨渠道平台、未选平台由后端拒绝。
- 固定补贴不能混入比例字段，比例补贴不能混入固定字段，切换方式正确清理旧值。
- 提交及重提日志必须有正数提交版本和对象形式的快照；每次拒绝意见及前次提交内容可以独立还原。
- 同一用户租户身份不重复；外键不能引用不存在的用户或申请；并发编辑和重复审核由后端版本保护拦截。

当前修正的检查范围为本地文件、脚本一致性及约束逻辑核对；已有建表执行情况仅依据用户提供的日志，尚未在 MySQL 验证修正后的脚本及上述业务场景。

## 6. 待确认事项

1. 现有机型表的字段、稳定主键和读取方式；对接前再确定关联列及增量迁移。
2. 数量缺省、零数量及空颜色的业务含义。
3. 固定补贴上限单位和作用范围，比例业务范围、精度、金额舍入及计算基数取值来源。
4. 覆盖匹配维度、时间端点、优先级、正式替换时点及部分替换规则。
5. 管理员名单、跨人数据可见范围、自审规则及拒绝原因是否必填。
6. 备注、附件及链接是否必填，附件存储方案、格式、大小和访问权限。

## 7. 完整建表 SQL

本代码块与独立脚本保持一致；后续修改结构时须同步更新两处，并为已建库另写增量迁移。

```sql
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
```
