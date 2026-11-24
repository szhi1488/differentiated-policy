# 数据库设计与字段字典

更新日期 2026-11-24。二十二表空库基线适用于 MySQL 8.0.16+，包含用户头像字段 `avatar_url` 和商品政策名 `policy_name`。公司所有有效管理员共用源商品、清洗结果和标准目录。后端须在建立会话前校验公司飞书租户，并在请求时校验有效身份及权限。政策三表已纳入空库脚本。申请人政策接口和商品引用检查已使用政策三表，目标 MySQL 待联调。2026-11-17 用户管理五个接口直接复用本基线，没有新增字段、表或迁移。

## 已有库升级飞书审核卡投递

2026-11-24 用户确认仅扩展 `policy_review_delivery.ck_review_delivery_recipient`。发卡和更新卡分别按个人指派保存回执。继续使用二十二张表及原有列、外键、唯一键和调度索引。

1. 已完成 B 阶段升级的数据库，在 Navicat 选中目标 MySQL 8.0.16+ 项目库，停止各实例写入并备份。执行[审核卡投递增量脚本](upgrade_policy_review_delivery_cards.sql)的第一段，核对库名及现有约束。
2. 如个人动作目前仅允许 `CREATE_TASK`、`CLOSE_TASK`，执行第二段替换此约束。已包含 `SEND_CARD`、`UPDATE_CARD` 的库直接核对第三段。首次新建空库可使用本版完整建表脚本，已有十五表库按下节升级会直接取得新约束。
3. 在同一连接核对前后投递记录数相等、约束 `ENFORCED=YES`，群动作为 0、四种个人动作均要求正指派 ID。结果符合后部署配套后端。已有外部回执的记录需保留至补偿结束。

## 已有库升级政策审核工作流

本次升级保留既有十五表，给每份审核日志增加工作流标记，另建七表保存企业名单、每轮指派、节点决定、改派和待投递动作。新空库直接执行下面的完整建表步骤。

1. 在 Navicat 选中已完成延期、平台分工和零售多场景升级的项目库，确认 MySQL 8.0.16 及以上。停止所有后端实例和后台写入。备份整个数据库及 `POLICY_STORAGE_ROOT` 所指附件目录（含文件和元数据），记录所用版本，核实能还原。保留十五表空库与已有库两条路径。
2. 已有库使用 [upgrade_policy_review_workflow.sql](upgrade_policy_review_workflow.sql)。先在同一查询窗口执行第 1 段，保存库名、版本、相关列和表清单、三项升级前行数。首次升级应查不到七张工作流表及标记列。临时底稿按主键记录申请、审核和商品行的 SHA-256，包括快照内的逐段选择、附件引用和调整证明。临时表的磁盘空间与既有记录数有关。
3. 在同一窗口顺序执行第 2 至 5 段，遇错停止。加列时已有轮次取得 0。七表创建成功后，数据库将该列的默认值切到 1。第 5 段的 `changed_or_missing_*`、`added_*` 六项以及 `wrongly_marked_legacy_rounds` 均应为 0。将这些数值与升级前行数一起保存。
4. 在同一库运行 [verify_policy_review_workflow.sql](verify_policy_review_workflow.sql)。回传七张表的名单、标记列类型/可空/默认值（`tinyint/NO/1`）、版本及状态分布、七表计数与五项一致性核对结果。刚升级时七表均为空，已有轮次全部为 0。五项一致性差异均为 0。结构查询可核对节点唯一主键、个人任务指派唯一键、投递业务唯一键及外键，执行计划留到有代表性数据时复核。
5. 部署配套服务并保存产品运营与采购名单。普通申请首提和重提都按轮冻结指派和分流依据。办理人须具备当前节点的有效指派及岗位资格。随父单终审一次获批的延期子单继承批准结果，无需单独指派或再次终审。
6. 核对一笔待审旧单、一笔有驳回重提的旧单及有调整证明的轮次。逐项核对对外单号、当前版本、原申请人、各轮决定与审核时间、延期来源、重叠段的原/拟名单，下载原附件、独立调整及随单调整证明；已有引用的附件仍受删除保护。后续阶段检查旧链接只读原轮、采购直审和初审不能越级、全局名单不改历史指派以及历史通知不会在切换时重复投递。

