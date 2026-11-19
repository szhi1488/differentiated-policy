# 数据库设计与字段字典

更新日期：2026-11-19。十四表空库基线适用于 MySQL 8.0.16+，包含用户头像字段 `avatar_url` 和商品政策名 `policy_name`。公司所有有效管理员共用源商品、清洗结果和标准目录。后端须在建立会话前校验公司飞书租户，并在请求时校验有效身份及权限。政策三表已纳入空库脚本。申请人政策接口和商品引用检查已使用政策三表，目标 MySQL 待联调。2026-11-17 用户管理五个接口直接复用本基线，没有新增字段、表或迁移。

## 已有十四表库升级步骤

2026-11-19 的两项增量适用于已有原始 SKU 一对一基线的十四表库。由用户停止后端写入、备份目标库，在 Navicat 核对库名和当前结构。两项都待升级时，先回填商品名称与政策名，再升级自动排除约束。已完成的步骤通过脚本的核对查询确认。两个差异数均为 0、两处约束均核对通过后启动新版后端。需要整体回退时，在停写期间恢复升级前备份并切回旧版。

### 已有十四表库升级商品名称与政策名

在 Navicat 按段执行[商品名称与政策名增量脚本](upgrade_goods_source_names.sql)。旧结构应为 `goods_name VARCHAR(384)` 且无 `policy_name`。先确认已关联来源的缺失原名清单为空。若有结果，先在吉客云补全原名并重新导入，再停写复核。

脚本将 `goods_name` 扩至 512 字符，新增可空的 `policy_name`（1024 字符），按 `source_goods_item.goods_id = goods.id` 将已关联商品的两项值覆盖为当前来源原文。手工商品及历轮政策快照保留原值。DDL 单独提交，已成功改表、回填中断时，从第 3 段继续。末尾按字节核对 `goods_name_differences` 与 `policy_name_differences`，预期均为 0，尾部空格及 NULL 与空串的差异也计入。

### 已有十四表库升级清洗自动排除

在 Navicat 按段执行[自动排除约束增量脚本](upgrade_catalog_direct_exclusions.sql)。核对 `source_goods_item.ck_source_clean_excluded` 允许记录无人工审核人的规则排除，`catalog_clean_job_item.ck_clean_item_outcome` 允许 `EXCLUDED`，且两项约束均已启用。两条 ALTER 分别提交，中断后核对已完成项并继续。升级保留既往任务结果。新版后端运行清洗后，可用脚本末尾查询核对该任务的自动排除数。

## 1. 在 Navicat 建表

项目未配置 Flyway 或自动建表。新空库由用户在 Navicat 执行以下第 1、3、4 步，最新基线已包含两项增量。第 2 步仅用于已明确要清空数据的重建。已有十四表库保留数据的升级见上节。

1. 停止项目后端，在 Navicat 选中目标库 `differentiated policy`。
2. 整份执行 [drop_all_tables.sql](drop_all_tables.sql)，刷新表列表。该脚本按外键顺序删除下列十四张项目表，用户、角色、商品、任务、政策及审核历史随表永久删除。数据库本身和磁盘附件保留。已是空库可直接从下一步开始。
3. 在同一库整份执行 [schema.sql](schema.sql) 或 [V1__create_policy_tables.sql](V1__create_policy_tables.sql)，任选一份。两份内容相同。执行成功后刷新表列表，应出现下列十四张空表。若建表中途失败，先查看 Navicat 报错，修正原因后从第 2 步重新开始。
4. 重启后端，用飞书重新登录，生成默认的申请人账号。在 Navicat 核对该账号并将其 `user_info.role_type` 设为 `ADMIN`，重新进入管理员工作台，从吉客云导入商品并发起清洗。

## 2. 十四表清单和约束

