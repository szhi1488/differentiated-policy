# 数据库设计与字段字典

更新日期：2026-11-12。本文描述当前九表基线，适用于 MySQL 8.0.16+。首次建表使用空库基线。本机项目库已执行旧版九表，经只读核对，商品导入任务、来源商品及商品操作日志均为空，可由用户在 Navicat 执行相应增量升级脚本。

第一开发阶段允许重新设计项目内所有表。每次重设计先向用户说明拟调整范围、原因、对现有数据和代码的影响，再征求确认。获明确同意后按业务读写路径设计数据约束、索引和事务，同时评估需保留的数据及迁移或重建方式。[项目约定第 13 条](../../AGENTS.md#3-核心技术规则与边界)

查库、建表、迁移、重建和验证所需 SQL 均以本目录中的 `.sql` 文件交付。用户在 Navicat 选中目标库，按交付说明执行并核对结果。需要进一步诊断时，提供对应查询或执行错误的结果即可。

## 1. 在 Navicat 建表

1. 选中你已经创建的目标数据库，先执行 SELECT VERSION(), DATABASE();，确认 MySQL 8.0.16+ 及当前库名。脚本不包含 CREATE DATABASE 或 USE，不会帮你选择库。
2. 执行 `SHOW TABLES;` 核对目标库。空库继续下一步。已有表的库先核对结构及需保留的数据，按已确认的增量迁移或重建方案操作。
3. 打开 [schema.sql](schema.sql)，整份执行一次。它与 [V1__create_policy_tables.sql](V1__create_policy_tables.sql) 内容完全一致，二选一，不能两份都执行。
4. 刷新表列表，应有九张表。确认外键及 CHECK 均创建成功。MySQL DDL 隐式提交，文件中途失败时先核对已创建部分，不直接重跑。

SQL 不含删表、清空、真实用户、凭证或示例机型数据。SET time_zone 仅设置当前连接；应用连接仍须遵守北京时间。SQL 文件是人工执行的空库基线，项目未配置 Flyway 或自动建表。

已执行旧版九表且 `goods_sync_job` 仍有 `oppo_status`、`oneplus_status` 的库，先核对商品任务、来源商品和商品操作日志是否为空。三张表都为空时，停止后端，在 Navicat 选中该库并运行[单品牌导入增量升级 SQL](upgrade_empty_goods_sync_to_single_brand.sql)。脚本仅修改导入任务表、来源表的相关字段与约束，并使用执行后清除的存储过程校验前提。已有导入历史的库需单独制定迁移方案。

2026-11-12 在隔离的 MySQL 8.0.29 上验证旧版九表升级。脚本拒绝已有任务的库，原任务和外键保留。清空测试任务后升级成功，原管理员保留，升级后的 5 项商品导入集成测试通过。本机项目库仅做了只读核对。

## 2. 九表清单和约束

| 表 | 职责 | 关键关联 |
| --- | --- | --- |
| user_info | 飞书身份、本地角色 | tenant_key + open_id 唯一；停用身份仍占唯一键 |
| catalog_variant | 品牌系列型号配置合并的标准规格 | 租户内稳定 variant_code；品牌 + 型号 + 配置唯一 |
| catalog_variant_color | 某一具体规格的颜色 | variant_id + tenant_key 外键；规格内颜色名唯一 |
| goods_sync_job | 每次单品牌导入任务与最新续拉进度 | 发起人同租户；同账套仅一个活动槽；幂等键唯一 |
| source_goods_item | 七字段来源及内联映射 | 首次/最近任务同租户账套；规格同品牌；颜色同规格 |
| catalog_operation_log | 商品维护审计 | 操作人同租户；对象类型和本地 ID 由服务端鉴权 |
| policy_master | 政策主表 | 申请人及最近审核人同租户 |
| policy_detail | 逐平台规格颜色范围及补贴 | 主表、规格同租户；指定颜色同规格 |
| policy_audit_log | 提交审核历史 | 政策及操作人同租户 |

完整关联图及业务见 [确认方案](../architecture/catalog-admin-design.md)。首期不独立建立品牌/系列/型号、映射、清洗规则、逐页响应及批次成员表。

## 3. 关键存储规则

### 来源与标准目录

吉客云业务字段只保存 goodsName、goodsNo、skuName、cateName、goodsField4、goodsField1、goodsField5。brand_name 来自查询分区；系列、型号、配置、颜色是这些原文经规则清洗或必要时人工纠正产生的本地派生标准信息，不是额外采集字段。原始层先落库，明确结果自动写入标准层，异常才人工审核；不要求用户先手工维护全量颜色。

来源字段保留 NULL 与空字符串原意，不自动清洗覆盖原文。VARCHAR 长度是当前工程上限，联调需核对；超长输入明确拒绝，不隐式截断。内容指纹对原始七字段固定编码 SHA256，命中后比较实际字符串，碰撞用 collision_no，不把哈希唯一等同真实 SKU 唯一。

source_goods_item 是内容项而非外部 SKU 身份，goods_no 不设唯一键。相同原文复用本地 ID，不同内容形成新候选，不自动继承映射。仅首次/最近任务关联，不提供完整历史批次回放。

目录和颜色保留 DRAFT / ACTIVE / DISABLED；清洗唯一通过时可以直接创建或复用 ACTIVE，人工草稿是补充入口。来源 mapping_status 保存当前生效关联，MAPPED 必须有规格及 AUTO/MANUAL 来源；clean_status 单独表达本次 PENDING/PROCESSING/AUTO_PASSED/REVIEW_REQUIRED/MANUAL_PASSED/ERROR/EXCLUDED。自动或人工通过必须有明确规格和颜色，未知颜色不得变不限颜色。重算异常可保留旧映射，不自动覆盖人工结果或复活 DISABLED。

source_goods_item 内新增 clean_candidate、clean_rule_version、clean_error_code、clean_job_id、执行租约/次数、完成时间和人工审核人/时间/原因。候选仅由七字段派生，JSON 外层必须对象，内容结构和大小由服务端白名单校验；成功/异常/纠正历史追加操作日志，当前字段不替代历史。AUTO_PASSED 要规则版本、候选、明确颜色和完成时间且审核人为空；MANUAL_PASSED 要真实审核人、时间、原因。ERROR/REVIEW_REQUIRED 要原因，PROCESSING 要成功批次依据及执行令牌/租约（批次是否成功仍由服务端核验）。详见 [自动清洗细则](../business/catalog-cleaning.md)。

标准规格唯一性为租户、品牌、型号、配置，不包含可调整的系列显示名。同一型号配置若有真正不同版本，应在规范配置中显式区分，不能强行合并。已被引用的身份不可换成另一型号配置；颜色不可挪到另一规格。名称去首尾空格、大小写与单位规范由服务端维护，不依赖数据库自动推断。

### 政策颜色和草稿

color_scope=ALL_COLORS 表示当前规格不限颜色，此时 variant_color_id 和 color 都必须 NULL。SPECIFIC_COLOR 表示指定本规格颜色，草稿可暂未选完，正式提交必须有 ID 和服务端颜色快照。color_scope=NULL 仅表示草稿未选范围，不能当不限颜色。

复合颜色外键使用 (variant_color_id, catalog_variant_id, tenant_key)，阻止跨规格和跨租户。明细颜色名只是历史快照，不充当身份。不同型号、同型号不同配置的颜色独立维护；不限颜色不按当前颜色清单展开。

数量允许 NULL 或非负整数，0 业务含义仍待确认。草稿允许机型、时间、补贴参数暂缺，但填写内容必须合法。SQL 不能跨表校验“主表已提交所以子明细必须完整”，必须由 Service 同事务检查。

### 金额、平台和时间

金额 DECIMAL(10,2)、比例 DECIMAL(5,2) 百分数，Java BigDecimal，API 十进制字符串，拒绝超精度，不依赖隐式舍入。固定/比例参数互斥；比例上限、固定上限单位和计算基数来源待确认，SQL 只做非负约束。

平台为 KUAISHOU/PDD/JD/DOUYIN/TMALL/TAOBAO/ZHAOSHANG/DEWU/UNICOM/GOV_ENTERPRISE；百亿仅 PDD/TAOBAO/UNICOM。selected_platforms 保存明确 JSON 数组，“全部”只是选中操作。后端校验明细平台属于已选集合及渠道范围。

百亿 verify_type、scene_type、is_estimated 必须 NULL。零售提交时必填。起止时间精确到秒、Asia/Shanghai，DATETIME 本身无时区，开始必须早于结束。APPROVED 不等于当前生效；覆盖时间端点、优先级和不限颜色与指定颜色的替换仍待确认。

### 权限、历史与任务

身份查找须读取停用记录并拒绝登录，不能过滤后新建绕过。本版保留原 user_info 字段，已有登录实体/测试不需要因九表 DDL 改写；新业务接口仍未实现。

含 is_deleted 的用户和政策主表正常业务查询过滤，明细日志经父政策鉴权；目录采用 status 停用，不物理删除。外键全部 RESTRICT。日志只追加，不随重提覆盖；服务端或数据库账号权限保证不可篡改，不声称仅靠 DDL 就防止直接改日志。

政策主表 submission_version 每轮提交递增，record_version 每次业务修改递增；主表、明细、提交快照及审核记录同事务。条件更新携带租户、状态和版本并检查影响行数。完整快照含标准 ID、品牌系列型号配置、颜色范围/ID/名称、平台、数量、补贴、时间及附件说明。

goods_sync_job 每条记录只导入一个品牌并独立保存当前游标；skuId 不逐商品保存。终态任务只保留该任务最后位置，日志不保存游标、worker_token 或完整上游响应。active_slot 为自动生成列，调用方不写；QUEUED/RUNNING/PAUSED 占同账套唯一槽。暂停任务可在冷却后恢复，或结束并保留已落库原文。源商品的首次、最近和清洗任务引用均要求同租户、账套和品牌。

数据库唯一活动槽和租约字段不能替代执行器逻辑，导入 worker 必须带 fencing_token，清洗 worker 带 clean_run_key、来源版本和租约条件提交。`status=SUCCEEDED` 表示本任务的单品牌导入完成。进入独立清洗阶段要求导入成功且已记录 `clean_rule_version`。`COMPLETED` 表示本轮已完成自动通过或异常分流，仍可有待人工审核的商品。异常不阻塞明确商品。

catalog_operation_log 新增 actor_type 和 rule_version，USER 必须关联同租户 operator_id，SYSTEM 必须无用户ID/姓名并有规则版本，不能伪造机器人用户或冒用发起管理员。自动建规格颜色、映射、完成状态及SYSTEM审计同事务；人工审核使用 USER。DDL 不能证明解析准确、权限正确、目标 ACTIVE 或历史只追加，服务端必须实现。include_blockup 必填0/1，业务默认仍待确定。

## 4. 字段字典

以下逐字段从本版 SQL 提取；可空指 DDL 层允许，不等于正式提交可缺失。完整索引/约束以 SQL 为准，ID 和游标精确传输，生成列不由调用方写入。

### user_info

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 本地用户主键 |
| tenant_key | VARCHAR(64) | 否 | — | 飞书租户标识 |
| open_id | VARCHAR(64) | 否 | — | 飞书应用内用户标识 |
| union_id | VARCHAR(64) | 是 | NULL | 飞书同一应用开发商下的用户标识 |
| name | VARCHAR(64) | 否 | — | 用户姓名 |
| department | VARCHAR(128) | 是 | NULL | 所属部门 |
| role_type | VARCHAR(32) | 否 | 'APPLICANT' | APPLICANT-申请人，ADMIN-管理员 |
| is_deleted | TINYINT | 否 | 0 | 0-未删除，1-已删除 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | 否 | CURRENT_TIMESTAMP |  |

### catalog_variant

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 本地标准规格主键 |
| tenant_key | VARCHAR(64) | 否 | — | 飞书租户标识 |
| variant_code | VARCHAR(64) | 否 | — | 租户内稳定规格编码，不随改名复用 |
| brand_name | VARCHAR(32) | 否 | — | 固定品牌：一加手机或OPPO手机 |
| series_name | VARCHAR(64) | 否 | — | 标准系列名称 |
| model_name | VARCHAR(64) | 否 | — | 标准具体型号名称 |
| spec | VARCHAR(64) | 否 | — | 标准配置文本，含必要版本差异 |
| display_name | VARCHAR(256) | 否 | — | 标准规格展示全称 |
| status | VARCHAR(16) | 否 | 'DRAFT' | DRAFT-待发布，ACTIVE-可选，DISABLED-停用 |
| record_version | INT UNSIGNED | 否 | 0 | 并发版本 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间，北京时间 |
| updated_at | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间，北京时间 |

### catalog_variant_color

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 规格颜色主键，同名颜色在不同规格中分别维护 |
| tenant_key | VARCHAR(64) | 否 | — | 租户标识 |
| variant_id | BIGINT | 否 | — | 所属标准规格 |
| color_name | VARCHAR(64) | 否 | — | 规则唯一确定或人工审核后的标准颜色名称 |
| status | VARCHAR(16) | 否 | 'DRAFT' | DRAFT-待发布，ACTIVE-可选，DISABLED-停用 |
| record_version | INT UNSIGNED | 否 | 0 | 并发版本 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间，北京时间 |
| updated_at | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间，北京时间 |

### goods_sync_job

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 同步任务主键，一个任务只导入一个品牌 |
| tenant_key | VARCHAR(64) | 否 | — | 租户标识 |
| source_key | VARCHAR(64) | 否 | — | 服务端受控账套标识，不保存凭证 |
| brand_name | VARCHAR(32) | 否 | — | OPPO手机或一加手机 |
| requester_id | BIGINT | 否 | — | 发起管理员 |
| idempotency_key | VARCHAR(64) | 否 | — | 任务创建幂等键 |
| status | VARCHAR(16) | 否 | 'QUEUED' | QUEUED、RUNNING、PAUSED、SUCCEEDED、CANCELLED |
| last_max_id | VARCHAR(32) | 否 | '0' | 本任务品牌的最新续拉位置，不保存逐商品标识 |
| page_size | INT | 否 | 50 | 单批 1–50 条 |
| include_blockup | TINYINT | 否 | — | 0 或 1；当前实现固定为 0，仅导入启用商品 |
| page_count | INT UNSIGNED | 否 | 0 | 已提交页数，包含确认导入完成的空页 |
| read_count | BIGINT UNSIGNED | 否 | 0 | 累计接收条次，重放可重复，非真实SKU总数 |
| content_count | BIGINT UNSIGNED | 否 | 0 | 当前任务已提交的去重内容计数 |
| clean_status | VARCHAR(16) | 否 | 'NOT_STARTED' | 清洗阶段，独立于导入状态：NOT_STARTED、RUNNING、COMPLETED、PARTIAL、FAILED |
| clean_rule_version | VARCHAR(64) | 是 | NULL | 本次清洗固定的受控规则包版本 |
| failure_count | INT UNSIGNED | 否 | 0 | 失败尝试次数 |
| error_code | VARCHAR(64) | 是 | NULL | 脱敏错误类别，不存原始响应或凭证 |
| worker_token | VARCHAR(64) | 是 | NULL | 当前执行者租约标识，不是上游凭证 |
| lease_until | DATETIME | 是 | NULL | 租约到期时间 |
| next_request_at | DATETIME | 否 | CURRENT_TIMESTAMP | 最早可请求时间，包含间隔或冷却 |
| fencing_token | BIGINT UNSIGNED | 否 | 0 | 接管代次，旧执行者必须条件写入 |
| record_version | INT UNSIGNED | 否 | 0 | 并发版本 |
| started_at | DATETIME | 是 | NULL | 开始时间 |
| finished_at | DATETIME | 是 | NULL | 结束时间 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间，北京时间 |
| updated_at | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间，北京时间 |
| active_slot | TINYINT | 生成 | 自动生成 | 同租户账套只保留一个活动任务，暂停也占位 |

### source_goods_item

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 本地来源内容主键，不是吉客云SKU主键 |
| tenant_key | VARCHAR(64) | 否 | — | 租户标识 |
| source_key | VARCHAR(64) | 否 | — | 受控账套标识 |
| brand_name | VARCHAR(32) | 否 | — | 品牌查询分区，不取上游返回字段 |
| goods_name | VARCHAR(512) | 是 | NULL | 来源goodsName原文 |
| goods_no | VARCHAR(128) | 是 | NULL | 来源goodsNo原文，不作SKU唯一键 |
| sku_name | VARCHAR(256) | 是 | NULL | 来源skuName原文 |
| cate_name | VARCHAR(256) | 是 | NULL | 来源cateName原文 |
| goods_field4 | VARCHAR(1024) | 是 | NULL | 来源goodsField4原文 |
| goods_field1 | VARCHAR(1024) | 是 | NULL | 来源goodsField1原文 |
| goods_field5 | VARCHAR(1024) | 是 | NULL | 来源goodsField5原文 |
| content_hash | CHAR(64) | 否 | — | 七字段固定编码的SHA256，命中后仍比较原文 |
| collision_no | INT UNSIGNED | 否 | 0 | 不同内容同哈希时分配的冲突序号 |
| first_seen_job_id | BIGINT | 否 | — | 首次写入的同步任务 |
| last_seen_job_id | BIGINT | 否 | — | 最近出现的同步任务，仅当前信息不作批次历史 |
| mapping_status | VARCHAR(16) | 否 | 'UNMAPPED' | UNMAPPED-待归类，MAPPED-已确认，EXCLUDED-排除 |
| catalog_variant_id | BIGINT | 是 | NULL | 规则唯一确定或人工确认的已生效规格映射 |
| variant_color_id | BIGINT | 是 | NULL | 清洗七字段或人工审核产生的本地颜色映射 |
| mapping_remark | VARCHAR(512) | 是 | NULL | 归类或排除原因 |
| mapping_origin | VARCHAR(16) | 是 | NULL | 生效映射来源：AUTO-自动清洗，MANUAL-人工审核 |
| clean_status | VARCHAR(16) | 否 | 'PENDING' | PENDING、PROCESSING、AUTO_PASSED、REVIEW_REQUIRED、MANUAL_PASSED、ERROR、EXCLUDED |
| clean_job_id | BIGINT | 是 | NULL | 本次清洗依据的已成功导入任务 |
| clean_rule_version | VARCHAR(64) | 是 | NULL | 本次处理的受控规则包版本，人工修正亦保留原依据 |
| clean_candidate | JSON | 是 | NULL | 本地候选及七字段证据，禁止完整响应或额外上游属性 |
| clean_error_code | VARCHAR(64) | 是 | NULL | 缺失、歧义、冲突或技术错误类别 |
| clean_attempts | INT UNSIGNED | 否 | 0 | 清洗尝试次数 |
| clean_run_key | VARCHAR(64) | 是 | NULL | 当前清洗执行标识，防止过期执行者提交 |
| clean_lease_until | DATETIME | 是 | NULL | 当前清洗租约到期时间 |
| cleaned_at | DATETIME | 是 | NULL | 最近一次清洗或人工审核结束时间 |
| reviewed_by | BIGINT | 是 | NULL | 必要时人工审核人，自动通过必须为空 |
| reviewed_at | DATETIME | 是 | NULL | 人工审核时间 |
| review_reason | VARCHAR(512) | 是 | NULL | 人工纠正或排除的原因 |
| record_version | INT UNSIGNED | 否 | 0 | 映射维护并发版本 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间，北京时间 |
| updated_at | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间，北京时间 |

### catalog_operation_log

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 商品操作审计主键 |
| tenant_key | VARCHAR(64) | 否 | — | 租户标识 |
| actor_type | VARCHAR(16) | 否 | 'USER' | USER-人工操作，SYSTEM-后台自动清洗 |
| operator_id | BIGINT | 是 | NULL | 人工操作用户，SYSTEM必须为空，不冒用发起人 |
| operator_name | VARCHAR(64) | 是 | NULL | 操作人姓名快照 |
| rule_version | VARCHAR(64) | 是 | NULL | 自动清洗使用的规则包版本 |
| entity_type | VARCHAR(32) | 否 | — | VARIANT、VARIANT_COLOR、SOURCE_GOODS_ITEM、SYNC_JOB |
| entity_id | BIGINT | 否 | — | 对应本地对象主键，由服务端校验类型和租户 |
| action_type | VARCHAR(32) | 否 | — | 目录维护、映射、任务控制及CLEAN_AUTO、CLEAN_REVIEW、CLEAN_ERROR、CLEAN_RETRY |
| batch_key | VARCHAR(64) | 是 | NULL | 本次批量操作关联号，不是外部商品ID |
| before_data | JSON | 是 | NULL | 本地字段变更前对象，不存完整上游响应或外部SKU标识 |
| after_data | JSON | 是 | NULL | 本地字段变更后对象，任务快照须剔除游标及执行令牌 |
| reason | VARCHAR(512) | 是 | NULL | 操作或纠正原因 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 操作时间 |

### policy_master

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 政策主键 |
| tenant_key | VARCHAR(64) | 否 | — | 申请所属租户，由会话确定 |
| policy_code | VARCHAR(64) | 否 | — | 政策单号，创建草稿时生成 |
| applicant_id | BIGINT | 否 | — | 申请人，关联 user_info.id |
| applicant_name | VARCHAR(64) | 是 | NULL | 申请人姓名快照 |
| perspective | VARCHAR(32) | 否 | — | RETAIL-零售，BILLION-百亿 |
| channel_type | VARCHAR(32) | 否 | — | RETAIL_CHANNEL-零售渠道，BILLION_CHANNEL-百亿渠道 |
| selected_platforms | JSON | 是 | NULL | 已选具体平台编码数组，不保存 ALL |
| verify_type | VARCHAR(32) | 是 | NULL | SALES-销售维度，ACTIVATION-激活维度；百亿为空 |
| scene_type | VARCHAR(32) | 是 | NULL | NATIONAL_SUB-国补，LOCAL_SUB-地补，SELF_SUB-自补；百亿为空 |
| is_estimated | TINYINT | 是 | NULL | 0-否，1-是；百亿及未填写的草稿为空 |
| start_time | DATETIME | 是 | NULL | 政策开始时间，草稿可为空 |
| end_time | DATETIME | 是 | NULL | 政策结束时间，草稿可为空 |
| special_remark | TEXT | 是 | — | 特别备注 |
| attachment_info | JSON | 是 | NULL | 附件及链接信息数组，不保存文件二进制内容 |
| status | VARCHAR(32) | 否 | 'DRAFT' | DRAFT-草稿，PENDING-待审核，APPROVED-已通过，REJECTED-已拒绝 |
| submission_version | INT UNSIGNED | 否 | 0 | 提交版本，未提交为0，每次提交或重提递增 |
| record_version | INT UNSIGNED | 否 | 0 | 并发控制版本号，每次修改递增 |
| reviewer_id | BIGINT | 是 | NULL | 最近一次审核人，关联 user_info.id |
| reviewer_name | VARCHAR(64) | 是 | NULL | 最近一次审核人姓名快照 |
| review_remark | VARCHAR(512) | 是 | NULL | 最近一次审核意见，完整历史保存在日志表 |
| review_time | DATETIME | 是 | NULL | 最近一次审核时间 |
| is_deleted | TINYINT | 否 | 0 | 0-未删除，1-已删除 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | 否 | CURRENT_TIMESTAMP |  |

### policy_detail

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 明细主键 |
| tenant_key | VARCHAR(64) | 否 | — | 与政策主表相同的租户 |
| policy_id | BIGINT | 否 | — | 关联 policy_master.id |
| platform_code | VARCHAR(32) | 否 | — | 具体平台编码，不保存 ALL |
| catalog_variant_id | BIGINT | 是 | NULL | 标准规格，草稿可暂未选 |
| brand_name_snapshot | VARCHAR(32) | 是 | NULL | 提交时品牌快照 |
| series_name_snapshot | VARCHAR(64) | 是 | NULL | 提交时系列快照 |
| color_scope | VARCHAR(32) | 是 | NULL | ALL_COLORS-不限颜色，SPECIFIC_COLOR-指定颜色；NULL仅为草稿未选择 |
| variant_color_id | BIGINT | 是 | NULL | 当前规格下的指定颜色ID |
| model_full_name | VARCHAR(256) | 是 | NULL | 涉及型号全称快照，草稿可为空 |
| single_model | VARCHAR(64) | 是 | NULL | 单型号快照，草稿可为空 |
| spec | VARCHAR(64) | 是 | NULL | 配置快照，草稿可为空 |
| color | VARCHAR(64) | 是 | NULL | 指定颜色的名称快照，不限颜色时为NULL |
| quantity | INT | 是 | NULL | 该平台该机型的数量；未填写为NULL，不自动记为0 |
| subsidy_mode | VARCHAR(32) | 是 | NULL | FIXED-固定补贴，RATIO-比例补贴；草稿可为空 |
| fixed_amount | DECIMAL(10,2) | 是 | NULL | 固定补贴金额 |
| max_subsidy_amount | DECIMAL(10,2) | 是 | NULL | 固定补贴上限金额，具体作用范围待确认 |
| subsidy_ratio | DECIMAL(5,2) | 是 | NULL | 补贴比例，百分数；10.00表示10% |
| base_price_type | VARCHAR(32) | 是 | NULL | RETAIL_PRICE-零售价，POLICY_PRICE-政策价，HAND_PRICE-到手价，LOCAL_ACTUAL-地补实际，MERCHANT_CONTROL-商家配控 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | 否 | CURRENT_TIMESTAMP |  |

### policy_audit_log

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 日志主键 |
| tenant_key | VARCHAR(64) | 否 | — | 与政策和操作人相同的租户 |
| policy_id | BIGINT | 否 | — | 关联 policy_master.id |
| submission_version | INT UNSIGNED | 否 | 0 | 对应的提交版本，首次提交前为0 |
| operator_id | BIGINT | 否 | — | 操作人，关联 user_info.id |
| operator_name | VARCHAR(64) | 是 | NULL | 操作人姓名快照 |
| action_type | VARCHAR(32) | 否 | — | CREATE_DRAFT、UPDATE_DRAFT、SUBMIT、APPROVE、REJECT、RESUBMIT |
| from_status | VARCHAR(32) | 是 | NULL | 操作前状态，创建时为空 |
| to_status | VARCHAR(32) | 否 | — | 操作后状态 |
| action_desc | VARCHAR(255) | 是 | NULL | 操作描述 |
| review_remark | VARCHAR(512) | 是 | NULL | 本次审核意见，不随后续重提覆盖 |
| submission_snapshot | JSON | 是 | NULL | 提交或重提时的完整主表及明细快照 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 操作时间 |

## 5. 本轮实际验证

本轮在项目缓存目录的独立 MySQL 8.0.29 实例（关闭网络监听）执行新版九表 SQL 成功，52 项合成数据检查通过：包含既有租户/品牌/规格颜色、补贴、任务及政策快照约束，以及新增原文先入库、清洗阶段分离、执行租约、自动通过必须有颜色/候选/规则、人工审核身份、异常原因、SYSTEM/USER 审计和原文保留。交付 SQL 不含测试记录，没有连接用户目标库或读取其凭证。

单品牌导入改版后再次在独立 MySQL 8.0.29 本机验收库执行完整九表脚本，`GoodsSyncIntegrationTest` 5 项通过。上游使用模拟返回，覆盖两品牌串行、原文去重、页事务回滚、冷却和续拉、过期执行者及管理员租户权限。目标库未连接、未建表。

两份建表脚本内容一致，全部字段字典、本地链接及差异格式在交付前核对。运行版本为8.0.29，未在最低8.0.16单独实测。未实现/运行真实自动解析器、规则包、商品接口或页面；不把约束验证称为自动清洗业务端到端通过。上一版27项记录属于历史验证，本轮52项包含相关回归。