**中断与恢复**　MySQL DDL 会隐式提交。若仍在原连接，保留临时底稿，核对 `SHOW CREATE TABLE` 及标记列默认值，从第一条未完成语句继续。不要把已存在的轮重新设为 0。若原连接已关闭，使用升级前备份在独立核对库还原第 1 段底稿或恢复至升级前一致版本，再重新执行。新连接不能用已变更的库自行生成「升级前」底稿。已有同名却不同结构的对象时停止，保留错误与结构输出。业务回退须停写同时恢复相配套的数据库、附件备份及服务端。已有新轮或已投递动作时先对账，恢复备份可能丢失备份后写入，已送达的外部任务也不会随数据库回退。

### 本轮完整性与并发

- 名单设置以企业为单位，锁定设置行后按用户 ID 升序锁申请人、设置操作人及所选账号（取本动作所需的人员），以配置版本检查并发保存。提交在同一事务读取该版本和所选有效成员，再按政策已有的平台/商品/机型锁定顺序复核交叉依据。整单快照、工作流轮、成员及待投递事件共进退。
- 节点写入按企业和申请、轮次核对最新状态与业务版本，锁定同一轮和本组成员后检查资格。并发办理及改派必须在相同顺序串行化。每轮每组至多一条决定，其 `member_id` 只能关联本企业、本申请、本轮、本组的指派。其余成员可标记「同组他人已处理」，实际签署人以决定行为准。
- 成员再次加入同一组时使用新的 `assignment_no` 与指派 ID，旧行保留以对照早期任务及回执。生成列保证同人同组最多一条有效指派。设置版本号和成员姓名部门一起冻结；默认名单修改后可从轮成员和改派前后清单恢复当时的人选。服务端检查两关实际办理人不同，改派必须校验现行资格、记录原因。
- 相同事件的一条群消息用接收键 0，每个个人动作以实际指派 ID 去重。动作与本地业务在一个事务登记，外部投递在提交后进行。后续投递按状态、下次尝试、租约截止和 ID 领取，按业务唯一键及回执逐项对账。结构升级本身不生成投递记录。退回时可给原申请人建立独立的 REVISION 修改指派，个人投递仍关联该指派。这类修改任务没有审核签署权。并发与重试需在后续目标环境复核。

## 已有库升级政策延期

已有项目库停写并备份后，由用户在 Navicat 选中目标库，一次执行[政策延期升级脚本](upgrade_policy_extensions.sql)。前提为 MySQL 8.0.16+，已有 `policy_apply` 及其 `(id, tenant_key)` 唯一索引，尚无 `extension_source_id`。脚本用一次 ALTER 增加可空的原单 ID、同租户自引用外键和唯一索引。普通申请及独立缩短申请该字段为 NULL。独立或随单生成的延期单在此记录本人已通过的来源政策。时间调整使用现有表单和快照 JSON，已具备该列、索引和外键的库无需为原因、证明或批量延期增设字段。创建延期时使用原单实际截止的下一秒。

核对 `policies_before = policies_after`、`extensions_after = 0`，建表结果应含 `uk_apply_extension_source` 和 `fk_apply_extension_source`。核对后启动新版服务。空库直接使用当前完整基线。延期草稿可按原草稿删除规则删除；延期已通过后可从新单继续延长。

2026-11-23 的 Navicat 回显显示延期字段、唯一索引和外键所在的 `ALTER TABLE` 已执行成功。验收查询的回显只有 `OK`，仍需看结果网格中的行数与 `invalid_extensions`（预期为 0）。已完成结构升级的库可单独运行脚本末尾的核对查询。

## 清空政策提交后重新验证

[清空政策提交脚本](clear_policy_submissions.sql) 用于已完成延期升级、需要从头验证提交的库。它清空所选库内**所有申请人**的草稿、延期单、待审单、已通过及已驳回的申请，也清空各轮补贴决定、审核记录和政策商品明细。在工作流库中也清理逐轮成员、节点决定、改派和投递。企业默认审核名单、账号、角色、平台分工、商品目录和磁盘附件保留。