| 表 | 职责 | 关键关联 |
| --- | --- | --- |
| user_info | 飞书身份、本地角色 | tenant_key + open_id 唯一；停用身份仍占唯一键 |
| brand | 公司品牌字典 | 名称直接对应吉客云 brandName，名称唯一 |
| series | 品牌下的标准系列 | 同品牌下系列名称唯一 |
| spu | 标准机型及版本 | 同品牌下机型名称 + 版本唯一 |
| color_dict | 独立颜色表 | 颜色名称唯一，可由多个机型商品引用 |
| goods | 每条已通过来源独立对应的标准商品，或手工商品 | 原始 SKU 非空唯一，来源 goods_id 一对一；同配置同颜色可有多个原始 SKU |
| goods_sync_job | 每次单品牌导入任务与最新续拉进度 | 发起人关联用户，同账套仅一个活动槽，幂等键唯一 |
| catalog_clean_job | 手动发起的清洗任务，保存选择范围、任务状态和执行租约 | 发起人关联用户，账套活动槽及请求键唯一，进度由任务明细统计 |
| catalog_clean_job_item | 本轮选中的源商品、确认时的来源版本及本轮结果 | `job_id` 关联清洗任务，`source_id` 关联本地源商品，两列构成主键 |
| source_goods_item | 当前 SKU 来源及内联映射 | sku_id 唯一，非空 goods_id 唯一，任务须匹配来源账套及品牌，标准商品须匹配来源品牌 |
| catalog_operation_log | 源商品变更前后值、导入任务操作和建档日命中记录 | `entity_type` 区分对象，`entity_id` 保存对应的本地主键 |
| policy_apply | 政策申请与当前编辑表单 | 申请人与政策同租户 |
| policy_audit_log | 每轮完整提交快照和审核结果 | 每单每轮唯一，审核人与政策同租户 |
| policy_goods_detail | 逐轮、逐平台的实际选中商品 | 归属对应提交轮次，商品 ID 与机型配置匹配 |

完整关联图及业务见 [确认方案](../architecture/catalog-admin-design.md)。来源到标准商品的关联保存在来源表。

### 2026-11-15 原始 SKU 一对一调整

用户确认：吉客云每条源商品通过清洗或人工审核后，单独对应一条标准商品，`goods.sku_id` 延续该来源的原始 SKU。不同源 SKU 的相同规格、颜色不再归并。`goods.id` 继续作为政策引用主键，品牌、系列、机型及颜色字典仍可共用。

流程验收按以下结果核对：

1. 导入仅保存源商品原文与原始 `sku_id`，等待管理员发起清洗；导入成功本身不生成 `goods`。
2. 某条来源自动清洗或人工审核通过后，来源 `goods_id` 指向自身商品，且 `goods.sku_id = source_goods_item.sku_id`，两侧均使用字符串。
3. 两条不同原始 SKU 即使机型版本、RAM、ROM、颜色相同，也保存两条商品；重复处理同一来源则复用自身商品，不重复建档。
4. 手工新增没有原始来源，`sku_id` 留空；政策继续按实际选中的 `goods.id` 引用商品。

