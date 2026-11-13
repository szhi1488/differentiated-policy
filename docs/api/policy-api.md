# 申请人政策接口设计

目标接口按 spuId、ram、rom 确定配置，指定颜色时读取 goodsId。后端据此生成快照，颜色范围必须显式传递。业务见 [商品后台确认方案](../architecture/catalog-admin-design.md)。

更新日期：2026-11-11。业务范围与结构已确认，**本文件接口仍待实现**。尚无以下政策 Controller/Service/Mapper；前端不应据此显示保存/提交成功。

## 公共约定

- 前缀 `/api/policies`，登录态使用 `POLICY_SESSION` Cookie，不接收客户端自报身份。写请求须可信 `Origin`、`X-Policy-Request: 1`、`Content-Type: application/json`。
- JSON 使用 camelCase，数据库 snake_case，由 DTO/VO 显式映射。BIGINT ID 用字符串；版本用整数；金额和比例用十进制字符串，空值用 JSON `null`，禁止浮点计算金额。
- 政策起止时间拟用 `2026-11-10T09:00:00+08:00`，必须含时分秒及 `+08:00`，服务端按 `Asia/Shanghai` 转 `DATETIME`。这是未来业务接口约定，既有认证 `expiresAt` 仍使用源码的 Instant 格式。
- 成功响应直接返回 VO，与现有认证接口风格一致；失败 `{ "code": "稳定代码", "message": "中文提示" }`。下文业务错误代码是建议命名，尚未加入代码。
- 本人列表、详情、日志和写入均由后端按会话用户及租户限制。主表 `is_deleted=0`，明细和日志经主表鉴权，不接收客户端 applicantId 或 reviewerId。

## 接口一览

| 方法 | 路径 | 用途 | 成功 |
| --- | --- | --- | --- |
| POST | `/api/policies/drafts` | 创建可不完整的草稿 | 201 |
| PUT | `/api/policies/{id}/draft` | 整体保存本人草稿/已拒绝申请 | 200 |
| POST | `/api/policies/{id}/submit` | `DRAFT` 提交或 `REJECTED` 重提 | 200 |
| GET | `/api/policies/mine` | 本人的申请分页 | 200 |
| GET | `/api/policies/{id}` | 本人申请完整详情与最新审核意见 | 200 |
| GET | `/api/policies/{id}/audit-logs` | 本人申请各轮操作及审核记录 | 200 |

不另设重提新建接口，同一 ID 的 submit 由后端依据当前状态产生 `SUBMIT` 或 `RESUBMIT`。不开放待审撤回、通过后修改或删除。

## 草稿内容 DTO

创建最小请求为 `{ "perspective": "RETAIL" }`，服务端生成 ID、单号、申请人、状态和版本。创建不能携带任意状态、审核人、提交版本。创建重试幂等键方案待实现前定稿；网络结果不明不能盲目重试 POST 造成多单。

保存为整体替换表单，须传 `recordVersion`；平台减少时同步处理明细。明细 ID 只能属于当前申请，新行不带 ID，不能接收其他申请的明细 ID。没有明细的平台不用保存空明细。

| DTO 字段 | 数据库 | 规则 |
| --- | --- | --- |
| `perspective` | `perspective` | `RETAIL` / `BILLION`，创建必填 |
| 服务端派生 | `channel_type` | 按视角派生，不能接受矛盾组合 |
| `selectedPlatforms` | `selected_platforms` | 具体编码数组，去重，不含 ALL |
| `verifyType` | `verify_type` | 零售 SALES / ACTIVATION；百亿 null |
| `sceneType` | `scene_type` | 零售 NATIONAL_SUB / LOCAL_SUB / SELF_SUB；百亿 null |
| `estimated` | `is_estimated` | 零售布尔，映射 0/1；百亿 null |
| `startTime`、`endTime` | `start_time`、`end_time` | 草稿可空；同时有值时 start < end |
| `specialRemark` | `special_remark` | 可空；必填口径待确认 |
| `attachmentInfo` | `attachment_info` | 暂留数组结构；元素契约待存储方案确认，未定义前不接受任意存储路径 |
| `details` | `policy_detail` | 每条一个平台的机型及补贴 |
| `recordVersion` | `record_version` | 更新必传，服务端条件校验 |

