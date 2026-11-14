# 申请人政策接口设计

两种商品卡均按实际选中的 `goodsId` 提交，`spuId + ram + rom` 用于配置确认和筛选。服务端据此生成商品快照。

申请页已接入在售目录。「选择手机型号」可多选具体在售商品并共用一张普通卡，「指定适用机型」每次选一个具体商品并单独成卡，数量按平台选填。政策接口按已确认三表设计。[动态表单与实单](../business/perspective-rules.md)

更新日期：2026-11-14。已实现草稿创建、继续保存、提交，以及本人分页列表、详情、历轮审核和单轮提交快照。

## 公共约定

- 前缀 `/api/policies`，登录态使用 `POLICY_SESSION` Cookie，不接收客户端自报身份。写请求须可信 `Origin`、`X-Policy-Request: 1`、`Content-Type: application/json`。
- JSON 使用 camelCase，数据库 snake_case，由 DTO/VO 显式映射。BIGINT ID 用字符串；版本用整数；金额和比例用十进制字符串，空值用 JSON `null`，禁止浮点计算金额。
- 政策起止时间使用 `2026-11-10T09:00:00+08:00`，必须含时分秒及 `+08:00`，服务端按 `Asia/Shanghai` 转 `DATETIME`。既有认证 `expiresAt` 仍使用源码的 Instant 格式。
- 成功响应直接返回 VO，与现有认证接口风格一致；失败 `{ "code": "稳定代码", "message": "中文提示" }`。业务错误见下表。
- 本人列表、详情、日志和写入均由后端按会话用户及租户限制。主表 `is_deleted=0`，明细和日志经主表鉴权，身份字段由会话和服务端确定。

## 接口一览

选品抽屉共用 `GET /api/catalog/options` 和 `GET /api/catalog/goods`。商品选择及分页约定见 [申请人目录](catalog-api.md#申请人目录)。

| 方法 | 路径 | 用途 | 成功 |
| --- | --- | --- | --- |
| POST | `/api/policies/drafts` | 首次保存，可携带当前可不完整的整单表单 | 201 |
| PUT | `/api/policies/{id}/draft` | 整体保存本人草稿/已拒绝申请 | 200 |
| POST | `/api/policies/{id}/submit` | `DRAFT` 提交或 `REJECTED` 重提 | 200 |
| GET | `/api/policies/mine` | 「我的提交」分页查询本人的草稿和提交记录 | 200 |
| GET | `/api/policies/{id}` | 草稿和驳回继续编辑时返回当前表单，待审或已通过时返回对应轮次快照 | 200 |
| GET | `/api/policies/{id}/audit-logs` | 本人申请各轮提交及审核记录 | 200 |
| GET | `/api/policies/{id}/audit-logs/{logId}` | 本人申请某轮提交快照及该轮审核结果 | 200 |

首提与重提共用同一个申请 ID 和提交接口，每次成功新增一个提交轮次。不开放待审撤回、通过后修改或删除。

## 草稿内容 DTO

首次保存与更新使用同一套表单字段。创建最小请求为 `{ "perspective": "RETAIL" }`，创建时省略 `recordVersion`，服务端生成 ID、单号、申请人、状态和版本。创建不能携带任意状态、审核人、提交版本。页面通过 `Idempotency-Key` 发送首次保存 UUID。服务端按本人和该标识生成单号。同一标识和同一表单可重放未变化的首次草稿，其他冲突返回 409。首次保存结果未知时，页面复用原请求，拿到申请 ID 后再保存后续编辑。

草稿保存整体替换当前表单，须传 `recordVersion`。平台减少时同步校验卡片适用平台及方案分配。正式明细由后端在提交时生成。

| DTO 字段 | 数据库 | 规则 |
| --- | --- | --- |
| `perspective` | `channel_type` | `RETAIL` / `BILLION`，创建必填 |
| `selectedPlatforms` | `platforms` | 具体编码数组，去重，不含 ALL |
| `verifyType` | `write_off_type` | 零售 SALES / ACTIVATION；百亿 null |
| `sceneType` | `scene` | 零售 NATIONAL_SUB / LOCAL_SUB / SELF_SUB；百亿 null |
| `estimated` | `is_estimated` | 零售布尔，映射 0/1；百亿 null |
| `startTime`、`endTime` | `start_time`、`end_time` | 草稿可空；同时有值时 start < end |
| `specialRemark` | `remark` | 可空；必填口径待确认 |
| `attachmentInfo` | `attachment_urls` | 可空数组或一个 `{ "type": "LINK", "url": "https://…" }`。仅接收 HTTP/HTTPS 外部链接，无登录信息，不抓取文件 |
| `otherExplanation` | `other_desc` | 其他说明，可空 |
| `goodsForm` | `goods_form_json` | 商品卡、选品、平台联动、方案及平台分配 |
| `recordVersion` | `record_version` | 更新必传，服务端条件校验 |

商品卡保留卡片标识、`cardType`（`COMMON` 普通卡或 `SPECIFIED` 指定卡）、`followsOrderPlatforms`、选品 `targets`、卡内方案 `subsidyPlans` 和实际 `platformAssignments`。平台分配按卡内方案标识关联，指定卡独立保存各平台数量。品牌、系列、机型、版本、配置及颜色展示快照由服务端从目录生成，租户由会话确定。

普通卡和指定卡各自保存实际选中的商品 ID。提交时每个 `goodsId` 必须存在、在售且属于该机型配置。同一配置下的其他商品须另行选中。

固定方式的比例和基数为空，比例方式的固定金额为空。当前实现的比例方式上限为空，正式接收比例上限及仅填写上限的规则待业务确认。按当前页面口径，普通配置的数量留空，指定适用机型数量按平台选填，空值不自动补 0。草稿已填内容仍校验，金额不超存储精度，同一平台重复选中同一商品须提示整理。

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
  "otherExplanation": null,
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

前端通过白名单适配当前 `channel/platforms/scene/mode/priceBase` 等字段。将 `remark/explanation/links` 分别映射为特别备注、其他说明及外部链接项。文件绑定和下载权限规划见 [附件接口](attachment-api.md)。

## 保存与提交响应

创建或保存返回至少 `id`、`policyCode`、`status`、`recordVersion`、`submissionVersion`。其中 `policyCode` 映射 `policy_no`，`submissionVersion` 映射 `current_version`。详情还返回 `applicantName`、`form` 和目录展示用的 `goods`。草稿和已驳回申请从 `#/apply?policyId=<id>` 回填当前表单。待审及已通过内容读取当前轮次快照，页面按只读展示。

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

单轮详情按列表返回的日志 `id` 查询，响应为 `{ "log": { ... }, "snapshot": { ... } }`。`snapshot` 包含原提交轮次的申请 ID、单号、申请人、完整表单与商品名称快照。每次读取先校验父申请的租户、申请人和删除状态，再按该申请匹配日志。草稿及驳回的编辑入口读取当前表单，历轮快照始终取自提交时保存的内容。

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