原始 SKU 约束已包含在两份建表脚本中。[Navicat 重建步骤](#1-在-navicat-建表)

预期 `goods.sku_id` 为可空 VARCHAR(19) ASCII，唯一键为 `uk_goods_sku`；来源 `goods_id` 由 `uk_source_goods` 保证一对一。规格组合使用普通索引 `idx_goods_spu_specs`。目标 MySQL 建表与并发验证由用户执行，本地 H2 仅验证应用逻辑。

### 商品表关联示例

以下用一条源商品说明十四表基线中的写入路径。示例 ID 和日期仅用于说明。

| 步骤 | 表 | 示例 |
| --- | --- | --- |
| 发起导入 | `goods_sync_job` | 任务 `20` 导入一加手机，记录导入状态和续拉断点。 |
| 保存当前原文 | `source_goods_item` | 吉客云 `skuId=123` 匹配本地源商品 `id=501`。源名称变化时更新原行并保留本地 ID。 |
| 记录导入事件 | `catalog_operation_log` | 管理员发起任务时记录 `SYNC_JOB / 20 / START`。名称变化时记录 `SOURCE_GOODS_ITEM / 501 / UPDATE`，保存变更前后的值。按建档日命中时可记录 `SOURCE_GOODS_ITEM / 501 / CREATE`，`batch_key` 例如 `CREATED-DATE-2026-11-12`。 |
| 发起清洗 | `catalog_clean_job` | 任务 `42` 保存品牌和建档日范围。 |
| 处理任务成员 | `catalog_clean_job_item` | `(job_id=42, source_id=501, source_version=3)` 表示该任务选中了本地源商品 `501`。结果先是 `PENDING`，通过后更新为 `AUTO_PASSED`。 |
| 确认标准商品 | `goods`、`source_goods_item` | 创建该来源独立的标准商品 `goods.id=801, sku_id=123`，在源商品 `501` 上回填 `goods_id=801`。 |

`source_goods_item.clean_status` 表示源商品当前的清洗状态，`catalog_clean_job_item.outcome` 表示该商品在某一轮任务中的结果。任务进度按本轮明细统计。吉客云业务字段未变时，本次导入不会新增 `UPDATE` 变更记录。按建档日导入仍可补齐日期证据。

## 3. 关键存储规则

### 来源与标准目录

吉客云来源保存原始七字段及 skuId、cateFullName、gmtModified、goodsGmtModified、isBlockup、isPackageGood。brand_name 来自校验后的查询分区。原始层先落库，系列、型号、配置、颜色经过规则校验后写入标准层，异常才人工审核。

来源字段保留 NULL 与空字符串原意，不自动清洗覆盖原文。VARCHAR 长度是当前工程上限，联调需核对；超长输入明确拒绝，不隐式截断。SKU ID 按 ASCII 十进制字符串保存，接口同样返回字符串，防止浏览器丢失精度。

`source_goods_item.sku_id` 是源商品库唯一键。第一次看到 SKU 时插入，有变化时按相同 SKU 更新原行并保留本地 ID。比较吉客云两类修改时间及实际来源字段，旧响应保留已有内容。同一 SKU 如出现在不同账套或品牌，进入冲突处理，避免覆盖或重复插入。来源内容、状态和时间均未变时，只推进任务和维护必要的任务引用、建档日证据，商品内容、更新时间和清洗状态保持原值。仅上游时间前进时更新来源时间，业务字段或状态变化时才按现有规则标记待清洗。导入在这一阶段只处理来源表。

来源列表的 `createdDate` 使用本地来源 ID 的建档日证据，审计的 `batch_key` 为 `CREATED-DATE-YYYY-MM-DD`，并可从首次、最近导入任务补查。同一 SKU 按日重新导入可补齐建档日。缺少证据时返回 null。

`gmt_modified` 和 `goods_gmt_modified` 分别保存吉客云规格和货品的 Unix 毫秒，独立于本系统的 DATETIME 以及任务的 `sync_date`。前端使用北京时间显示两个修改时间，并保留非零毫秒。

标准目录按品牌 → 系列 → 机型版本 → 配置 → 颜色查询。品牌 `name` 直接对应清洗确认的吉客云 `brandName`。独立颜色表 `color_dict` 只保存颜色 ID 和名称。商品通过 `color_id` 引用颜色，某配置实际可选颜色由其有效商品行决定。

自动清洗或人工审核通过时，`goods_name`、`policy_name` 分别保存来源 `goodsName`、`goodsField1` 原文。手工新增使用管理员填写的商品名称，政策名为 NULL。品牌通过 `brand_id` 关联。`product_no` 对应来源 `goodsNo`，可为空、可重复。商品 `sku_id` 保存其对应源商品的原始 SKU 字符串；`uk_goods_sku` 保证非空值唯一，`uk_source_goods` 保证同一个标准商品最多关联一条来源。同一规格和颜色的不同源 SKU 分别建档，`idx_goods_spu_specs` 仅用于查询，不再去重。来源重处理按自身映射或自身原始 SKU 找回商品，更新商品名称和政策名，保留货号和明确的销售状态。手工新增没有原始来源，`sku_id` 为 NULL，以 `goods.id` 标识；服务端仍拒绝手工重复创建现存配置与颜色；来源商品编辑可与其他 SKU 同规格，无来源手工商品修改规格时仍校验重复。

规格身份使用 `(spu_id, ram, rom)`，容量统一为正整数 GB，按 1 TB = 1024 GB 规范化。机型身份以品牌、规范机型名和版本区分。已存在的机型归属不同系列或版本证据不明时转人工核对。被引用的机型、配置和颜色身份应保持稳定，纠错创建或选择正确目标。

标准商品编辑使用现有表结构。服务端在事务内锁定 `goods` 行，以当前商品字段和 `updated_at` 生成的 `editVersion` 核对提交版本，并检查 `source_goods_item` 的品牌绑定及政策的商品引用。Mapper 查询 `policy_goods_detail`，按具体 `goods_id` 保护商品。提交与商品变更使用同一商品行锁。允许的修改写回同一商品行，继续使用原主键和原始 SKU。修改机型版本时，按已有唯一键复用或创建 `spu`，再更新商品的关联。[商品编辑接口](../api/catalog-api.md#商品编辑)

系列的引用状态根据下属 `spu` 是否被 `goods.spu_id` 或政策商品明细的 `spu_id` 引用判断。永久删除商品后，机型记录会保留到管理员确认删除系列。删除系列时，服务端锁定系列和下属机型，核对商品与政策明细引用，在同一事务先移除无引用机型再移除系列。系列的机型引用检查读取 `policy_goods_detail`。[商品数据字典](../api/catalog-api.md#商品数据字典)

`source_goods_item` 保留候选、异常原因、尝试次数、最近清洗任务和审核信息。已映射必须有 `catalog_brand_id`、`goods_id` 和 AUTO/MANUAL 来源。复合外键同时校验吉客云分区对应的品牌和商品归属。自动通过需要候选、任务和完成时间。人工通过或人工排除需要审核人、时间和结果。自动排除需要任务、候选证据、原因和清洗时间，人工审核字段均为空。重算时可保留旧映射，人工结果受保护。

清洗和人工审核通过时，按该来源自身映射或原始 SKU 创建或复用对应标准商品，机型版本、RAM、ROM 和颜色用于校验目标规格，不用于跨来源归并。`goods.status` 表示销售状态，1 在售、2 待上架、3 已下架、4 已停产、NULL 未知。清洗或人工审核通过时，新建标准商品及复用时销售状态为空的标准商品由应用写入在售。已有明确销售状态予以保留。

### 政策三表

- `policy_apply` 按申请保存基本字段、整单平台和当前草稿。草稿可缺时间或商品。商品卡 JSON 保留稳定的卡片和方案标识、普通卡及指定卡选中的具体商品 ID、是否跟随整单平台、方案参数、各平台分配及指定商品数量。百亿的零售专属字段为空。档案附件存文件 ID 或外部链接，文件正文由附件存储管理。已实现 FILE 引用的上传者校验和服务端名称回填，物理删除检查当前 JSON 和所有提交快照引用，绑定与删除使用上传者用户行锁。
- `policy_audit_log` 每次提交或重提插入一行，内容快照含完整基础信息、商品卡、方案分配、标准 ID、服务端生成的品牌系列型号版本配置与颜色显示值、数量、时间、备注和附件引用。审核只更新这一轮的结果、审核人、意见和时间。
- `policy_goods_detail` 按实际平台和所选商品展开，绑定 `(policy_id, version)`。普通卡和指定卡均须保存 `goods_id`，以 `(goods_id, spu_id, ram, rom)` 校验商品归属。新增或未勾选的商品不进入本轮。明细供商品筛选、覆盖候选和商品引用检查使用。补贴、数量及历史展示字段从同轮快照读取。
- 提交时后端从同一份已校验表单生成快照与明细，按版本更新申请并在同一事务中写入。每次业务写入递增 `record_version`，每次正式提交递增 `current_version`。审核按当前待审轮次条件更新。详细流程见 [状态机](../business/status-machine.md)。

本人未提交草稿允许物理删除 `policy_apply`，条件为 `DRAFT`、`current_version=0`、本人归属、`is_deleted=0` 和当前记录版本，并排除已有 `policy_audit_log`、`policy_goods_detail` 引用。删除与保存、提交采用同一主表行的条件写入。其他状态的申请及历史继续保留，磁盘附件独立处理。

### 数据库与服务端校验

| 范围 | 数据库 | 服务端 |
| --- | --- | --- |
| 身份与历史 | 同租户账号外键、每单每轮唯一、明细关联本轮、外键限制删除 | 校验有效身份和权限，正常业务过滤主表逻辑删除，保护每轮快照与历史明细 |
| 平台与颜色 | 正式明细平台编码、商品复合外键、同轮同平台同商品唯一 | 校验渠道与整单平台、卡片与方案分配、选中商品在售、同平台跨卡重复选中同一商品 |
| 数量和补贴 | 快照和草稿使用 JSON 对象 | 指定颜色按平台选填参考数量，留空保存为 NULL，填写 0 原样保存。补贴仅保存所选方式的参数，金额按 `DECIMAL(10,2)`、比例按 `DECIMAL(5,2)` 百分数的精度校验 |
| 日期和提交完整性 | 同时提供两个时间时要求开始早于参考到期 | 草稿保留未填项，提交完整校验。时间为北京时间，精确到秒。后续政策按最新申请覆盖，参考到期时间用于展示 |

### 权限、历史与任务

登录时须识别停用记录并拒绝登录。申请人只能操作本人申请，审核应校验有效管理员及租户；明细和日志经父政策授权。商品中心的编辑、删除及字典维护须检查历史商品或机型引用。正式提交与商品改规格、删除共享事务锁定约定，提交完成前重新验证目录，具体并发验收见第 5 节。

goods_sync_job 每条记录只导入一个品牌，并独立保存导入模式、北京时间日期、查询阶段和当前阶段游标。按修改日先查商品修改，再重置游标查 SKU 修改。终态任务只保留该任务最后位置，日志不保存游标、worker_token 或完整上游响应。active_slot 为自动生成列，调用方不写；QUEUED/RUNNING/PAUSED 占同账套唯一槽。暂停任务可在冷却后恢复，或结束并保留已落库原文。源商品的首次和最近导入任务引用要求同账套和品牌。

清洗任务独立领取租约。成员快照固定内部来源版本，用于避免处理期间来源变化后写入旧结果。来源、映射、目录和成员结果同事务更新。同账套并发发起由活动槽唯一索引约束；调度通过任务索引及成员待处理索引读取。

商品导入记录保留建档日、来源变更和导入操作。清洗不写此表，当前异常原因和人工审核结果保存在来源行，本轮处理进度从任务成员结果统计。政策提交和审核历史仍保存在 policy_audit_log。

## 4. 字段字典

以下逐字段从本版 SQL 提取；可空指 DDL 层允许，不等于正式提交可缺失。完整索引/约束以 SQL 为准，ID 和游标精确传输，生成列不由调用方写入。

### user_info

2026-11-15 用户已明确确认新增可空 `avatar_url`，保存飞书登录返回的 HTTPS 头像地址。两份建表脚本均包含该字段。已有用户无需回填，重新登录时同步；缺失或无效头像不覆盖已有地址，不影响身份登录，停用用户不能更新。头像仅用于展示，不新增索引，不参与身份或角色判断。

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 本地用户主键 |
| tenant_key | VARCHAR(64) | 否 | — | 飞书租户标识 |
| open_id | VARCHAR(64) | 否 | — | 飞书应用内用户标识 |
| union_id | VARCHAR(64) | 是 | NULL | 飞书同一应用开发商下的用户标识 |
| name | VARCHAR(64) | 否 | — | 用户姓名 |
| avatar_url | VARCHAR(2048) | 是 | NULL | 飞书头像 HTTPS 地址；登录时同步 |
| department | VARCHAR(128) | 是 | NULL | 所属部门 |
| role_type | VARCHAR(32) | 否 | 'APPLICANT' | APPLICANT-申请人，ADMIN-管理员 |
| is_deleted | TINYINT | 否 | 0 | 0-未删除，1-已删除 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | 否 | CURRENT_TIMESTAMP |  |

2026-11-17 已实现的用户列表、全量统计、详情、角色调整和启停直接使用上述字段，无需改表。用户管理将现有 `is_deleted=0/1` 映射为启用／停用，允许管理员查看两种状态；登录仍读取并拒绝停用身份，不能按不存在的账号重新创建。部门可空，本轮不增加通讯录同步。

角色接口仅在实际变更时更新 `role_type` 和 `updated_at`，用目标当前角色及启停值做旧值校验，事务内锁定操作人和目标账号并保护有效管理员。不新增版本号或角色操作历史表，不改变用户 ID、飞书身份、启停和政策历史。启停接口只更新 `is_deleted` 和 `updated_at`，沿用相同的锁顺序及旧值校验，原有角色与业务关联继续保留。[用户管理接口及验证范围](../api/user-api.md)

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
| sku_id | VARCHAR(19) ASCII | 是 | NULL | 对应源商品的原始 SKU 字符串，非空唯一；无来源手工商品为空 |
| product_no | VARCHAR(128) | 是 | NULL | 货品编号，对应来源 goodsNo，可重复 |
| brand_id | BIGINT | 否 | — | 与机型一致的品牌 |
| series_id | BIGINT | 否 | — | 与机型一致的系列 |
| spu_id | BIGINT | 否 | — | 所属机型及版本 |
| goods_name | VARCHAR(512) | 否 | — | 商品名称，清洗或审核通过时取来源 goodsName 原文 |
| policy_name | VARCHAR(1024) | 是 | NULL | 政策名，取来源 goodsField1 原文，保留空串 |
| ram | VARCHAR(20) | 否 | — | 规范运行内存，如16GB |
| rom | VARCHAR(20) | 否 | — | 规范机身存储，统一GB，如1TB记为1024GB |
| color_id | INT UNSIGNED | 否 | — | 关联独立颜色表的主键 |
| status | TINYINT UNSIGNED | 是 | NULL | 销售状态：1-在售，2-待上架，3-已下架，4-已停产，NULL-未知 |
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
| outcome | VARCHAR(16) | 否 | PENDING、AUTO_PASSED、MANUAL_PASSED、REVIEW_REQUIRED、EXCLUDED、ERROR、SKIPPED |

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

### policy_apply

对外申请单号 `policyCode` 统一由本表 `id` 格式化为 `ZC-` 加最少六位数字，例如 `ZC-000005`，超过六位时完整保留。现阶段用户选择不调整结构，现有 `policy_no` 字段和唯一索引继续保存原有创建幂等标识，兼容首次保存重放；页面不直接展示该内部值。新旧申请及快照响应均由服务端适配短单号，历史原文不回写，本次不需要迁移 SQL。

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 申请主键 |
| policy_no | VARCHAR(32) | 否 | — | 沿用的内部创建幂等标识；对外短单号由 id 生成 |
| tenant_key | VARCHAR(64) | 否 | — | 会话确定的租户 |
| applicant_id | BIGINT | 否 | — | 同租户的本地账号 |
| applicant_name | VARCHAR(64) | 否 | — | 申请人姓名快照 |
| channel_type | VARCHAR(20) | 否 | — | RETAIL 或 BILLION |
| platforms | JSON | 是 | NULL | 整单平台编码数组 |
| write_off_type | VARCHAR(20) | 是 | NULL | SALES 或 ACTIVATION |
| scene | VARCHAR(20) | 是 | NULL | NATIONAL_SUB、LOCAL_SUB 或 SELF_SUB |
| is_estimated | TINYINT | 是 | NULL | 0 否、1 是，百亿为空 |
| start_time、end_time | DATETIME | 是 | NULL | 开始时间、参考到期时间。北京时间，精确到秒 |
| remark | TEXT | 是 | NULL | 特别备注，申请唯一备注字段 |
| goods_form_json | JSON | 是 | NULL | 可回填的商品卡、选品、联动及方案 |
| attachment_urls | JSON | 是 | NULL | 附件文件 ID 和外部链接列表 |
| status | VARCHAR(20) | 否 | DRAFT | DRAFT、PENDING、APPROVED、REJECTED |
| current_version | INT UNSIGNED | 否 | 0 | 最近提交轮次 |
| record_version | INT UNSIGNED | 否 | 1 | 并发编辑版本 |
| is_deleted | TINYINT | 否 | 0 | 0 正常、1 已删除 |
| created_at、updated_at | DATETIME | 否 | CURRENT_TIMESTAMP | 建单时间、更新时间 |

2026-11-15 已确认移除 `policy_apply.other_desc`，两份建表脚本均只保留 `remark`（特别备注）。旧提交快照中的 `otherExplanation` 通过 DTO 兼容读取时忽略。

### policy_audit_log

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 审核记录主键 |
| policy_id | BIGINT | 否 | — | 关联 policy_apply.id |
| tenant_key | VARCHAR(64) | 否 | — | 与申请及审核人相同 |
| version | INT UNSIGNED | 否 | — | 提交轮次，从 1 开始 |
| snapshot_data | JSON | 否 | — | 每轮正式提交的表单、商品和附件引用，由 Java 记录类 `PolicySnapshotData` 定义格式。[快照读写与旧数据兼容](../api/policy-api.md#提交快照的存储与兼容) |
| audit_status | VARCHAR(20) | 否 | SUBMITTED | SUBMITTED、APPROVED、REJECTED |
| auditor_id | BIGINT | 是 | NULL | 同租户的审核人账号 |
| auditor_name | VARCHAR(64) | 是 | NULL | 审核人姓名快照 |
| audit_comment | VARCHAR(512) | 是 | NULL | 本轮审核意见 |
| audit_time | DATETIME | 是 | NULL | 本轮审核时间 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 本轮提交时间 |

### policy_goods_detail

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 适用明细主键 |
| policy_id | BIGINT | 否 | — | 与 version 关联提交记录 |
| version | INT UNSIGNED | 否 | — | 对应提交轮次 |
| platform_code | VARCHAR(32) | 否 | — | 实际适用的平台编码 |
| spu_id | BIGINT | 否 | — | 标准机型及版本 |
| ram、rom | VARCHAR(20) ASCII | 否 | — | 配置容量，统一 GB |
| goods_id | BIGINT | 否 | — | 普通卡或指定卡实际选中的标准商品 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 本轮明细写入时间 |

## 5. 验证范围

建表后在 Navicat 刷新表列表，与第 2 节的十四表清单核对。建表与业务数据库验收以实际执行结果为准。

政策联调时，用两份相同 `recordVersion` 的请求并发提交同一草稿，再并发审核同一提交轮次。预期每一组只有一个成功，另一个返回 409，申请仅有一个新增轮次，审定结果和快照仍对应该轮。再用测试商品并发执行正式提交与删除或改规格，预期提交成功后目录变更受引用保护，目录先变更时提交须重新验证并处理失效选品。联调后核对政策三表的关联、商品范围和审核状态。执行计划用实际数据和筛选条件复核。