1. 停止后端写入并备份目标库。在 Navicat 同一查询窗口按段执行，先核对库名、ready_to_clear=1、政策三表及适用的工作流记录数。
2. 运行脚本的事务段，查看结果网格中的 `applications_left`、`audit_rounds_left` 和 `goods_details_left`。三项及工作流记录余量均为 0 且确认清空时，在原窗口单独执行 `COMMIT;`。提交前要取消时，在原窗口执行 `ROLLBACK;`。脚本末尾的提交和回滚命令都已注释，需要手动选择。
3. 提交后，在同一库的新查询窗口复查适用的全部余量，并刷新已打开的表格页，再恢复写入。此次执行回显为删除 20 条商品明细、6 条审核记录、7 条申请，解除延期引用影响 0 行。该回显只记录事务内执行的行数，最终以提交后的复查为准。

## 已有十四表库升级步骤

2026-11-19 的两项增量适用于已有原始 SKU 一对一基线的十四表库。由用户停止后端写入、备份目标库，在 Navicat 核对库名和当前结构。两项都待升级时，先回填商品名称与政策名，再升级自动排除约束。已完成的步骤通过脚本的核对查询确认。两个差异数均为 0、两处约束均核对通过后启动新版后端。需要整体回退时，在停写期间恢复升级前备份并切回旧版。

### 已有十四表库升级商品名称与政策名

在 Navicat 按段执行[商品名称与政策名增量脚本](upgrade_goods_source_names.sql)。旧结构应为 `goods_name VARCHAR(384)` 且无 `policy_name`。先确认已关联来源的缺失原名清单为空。若有结果，先在吉客云补全原名并重新导入，再停写复核。

脚本将 `goods_name` 扩至 512 字符，新增可空的 `policy_name`（1024 字符），按 `source_goods_item.goods_id = goods.id` 将已关联商品的两项值覆盖为当前来源原文。手工商品及历轮政策快照保留原值。DDL 单独提交，已成功改表、回填中断时，从第 3 段继续。末尾按字节核对 `goods_name_differences` 与 `policy_name_differences`，预期均为 0，尾部空格及 NULL 与空串的差异也计入。

### 已有十四表库升级清洗自动排除

在 Navicat 按段执行[自动排除约束增量脚本](upgrade_catalog_direct_exclusions.sql)。核对 `source_goods_item.ck_source_clean_excluded` 允许记录无人工审核人的规则排除，`catalog_clean_job_item.ck_clean_item_outcome` 允许 `EXCLUDED`，且两项约束均已启用。两条 ALTER 分别提交，中断后核对已完成项并继续。升级保留既往任务结果。新版后端运行清洗后，可用脚本末尾查询核对该任务的自动排除数。

### 已有库升级零售场景多选

1. 使用 MySQL 8.0.16 或以上。停止后端写入并备份，确认所选项目库已有政策主表和审核日志，在 Navicat 同一连接整份运行[零售场景多选升级脚本](upgrade_policy_scene_types.sql)。新建空库直接使用当前基线。
2. 脚本添加 `policy_apply.scene_types`，按行将旧值转成一个元素的 JSON 数组；旧空值保留 SQL NULL。迁移包含逻辑删除行，并显式保留原更新时间。场景检查限制渠道、数组类型、允许的编码与唯一性。逐行对照通过后才移除旧列。历史提交快照原文、政策轮次及商品明细继续保留。
3. 在结果集中找到 `upgrade_result = 完成`。首次升级 `scene_differences = 0`，`billion_scene_errors = 0`，政策和快照各自的前后行数相等。已完成的库重跑时，旧列已移除，`scene_differences` 为 NULL。末尾可查看新旧快照字段及管理员场景筛选的执行计划。中断后保持停写，核对错误再重跑；出现非零差异时保留旧列，先核对数据。需要整体回退时在停写期间恢复备份及配套应用版本。
4. 确认结果后启动配套前后端。若库中已有迁移过的草稿，用该草稿核对转换后的单元素场景数组，再保存一份「国补、地补」申请并重开。正式提交后，管理员分别按国补、地补均可找到该单，自补筛选不命中。驳回后修改场景仅影响草稿，重提后管理员看到新组合，首轮快照仍显示原组合。两个页面同时编辑同一草稿，后保存的旧版本应返回 409。

