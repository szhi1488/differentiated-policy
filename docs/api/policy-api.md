# 申请人政策接口设计

两种商品卡均按实际选中的 `goodsId` 提交，`spuId + ram + rom` 用于配置确认和筛选。服务端据此生成商品快照。

申请页已接入在售目录。「选择手机型号」每次确认新建一张普通卡，普通卡可通过「编辑」追加配置。「指定适用机型」每次选一个具体商品并单独成卡，数量按平台选填。政策接口按已确认三表设计。[动态表单与实单](../business/perspective-rules.md)

更新日期：2026-11-17。已实现草稿创建、继续保存、提交，以及本人分页列表、当前详情和驳回原因。后台保留历轮审核和单轮提交快照接口，申请人页面不提供历史入口。

## 公共约定

- 前缀 `/api/policies`，登录态使用 `POLICY_SESSION` Cookie，不接收客户端自报身份。写请求须可信 `Origin`、`X-Policy-Request: 1`、`Content-Type: application/json`。
- JSON 使用 camelCase，数据库 snake_case，由 DTO/VO 显式映射。BIGINT ID 用字符串；版本用整数；金额和比例用十进制字符串，空值用 JSON `null`，禁止浮点计算金额。
- 政策起止时间使用 `2026-11-10T09:00:00+08:00`，必须含时分秒及 `+08:00`，服务端按 `Asia/Shanghai` 转 `DATETIME`。既有认证 `expiresAt` 仍使用源码的 Instant 格式。
- 成功响应直接返回 VO，与现有认证接口风格一致；失败 `{ "code": "稳定代码", "message": "中文提示" }`。业务错误见下表。
- 本人列表、详情、日志和写入均由后端按会话用户及租户限制。主表 `is_deleted=0`，明细和日志经主表鉴权，身份字段由会话和服务端确定。

## 接口一览

