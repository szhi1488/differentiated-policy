# 数据库设计与字段字典

本项目使用 MySQL 8.0.16 及以上版本，当前空库基线包含 **29 张表**。新增角色授权、平台团队及按品牌和系列预设的审核负责人。商品目录由公司共用。业务关联与提交案例见[角色、平台和审核分工设计](role-platform-review-design.md)。

前后端按本基线读写。首次飞书登录初始化内置岗位、权限和渠道平台，同时建立运营账号。首位管理员授权后，从工作台配置平台团队和品牌、系列审核负责人。

## 1. 在 Navicat 建表

项目由用户在 Navicat 建表，后端启动时不会自动创建或更新目标库结构。

1. 在 Navicat 中创建或选中目标空数据库，核对连接、数据库名、MySQL 版本及现有表数为 0。
2. 对该库运行 [schema.sql](schema.sql) 或 [V1__create_policy_tables.sql](V1__create_policy_tables.sql) **其中一份一次**。两者内容相同，已按外键依赖顺序排列。
3. 刷新“表”，按[29 表清单](#2-二十九表清单和约束)核对表名。预期得到 29 张空表。MySQL DDL 会隐式提交，如中途报错，记录失败的语句和已建表名再定位。

两份基线只创建结构。首次登录后，将核验过的本地账号通过 `sys_user_role` 授予管理员。管理员配置其他人员和具体品牌、系列的审核分工。仓库中的 [drop_all_tables.sql](drop_all_tables.sql) 仅适用于旧 23 表，本次空库建表无需使用。

<a id="2-二十三表清单和约束"></a>

## 2. 二十九表清单和约束

| 表 | 职责 | 关键关联 |
| --- | --- | --- |
| user_info | 飞书身份、启停与授权版本 | tenant_key + open_id 唯一，停用身份仍占唯一键 |
| sys_role | 本企业的岗位角色 | 企业内角色编码唯一 |
| sys_user_role | 账号担任的岗位 | 企业、用户、角色联合主键，两端均核对所属企业 |
| sys_role_permission | 岗位可用的权限项 | 企业、角色、权限编码联合主键 |
| platform_team | 渠道平台团队及分工版本 | 企业、渠道、平台联合主键，零售和百亿同名平台分别配置 |
| user_platform_assignment | 平台负责成员与指定提交人 | 同平台同人只一行，可以有多位共同成员，数据库保证最多一名提交人 |
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
| policy_audit_log | 每轮完整提交快照和审核结果 | 每单每轮唯一，冻结渠道、唯一品牌及系列，审核人与政策同企业 |
| policy_round_platform | 本轮正式勾选的平台 | 每单、轮次、平台唯一，渠道与该轮快照和平台团队一致 |
| policy_time_change | 已生效的原单改期流水 | 关联原单获批轮次、实际办理人和可选的发起轮次，按版本与幂等标识唯一 |
| policy_goods_detail | 逐轮、逐平台的实际选中商品 | 品牌、系列与正式轮次和机型一致，平台属于该轮，商品 ID 与机型配置匹配 |
| policy_review_settings | 企业审核分工的配置版本及修改者 | 一企业一行 |
| policy_review_brand_owner | 品牌唯一终审采购及初审方式 | 按品牌初审时记录产品运营，按系列初审时关联系列分工 |
| policy_review_series_owner | 系列唯一初审产品运营 | 关联已配置的品牌及其标准系列、同企业产品运营 |
| policy_review_round | 本轮阶段与路由快照 | 关联当轮审核及同企业申请 |
| policy_review_member | 节点成员及个人任务 | 本轮每个审核节点最多一名有效指派人，提交时冻结负责人，办理后留存结办状态 |
| policy_review_decision | 节点实际决定 | 每单每轮每组最多一条 |
| policy_review_reassignment | 已停用的在途改派表 | 保留既有表结构和历史数据，当前流程不读写 |
| policy_review_delivery | 审核投递及执行期、平台交接和商品导入异常暂停提醒 | 审核按轮次、事件、动作和收件方去重，独立提醒按企业与 notice_key 去重 |

完整关联图及业务见 [确认方案](../architecture/catalog-admin-design.md)。来源到标准商品的关联保存在来源表。

### 2026-11-15 原始 SKU 一对一调整

用户确认：吉客云每条源商品通过清洗或人工审核后，单独对应一条标准商品，`goods.sku_id` 延续该来源的原始 SKU。不同源 SKU 的相同规格、颜色不再归并。`goods.id` 继续作为政策引用主键，品牌、系列、机型及颜色字典仍可共用。

流程验收按以下结果核对：

1. 导入仅保存源商品原文与原始 `sku_id`，等待管理员发起清洗；导入成功本身不生成 `goods`。
2. 某条来源自动清洗或人工审核通过后，来源 `goods_id` 指向自身商品，且 `goods.sku_id = source_goods_item.sku_id`，两侧均使用字符串。
3. 两条不同原始 SKU 即使机型版本、RAM、ROM、颜色相同，也保存两条商品；重复处理同一来源则复用自身商品，不重复建档。
4. 手工新增没有原始来源，`sku_id` 留空；政策继续按实际选中的 `goods.id` 引用商品。

原始 SKU 约束已包含在两份建表脚本中。[Navicat 建表步骤](#1-在-navicat-建表)

预期 `goods.sku_id` 为可空 VARCHAR(19) ASCII，唯一键为 `uk_goods_sku`；来源 `goods_id` 由 `uk_source_goods` 保证一对一。规格组合使用普通索引 `idx_goods_spu_specs`。目标 MySQL 建表与并发验证由用户执行，本地 H2 仅验证应用逻辑。

### 商品表关联示例

以下用一条源商品说明从导入到建档的写入路径。示例 ID 和日期仅用于说明。

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

来源列表的 `createdDate` 使用本地来源 ID 的建档日证据，审计的 `batch_key` 为 `CREATED-DATE-YYYY-MM-DD`。同一 SKU 按日重新导入可补齐建档日。缺少证据时返回 null。

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
- `policy_round_platform` 冻结该轮正式勾选的平台。`policy_goods_detail` 按实际平台和所选商品展开，外键将每条明细绑定到本轮已勾选的平台及唯一品牌、系列。普通卡和指定卡均保存 `goods_id`，以 `(goods_id, spu_id, ram, rom)` 校验商品归属。补贴、数量及历史展示字段从同轮快照读取。
- 提交时后端从同一份已校验表单生成快照与明细，按版本更新申请并在同一事务中写入。每次业务写入递增 `record_version`，每次正式提交递增 `current_version`。审核按当前待审轮次条件更新。详细流程见 [状态机](../business/status-machine.md)。

本人未提交草稿允许物理删除 `policy_apply`，条件为 `DRAFT`、`current_version=0`、本人归属、`is_deleted=0` 和当前记录版本，并排除已有 `policy_audit_log`、`policy_goods_detail` 引用。删除与保存、提交采用同一主表行的条件写入。其他状态的申请及历史继续保留，磁盘附件独立处理。

#### 在 Navicat 核对逐段采用决定

从 `policy_apply.id` 找到申请，再按 `policy_audit_log.policy_id` 和 `version` 打开对应提交轮次，展开 `snapshot_data.consent`。正式提交成功时，后端把决定与本轮快照、商品明细一起写入同一事务。只在预览窗口勾选或点「返回核对」时，选择仍在页面中。

| 快照字段 | 可以核对的内容 |
| --- | --- |
| `comparedRounds` | 当时参与对照的旧申请 ID、提交轮次和审核日志 ID |
| `decisions[].scope` | 本段适用的平台，以及零售的具体商品或百亿的机型版本、RAM 和 ROM |
| `decisions[].from`、`through` | 本段开始与结束的北京时间，包含首尾秒 |
| `decisions[].groups`、`eligiblePolicyIds`、`selectedPolicyIds` | 该段的分组、候选申请 ID 和实际选中的申请 ID |

例如两单重叠，第一段只用 A，第二段 A、B 叠加，两段分别保存自己的起止和选中名单。无交叉且无随单调整的普通申请也保存规则版本和空的 `decisions`。生效查询重放已批准轮次时要求 `consent` 存在且规则版本为 2、3 或 4，遇到缺失或无法识别的版本会报错。[审核与生效查询](../api/admin-api.md#覆盖预览与有效政策)

机型补贴查询结合已通过轮次的快照及查询时已生效的改期流水，逐段计算实际采用、未采用和后续安排。再次查询时，左侧原单有效期读取原开始时间与最新获批的实际截止。右侧所选阶段取该段实际或计划执行的开始和结束时间。[机型补贴查询契约](../api/page-api-plan.md#查询补贴历史)

<a id="在-navicat-核对整单提前结束记录"></a>

#### 在 Navicat 按发起单或原单核对时间调整

获批原单的 `policy_apply.end_time` 始终是当前实际截止，首次截止从原获批 `policy_audit_log.snapshot_data.form.endTime` 读取。每次独立调整或随单终审生效时，在同一事务内条件更新原单截止和 `record_version`，追加一条 `policy_time_change`，再登记该记录唯一的调整群通知。原审批轮次和原有商品明细继续沿用。

| 流水字段 | 含义 |
| --- | --- |
| `id` | 本次调整的唯一编号，对应群通知中该次改期事件 |
| `policy_id, tenant_key, policy_version, audit_log_id, after_record_version` | 原政策、企业、获批轮次和审核行、完成本次变更后的主表版本 |
| `direction, previous_end, requested_end, actual_end` | 延期或提前结束、变更前实际截止、申请截止、变更后实际截止 |
| `changed_at, effective_at` | 办理时间与执行区间受影响的第一秒，均为北京时间。延期从原截止下一秒和办理时刻中的较晚者接续，提前结束从实际截止下一秒退出 |
| `actor_id, actor_name, reason, attachment_json` | 实际办理人、当时姓名、原因、可选证明 |
| `request_key, request_hash` | 独立提交按操作人、批次键和目标原单防重，重放先比对原请求摘要 |
| `source_policy_id, source_version` | 随单调整的发起单和轮次，该轮对同一原单仅可批准一次 |
| `consent_json` | 使用真实原政策 ID 保存本次新增时段的范围、候选和采用选择，对外仍显示原政策编号 |

连续办理时，同一 `policy_id` 后一条的 `previous_end` 应等于前一条的 `actual_end`，最新一条的 `actual_end` 与主表当前截止一致。随单待审、驳回时，发起轮次快照的 `consent.endAdjustments` 保留申请依据，采购终审通过才写目标原单的流水。在 Navicat 按原单的 `policy_id` 和 `after_record_version` 顺序查看改期，按 `source_policy_id, source_version` 可反查同一申请获批的全部随单调整。[业务接口](../api/policy-api.md#整单提前结束)

### 本轮完整性与并发

- 管理员在运营提交前，按品牌指定终审采购，按 OPPO 和一加各自的标准系列指定初审产品运营。管理员保存分工时锁定企业设置行并校验版本。运营提交时系统在同一事务读取已生效设置，按需要的节点登记其预设人员，冻结所用设置版本。所需负责人缺失、停用、自审或两关只能由同一人办理时，提交失败，表单可继续编辑。
- 节点写入按企业、申请、轮次核对最新状态与业务版本，同一轮的审核决定按统一顺序串行。每轮每个审核节点最多一条有效指派和一条成功决定，决定行的 `member_id` 必须关联本企业、本申请、本轮、本组的指派。产品运营可按实际签署人查自己的初审记录。
- 改长期分工从下一次首提或重提起生效。在途替换另记原因、前后人员及当次指派 ID。再次派给同一人使用新的 `assignment_no`，此前的签署、任务及回执仍可追溯。
- 相同事件的一条群消息用接收键 0，每个个人动作以实际指派 ID 去重。动作与本地业务在一个事务登记，外部投递在提交后进行。后台按状态、下次尝试时间、租约截止和 ID 领取登记，按业务唯一键及回执逐项对账。退回时可给原申请人建立独立的 REVISION 修改指派，个人投递仍关联该指派。这类修改任务没有审核签署权。并发与重试需在目标环境复核。

### 数据库与服务端校验

| 范围 | 数据库 | 服务端 |
| --- | --- | --- |
| 身份与历史 | 同租户账号外键、每单每轮唯一、明细关联本轮、外键限制删除 | 校验有效身份和权限，正常业务过滤主表逻辑删除，保护每轮快照与历史明细 |
| 平台与商品 | 每个平台最多一名指定提交人。正式明细的品牌、系列与本轮及机型一致，平台属于该轮。同轮同平台同商品唯一 | 校验运营在全部勾选平台的提交资格和账号状态；需有商品，核对在售、卡片和方案分配，拦截跨卡重复选品 |
| 数量和补贴 | 快照和草稿使用 JSON 对象 | 指定颜色按平台选填参考数量，留空保存为 NULL，填写 0 原样保存。补贴仅保存所选方式的参数，金额按 `DECIMAL(10,2)`、比例按 `DECIMAL(5,2)` 百分数的精度校验 |
| 日期和提交完整性 | 草稿和待审申请同时填写时间时，开始早于截止 | 获批政策按原单最新实际截止执行，含截止秒；调整记录保留每次生效依据 |

### 权限、历史与任务

登录时须识别停用记录并拒绝登录。运营编辑自己的草稿，共同负责成员通过最新正式轮次的平台交集查看政策。采购具备对应终审权限并获本轮有效指派才可办理，系统管理员签署时也要核对本轮指派、自审及两关分离约束。明细、日志和附件按父政策及具体轮次鉴权。商品中心的编辑、删除及字典维护须检查历史商品或机型引用。[权限和数据范围](role-platform-review-design.md)

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
| auth_version | INT UNSIGNED | 否 | 1 | 用户角色或启停变更时由服务端递增，供并发校验 |
| is_deleted | TINYINT | 否 | 0 | 0-未删除，1-已删除 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | 否 | CURRENT_TIMESTAMP |  |

用户管理将 `is_deleted=0/1` 映射为启用／停用。角色关系独立存放，账号与历史申请的 ID 保持稳定。现行单角色接口的实现及历史验收见[用户管理接口](../api/user-api.md)。

### sys_role、sys_user_role 与 sys_role_permission

| 表 | 字段与约束 |
| --- | --- |
| `sys_role` | 自增主键 `id BIGINT`、`tenant_key VARCHAR(64)`、`role_code VARCHAR(64)`、`role_name VARCHAR(64)`、`is_builtin TINYINT` 默认 0、`is_enabled TINYINT` 默认 1、`version INT UNSIGNED` 默认 1、创建和更新时间。角色编码在企业内唯一，`(id,tenant_key)` 供同企业外键引用。`ADMIN` 行必须是已启用的内置角色 |
| `sys_user_role` | `tenant_key,user_id,role_id` 为联合主键；用户和角色分别引用所属企业的记录。可反向按角色查人 |
| `sys_role_permission` | `tenant_key,role_id,permission_code VARCHAR(80)` 为联合主键，权限项由应用登记。服务端检查编码和角色版本，同角色的变更即时作用于持有人 |

四个内置岗位编码为 `ADMIN`（系统管理员）、`PURCHASE`（采购）、`OPERATIONS`（运营）、`PRODUCT_OPS`（产品运营）。系统管理员拥有全部功能，其他岗位按授权动作和具体数据范围办理。一人可兼岗。自我授权限制、最后一名有效管理员及岗位启停规则由应用事务校验。

### platform_team

主键 `(tenant_key,perspective,platform_code)` 标识企业内一个渠道平台。`version INT UNSIGNED` 初始为 0，用于整组分工并发校验。`updated_by BIGINT` 可空，关联最后修改的同企业账号，`updated_at DATETIME` 记录最后保存时间。零售与百亿分别分工。团队行可先于成员建立，支持在空平台上锁定首次分配。

### user_platform_assignment

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `tenant_key` | VARCHAR(64) | 本地账号所属飞书企业，区分大小写 |
| `perspective` | VARCHAR(16) | RETAIL 或 BILLION |
| `platform_code` | VARCHAR(32) | 与企业、渠道共同引用平台团队 |
| `user_id` | BIGINT | 当前成员的本地 ID，和 tenant_key 组成外键指向 user_info |
| `member_kind` | VARCHAR(16) | SUBMITTER 指定提交人或 MEMBER 共同成员 |
| `submitter_slot` | TINYINT 生成列 | SUBMITTER 取 1，普通成员为空 |

主键 `(tenant_key,perspective,platform_code,user_id)` 允许多人共同负责。团队加 `submitter_slot` 的唯一键阻止出现第二名提交人。团队是否具备一名启用且有资格的提交人，由整组分工与正式提交事务核对。同账号可以在零售和百亿分别负责同名平台。

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
| review_reason | VARCHAR(512) | 是 | NULL | 人工审核时由服务端按决定写入「审核通过」或「审核不通过」 |
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

对外申请单号 `policyCode` 统一由本表 `id` 格式化为 `ZC-` 加最少六位数字，例如 `ZC-000005`，超过六位时完整保留。现有 `policy_no` 及其唯一索引服务于首次创建请求重放。正式提交时将对外短单号写入该轮快照，历轮详情直接展示当轮保存的编号。

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 申请主键 |
| policy_no | VARCHAR(32) | 否 | — | 沿用的内部创建幂等标识；对外短单号由 id 生成 |
| extension_source_id | BIGINT | 是 | NULL | 早期测试单的来源引用；新改期统一记录在 policy_time_change，不创建延期单 |
| tenant_key | VARCHAR(64) | 否 | — | 会话确定的租户 |
| applicant_id | BIGINT | 否 | — | 同租户的本地账号 |
| applicant_name | VARCHAR(64) | 否 | — | 申请人姓名快照 |
| brand_id、series_id | BIGINT | 是 | NULL | 当前编辑表单的标准品牌、系列，两者须同时有值或同时为空。空值仅限草稿或退回编辑，非空时复合外键核对系列归属 |
| channel_type | VARCHAR(20) | 否 | — | RETAIL 或 BILLION |
| platforms | JSON | 是 | NULL | 整单平台编码数组 |
| write_off_type | VARCHAR(20) | 是 | NULL | SALES 或 ACTIVATION |
| scene_types | JSON | 是 | NULL | 零售实际选择的场景数组，1–3 个互不重复的 NATIONAL_SUB、LOCAL_SUB、SELF_SUB；空草稿及百亿使用 SQL NULL |
| is_estimated | TINYINT | 是 | NULL | 0 否、1 是，百亿为空 |
| start_time、end_time | DATETIME | 是 | NULL | 政策开始时间、当前实际截止，均为北京时间。原获批快照保留首次截止；执行查询按每次生效的改期与采用决定重放 |
| remark | TEXT | 是 | NULL | 特别备注，申请唯一备注字段 |
| goods_form_json | JSON | 是 | NULL | 可回填的商品卡、选品、联动及方案 |
| attachment_urls | JSON | 是 | NULL | 附件文件 ID 和外部链接列表 |
| status | VARCHAR(20) | 否 | DRAFT | DRAFT、PENDING、APPROVED、REJECTED |
| current_version | INT UNSIGNED | 否 | 0 | 最近提交轮次 |
| record_version | INT UNSIGNED | 否 | 1 | 并发编辑版本 |
| is_deleted | TINYINT | 否 | 0 | 0 正常、1 已删除 |
| created_at、updated_at | DATETIME | 否 | CURRENT_TIMESTAMP | 建单时间、更新时间 |

2026-11-15 已确认移除 `policy_apply.other_desc`，两份建表脚本均只保留 `remark`（特别备注）。当前轮次的提交快照只写 `specialRemark`。

### policy_audit_log

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 审核记录主键 |
| policy_id | BIGINT | 否 | — | 关联 policy_apply.id |
| tenant_key | VARCHAR(64) | 否 | — | 与申请及审核人相同 |
| version | INT UNSIGNED | 否 | — | 提交轮次，从 1 开始 |
| brand_id、series_id | BIGINT | 否 | — | 本轮冻结的唯一标准品牌与系列，复合外键引用系列及其所属品牌 |
| perspective | VARCHAR(16) | 否 | — | 本轮冻结的 RETAIL 或 BILLION 渠道，供该轮平台引用 |
| snapshot_data | JSON | 否 | — | 每轮正式提交的表单、商品、附件引用及本轮适用范围决定。随单调整申请写入 `$.consent.endAdjustments`，生效的改期与新增段采用决定另存时间调整流水。本轮采用的规则版本和逐段决定保存在 `$.consent`。由 Java 记录类 `PolicySnapshotData` 定义格式。[按轮快照读写](../api/policy-api.md#提交快照的存储与回读) |
| audit_status | VARCHAR(20) | 否 | SUBMITTED | SUBMITTED、APPROVED、REJECTED |
| auditor_id | BIGINT | 是 | NULL | 同租户的审核人账号 |
| auditor_name | VARCHAR(64) | 是 | NULL | 审核人姓名快照 |
| audit_comment | VARCHAR(512) | 是 | NULL | 本轮审核意见 |
| audit_time | DATETIME | 是 | NULL | 本轮审核时间 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 本轮提交时间 |

### policy_round_platform

字段为 `policy_id BIGINT`、`version INT UNSIGNED`、`tenant_key VARCHAR(64)`、`perspective VARCHAR(16)`、`platform_code VARCHAR(32)`，均必填。主键 `(policy_id,version,platform_code)`。一组复合外键关联正式轮次的企业和渠道，另一组引用对应的渠道平台团队。草稿没有本轮行。共同成员查询时按企业、渠道和平台反查正式轮次，并与申请的 `current_version` 匹配。退回编辑期间仍可查最近正式提交的内容。

### policy_time_change

每次改期使用自增 `id` 留痕，`(policy_id, after_record_version)` 唯一。同一独立批次通过企业、办理人、请求键和原单防重，随单获批通过发起单、发起轮次和原单防重。独立办理填写 `request_key, request_hash`，随单办理填写 `source_policy_id, source_version`。原单的获批轮次、办理前后的截止、执行生效时间、附件和选单依据见[时间调整流水字段](#在-navicat-按发起单或原单核对时间调整)。

### policy_goods_detail

| 字段 | 类型 | 可空 | 默认值/生成 | 说明 |
| --- | --- | --- | --- | --- |
| id | BIGINT | 否 | 自增 | 适用明细主键 |
| policy_id | BIGINT | 否 | — | 与 version 关联提交记录 |
| version | INT UNSIGNED | 否 | — | 对应提交轮次 |
| platform_code | VARCHAR(32) | 否 | — | 实际适用的平台编码 |
| brand_id、series_id | BIGINT | 否 | — | 同时关联本轮申报范围以及该条商品的机型归属 |
| spu_id | BIGINT | 否 | — | 标准机型及版本 |
| ram、rom | VARCHAR(20) ASCII | 否 | — | 配置容量，统一 GB |
| goods_id | BIGINT | 否 | — | 普通卡或指定卡实际选中的标准商品 |
| created_at | DATETIME | 否 | CURRENT_TIMESTAMP | 本轮明细写入时间 |

<a id="工作流七表字段"></a>

### 预设审核分工与工作流字段

以下时间均为北京时间 DATETIME，企业键 `tenant_key` 为 VARCHAR(64) 并与账号/申请具有相同的二进制排序规则。轮次外键沿用 `policy_id BIGINT` 和 `version INT UNSIGNED`。除标明可空的字段外，其余列均必填或由数据库生成。

| 表 | 字段、取值与默认值 | 核对用途 |
| --- | --- | --- |
| `policy_review_settings` | `tenant_key` 主键；`version INT` 默认 0；`updated_by BIGINT` 可空，关联同企业本地账号；`updated_at` 默认当前时间 | 未配置为版本 0，更新成功后版本递增 |
| `policy_review_brand_owner` | `(tenant_key,brand_id)` 主键，`purchase_user_id BIGINT` 必填，`product_mode VARCHAR(16)` 取 BY_BRAND 或 BY_SERIES，`brand_product_user_id BIGINT` 按模式可空 | 同一企业每个品牌提前指定一名采购。按品牌初审必须填产品运营，按系列初审该字段必须为空。配置关联标准品牌、企业设置与同企业账号 |
| `policy_review_series_owner` | `(tenant_key,series_id)` 主键，`brand_id BIGINT,product_user_id BIGINT` 必填 | 外键同时核对标准系列的所属品牌、已配置品牌以及同企业人员。确认品牌采用 BY_SERIES 后，管理员为每个系列预设一名初审产品运营 |
| `policy_review_round` | `policy_id,version` 主键；`tenant_key`；`settings_version INT` 须大于 0；`stage VARCHAR(24)`；`route_json JSON` 对象；`created_at` 默认当前时间 | 阶段为 PRODUCT_REVIEW、PURCHASE_REVIEW 或 FINISHED。整单路由、样本轮次、触发段和展示依据从冻结的 JSON 读取 |
| `policy_review_member` | `id BIGINT` 自增，`policy_id,version,tenant_key,group_code,user_id` 标识指派，`display_name VARCHAR(64)`、可空的 `department VARCHAR(128)` 保存人员快照。`assignment_no INT` 默认 1，`member_status VARCHAR(16)` 默认 ACTIVE，`task_guid VARCHAR(128)` 可空，`active_user_id BIGINT`、`active_review_slot TINYINT` 为生成列 | PRODUCT 和 PURCHASE 节点仅 ACTIVE 行的 `active_review_slot` 为 1，轮次、组、该列的唯一键确保最多一名当前审核人，保留已办理及移除记录。REVISION 供原提交人修改。飞书待办 GUID 非空时在企业内唯一 |
| `policy_review_decision` | `policy_id,version,group_code` 主键；`tenant_key,member_id BIGINT`；`decision VARCHAR(16)` 为 APPROVE 或 REJECT；`reason VARCHAR(512)` 可空，产品运营退回时必填；`decided_at` 默认当前时间 | 保存该节点实际决定人的一次操作，按指派查询姓名部门及节点 |
| `policy_review_reassignment` | `id BIGINT` 自增；`policy_id,version,tenant_key,group_code`；`operator_id BIGINT` 为同企业操作人；`reason VARCHAR(512)` 须有内容；`before_members,after_members JSON` 数组保存改派前后指派 ID 及人员 ID；`created_at` 默认当前时间 | 保留原结构，当前流程不读写 |
| `policy_review_delivery` | `id BIGINT` 自增；`tenant_key,policy_id,version`；`event_key VARCHAR(128)`；`action_type VARCHAR(16)` 为 GROUP_MESSAGE、SEND_CARD、UPDATE_CARD、CREATE_TASK 或 CLOSE_TASK；`recipient_key BIGINT` 为 0 或指派 ID，`recipient_member_id` 自动映射个人指派；`payload_json JSON` 对象；`created_at` 默认当前时间 | 轮次内事件、动作和接收键联合唯一。独立业务提醒的 policy_id 与 version 同时为空，库内 action_type 为 GROUP_MESSAGE、recipient_key 为 0，按企业和生成列 notice_key 去重。首次发送目标、请求摘要指纹及尝试信息保存在本条登记的 JSON 中。 |
| 同一投递记录的结果 | `delivery_status VARCHAR(24)` 默认 PENDING，可取 SENDING、SENT、RETRY、UNKNOWN、FAILED、CANCELLED；`attempts INT` 默认 0；`next_attempt_at,lease_until,delivered_at`、`lease_owner VARCHAR(128)`、`receipt_id VARCHAR(128)`、`last_error VARCHAR(512)` 均可空 | 保存派发进度、限时领取者、重试时间、外部回执与脱敏错误。结果未知时优先核对本动作 |

### 群消息投递编号与飞书回执

一条群通知对应一行 `policy_review_delivery`。后台使用的 `deliveryKey` 是根据该行生成的 UUID，表中没有单独的 UUID 列。后端依次用 `/` 拼接 `tenant_key/policy_id/version/event_key/action_type/recipient_key`，将结果编码为 UTF-8，调用 `UUID.nameUUIDFromBytes`。独立提醒的 `policy_id`、`version` 在库中均为 NULL，生成编号时两者均取 `0`。同一投递重试时复用该编号作为飞书发消息请求的 `uuid`。[编号生成代码](../../backend/src/main/java/com/company/policy/service/PolicyFeishuSender.java)

单卡验证命令需要原投递编号时，可在本机运行以下 Python 片段。输入上述六个字段用 `/` 拼成的字符串（从 Navicat 已核对的实际登记行取值）。

```python
import hashlib
import uuid

key = input("企业/申请ID/轮次/事件/动作/接收键：").strip()
print(uuid.UUID(bytes=hashlib.md5(key.encode("utf-8")).digest(), version=3))
```

| Navicat 中的字段 | 用途 |
| --- | --- |
| `id` | 本地投递记录的自增主键。 |
| `event_key` | 触发投递的业务事件。审核类示例为 `SUBMITTED`，平台交接以 `PL/` 开头。 |
| `notice_key` | 独立提醒上等于 `event_key`，与 `tenant_key` 组合对登记去重。按审核轮次登记的行此列为 NULL。 |
| `receipt_id` | 确认送达时保存的飞书回执。群消息通常为 `om_…`，个人待办为任务 GUID。 |

在 Navicat 中按企业和业务事件定位投递，再对照 `delivery_status`、`delivered_at`、`receipt_id` 与飞书回执。常见的核对方式有三种。

- 平台交接按 `event_key` 前缀查找。例如零售快手以 `PL/RETAIL/KUAISHOU/` 开头，分工的变动保存在 `payload_json`。
- 终审通过的群公告按 `policy_id`、`version`、`event_key = 'PURCHASE_DECIDED'` 及 `action_type = 'GROUP_MESSAGE'` 查找。表单来自该轮 `policy_audit_log.snapshot_data`，叠加名单来自同轮 `policy_review_round.route_json`。29 表方案要求终审批准时将该轮平台当时的有效成员与原提交人按本地用户 ID 去重，冻结在公告的 `payload_json`，重试复用同一名单。采购提醒关联采购指派 ID。
- 原申请人的临期提醒按 `P/{policyId}/EXPIRY_REMINDER/{reminderDate}` 查找，日期为北京时间的提醒日。`policy_id`、`version` 在这类独立提醒的投递行上均为 NULL，按企业和 `notice_key` 去重。`payload_json.kind` 为 `EXPIRY_REMINDER`，管理员页面显示实际私发动作 `SEND_CARD`。[时间与收件规则](../architecture/policy-workflow-phase-f.md#原申请人的临期私聊)

## 5. 验证范围

本次交付为 29 表完整 DDL。目标 MySQL 的执行结果由用户在 Navicat 核对。下列场景供建库和后续应用适配验收使用。

- **授权与平台。** 同渠道平台可有多个 MEMBER，仅允许一条 SUBMITTER。跨企业用户角色关联、同一人重复加入同一团队均应被约束拒绝。登记平台后，分别核对成员查看最新正式轮次、共同成员无法提交及指定提交人可提交。
- **单系列与预设指派。** 为同企业品牌配置一名采购，并预设需要的初审负责人。本轮正式明细写入其他系列商品、同轮同组增加第二名有效审核人时，数据库应拒绝。预设缺人时，提交失败并保留可编辑表单。

- **提交与审核。** 在联调环境并发提交同一草稿或办理同一待审节点，核对只有一个新增轮次或一个有效决定。对照该轮快照、商品明细、冻结的指派及投递记录。可用决定行的 `member_id` 核对实际签署人。
- **商品引用。** 并发提交政策与编辑规格或删除同一商品。若提交先成功，后续目录操作应保护实际引用。若目录先变更，提交须重新校验选品。
- **改期与投递。** 对照原单的最新截止、流水及发起轮次，检查对应群消息和原申请人的两次临期私聊。每个已送达事件应有对应回执，并发领取不得让两名执行者同时取得同一条有效租约。查询计划按实际数据量和筛选条件核对。[试点与恢复记录](../architecture/policy-workflow-phase-f.md)
