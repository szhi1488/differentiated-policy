# 数据库设计与字段字典

更新日期：2026-11-13。十四表空库基线适用于 MySQL 8.0.16+。本次按用户确认重建空库。公司所有有效管理员共用源商品、清洗结果和标准目录。后端须在建立会话前校验公司飞书租户，并在请求时校验有效身份及权限。应用适配待实施。

## 1. 在 Navicat 建表

1. 停止指向目标库的应用。由用户准备空库，如需留存旧库数据，先备份。
2. 在 Navicat 选中目标空库，执行 [schema.sql](schema.sql) 或 [V1__create_policy_tables.sql](V1__create_policy_tables.sql) 中任意一份。脚本只创建结构并设置当前连接的字符集与时区。
3. 执行 [SKU 与标准目录核对](verify_sku_sync.sql) 和 [清洗结构核对](verify_catalog_cleaning.sql)。预期列出十四张表、标准商品唯一键、映射外键和清洗任务索引，异常关联查询应为空。
4. 后端完成新目录接入后，再配置管理员、从吉客云重新导入、清洗并核对标准商品。空库没有历史任务、来源、政策或已分配的管理员角色。MySQL DDL 隐式提交，执行失败后先核对已创建的表。

项目未配置 Flyway 或自动建表。

## 2. 十四表清单和约束

| 表 | 职责 | 关键关联 |
| --- | --- | --- |
| user_info | 飞书身份、本地角色 | tenant_key + open_id 唯一；停用身份仍占唯一键 |
| brand | 公司品牌字典 | 名称直接对应吉客云 brandName，名称唯一 |
| series | 品牌下的标准系列 | 同品牌下系列名称唯一 |
| spu | 标准机型及版本 | 同品牌下机型名称 + 版本唯一 |
| color_dict | 独立颜色表 | 颜色名称唯一，可由多个机型商品引用 |
| goods | 一种机型版本、RAM、ROM、颜色的标准商品 | 同配置同颜色唯一 |
| goods_sync_job | 每次单品牌导入任务与最新续拉进度 | 发起人关联用户，同账套仅一个活动槽，幂等键唯一 |
| catalog_clean_job | 手动发起的清洗任务，保存选择范围、任务状态和执行租约 | 发起人关联用户，账套活动槽及请求键唯一，进度由任务明细统计 |
| catalog_clean_job_item | 本轮选中的源商品、确认时的来源版本及本轮结果 | `job_id` 关联清洗任务，`source_id` 关联本地源商品，两列构成主键 |
| source_goods_item | 当前 SKU 来源及内联映射 | sku_id 唯一，任务须匹配来源账套及品牌，标准商品须匹配来源品牌 |
| catalog_operation_log | 源商品变更前后值、导入任务操作和建档日命中记录 | `entity_type` 区分对象，`entity_id` 保存对应的本地主键 |
| policy_master | 政策主表 | 申请人及最近审核人同租户 |
| policy_detail | 逐平台规格颜色范围及补贴 | 通过 policy_id 归属主表，指定颜色商品须同配置 |
| policy_audit_log | 提交审核历史 | 政策及操作人同租户 |

完整关联图及业务见 [确认方案](../architecture/catalog-admin-design.md)。来源到标准商品的关联保存在来源表。

### 商品表关联示例

以下用一条源商品说明十四表基线中的写入路径。示例 ID 和日期仅用于说明。

| 步骤 | 表 | 示例 |
| --- | --- | --- |
| 发起导入 | `goods_sync_job` | 任务 `20` 导入一加手机，记录导入状态和续拉断点。 |
| 保存当前原文 | `source_goods_item` | 吉客云 `skuId=123` 匹配本地源商品 `id=501`。源名称变化时更新原行并保留本地 ID。 |
| 记录导入事件 | `catalog_operation_log` | 管理员发起任务时记录 `SYNC_JOB / 20 / START`。名称变化时记录 `SOURCE_GOODS_ITEM / 501 / UPDATE`，保存变更前后的值。按建档日命中时可记录 `SOURCE_GOODS_ITEM / 501 / CREATE`，`batch_key` 例如 `CREATED-DATE-2026-11-12`。 |
| 发起清洗 | `catalog_clean_job` | 任务 `42` 保存品牌和建档日范围。 |
| 处理任务成员 | `catalog_clean_job_item` | `(job_id=42, source_id=501, source_version=3)` 表示该任务选中了本地源商品 `501`。结果先是 `PENDING`，通过后更新为 `AUTO_PASSED`。 |
| 确认标准商品 | `goods`、`source_goods_item` | 创建或复用标准商品 `goods.id=801`，在源商品 `501` 上回填 `goods_id=801`。 |