每条 detail 的写入字段：`id`（已有行）、`platformCode`、`spuId`、`ram`、`rom`、`colorScope`、`goodsId`、`quantity`、`subsidyMode`、`fixedAmount`、`maxSubsidyAmount`、`subsidyRatio`、`basePriceType`。品牌系列、modelFullName、singleModel、spec、color 等快照由服务端从目录生成，响应中返回，不接受客户端任意自报文本作为身份。tenantKey 来自会话，不接受前端传入。

colorScope 为 ALL_COLORS 时 goodsId 和服务端 color 快照均为 null。SPECIFIC_COLOR 正式提交必须选择当前配置下 ACTIVE 商品，由服务端生成颜色快照。草稿允许暂未选完。colorScope=null 仅为草稿未选择，不推断不限颜色。不同型号配置的颜色分别维护，后端校验商品所属配置及政策主表权限。

固定方式的比例/基数必须为 null；比例方式的固定金额/上限必须为 null。数量可空、不自动补 0；颜色按显式范围处理。草稿已填内容仍校验，金额不超存储精度，同平台同规格相同范围重复或不限/指定相交须提示整理，不静默取最后一条。

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
  "details": [
    {
      "platformCode": "PDD",
      "spuId": "1001",
      "ram": "16GB",
      "rom": "512GB",
      "colorScope": "ALL_COLORS",
      "goodsId": null,
      "quantity": null,
      "subsidyMode": "FIXED",
      "fixedAmount": "50.00",
      "maxSubsidyAmount": "5000.00",
      "subsidyRatio": null,
      "basePriceType": null
    }
  ]
}
```

前端当前 `channel/platforms/scene/mode/priceBase` 等命名不同，必须增加明确适配；`remark/explanation/links` 的无损映射及附件契约待定，不能直接把当前 form 整体发送就认为对接完成。

## 保存与提交响应

创建或保存返回至少 `id`、`policyCode`、`status`、`recordVersion`、`submissionVersion`；详情另含表单字段、申请人姓名快照、最近审核意见和明细 ID。

提交请求：

```json
{ "recordVersion": 3 }
```

提交读取已保存内容并完整校验，不接受客户端 status。未保存的编辑需先保存，避免用户误以为 submit 同时保存页面内容。DRAFT/REJECTED 才可提交，后端递增提交版本、记录完整快照、转 PENDING；示例响应：

```json
{
  "id": "1001",
  "policyCode": "示例单号",
  "status": "PENDING",
  "recordVersion": 4,
  "submissionVersion": 1
}
```

须校验平台集合、有效标准规格、明确颜色范围、时间、零售分类、补贴方式和参数，百亿不带零售值。指定颜色必须有本配置下有效商品，服务端生成完整快照。不限颜色的商品 ID 为空。数量可空。固定上限必填性、每个所选平台是否必须有明细及附件备注必填性仍待确认；不能用数据库允许 NULL 代替提交完整校验。

## 分页、筛选与日志

`mine` 拟支持 `page=1`、`pageSize=20`（最大 100）、`status`、`perspective`、`platformCode`；默认 `created_at DESC, id DESC`，排序字段白名单，未知枚举拒绝。分页参数是工程草案。

响应形如 `{ "items": [], "total": 0, "page": 1, "pageSize": 20 }`。按明细筛选用 EXISTS 或先去重申请 ID，不能把明细 JOIN 行数当申请数。

`audit-logs` 同样分页，按 `id ASC` 展示提交轮次、动作、前后状态、操作人姓名、审核意见和时间。完整提交快照拟由 `GET /api/policies/{id}/audit-logs/{logId}` 在父申请鉴权后返回；仅 SUBMIT/RESUBMIT 存在快照，其他动作不可伪造快照。该补充路径也待实现。

## 错误与实现要求

| HTTP | 建议 code | 场景 |
| --- | --- | --- |
| 400 | POLICY_VALIDATION_FAILED | 枚举、字段组合、精度或正式提交不完整 |
| 401 | LOGIN_REQUIRED | 未登录或会话到期，沿用现有代码 |
| 403 | POLICY_ACCESS_DENIED | 无归属或租户权限；存在性隐藏策略需统一 |
| 404 | POLICY_NOT_FOUND | 不存在或已删除 |
| 409 | POLICY_VERSION_CONFLICT / POLICY_STATE_CONFLICT | 版本过期、重复提交、不允许状态 |
| 503 | POLICY_STORAGE_UNAVAILABLE | 存储不可用，不显示假成功 |

事务与历史遵循 [状态机](../business/status-machine.md)，字段枚举见 [数据字典](../sql/database-design.md)。实现时同步 Knife4j、DTO/VO 及对应权限、版本、空值、精度和重提测试。