本次结构变更用于保存零售的实际选择，范围为主表场景列及约束。「全部」保存三个场景编码；百亿的场景为 SQL NULL。逐段决定复用审核日志原有的 `snapshot_data`，旧轮次保留原始 JSON。确认页合并同配置颜色只影响展示，零售商品明细仍按本轮实际选中的平台和商品 ID 保存。[按轮快照回读](../api/policy-api.md#提交快照的存储与回读)

### 已有库升级申请人平台分工

已有项目库须有 `user_info` 表和 `uk_user_id_tenant` 索引。停止后端写入并备份后，在 Navicat 选中项目库，整份运行同一个[平台分工升级脚本](upgrade_user_platform_assignments.sql)。脚本自动识别下列三种情况。

| 执行前的分工表状态 | 脚本处理 |
| --- | --- |
| 尚无 `user_platform_assignment` | 按十五表基线创建空表，由管理员首次分配 |
| 已有分工表，仍有旧唯一索引 `uk_owner_platform` | 核对组合主键和旧索引定义，仅移除跨渠道限制，保留原有分工 |
| 分工表已是新结构 | 核对现状和行数，可重复执行 |

最后一行 `upgrade_result` 预期为「完成」，`assignments_before` 和 `assignments_after` 相同。首次建表时两者均为 0，原来已有分工时两者为已有记录数。若提示核对结构，请查看脚本输出的 `SHOW CREATE TABLE`。完成后启动新版前后端，在「系统管理 → 用户管理 → 负责平台」配置各渠道的平台负责人。

## 1. 在 Navicat 建表

项目未配置 Flyway 或自动建表。新空库由用户在 Navicat 执行以下第 1、3、4 步，最新基线已包含商品清洗、平台分工、政策延期、零售场景及审核工作流历次增量。第 2 步仅用于已明确要清空数据的重建。已有十四表库保留数据的升级见上节。

1. 停止项目后端，在 Navicat 选中目标库 `differentiated policy`。
2. 整份执行 [drop_all_tables.sql](drop_all_tables.sql)，刷新表列表。该脚本按外键顺序删除下列二十二张项目表，用户、角色、商品、任务、政策及审核历史随表永久删除。数据库本身和磁盘附件保留。已是空库可直接从下一步开始。
3. 在同一库整份执行 [schema.sql](schema.sql) 或 [V1__create_policy_tables.sql](V1__create_policy_tables.sql)，任选一份。两份内容相同。执行成功后刷新表列表，应出现下列二十二张空表。若建表中途失败，先查看 Navicat 报错，修正原因后从第 2 步重新开始。
4. 重启后端，用飞书重新登录，生成默认的申请人账号。在 Navicat 核对该账号并将其 `user_info.role_type` 设为 `ADMIN`，重新进入管理员工作台，从吉客云导入商品并发起清洗。

## 2. 二十二表清单和约束

| 表 | 职责 | 关键关联 |
| --- | --- | --- |
| user_info | 飞书身份、本地角色 | tenant_key + open_id 唯一；停用身份仍占唯一键 |
| user_platform_assignment | 当前申请人分工 | (tenant_key, perspective, platform_code) 主键，每个渠道平台至多一位负责人，零售和百亿的同名平台独立分配 |
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
| policy_review_settings | 企业名单版本及修改者 | 一企业一行 |
| policy_review_setting_member | 产品运营、采购两组名单 | 同企业同组按用户去重 |
| policy_review_round | 本轮阶段与路由快照 | 关联当轮审核及同企业申请 |
| policy_review_member | 节点成员及个人任务 | 锁定人员和当次指派，保留改派与结办 |
| policy_review_decision | 节点实际决定 | 每单每轮每组最多一条 |
| policy_review_reassignment | 在途改派轨迹 | 保存同组变更前后指派及操作人 |
| policy_review_delivery | 审核投递及执行业务群提醒 | 审核按轮次、事件、动作和收件方去重，独立提醒按企业与 notice_key 去重 |

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

以下用一条源商品说明十五表基线中的写入路径。示例 ID 和日期仅用于说明。

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
- `policy_goods_detail` 按实际平台和所选商品展开，绑定 `(policy_id, version)`。普通卡和指定卡均须保存 `goods_id`，以 `(goods_id, spu_id, ram, rom)` 校验商品归属。新增或未勾选的商品不进入本轮。明细供商品筛选、覆盖候选和商品引用检查使用。补贴、数量及历史展示字段从同轮快照读取。
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

审核通过后，机型补贴查询读取已通过的轮次及其快照。时间轴的实际采用、未采用和后续安排在查询时逐段计算。服务重启后可据此还原结果，后续有新单获批时再查会反映新安排。[机型补贴查询契约](../api/page-api-plan.md#查询补贴历史)

<a id="在-navicat-核对整单提前结束记录"></a>

#### 在 Navicat 按发起单或原单核对时间调整

独立时间调整草稿保存在 `policy_apply.goods_form_json` 的 `$.timeAdjustment`，正式提交时冻结于本轮 `policy_audit_log.snapshot_data` 的 `$.form.goodsForm.timeAdjustment`。该对象记录本次核对的旧单和所提的新截止、原因、文件。独立缩短还会由服务端将这一项写入 `$.consent.endAdjustments`，审核后供原政策计算实际截止。该缩短申请不新增 `policy_goods_detail` 商品明细。独立延期通过 `extension_source_id` 指向原单，延期单有自己的明细。

随单调整的每个目标占发起申请本轮 `$.consent.endAdjustments` 的一项。点「确认调整」只重算预览，正式提交后才有审核记录。例如 `ZC-000020` 同时缩短 `ZC-000017`、延长 `ZC-000016`，在本公司筛选 `policy_audit_log.policy_id = 20`，按 `version` 打开对应快照，可在数组里找到 `target.policyId` 为 `"17"`、`"16"` 的两项。逐段选择的政策名单另在 `decisions`。[逐段决定的快照字段](#在-navicat-核对逐段采用决定)

| 调整项字段 | 可核对的内容 |
| --- | --- |
| `target.policyId`、`target.auditLogId` | 目标旧单的 `policy_apply.id` 及其获批轮次的 `policy_audit_log.id` |
| `target.previousEnd` | 申请调整时核对的旧单实际截止秒，包括此前已批准的缩短 |
| `endTime`、`reason` | 新截止及必填原因。`endTime` 早于 `previousEnd` 表示缩短，晚于表示延长，两者均使用 `+08:00`、精确到秒 |
| `attachmentInfo` | 本项目标的上传文件 ID 和服务端文件名。可省略，未保存调整附件的旧轮次按无文件读取 |

每个调整项的审核结果取发起单对应轮次的 `policy_audit_log`。`SUBMITTED` 为整批待审，`REJECTED` 为整批驳回，两个状态下调整均留在快照，原政策继续按此前的截止执行。该轮 `APPROVED` 后，缩短作用于目标旧单全部平台和商品。若批准晚于所填截止，旧单保留到批准前一秒。多次获批缩短取最早的实际截止。随单延期在同一审批事务为每张原单生成真实的已通过子单。子单通过 `extension_source_id` 关联原单，`$.consent.batchPolicyKey` 保存预览及逐段决定中该延期的标识，可与发起单该轮对照。子单承接原实际截止的下一秒，原单主表和原获批快照仍保留原值。[审批与生效规则](../api/policy-api.md#整单提前结束)

只读查询的 `tenant_key` 填本企业，`request_id` 和 `target_id` 分别按发起单和原单筛选。比如按发起单查填 `20`、`NULL`，按旧单反查填 `NULL`、`17`。`adjustment_direction` 为 `SHORTEN` 或 `EXTEND`。仅已生效的批准缩短显示本轮的 `approved_through`，延期的 `approved_through` 为空。每个发起单、每个提交轮次、每个目标各列一次，批量获批生成的子单不再重复计入。早期独立延期若未保存 `timeAdjustment`，可通过 `extension_source_id` 查看原单及延期单的起止，查询不推算其原因。[时间调整只读查询](query_policy_end_adjustments.sql)

### 数据库与服务端校验

| 范围 | 数据库 | 服务端 |
| --- | --- | --- |
| 身份与历史 | 同租户账号外键、每单每轮唯一、明细关联本轮、外键限制删除 | 校验有效身份和权限，正常业务过滤主表逻辑删除，保护每轮快照与历史明细 |
| 平台与颜色 | 正式明细平台编码、商品复合外键、同轮同平台同商品唯一 | 校验渠道与整单平台、卡片与方案分配、选中商品在售、同平台跨卡重复选中同一商品 |
| 数量和补贴 | 快照和草稿使用 JSON 对象 | 指定颜色按平台选填参考数量，留空保存为 NULL，填写 0 原样保存。补贴仅保存所选方式的参数，金额按 `DECIMAL(10,2)`、比例按 `DECIMAL(5,2)` 百分数的精度校验 |
| 日期和提交完整性 | 同时填写时校验开始时刻早于表单结束时刻 | 草稿保留未填项，提交完整校验。时间为北京时间，精确到秒。普通原单原定有效至快照截止日 23:59:59，延期单按所选截止秒，获批的整单提前结束进一步限定旧单截止 |

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

### user_platform_assignment

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `tenant_key` | VARCHAR(64) | 本地账号所属飞书企业，区分大小写 |
| `perspective` | VARCHAR(16) | RETAIL 或 BILLION |
| `platform_code` | VARCHAR(32) | 渠道可用的平台编码，取值由检查约束限定 |
| `user_id` | BIGINT | 当前负责人的本地 ID，和 tenant_key 组成外键指向 user_info |

每条记录表示一个当前负责人，未分配的渠道平台没有记录。主键限定同一企业内每个渠道平台至多一人负责。同一账号可在两个渠道分别持有同名平台。草稿与审核快照保存各自的申请人和平台。

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
| extension_source_id | BIGINT | 是 | NULL | 延期所承接的已通过政策 ID，按租户关联原单。每张原单最多一张直接延期单 |
| tenant_key | VARCHAR(64) | 否 | — | 会话确定的租户 |
| applicant_id | BIGINT | 否 | — | 同租户的本地账号 |
| applicant_name | VARCHAR(64) | 否 | — | 申请人姓名快照 |
| channel_type | VARCHAR(20) | 否 | — | RETAIL 或 BILLION |
| platforms | JSON | 是 | NULL | 整单平台编码数组 |
| write_off_type | VARCHAR(20) | 是 | NULL | SALES 或 ACTIVATION |
| scene_types | JSON | 是 | NULL | 零售实际选择的场景数组，1–3 个互不重复的 NATIONAL_SUB、LOCAL_SUB、SELF_SUB；空草稿及百亿使用 SQL NULL |
| is_estimated | TINYINT | 是 | NULL | 0 否、1 是，百亿为空 |
| start_time、end_time | DATETIME | 是 | NULL | 表单开始时间、截止时间，均为北京时间。实际有效期按获批快照与后续获批的整单提前结束决定计算 |
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
| snapshot_data | JSON | 否 | — | 每轮正式提交的表单、商品、附件引用及本轮适用范围决定。随单时间调整和独立缩短写入 `$.consent.endAdjustments`，独立调整保存在 `$.form.goodsForm.timeAdjustment`。本轮采用的规则版本和逐段决定保存在 `$.consent`。由 Java 记录类 `PolicySnapshotData` 定义格式。[按轮快照读写](../api/policy-api.md#提交快照的存储与回读) |
| workflow_version | TINYINT | 否 | 1 | 普通提交轮次写 1，随父单终审一并批准生成的延期子单写 0；采购授权核对实际阶段、指派与本轮决定 |
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

### 工作流七表字段

以下时间均为北京时间 DATETIME，企业键 `tenant_key` 为 VARCHAR(64) 并与账号/申请具有相同的二进制排序规则。轮次外键沿用 `policy_id BIGINT` 和 `version INT UNSIGNED`。除标明可空的字段外，其余列均必填或由数据库生成。

| 表 | 字段、取值与默认值 | 核对用途 |
| --- | --- | --- |
| `policy_review_settings` | `tenant_key` 主键；`version INT` 默认 0；`updated_by BIGINT` 可空，关联同企业本地账号；`updated_at` 默认当前时间 | 未配置为版本 0，更新成功后版本递增 |
| `policy_review_setting_member` | `tenant_key,group_code VARCHAR(16),user_id BIGINT` 联合主键；组为 PRODUCT 或 PURCHASE，人员关联同企业账号 | 同组同人只保存一次。采购资格、启停及禁止自审由服务端核验 |
| `policy_review_round` | `policy_id,version` 主键；`tenant_key`；`settings_version INT` 须大于 0；`stage VARCHAR(24)`；`route_json JSON` 对象；`created_at` 默认当前时间 | 阶段为 PRODUCT_REVIEW、PURCHASE_REVIEW 或 FINISHED。整单路由、样本轮次、触发段和展示依据从冻结的 JSON 读取 |
| `policy_review_member` | `id BIGINT` 自增；`policy_id,version,tenant_key,group_code,user_id`；`display_name VARCHAR(64)`、`department VARCHAR(128)`（可空）为指派时快照；`assignment_no INT` 默认 1；`member_status VARCHAR(16)` 默认 ACTIVE；`task_guid VARCHAR(128)` 可空；`active_user_id BIGINT` 生成 | 组为 PRODUCT 或 PURCHASE；REVISION 留作后续退回时给原申请人的修改指派。设置与节点决定只接受前两组。ACTIVE、REMOVED、HANDLED、PEER_HANDLED 分别为有效、移除、本人办理、同组他人处理。未送达可无 GUID，非空 GUID 在企业内唯一 |
| `policy_review_decision` | `policy_id,version,group_code` 主键；`tenant_key,member_id BIGINT`；`decision VARCHAR(16)` 为 APPROVE 或 REJECT；`reason VARCHAR(512)` 可空，产品运营退回时必填；`decided_at` 默认当前时间 | 保存该节点实际决定人的一次操作，按指派查询姓名部门及节点 |
| `policy_review_reassignment` | `id BIGINT` 自增；`policy_id,version,tenant_key,group_code`；`operator_id BIGINT` 为同企业操作人；`reason VARCHAR(512)` 须有内容；`before_members,after_members JSON` 数组保存改派前后指派 ID 及人员 ID；`created_at` 默认当前时间 | 按同轮、同组及记录 ID 回看谁何时增减了哪些人 |
| `policy_review_delivery` | `id BIGINT` 自增；`tenant_key,policy_id,version`；`event_key VARCHAR(128)`；`action_type VARCHAR(16)` 为 GROUP_MESSAGE、SEND_CARD、UPDATE_CARD、CREATE_TASK 或 CLOSE_TASK；`recipient_key BIGINT` 为 0 或指派 ID，`recipient_member_id` 自动映射个人指派；`payload_json JSON` 对象；`created_at` 默认当前时间 | 轮次内事件、动作和接收键联合唯一。独立业务群提醒的 policy_id 与 version 同时为空，生成列 notice_key 与企业联合唯一。请求收件人、摘要指纹及人工处理人、依据、时间保存在本条登记的 JSON 中。 |
| 同一投递记录的结果 | `delivery_status VARCHAR(24)` 默认 PENDING，可取 SENDING、SENT、RETRY、UNKNOWN、FAILED、CANCELLED；`attempts INT` 默认 0；`next_attempt_at,lease_until,delivered_at`、`lease_owner VARCHAR(128)`、`receipt_id VARCHAR(128)`、`last_error VARCHAR(512)` 均可空 | 保存派发进度、限时领取者、重试时间、外部回执与脱敏错误。结果未知时优先核对本动作 |

### 群消息投递编号与飞书回执

一条群通知对应一行 `policy_review_delivery`。管理员在「政策审核设置 → 飞书投递记录」看到的 `deliveryKey` 是后端根据该行生成的 UUID，表中没有单独的 UUID 列。后端依次用 `/` 拼接 `tenant_key/policy_id/version/event_key/action_type/recipient_key`，将结果编码为 UTF-8，调用 `UUID.nameUUIDFromBytes`。独立提醒的 `policy_id`、`version` 在库中均为 NULL，生成编号时两者均取 `0`。同一投递重试时复用该编号作为飞书发消息请求的 `uuid`。[编号生成代码](../../backend/src/main/java/com/company/policy/service/PolicyFeishuSender.java)

| Navicat 中的字段 | 用途 |
| --- | --- |
| `id` | 本地投递记录的自增主键。 |
| `event_key` | 触发投递的业务事件。审核类示例为 `SUBMITTED`，平台交接以 `PL/` 开头。 |
| `notice_key` | 独立提醒上等于 `event_key`，与 `tenant_key` 组合对登记去重。按审核轮次登记的行此列为 NULL。 |
| `receipt_id` | 确认送达时保存的飞书回执。群消息通常为 `om_…`，个人待办为任务 GUID。 |

在 Navicat 选择与后端一致的库，打开投递表，按测试企业及 `action_type = 'GROUP_MESSAGE'` 筛选，按 `delivered_at` 倒序。11 月 24 日群截图中的两类通知可按下面的条件核对，再用 `delivery_status`、送达时间和 `receipt_id` 对照原消息。[已有库投递核对脚本](verify_workflow_execution_notices.sql)

- **零售快手分工变更**。查 `event_key` 以 `PL/RETAIL/KUAISHOU/` 开头的记录。`policy_id`、`version` 均为 NULL，`payload_json` 里有本次的平台和分工变更信息。
- **ZC-000001 已提交，请采购办理终审**。查 `policy_id = 1`、对应 `version`、`event_key = 'SUBMITTED'` 的记录。对外政策单号由 `policy_apply.id` 格式化为 `ZC-` 加至少六位数字。`policy_apply.policy_no` 用于首次创建请求的幂等核对。

## 5. 验证范围

政策审核工作流的两份 H2 初始化脚本已在同一隔离内存库建出 22 表。样例验证同一人同组重复有效指派、同节点第二个决定、串组签署、跨企业个人投递及同一动作重登均被数据库拒绝；移除后以新指派编号加入成功。原申请人的 REVISION 指派可关联修改待办，写入默认审核名单或节点决定均被拒绝。目标 MySQL 的升级前后行级核对、索引计划与实际并发仍待用户在 Navicat 验证。

建表后在 Navicat 刷新表列表，与第 2 节的二十二表清单核对。建表与业务数据库验收以实际执行结果为准。

政策联调时，用两份相同 `recordVersion` 的请求并发提交同一草稿，再并发审核同一提交轮次。预期每一组只有一个成功，另一个返回 409，申请仅有一个新增轮次，审定结果和快照仍对应该轮。再用测试商品并发执行正式提交与删除或改规格，预期提交成功后目录变更受引用保护，目录先变更时提交须重新验证并处理失效选品。联调后核对政策三表的关联、商品范围和审核状态。执行计划用实际数据和筛选条件复核。

## 已有库升级执行期与业务提醒

2026-11-24 用户确认保留数据的增量升级。表数仍为 22。审核动作继续受原轮次和成员外键约束。`policy_review_delivery.policy_id` 和 `version` 同时为空时仅允许收件键为 0 的群消息。生成列 `notice_key` 在这类登记上取 `event_key`，独立唯一索引按企业去重；普通审核登记继续使用原业务键。业务内容与原单、平台、批次和负责人保存在该项的 `payload_json`。原消息和任务继续沿用原 ID、业务标识和回执。

1. **已有库**先在 Navicat 单独执行脚本开头的两条只读语句，确认库名与后端一致。完整 E 阶段投递表应有 `SEND_CARD`、`UPDATE_CARD` 及外键 `fk_review_delivery_round`、`fk_review_delivery_member`。确认后停写并备份，在同一查询窗口继续执行升级。脚本先把升级前的行数保存到会话变量，末尾逐行核对时只读取一次临时表，避免 MySQL 报 `Can't reopen table`。预期 `row_difference` 和 `changed_rows` 均为 0，表定义出现 `uk_delivery_notice`、`ck_delivery_subject` 和两条原外键。[执行期增量升级](upgrade_workflow_execution_notices.sql)
2. **新空库**在 [schema.sql](schema.sql) 和 [V1 基线](V1__create_policy_tables.sql) 中任选一份。这两份已含 F 结构。
3. **升级后**在 Navicat 运行[完整性、执行计划和双窗口并发核对](verify_workflow_execution_notices.sql)，填写测试企业再查业务行。空队列时业务查询应为空；试点后按原单、实际截止及收件人核对，一项成功登记对应一份回执。两窗口分别领取时不应取得同一行。目标库结果由用户现场核对。[试点及恢复顺序](../architecture/policy-workflow-phase-f.md)

若后端同时报 `Unknown column 'd.notice_key'` 和 `Field 'policy_id' doesn't have a default value`，说明报错时连接的投递表仍缺 F 阶段结构，队列领取和独立提醒登记均受影响。若尝试过升级且外键缺失或已有部分 F 结构，MySQL 的 DDL 可能已分段提交。此时保持停写，保存当前表定义与首次执行错误，定位中断位置并恢复完整约束后再启用服务。已经具备 F 结构的库，隐藏群消息正文投递编号这一展示调整无需另跑迁移。