`source_goods_item.clean_status` 表示源商品当前的清洗状态，`catalog_clean_job_item.outcome` 表示该商品在某一轮任务中的结果。任务进度按本轮明细统计。吉客云业务字段未变时，本次导入不会新增 `UPDATE` 变更记录。按建档日导入仍可补齐日期证据。

## 3. 关键存储规则

### 来源与标准目录

吉客云来源保存原始七字段及 skuId、cateFullName、gmtModified、goodsGmtModified、isBlockup、isPackageGood。brand_name 来自校验后的查询分区。原始层先落库，系列、型号、配置、颜色经过规则校验后写入标准层，异常才人工审核。

来源字段保留 NULL 与空字符串原意，不自动清洗覆盖原文。VARCHAR 长度是当前工程上限，联调需核对；超长输入明确拒绝，不隐式截断。SKU ID 按 ASCII 十进制字符串保存，接口同样返回字符串，防止浏览器丢失精度。

`source_goods_item.sku_id` 是源商品库唯一键。第一次看到 SKU 时插入，有变化时按相同 SKU 更新原行并保留本地 ID。比较吉客云两类修改时间及实际来源字段，旧响应保留已有内容。同一 SKU 如出现在不同账套或品牌，进入冲突处理，避免覆盖或重复插入。来源内容、状态和时间均未变时，只推进任务和维护必要的任务引用、建档日证据，商品内容、更新时间和清洗状态保持原值。仅上游时间前进时更新来源时间，业务字段或状态变化时才按现有规则标记待清洗。导入在这一阶段只处理来源表。

来源列表的 `createdDate` 使用本地来源 ID 的建档日证据，审计的 `batch_key` 为 `CREATED-DATE-YYYY-MM-DD`，并可从首次、最近导入任务补查。同一 SKU 按日重新导入可补齐建档日。缺少证据时返回 null。

`gmt_modified` 和 `goods_gmt_modified` 分别保存吉客云规格和货品的 Unix 毫秒，独立于本系统的 DATETIME 以及任务的 `sync_date`。前端使用北京时间显示两个修改时间，并保留非零毫秒。

标准目录按品牌 → 系列 → 机型版本 → 配置 → 颜色查询。品牌 `name` 直接对应清洗确认的吉客云 `brandName`。独立颜色表 `color_dict` 只保存颜色 ID 和名称。商品通过 `color_id` 引用颜色，某配置实际可选颜色由其有效商品行决定。

标准商品保存清洗后的 `goods_name`。`product_no` 对应来源 `goodsNo`，可为空、可重复。商品 SKU 是公司内稳定的本地编号。多条来源可映射同一个标准商品，来源行各自保留原始货品编号。复用同一标准商品时，已确认的商品名称和货品编号保持原值。标准商品编辑不依赖版本列。

规格身份使用 `(spu_id, ram, rom)`，容量统一为正整数 GB，按 1 TB = 1024 GB 规范化。机型身份以品牌、规范机型名和版本区分。已存在的机型归属不同系列或版本证据不明时转人工核对。被引用的机型、配置和颜色身份应保持稳定，纠错创建或选择正确目标。

`source_goods_item` 保留候选、异常原因、尝试次数、最近清洗任务和审核信息。已映射必须有 `catalog_brand_id`、`goods_id` 和 AUTO/MANUAL 来源。复合外键同时校验吉客云分区对应的品牌和商品归属。自动通过需要候选、任务和完成时间。人工通过或排除需要审核人、时间和结果。重算时可保留旧映射，人工结果受保护。

