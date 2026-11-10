# 差异化政策管理数据库设计

更新日期：2026-11-10。适用数据库：MySQL 8.0.16 及以上。

本版采用四张业务表，保存政策申请、平台机型明细、用户角色及审核历史。建表脚本为 [schema.sql](schema.sql)，版本追踪脚本为 [V1__create_policy_tables.sql](V1__create_policy_tables.sql)，两者保持一致。完整 DDL 只在 SQL 文件维护，不在数据字典重复。

当前交付状态：用户最新 Navicat 截图显示四张表均已存在。本次已核对本机 `differentiated policy` 库的 `user_info` 字段并接入飞书登录落库，真实 MySQL 的临时写入验证已通过并回滚，未改表结构。政策主表、明细表和审核日志尚未接入业务持久化；其实际约束未逐项回读核验。SQL 文件名用于版本追踪，不表示启用 Flyway 或其他自动迁移工具；不要对已有表重跑新建脚本。

## 1. 已明确的业务与设计边界

- 录入顺序为“选择零售或百亿 → 选择平台 → 选择该平台下的机型，填写数量和补贴”。一条明细对应一个平台下的一个机型，数量属于这一条明细；跨平台数量分别保存。
- 同一机型在不同平台可以有不同补贴；部分或全部平台共享补贴时，保存为各具体平台的明细，由录入界面提供批量填写能力。不能把各平台数量直接当成同一个共享数量重复填充。
- “全部”是选择操作，保存操作当时明确的平台集合，不保存 `ALL`。当前不自动增加历史适用平台；未来新增平台是否适用历史全选政策仍待确认。
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
3. 打开 `docs/sql/schema.sql`（与 V1 二选一，不要执行两遍），按文件顺序执行：用户表、主表、明细表、日志表。
4. 查看执行结果，确认四张表、外键和 `CHECK` 约束均创建成功。MySQL DDL 不构成整个文件的原子事务；中途报错时，前面已成功创建的表可能仍存在，先核对结果，不要直接重跑整个文件。

本脚本只新建业务表，不包含建库、真实用户或机型初始化数据。建表成功并不代表后端数据库连接、权限控制或业务流程已经实现。

### CHECK 兼容修正与已有表处理

2026-11-10 的历史日志显示，原 `ck_detail_subsidy_exclusive` 用 `CASE` 返回整数，触发 MySQL 3812。V1 已改为直接 `AND` / `OR` 布尔表达式，并显式处理空补贴方式；`schema.sql` 沿用修正版。

用户后续截图显示四表均已存在，不再引用仓库中不存在的 `continue_create_policy_tables.sql`。现有库须先读取结构、约束与执行结果，再为缺失或不一致部分设计独立增量脚本；不重跑整份新建脚本，不删除重建。

### 验证场景与当前结果

以下为数据库及业务接入后的验证要求，不表示已执行通过：

- 不完整草稿可以保存，颜色和数量为空时回读仍为空。
- 百亿不能保存零售专属字段；未知明细平台被约束拒绝。跨渠道平台、未选平台由后端拒绝。
- 固定补贴不能混入比例字段，比例补贴不能混入固定字段，切换方式正确清理旧值。
- 提交及重提日志必须有正数提交版本和对象形式的快照；每次拒绝意见及前次提交内容可以独立还原。
- 同一用户租户身份不重复；外键不能引用不存在的用户或申请；并发编辑和重复审核由后端版本保护拦截。

当前四表存在情况依据用户截图；此前已实际核对 user_info 字段并通过真实 MySQL 临时写入回滚验证。其余三表未接业务持久化，实际约束未逐项核验。本次文档整理未连接数据库、未执行 DDL，也未验证以上政策业务场景。

## 6. 待确认事项

1. 现有机型表的字段、稳定主键和读取方式；对接前再确定关联列及增量迁移。
2. 数量缺省、零数量及空颜色的业务含义。
3. 固定补贴上限单位和作用范围，比例业务范围、精度、金额舍入及计算基数取值来源。
4. 覆盖匹配维度、时间端点、优先级、正式替换时点及部分替换规则。
5. 管理员名单、跨人数据可见范围、自审规则及拒绝原因是否必填。
6. 备注、附件及链接是否必填，附件存储方案、格式、大小和访问权限。

## 7. 字段字典

以下字段与 [schema.sql](schema.sql) 对应。“可空”指 DDL 层允许 SQL NULL，不等于正式提交允许缺失。ID 使用 BIGINT，API 传十进制字符串。未注明默认值的可空字段默认 NULL；具体索引和约束以 SQL 为准。

### user_info：用户与角色

