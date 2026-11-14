# 采购管理员审核接口设计

商品导入、清洗及标准目录接入情况见 [商品同步与管理员后台方案](../architecture/catalog-admin-design.md)。本文件维护政策审核契约。

更新日期：2026-11-14。**以下接口均未实现**。政策表结构和规格颜色范围已确认，不代表已有审核权限校验或接口。公共约定沿用 [申请人接口](policy-api.md)；商品维护接口见 [商品接口](catalog-api.md)。

当前管理员工作台侧栏只有商品相关页面。建议增加「政策审核」入口 `#/admin/policies`，含待审列表、详情和历轮记录。列表默认筛选待审核，可切换已通过或已驳回。[管理员工作台](../architecture/admin-workspace.md)

## 权限及接口

每次操作由后端校验当前用户有效且具 ADMIN 角色，并检查政策租户范围；不能只信前端路由或登录时角色快照。跨人可见范围、自审和跨租户管理规则待确认，未确认前不扩展权限。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/admin/policies` | 分页筛选申请和政策 |
| GET | `/api/admin/policies/{id}` | 当前提交快照、版本和最近审核 |
| GET | `/api/admin/policies/{id}/audit-logs` | 分页审核历史 |
| GET | `/api/admin/policies/{id}/audit-logs/{logId}` | 经父申请鉴权后读取该轮提交快照 |
| POST | `/api/admin/policies/{id}/review` | 通过或拒绝当前提交版本 |
| GET | `/api/admin/policies/{id}/override-preview` | 后续接入，读取待审版本的覆盖候选 |

成功默认 200。当前只设计查询/审核，不提供直接修改申请人内容、覆盖删除、批量通过或通过后修改接口。

## 分页筛选

建议参数：`page`（默认 1）、`pageSize`（默认 20，最大 100）、`perspective`、`platformCode`、`modelKeyword`、`status`、`applicantId`、`startTimeFrom`、`startTimeTo`、`endTimeFrom`、`endTimeTo`；零售可用 `verifyType`、`sceneType`、`estimated`。时间筛选暂定义为对应字段闭区间上下界，不把它混成未确认的“当前最终生效”条件。

modelKeyword 匹配本轮提交快照中的机型名称。精确筛选采用当前轮次的 `policy_goods_detail`，按 spuId、ram、rom、goodsId 匹配。商品必须属于该配置。查询按 `policy_apply.tenant_key` 和服务端会话限定。百亿与零售专属条件同时出现须提示非法组合；枚举、排序及分页在后端验证。

返回 `{ "items": [], "total": 0, "page": 1, "pageSize": 20 }`，列表项至少含 ID、单号、申请人、视角、平台集合、起止时间、审核状态、版本和最近审核时间。默认 `created_at DESC, id DESC`。明细筛选用 EXISTS 或先去重 ID，total 按申请数计算。不能用状态 APPROVED 显示“当前生效”。

管理员按当前轮次的 `snapshot_data` 查看商品、补贴方案、各平台数量及提交时名称。每轮审核结果和提交快照支持独立查看。

## 覆盖候选预览

请求 query 必带 `recordVersion`、`submissionVersion`，只预览当前 PENDING 轮次；不匹配返回 409。未来草稿预览如有需要另行设计。未定口径期间只提供结构或带“候选”标记的结果，不能把空列表显示为已确认无覆盖。

建议响应结构（示例，不代表已能计算）：

```json
{
  "policyId": "1001",
  "recordVersion": 4,
  "submissionVersion": 1,
  "determination": "UNCONFIRMED",
  "message": "覆盖口径尚未确认，当前不能判定实际覆盖结果。",
  "items": []
}
```

`determination` 草案枚举：`UNCONFIRMED` 尚不能判定、`CANDIDATE` 仅候选、`CONFIRMED` 最终口径计算；最后一种须规则及实现均完成后启用。

候选 item 应含新旧 policyId/policyCode/detailId、平台、spuId、ram、rom、goodsId、品牌系列型号配置颜色快照、补贴参数及时间范围。商品范围相交须为同一个平台和同一个实际选中的商品 ID，优先级与替换规则仍待确认。预览不改变数据库，不隐含二次确认要求。

算法假设、SQL 及局限见 [覆盖查询草案](../business/override-engine.md)。最终通过时须重新检查候选与并发，不能把预览结果当锁或提交凭证。

## 有效政策查询

优先复用管理员列表筛选。业务确认生效时间和覆盖规则后，再定查询时点、平台、`goodsId` 与最终适用政策的响应契约。

## 审核请求与响应

```json
{
  "recordVersion": 4,
  "submissionVersion": 1,
  "decision": "REJECT",
  "reviewRemark": "请核对所选平台的补贴参数。"
}
```

`decision` 仅 `APPROVE` / `REJECT`，不直接接受 status；`reviewRemark` 最长 512 字符，拒绝是否必填待确认。审核人、姓名、时间从服务端取得，禁止客户端伪造。

通过或拒绝仅允许当前 PENDING、指定提交版本、指定记录版本。在同一事务中条件更新申请、递增 recordVersion，并更新本轮待审记录的审核结果、审核人、意见和时间；返回 `id`、`status`、`recordVersion`、`submissionVersion`、`reviewTime`。

```json
{
  "id": "1001",
  "status": "REJECTED",
  "recordVersion": 5,
  "submissionVersion": 1,
  "reviewTime": "2026-11-10T10:00:00+08:00"
}
```

`APPROVE` 不能隐含“删除旧政策”“整单失效”或“已打款”。正式通过及替换逻辑在覆盖规则确认后实现；此前不得仅跳过覆盖校验就声称完成审核生效闭环。拒绝记录保留，申请人可原 ID 修改重提。

## 错误与验收

HTTP 400 非法筛选/decision/参数，401 未登录，403 非管理员或范围越界，404 申请不存在，409 记录/提交版本或状态冲突，503 存储不可用。稳定 code 在实现时与申请人接口统一；未确认覆盖规则的审核通过能力不得开放，可预留 `OVERRIDE_RULES_UNCONFIRMED` 明确返回未就绪，而不是伪报通过。

后续验证非管理员/跨租户拦截、重复并发审核、申请重提导致旧预览失效、历史保留、分页不因明细重复、有效时间端点、局部平台及无关机型不受影响。当前没有这些业务测试通过记录。