`goods.catalog_status` 表示目录可选状态，取 DRAFT、ACTIVE、DISABLED。明确通过的清洗可创建 ACTIVE 商品，已有 ACTIVE 商品复用，DRAFT 或 DISABLED 交人工处理。`goods.status` 表示销售状态，1 在售、2 待上架、3 已下架、4 已停产、NULL 未知。清洗不推断销售状态。

### 政策颜色和草稿

政策以 `spu_id + ram + rom` 表示配置。ALL_COLORS 的 `goods_id` 与颜色快照均为空。SPECIFIC_COLOR 正式提交须选当前配置下的 ACTIVE 商品，服务端由其颜色生成快照。草稿允许尚未选择范围或尚未选齐，NULL 范围只表示未选。

`policy_detail` 通过 `policy_id` 归属政策主表，指定颜色时以 `(goods_id, spu_id, ram, rom)` 外键校验商品及配置。配置由商品集合派生，提交时服务端还须确认该配置至少有一个 ACTIVE 商品。品牌、系列、机型、配置展示文本和颜色保留提交快照。

### 金额、平台和时间

金额 DECIMAL(10,2)、比例 DECIMAL(5,2) 百分数，Java BigDecimal，API 十进制字符串，拒绝超精度，不依赖隐式舍入。固定/比例参数互斥；比例上限、固定上限单位和计算基数来源待确认，SQL 只做非负约束。

平台为 KUAISHOU/PDD/JD/DOUYIN/TMALL/TAOBAO/ZHAOSHANG/DEWU/UNICOM/GOV_ENTERPRISE；百亿仅 PDD/TAOBAO/UNICOM。selected_platforms 保存明确 JSON 数组，“全部”只是选中操作。后端校验明细平台属于已选集合及渠道范围。

百亿 verify_type、scene_type、is_estimated 必须 NULL。零售提交时必填。起止时间精确到秒、Asia/Shanghai，DATETIME 本身无时区，开始必须早于结束。APPROVED 不等于当前生效；覆盖时间端点、优先级和不限颜色与指定颜色的替换仍待确认。

### 权限、历史与任务

身份查找须读取停用记录并拒绝登录，不能过滤后新建绕过。本版保留原 user_info 字段，登录沿用原有身份字段。

含 is_deleted 的用户和政策主表正常业务查询过滤，明细日志经父政策鉴权；目录采用 status 停用，不物理删除。外键全部 RESTRICT。日志只追加，不随重提覆盖；服务端或数据库账号权限保证不可篡改，不声称仅靠 DDL 就防止直接改日志。

政策主表 submission_version 每轮提交递增，record_version 每次业务修改递增；主表、明细、提交快照及审核记录同事务。条件更新携带租户、状态和版本并检查影响行数。完整快照含标准 ID、品牌系列型号配置、颜色范围/ID/名称、平台、数量、补贴、时间及附件说明。

goods_sync_job 每条记录只导入一个品牌，并独立保存导入模式、北京时间日期、查询阶段和当前阶段游标。按修改日先查商品修改，再重置游标查 SKU 修改。终态任务只保留该任务最后位置，日志不保存游标、worker_token 或完整上游响应。active_slot 为自动生成列，调用方不写；QUEUED/RUNNING/PAUSED 占同账套唯一槽。暂停任务可在冷却后恢复，或结束并保留已落库原文。源商品的首次和最近导入任务引用要求同账套和品牌。

清洗任务独立领取租约。成员快照固定内部来源版本，用于避免处理期间来源变化后写入旧结果。来源、映射、目录和成员结果同事务更新。同账套并发发起由活动槽唯一索引约束；调度通过任务索引及成员待处理索引读取。

商品导入记录保留建档日、来源变更和导入操作。清洗不写此表，当前异常原因和人工审核结果保存在来源行，本轮处理进度从任务成员结果统计。政策提交和审核历史仍保存在 policy_audit_log。

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