| 字段 | 类型 | 可空 / 默认 | 含义 |
| --- | --- | --- | --- |
| `id` | BIGINT | 否，自增 | 本地主键 |
| `tenant_key` | VARCHAR(64) | 否 | 飞书租户，二进制排序规则 |
| `open_id` | VARCHAR(64) | 否 | 应用内用户标识，二进制排序规则 |
| `union_id` | VARCHAR(64) | 是 | 同开发商用户标识，二进制排序规则 |
| `name` | VARCHAR(64) | 否 | 用户姓名 |
| `department` | VARCHAR(128) | 是 | 部门，重复登录保留现有值 |
| `role_type` | VARCHAR(32) | 否，APPLICANT | 本地角色，不能由前端赋值 |
| `is_deleted` | TINYINT | 否，0 | 0 有效 / 1 停用 |
| `created_at` | DATETIME | 否，CURRENT_TIMESTAMP | 创建时间 |
| `updated_at` | DATETIME | 否，CURRENT_TIMESTAMP | 数据库更新时自动刷新 |

唯一索引 `(tenant_key, open_id)`；停用身份仍占用唯一键。当前登录按真实身份查找并拒绝停用用户，不能只过滤后当作新用户。角色修改、恢复入口尚未开发。

### policy_master：政策申请主表

| 字段 | 类型 | 可空 / 默认 | 含义 |
| --- | --- | --- | --- |
| `id` | BIGINT | 否，自增 | 政策 ID |
| `policy_code` | VARCHAR(64) | 否 | 唯一单号，二进制排序规则 |
| `applicant_id` | BIGINT | 否 | user_info.id，由会话确定 |
| `applicant_name` | VARCHAR(64) | 是 | 申请人姓名快照 |
| `perspective` | VARCHAR(32) | 否 | RETAIL / BILLION |
| `channel_type` | VARCHAR(32) | 否 | 与视角一一对应 |
| `selected_platforms` | JSON | 是 | 明确平台编码数组，草稿可空数组 |
| `verify_type` | VARCHAR(32) | 是 | 零售核销类型，百亿 NULL |
| `scene_type` | VARCHAR(32) | 是 | 零售场景，百亿 NULL |
| `is_estimated` | TINYINT | 是 | 零售 0/1，百亿 NULL |
| `start_time` | DATETIME | 是 | 开始时间，秒级北京时间 |
| `end_time` | DATETIME | 是 | 结束时间，start < end |
| `special_remark` | TEXT | 是 | 特别备注 |
| `attachment_info` | JSON | 是 | 附件/链接数组；元素定义待确认 |
| `status` | VARCHAR(32) | 否，DRAFT | 审核状态，不是生效状态 |
| `submission_version` | INT UNSIGNED | 否，0 | 提交轮次 |
| `record_version` | INT UNSIGNED | 否，0 | 业务修改并发版本 |
| `reviewer_id` | BIGINT | 是 | 最近审核人 user_info.id |
| `reviewer_name` | VARCHAR(64) | 是 | 最近审核人姓名快照 |
| `review_remark` | VARCHAR(512) | 是 | 最近审核意见 |
| `review_time` | DATETIME | 是 | 最近审核时间 |
| `is_deleted` | TINYINT | 否，0 | 主表逻辑删除标记，不代表已开放删除 API |
| `created_at` | DATETIME | 否，CURRENT_TIMESTAMP | 创建时间 |
| `updated_at` | DATETIME | 否，CURRENT_TIMESTAMP | 自动刷新 |

`policy_code` 唯一。索引覆盖申请人+状态+创建时间、视角+状态+创建时间、起止时间及审核人。主表没有 tenant_key 列，当前设计可通过申请人关联限定租户；正式接入须统一外键对象租户校验，不只凭传入 ID 操作。

### policy_detail：具体平台的机型与补贴

| 字段 | 类型 | 可空 / 默认 | 含义 |
| --- | --- | --- | --- |
| `id` | BIGINT | 否，自增 | 明细 ID |
| `policy_id` | BIGINT | 否 | policy_master.id |
| `platform_code` | VARCHAR(32) | 否 | 具体平台，不含 ALL |
| `model_full_name` | VARCHAR(128) | 是 | 型号全称快照 |
| `single_model` | VARCHAR(64) | 是 | 单型号快照 |
| `spec` | VARCHAR(64) | 是 | 配置快照 |
| `color` | VARCHAR(32) | 是 | 颜色，空值不作未经确认的通配 |
| `quantity` | INT | 是 | 此平台此机型数量，非负，不自动补 0 |
| `subsidy_mode` | VARCHAR(32) | 是 | FIXED / RATIO，草稿可缺 |
| `fixed_amount` | DECIMAL(10,2) | 是 | 固定金额，非负 |
| `max_subsidy_amount` | DECIMAL(10,2) | 是 | 固定上限，非负，作用范围待确认 |
| `subsidy_ratio` | DECIMAL(5,2) | 是 | 百分数，非负，10.00 表示 10% |
| `base_price_type` | VARCHAR(32) | 是 | 比例计算基数编码 |
| `created_at` | DATETIME | 否，CURRENT_TIMESTAMP | 创建时间 |
| `updated_at` | DATETIME | 否，CURRENT_TIMESTAMP | 自动刷新 |