选品抽屉共用 `GET /api/catalog/options` 和 `GET /api/catalog/goods`。商品选择及分页约定见 [申请人目录](catalog-api.md#申请人目录)。

申请地址切换已在前端加载详情前确认未保存内容，取消时不请求目标详情并保留原地址和本地输入；Hash 事件不关闭确认框。普通卡取消配置按完整卡片暂存已有商品的移除，确认保存配置后才改变 DTO 中的最终商品集合；取消编辑保留原卡，手动取消的颜色在重选配置后仍保持取消。此次修复沿用全部现有路径、DTO、版本校验和权限，不涉及数据库调整。[规则与验证](../architecture/frontend-development.md#2026-11-15-申请地址切换保护与已有配置移除)

| 方法 | 路径 | 用途 | 成功 |
| --- | --- | --- | --- |
| POST | `/api/policies/drafts` | 首次保存，可携带当前可不完整的整单表单 | 201 |
| PUT | `/api/policies/{id}/draft` | 整体保存本人草稿/已拒绝申请 | 200 |
| DELETE | `/api/policies/{id}/draft?recordVersion={version}` | 物理删除本人未提交草稿 | 204 |
| POST | `/api/policies/{id}/submit` | `DRAFT` 提交或 `REJECTED` 重提 | 200 |
| GET | `/api/policies/mine` | 「我的提交」分页查询本人的草稿和提交记录 | 200 |
| GET | `/api/policies/{id}` | 草稿和驳回继续编辑时返回当前表单，待审或已通过时返回对应轮次快照 | 200 |
| GET | `/api/policies/{id}/audit-logs` | 本人申请各轮提交及审核记录 | 200 |
| GET | `/api/policies/{id}/audit-logs/{logId}` | 本人申请某轮提交快照及该轮审核结果 | 200 |

首提与重提共用同一个申请 ID 和提交接口，每次成功新增一个提交轮次。不开放待审撤回、通过后修改或删除。

## 删除未提交草稿

「我的提交」仅为 `DRAFT` 且 `submissionVersion=0` 的记录显示删除按钮。确认后携带当前 `recordVersion` 发起 DELETE，版本为必填正整数。

后端核验本公司有效申请人、本人归属、逻辑删除状态、`DRAFT` 状态和提交轮次 0。条件 DELETE 同时匹配记录版本，并排除已存在审核快照或商品明细的申请。成功后政策主表记录从数据库移除，返回 204。不存在或非本人返回 404，状态或版本变化返回 409，非法或缺少版本返回 400。删除、保存与提交争用同一申请行，旧版本请求不能覆盖已提交的状态。

成功后刷新当前筛选及分页，当前页为空且超出末页时回到最后有效页。若删除的是编辑页缓存的同一草稿，一并清理表单和本地附件状态。取消或删除失败时保留输入。结果不明时只读取申请核对，确认不存在后完成清理，继续存在或无法核对时提示刷新，不自动重放删除请求。

草稿删除会解除该申请的附件引用，磁盘文件由附件存储继续保留。文件物理删除仍由独立附件接口检查其他当前表单和历史快照引用。[附件引用保护](attachment-api.md#政策绑定与历史保护)

## 草稿内容 DTO

首次保存与更新使用同一套表单字段。创建最小请求为 `{ "perspective": "RETAIL" }`，创建时省略 `recordVersion`，服务端生成 ID、单号、申请人、状态和版本。创建不能携带任意状态、审核人、提交版本。页面通过 `Idempotency-Key` 发送首次保存 UUID。服务端按本人和该标识生成内部创建幂等标识，继续保存在现有 `policy_no` 字段；对外 `policyCode` 由申请 ID 生成为 `ZC-000005` 这样的短单号，最少六位且不截断更长 ID。同一标识和同一表单可重放未变化的首次草稿，其他冲突返回 409。首次保存结果未知时，页面复用原请求，拿到申请 ID 后再保存后续编辑。

草稿保存整体替换当前表单，须传 `recordVersion`。平台减少时同步校验卡片适用平台及方案分配。正式明细由后端在提交时生成。

| DTO 字段 | 数据库 | 规则 |
| --- | --- | --- |
| `perspective` | `channel_type` | `RETAIL` / `BILLION`，创建必填 |
| `selectedPlatforms` | `platforms` | 具体编码数组，去重，不含 ALL |
| `verifyType` | `write_off_type` | 零售 SALES / ACTIVATION；百亿 null |
| `sceneType` | `scene` | 零售 NATIONAL_SUB / LOCAL_SUB / SELF_SUB；百亿 null |
| `estimated` | `is_estimated` | 零售布尔，映射 0/1；百亿 null |
| `startTime`、`endTime` | `start_time`、`end_time` | 申请开始时间、参考到期时间。草稿可空，同时有值时 start < end |
| `specialRemark` | `remark` | 可空；必填口径待确认 |
| `attachmentInfo` | `attachment_urls` | 可空数组，最多 10 个 `{ "type": "FILE", "id": "UUID", "originalName": "文件名" }` 和一个 `{ "type": "LINK", "url": "https://…" }`。核验本人文件并以存储名称为准；外部链接限 HTTP/HTTPS，无登录信息 |
| `goodsForm` | `goods_form_json` | 商品卡、选品、平台联动、方案及平台分配 |
| `recordVersion` | `record_version` | 更新必传，服务端条件校验 |

商品卡保留卡片标识、`cardType`（`COMMON` 普通卡或 `SPECIFIED` 指定卡）、`followsOrderPlatforms`、选品 `targets`、卡内方案 `subsidyPlans` 和实际 `platformAssignments`。平台分配按卡内方案标识关联，指定卡独立保存各平台数量。品牌、系列、机型、版本、配置及颜色展示快照由服务端从目录生成，租户由会话确定。

同一申请可包含多张普通卡和指定卡，各卡保存实际选中的商品 ID。普通卡按 `spuId + ram + rom` 在申请内保持唯一归属，同一张普通卡可保存该配置下勾选的多个颜色。所有卡片仍须保证平台与商品 ID 的组合唯一。提交时每个 `goodsId` 必须存在、在售且属于该机型配置。

每个方案按固定或比例方式填写对应字段，保存填写值供查阅。固定方式的比例和基数为空，比例方式的固定金额和上限为空。普通配置数量留空，指定卡按平台选填参考数量，保留空值或 0。同一平台下普通卡与指定卡重复选中同一商品时，返回 400 并提示申请人先从普通卡取消勾选该颜色。

示例保存请求（结构演示，机型及数字不是初始化数据）：

```json
{
  "recordVersion": 2,
  "perspective": "RETAIL",
  "selectedPlatforms": ["PDD"],
  "verifyType": "SALES",
  "sceneType": "NATIONAL_SUB",
  "estimated": false,
  "startTime": "2026-11-10T09:00:00+08:00",
  "endTime": "2026-11-20T18:00:00+08:00",
  "specialRemark": null,
  "attachmentInfo": [],
  "goodsForm": {
    "cards": [
      {
        "key": "card-1",
        "cardType": "COMMON",
        "followsOrderPlatforms": true,
        "targets": [
          { "spuId": "1001", "ram": "16GB", "rom": "512GB", "goodsId": "2001" }
        ],
        "subsidyPlans": [
          { "key": "plan-1", "subsidyMode": "FIXED", "fixedAmount": "50.00", "maxSubsidyAmount": "5000.00" }
        ],
        "platformAssignments": [
          { "platformCode": "PDD", "planKey": "plan-1", "quantity": null }
        ]
      }
    ]
  }
}
```

前端通过白名单适配当前 `channel/platforms/scene/mode/priceBase` 等字段。将 `remark/links` 分别映射为特别备注及外部链接项。申请内容仅保留一个特别备注字段，旧请求或旧快照中的 `otherExplanation` 作为兼容字段忽略。文件绑定和下载权限见 [附件接口](attachment-api.md)。

## 保存与提交响应

创建或保存返回至少 `id`、`policyCode`、`status`、`recordVersion`、`submissionVersion`。其中 `policyCode` 由已有申请 ID 格式化为 `ZC-` 加最少六位数字，重提时保持不变；`submissionVersion` 映射 `current_version`，仅供并发和后台日志使用，申请人页面不展示。`policy_no` 沿用内部创建幂等标识，不调整表结构或改写既有数据。新旧申请、管理员列表、详情、覆盖引用和快照响应统一返回短单号；旧快照仅在读取响应时适配编号，原始快照保持不变。详情还返回 `applicantName`、`form` 和目录展示用的 `goods`。草稿和已驳回申请从 `#/apply?policyId=<id>` 回填当前表单。待审及已通过内容读取当前轮次快照，页面按只读展示。

提交请求：

```json
{ "recordVersion": 3 }
```

点击「提交申请」时，页面先创建或更新草稿，再用保存返回的 `recordVersion` 提交。提交接口读取已保存表单并完整校验，状态由后端流转。若保存成功但提交失败，页面保留申请 ID、表单和已保存版本供继续编辑。DRAFT/REJECTED 才可提交，后端在同一事务中递增 `current_version` 和 `record_version`、插入本轮审核快照及商品适用明细，并转 PENDING；示例响应：

```json
{
  "id": "1001",
  "policyCode": "示例单号",
  "status": "PENDING",
  "recordVersion": 4,
  "submissionVersion": 1
}
```

须校验整单平台、商品在售状态及配置归属、时间、零售分类与补贴参数。百亿的零售字段为空。普通卡数量为空，指定卡可选填数量。当前固定上限、附件和备注选填，服务端校验每张卡有适用平台。是否要求整单每个平台均有明细仍待确认。

## 分页、筛选与日志

`mine` 支持以下参数。顺序固定为 `created_at DESC, id DESC`。筛选条件可组合，未知枚举及越界分页参数返回 400。

| 参数 | 取值 |
| --- | --- |
| `page` | 默认 1，正整数 |
| `pageSize` | 默认 20，1 至 100 |
| `status` | 可选，`DRAFT`、`PENDING`、`APPROVED`、`REJECTED` |
| `perspective` | 可选，`RETAIL`、`BILLION` |
| `platformCode` | 可选，具体整单平台编码，例如 `JD` 或 `PDD` |

分页响应形如 `{ "items": [], "total": 0, "page": 1, "pageSize": 20 }`。列表项含 `id`、`policyCode`、`status`、`recordVersion`、`submissionVersion`、`perspective`、`selectedPlatforms`、`createdAt` 和 `updatedAt`。整单平台按 `policy_apply.platforms` 中的编码精确匹配。

`audit-logs` 使用同样的分页参数和响应外层，按提交轮次倒序。每条含字符串 `id`、`submissionVersion`、`submittedAt`、`auditStatus`、`auditorName`、`auditComment` 和 `auditTime`。审核状态为 `SUBMITTED`、`APPROVED` 或 `REJECTED`，待审轮次的审核信息为 `null`，未提交的草稿返回空页。

申请详情及继续编辑页在状态为 `REJECTED` 时，通过 `audit-logs?page=1&pageSize=1` 读取最新审核记录，核对 `submissionVersion` 与当前申请一致且日志为 `REJECTED`，在页面顶部显示驳回原因、审核人及时间。`auditComment` 为空或只有空白时显示「审核人未填写驳回原因」；请求失败和轮次不一致分别提示并允许重试，不显示旧轮次原因。接口及数据结构不变，继续沿用本人归属和有效会话校验。

单轮详情按列表返回的日志 `id` 查询，响应为 `{ "log": { ... }, "snapshot": { ... } }`。`snapshot` 包含原提交轮次的申请 ID、单号、申请人、完整表单与商品名称快照。每次读取先校验父申请的租户、申请人和删除状态，再按该申请匹配日志。草稿及驳回的编辑入口读取当前表单，历轮快照始终取自提交时保存的内容。

### 提交快照的存储与兼容

一次快照记录某份政策某一轮正式提交时的完整内容。`PolicySnapshotData` 是 Java 的 `record`（记录类），每次提交都有各自的字段值。固定金额、比例这样的有限选项才适合用枚举（`enum`）表达。快照使用现有审核日志的一个 JSON 字段，无需单独建表。[快照模型](../../backend/src/main/java/com/company/policy/entity/PolicySnapshotData.java)

1. **提交。** 服务端校验已保存的表单和在售商品，取得该时点的商品名称、规格和颜色。`PolicySnapshotCodec` 将表单、补贴、附件引用和商品显式转换为快照模型，再序列化为 JSON。提交事务将 JSON 写入 MySQL 的 `policy_audit_log.snapshot_data`，同时更新申请状态并写入本轮商品明细。[快照编解码](../../backend/src/main/java/com/company/policy/support/policy/PolicySnapshotCodec.java)
2. **回读。** 本人已提交详情、管理员审核详情、历轮记录和补贴对照读取所需轮次的快照。旧单号在展示时格式化，已移除的 `otherExplanation` 在响应中省略，数据库中的原文保留。新旧记录使用相同的 `form.goodsForm`、`form.attachmentInfo` 和 `goods` 路径，金额与比例保留十进制字符串，数量区分 0 和未填写。[旧格式回归样本](../../backend/src/test/java/com/company/policy/support/policy/PolicySnapshotCodecTest.java)
3. **重启和迁移。** 后端重启或更换 JAR 后，连接原 MySQL 数据库即可继续读取历轮政策。快照中的文件 ID 和文件名用于定位附件，原文件及元数据保存在附件目录，迁移时须一并保留。[附件存储和配置](attachment-api.md#存储和配置)

## 错误与实现要求

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | POLICY_VALIDATION_FAILED | 枚举、字段组合、精度或正式提交不完整 |
| 401 | LOGIN_REQUIRED | 未登录或会话到期，沿用现有代码 |
| 403 | POLICY_ACCESS_DENIED | 当前账号不是本公司有效申请人 |
| 404 | POLICY_NOT_FOUND | 不存在、已删除或非本人申请 |
| 404 | POLICY_AUDIT_NOT_FOUND | 该申请下不存在指定审核记录 |
| 409 | POLICY_VERSION_CONFLICT / POLICY_STATE_CONFLICT | 版本过期、重复提交、不允许状态 |
| 409 | POLICY_GOODS_CHANGED | 选中商品下架、删除或配置变化 |
| 409 | POLICY_CREATE_CONFLICT | 首次保存标识已用于其他内容或申请已变化 |
| 503 | POLICY_STORAGE_UNAVAILABLE | 存储不可用，不显示假成功 |

事务与历史遵循 [状态机](../business/status-machine.md)，字段枚举见 [数据字典](../sql/database-design.md)。已实现接口可通过 Knife4j 查看。