### brand

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 品牌主键 |
| name | VARCHAR(50) | 否 | — | 清洗后的品牌名称，直接对应吉客云 brandName |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间，北京时间 |

### series

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 系列主键 |
| brand_id | BIGINT | 否 | — | 所属品牌 |
| name | VARCHAR(64) | 否 | — | 标准系列名称 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间，北京时间 |

### spu

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 标准机型主键 |
| brand_id | BIGINT | 否 | — | 所属品牌 |
| series_id | BIGINT | 否 | — | 所属系列 |
| name | VARCHAR(100) | 否 | — | 标准机型名，含常规机型后缀如 Pro |
| edition | VARCHAR(64) | 否 | '' | 另行确认的版本标识，空字符串表示无独立版本 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间，北京时间 |

### color_dict

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | INT UNSIGNED | 否 | 自增 | 颜色主键 |
| color_name | VARCHAR(64) | 否 | — | 颜色名称 |

### goods

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 标准商品主键 |
| sku_id | VARCHAR(64) | 否 | — | 公司内稳定的本地标准 SKU 编号 |
| product_no | VARCHAR(128) | 是 | NULL | 货品编号，对应来源 goodsNo，可重复 |
| brand_id | BIGINT | 否 | — | 与机型一致的品牌 |
| series_id | BIGINT | 否 | — | 与机型一致的系列 |
| spu_id | BIGINT | 否 | — | 所属机型及版本 |
| goods_name | VARCHAR(384) | 否 | — | 清洗落库后的商品名称 |
| ram | VARCHAR(20) | 否 | — | 规范运行内存，如16GB |
| rom | VARCHAR(20) | 否 | — | 规范机身存储，统一GB，如1TB记为1024GB |
| color_id | INT UNSIGNED | 否 | — | 关联独立颜色表的主键 |
| status | TINYINT UNSIGNED | 是 | NULL | 销售状态：1-在售，2-待上架，3-已下架，4-已停产，NULL-未知 |
| catalog_status | VARCHAR(16) | 否 | 'DRAFT' | 目录状态：DRAFT-草稿，ACTIVE-可选，DISABLED-停用 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间，北京时间 |
| updated_at | DATETIME | 否 | CURRENT_TIMESTAMP | 更新时间，北京时间 |

### goods_sync_job

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 同步任务主键，一个任务只导入一个品牌 |
| source_key | VARCHAR(64) | 否 | — | 服务端受控账套标识，不保存凭证 |
| brand_name | VARCHAR(50) | 否 | — | OPPO手机或一加手机 |
| requester_id | BIGINT | 否 | — | 发起管理员 |
| idempotency_key | VARCHAR(64) | 否 | — | 任务创建幂等键 |
| status | VARCHAR(16) | 否 | 'QUEUED' | QUEUED、RUNNING、PAUSED、SUCCEEDED、CANCELLED |
| sync_mode | VARCHAR(16) | 否 | 'FULL' | FULL 全量、CREATED 按建档日、MODIFIED 按修改日 |
| sync_date | DATE | 是 | NULL | 按日导入的北京时间日期，全量为空 |
| sync_phase | VARCHAR(16) | 否 | 'FULL' | FULL、CREATED、GOODS_MODIFIED、SKU_MODIFIED |
| last_max_id | VARCHAR(32) | 否 | '0' | 本任务当前品牌及查询阶段的续拉位置 |
| page_size | INT | 否 | 50 | 单批 1–50 条 |
| include_blockup | TINYINT | 否 | — | 0 或 1；当前导入固定为 1，包含停用 SKU |
| page_count | INT UNSIGNED | 否 | 0 | 已提交页数，包含确认导入完成的空页 |
| read_count | BIGINT UNSIGNED | 否 | 0 | 累计接收条次，重放可重复，非真实SKU总数 |
| content_count | BIGINT UNSIGNED | 否 | 0 | 当前任务已提交的不同 SKU 数 |
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
| active_slot | TINYINT | 生成 | 自动生成 | 同一账套只保留一个活动任务，暂停也占位 |

### catalog_clean_job