索引 `(policy_id, platform_code)`、`(platform_code, single_model, spec)` 仅供检索，不保证机型唯一。当前没有稳定主数据 ID，也没有逻辑删除字段。重写明细前须以主表版本锁定业务修改，待审/通过版本不允许直接重写。

### policy_audit_log：操作、提交快照与审核历史

| 字段 | 类型 | 可空 / 默认 | 含义 |
| --- | --- | --- | --- |
| `id` | BIGINT | 否，自增 | 日志 ID |
| `policy_id` | BIGINT | 否 | policy_master.id |
| `submission_version` | INT UNSIGNED | 否，0 | 对应提交轮次 |
| `operator_id` | BIGINT | 否 | user_info.id，服务端确定 |
| `operator_name` | VARCHAR(64) | 是 | 操作人姓名快照 |
| `action_type` | VARCHAR(32) | 否 | 操作枚举 |
| `from_status` | VARCHAR(32) | 是 | 操作前状态，创建时 NULL |
| `to_status` | VARCHAR(32) | 否 | 操作后状态 |
| `action_desc` | VARCHAR(255) | 是 | 操作描述 |
| `review_remark` | VARCHAR(512) | 是 | 此次审核意见，不被后续覆盖 |
| `submission_snapshot` | JSON | 是 | SUBMIT/RESUBMIT 必须为对象 |
| `created_at` | DATETIME | 否，CURRENT_TIMESTAMP | 操作时间 |

索引 `(policy_id, submission_version, id)`、`(operator_id, created_at)`。没有 is_deleted 和 updated_at；历史记录不得随主表编辑清空。CHECK 校验枚举及提交快照，但不完整限制所有 from/to/action 组合，状态迁移仍由 Service 保证。

## 8. 枚举字典

| 字段 | 值 | 中文含义 / 约束 |
| --- | --- | --- |
| `role_type` | APPLICANT / ADMIN | 申请人 / 采购管理员 |
| `perspective` | RETAIL / BILLION | 零售 / 百亿 |
| `channel_type` | RETAIL_CHANNEL / BILLION_CHANNEL | 必须分别匹配 RETAIL / BILLION |
| `verify_type` | SALES / ACTIVATION | 销售维度 / 激活维度 |
| `scene_type` | NATIONAL_SUB / LOCAL_SUB / SELF_SUB | 国补 / 地补 / 自补 |
| `is_estimated` | 0 / 1 / NULL | 否 / 是 / 不适用或未填写草稿 |
| `status`、日志前后状态 | DRAFT / PENDING / APPROVED / REJECTED | 草稿 / 待审核 / 已通过 / 已拒绝 |
| `subsidy_mode` | FIXED / RATIO | 固定 / 比例 |
| `base_price_type` | RETAIL_PRICE | 零售价 |
| `base_price_type` | POLICY_PRICE | 政策价 |
| `base_price_type` | HAND_PRICE | 到手价 |
| `base_price_type` | LOCAL_ACTUAL | 地补实际 |
| `base_price_type` | MERCHANT_CONTROL | 商家配控 |
| `action_type` | CREATE_DRAFT / UPDATE_DRAFT | 创建 / 修改草稿或已拒绝申请 |
| `action_type` | SUBMIT / RESUBMIT | 首次提交 / 拒绝后重提 |
| `action_type` | APPROVE / REJECT | 审核通过 / 拒绝 |
| `is_deleted` | 0 / 1 | 未删除 / 已删除（用户用于停用判断） |

平台编码完整映射见本文件第 3 节。基数和平台中文名须保留原名，未经确认不重命名。

## 9. 数据库与应用分别承担的校验

SQL 的 CHECK 允许草稿暂缺，但校验枚举、非负、渠道对应、百亿字段为空、两组补贴互斥、数组外层类型及提交日志对象。外键 RESTRICT 防止误删关联历史。数据库没有校验所有跨行、跨表规则。

应用必须校验：平台数组内容去重和渠道范围、明细平台属于已选集合、稳定主数据有效性、跨平台共享展开、完整提交、状态迁移、每轮快照、本人及租户归属、ADMIN 权限、并发版本。JSON 数组或外键存在不代表业务校验通过。

`DATETIME` 不含时区；SQL 的 SET time_zone 只作用于执行连接。后端 Jackson 和 JDBC 已配置 Asia/Shanghai，后续仍须核对数据库服务器/连接、JVM 时间与读写转换，不能仅靠一个配置宣称时间语义全部验证完成。

关联文档：[表单规约](../business/perspective-rules.md)、[状态机](../business/status-machine.md)、[覆盖查询草案](../business/override-engine.md)、[申请人接口](../api/policy-api.md)、[管理员接口](../api/admin-api.md)。