| 字段 | 类型 | 可空 | 用途 |
| --- | --- | --- | --- |
| id | BIGINT | 否 | 自增清洗任务主键 |
| source_key / brand_name | VARCHAR(64) / VARCHAR(50) | 否 | 账套、品牌 |
| created_date | DATE | 是 | 指定建档日，NULL 表示品牌全部 |
| include_reviews | TINYINT | 否 | 是否纳入未映射待审商品 |
| target_source_id | BIGINT | 是 | 单条技术重试的来源 ID |
| request_key | VARCHAR(64) | 否 | 账套内提交幂等键 |
| requester_id / requester_name | BIGINT / VARCHAR(64) | 姓名可空 | 发起人和姓名快照 |
| status | VARCHAR(16) | 否 | RUNNING、COMPLETED、PARTIAL、FAILED |
| worker_token / lease_until | VARCHAR(64) / DATETIME | 是 | 运行租约 |
| created_at | DATETIME | 否 | 创建时间 |
| active_slot | TINYINT | 是 | 运行时生成 1，其余 NULL |

### catalog_clean_job_item

| 字段 | 类型 | 可空 | 用途 |
| --- | --- | --- | --- |
| job_id / source_id | BIGINT | 否 | 复合主键，关联清洗任务和本地来源 SKU |
| source_version | INT UNSIGNED | 否 | 确认时的来源版本 |
| outcome | VARCHAR(16) | 否 | PENDING、AUTO_PASSED、MANUAL_PASSED、REVIEW_REQUIRED、ERROR、SKIPPED |

### source_goods_item

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 本地来源内容主键，不是吉客云SKU主键 |
| source_key | VARCHAR(64) | 否 | — | 受控账套标识 |
| brand_name | VARCHAR(50) | 否 | — | 品牌查询分区，不取上游返回字段 |
| goods_name | VARCHAR(512) | 是 | NULL | 来源goodsName原文 |
| goods_no | VARCHAR(128) | 是 | NULL | 来源goodsNo原文，不作SKU唯一键 |
| sku_name | VARCHAR(256) | 是 | NULL | 来源skuName原文 |
| cate_name | VARCHAR(256) | 是 | NULL | 来源cateName原文 |
| goods_field4 | VARCHAR(1024) | 是 | NULL | 来源goodsField4原文 |
| goods_field1 | VARCHAR(1024) | 是 | NULL | 来源goodsField1原文 |
| goods_field5 | VARCHAR(1024) | 是 | NULL | 来源goodsField5原文 |
| sku_id | VARCHAR(19) ASCII | 否 | — | 吉客云 SKU ID，源商品库唯一 |
| cate_full_name | VARCHAR(1024) | 是 | NULL | 完整分类路径原文 |
| gmt_modified | BIGINT UNSIGNED | 是 | NULL | SKU 修改时间，Unix 毫秒 |
| goods_gmt_modified | BIGINT UNSIGNED | 是 | NULL | 商品修改时间，Unix 毫秒 |
| is_blockup | TINYINT UNSIGNED | 是 | NULL | 1 停用、0 启用、NULL 未知 |
| is_package_good | TINYINT UNSIGNED | 是 | NULL | 1 组合商品、0 普通商品、NULL 未知 |
| first_seen_job_id | BIGINT | 否 | — | 首次写入的同步任务 |
| last_seen_job_id | BIGINT | 否 | — | 最近出现的同步任务，仅当前信息不作批次历史 |
| mapping_status | VARCHAR(16) | 否 | 'UNMAPPED' | UNMAPPED-待归类，MAPPED-已确认，EXCLUDED-排除 |
| catalog_brand_id | BIGINT | 是 | NULL | 与来源品牌名称相同的标准品牌 |
| goods_id | BIGINT | 是 | NULL | 自动清洗或人工审核确认的标准商品 |
| mapping_remark | VARCHAR(512) | 是 | NULL | 归类或排除原因 |
| mapping_origin | VARCHAR(16) | 是 | NULL | 生效映射来源：AUTO-自动清洗，MANUAL-人工审核 |
| clean_status | VARCHAR(16) | 否 | 'PENDING' | PENDING、PROCESSING、AUTO_PASSED、REVIEW_REQUIRED、MANUAL_PASSED、ERROR、EXCLUDED |
| clean_job_id | BIGINT | 是 | NULL | 最近生成清洗或审核结果的独立任务 |
| clean_candidate | JSON | 是 | NULL | 本地候选及白名单字段证据，禁止完整响应 |
| clean_error_code | VARCHAR(64) | 是 | NULL | 缺失、歧义、冲突或技术错误类别 |
| clean_attempts | INT UNSIGNED | 否 | 0 | 清洗尝试次数 |
| cleaned_at | DATETIME | 是 | NULL | 最近一次清洗或人工审核结束时间 |
| reviewed_by | BIGINT | 是 | NULL | 必要时人工审核人，自动通过必须为空 |
| reviewed_at | DATETIME | 是 | NULL | 人工审核时间 |
| review_reason | VARCHAR(512) | 是 | NULL | 审核结果说明，兼容旧请求提交的原因 |
| record_version | INT UNSIGNED | 否 | 0 | 防止清洗或审核使用已变化的来源记录 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间，北京时间 |
| updated_at | DATETIME | 否 | CURRENT_TIMESTAMP | 来源或清洗结果的更新时间，由应用在实际变更时写入 |

### catalog_operation_log

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 商品导入记录主键 |
| actor_type | VARCHAR(16) | 否 | 'USER' | USER 人工操作，SYSTEM 后台导入 |
| operator_id | BIGINT | 是 | NULL | 人工操作用户，SYSTEM必须为空，不冒用发起人 |
| operator_name | VARCHAR(64) | 是 | NULL | 操作人姓名快照 |
| entity_type | VARCHAR(32) | 否 | — | SOURCE_GOODS_ITEM-来源商品，SYNC_JOB-导入任务 |
| entity_id | BIGINT | 否 | — | 对应本地对象主键，由服务端校验类型和归属 |
| action_type | VARCHAR(32) | 否 | — | 来源建档日、来源更新或导入任务操作 |
| batch_key | VARCHAR(64) | 是 | NULL | 批量操作关联号，建档日证据用 `CREATED-DATE-YYYY-MM-DD` |
| before_data | JSON | 是 | NULL | 本地字段变更前对象，不存完整上游响应或外部SKU标识 |
| after_data | JSON | 是 | NULL | 本地字段变更后对象，任务快照须剔除游标及执行令牌 |
| reason | VARCHAR(512) | 是 | NULL | 导入或变更说明 |
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
| policy_id | BIGINT | 否 | — | 关联 policy_master.id |
| platform_code | VARCHAR(32) | 否 | — | 具体平台编码，不保存 ALL |
| spu_id | BIGINT | 是 | NULL | 标准机型及版本，草稿可暂未选 |
| ram | VARCHAR(20) | 是 | NULL | 配置身份的运行内存，统一GB |
| rom | VARCHAR(20) | 是 | NULL | 配置身份的机身存储，统一GB |
| brand_name_snapshot | VARCHAR(50) | 是 | NULL | 提交时品牌快照 |
| series_name_snapshot | VARCHAR(64) | 是 | NULL | 提交时系列快照 |
| color_scope | VARCHAR(32) | 是 | NULL | ALL_COLORS-不限颜色，SPECIFIC_COLOR-指定颜色；NULL仅为草稿未选择 |
| goods_id | BIGINT | 是 | NULL | 指定颜色时引用当前配置下的标准商品 |
| model_full_name | VARCHAR(384) | 是 | NULL | 涉及型号全称快照，草稿可为空 |
| single_model | VARCHAR(100) | 是 | NULL | 单型号快照，草稿可为空 |
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

## 5. 验证范围

两份建表脚本内容一致，已静态核对表、外键、索引和字段字典。建表执行结果由用户在 Navicat 核对。标准目录联调覆盖同组合复用、公司外账号拒绝、跨品牌来源映射拒绝、指定颜色与配置不匹配拒绝、停用商品不自动恢复、容量及版本不明转审核、并发清洗归并和政策快照。
