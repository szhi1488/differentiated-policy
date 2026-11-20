# 采购管理员审核与有效政策接口

更新日期：2026-11-16。以下七个接口已由 `PolicyReviewController` 提供。管理员从「政策管理 → 差异化政策」查看已提交的申请。接口依据会话核对账号、角色和公司租户。申请人转任管理员后，由其他管理员审核其原申请。公共约定见 [申请人接口](policy-api.md)。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/admin/policies` | 分页查看已提交的政策 |
| GET | `/api/admin/policies/{id}` | 当前提交快照、审核结果和覆盖状态 |
| GET | `/api/admin/policies/{id}/audit-logs` | 分页查看各轮审核 |
| GET | `/api/admin/policies/{id}/audit-logs/{logId}` | 指定轮次的审核结果和提交快照 |
| POST | `/api/admin/policies/{id}/review` | 通过或驳回当前待审轮次 |
| GET | `/api/admin/policies/{id}/override-preview` | 查看待审轮次假设此刻通过后的覆盖情况 |
| GET | `/api/admin/policies/effective` | 查询渠道、平台和商品在指定时点适用的政策 |

## 列表与详情

`page` 默认 1，`pageSize` 默认 20，范围 1 至 100。支持 `status`（PENDING、APPROVED、REJECTED）、`perspective`、`platformCode`（整单平台）、`verifyType`、`sceneType`、`applicant`、`submittedFrom`、`submittedTo`、`keyword`。提交日期为北京时间 `YYYY-MM-DD`，含所选的首尾日期。关键词匹配单号、申请人和快照机型名。列表按本轮提交时间、提交日志 ID 倒序。`counts` 返回其他筛选条件下各状态的数量，`total` 为当前状态筛选后的行数。

详情的 `policy` 提供记录版本、提交轮次、审核状态和 `reviewable`；`snapshot` 保存本轮提交内容；`audit` 保存当前审核结果。省略 `includeCoverage` 时兼容旧客户端，待审核和已通过详情包含 `coverage`。

管理员列表、筛选、状态数量统计及详情的 `policy` 摘要统一按 `current_version` 关联最近正式提交快照，读取其中的申请人、渠道、整单平台、核销类型及场景。驳回后申请人保存编辑内容只影响本人可回填的主表，管理员继续展示和筛选原提交内容；重提事务成功后才切换到新轮次。状态、并发版本、归属和审核权限仍按有效主表判断。快照中的可空核销类型和场景返回 null，不将 JSON null 显示为字符串。接口路径及响应字段保持兼容。

目标库已具备政策三表和正式提交数据后，在 Navicat 对照主表与最近提交快照，核对筛选结果、状态计数及查询计划。驳回修改保存后按旧提交条件应命中，重提成功后切换到新条件。目标库结果和执行计划待用户核对。

文件附件下载由 `PolicyAttachmentController` 提供 `GET /api/admin/policies/{id}/audit-logs/{logId}/attachments/{attachmentId}/content`。详情页面使用 `audit.id` 和快照中的文件 ID 生成下载入口，服务端校验管理员身份、申请权限及该文件属于指定提交轮次。字段和删除保护见[附件接口](attachment-api.md)。

「差异化政策」页面默认传 `pageSize=10`。查看和审核共用详情接口，传 `includeCoverage=false` 跳过覆盖计算，响应的 `coverage` 为 null。普通机型的已选颜色、指定机型的平台及数量均从该轮快照读取。单独的覆盖预览、有效政策查询和历轮审核接口可独立调用。[管理员工作台](../architecture/admin-workspace.md#差异化政策)

## 审核

请求体示例：

```json
{
  "recordVersion": 4,
  "submissionVersion": 1,
  "decision": "REJECT",
  "reviewRemark": "请核对所选平台的补贴参数。"
}
```

`decision` 取 `APPROVE` 或 `REJECT`，意见选填，最多 512 字。服务端确定审核人及审核时间，并在同一事务更新主表和当前待审轮次。响应包含 `id`、`status`、`recordVersion`、`submissionVersion`、`reviewTime`。审核记录保留，驳回后可原单重提。

管理页点击「通过」发送 `APPROVE`，`reviewRemark` 为 null。管理员核对本轮申请内容，服务端按实际权限、状态、版本和轮次更新。写入冲突或结果未知时须重新读取。预计覆盖范围由申请人在正式提交前核对。[提交契约](policy-api.md)

## 历轮审核

列表接口支持 `page` 和 `pageSize`，默认分别为 1 和 20，每页最多 100 条，按提交轮次倒序。条目中的 `id` 为审核日志 ID，用作单轮接口的 `logId`。单轮接口返回 `log` 和该轮的 `snapshot`，并核对审核日志属于路径中的申请。

管理员详情和审核窗口已接入「审核记录」页签，列表传 `pageSize=10`。条目展示 `submissionVersion`、`submittedAt`、`auditStatus`、`auditorName`、`auditTime`、`auditComment`；「查看内容」按条目的 `id` 请求单轮接口。历史展示只使用该响应的 `snapshot` 和 `log`，不混入当前主表内容、审核权限或覆盖状态，文件下载使用该轮 `log.id`。接口权限及数据库结构沿用现有实现。

`auditStatus` 使用审核日志枚举：`SUBMITTED` 对应页面「待审核」（申请主表状态为 `PENDING`），`APPROVED` 对应「已通过」，`REJECTED` 对应「已驳回」。待审核的意见显示「待审核」；已通过无需填写意见，空值在列表显示「—」，详情隐藏该项并保留审核人和时间；已驳回且意见为空时显示「未填写审核意见」。历史非空意见继续展示，接口及保存逻辑不变。

## 覆盖预览与有效政策

预览请求携带 `recordVersion` 和 `submissionVersion`。返回比较时点 `at`、该申请最早满足生效条件的时点 `effectiveFrom`，以及每个商品和平台的结果 `items`。

| 场景 | 结果 |
| --- | --- |
| 待审核 | `NEW` 新增，`REPLACES` 覆盖现有政策，`RETAINED` 由较新的申请继续适用 |
| 已通过 | `SCHEDULED` 等待本申请开始时间，`EFFECTIVE` 当前生效，`SUPERSEDED` 已被较新申请覆盖 |

条目包含 `platformCode`、`goodsId`、`outcome` 及对照政策 `winner`。`winner.auditLogId` 是该匹配提交轮次的审核日志 ID，以字符串返回，供单轮快照与附件读取；不使用该申请另一个轮次替代。确认审核使用实际通过时间。预览按当时已通过的记录计算。

每条 `items` 增加可空 `existingSubsidy`，提供命中已通过快照中同商品同平台的补贴方案（方式、固定金额、上限、比例和基数）。无对照政策或缺少对应方案时为 null。后端按命中的日志 ID 去重后一次批量读取，已通过详情命中自身时复用本轮快照；仍按有效主表的企业、删除状态、已通过状态和日志审核状态过滤。

申请人预览通过本人申请接口读取整单适用范围和命中旧补贴。[范围与对照](../business/override-engine.md#4-申请人提交前确认覆盖范围)


`GET /api/admin/policies/effective` 必须提供 `perspective`、`platformCode`、`goodsId`。可选 `at` 格式为 `YYYY-MM-DDTHH:mm:ss+08:00`，默认当前北京时间。手写查询字符串时，时区中的 `+` 编码为 `%2B`。

响应包含查询时点 `at`、生效政策 `winner` 和该轮 `snapshot`。`winner` 包含 `id`、`policyCode`、`auditLogId`、`submissionVersion`、`submittedAt` 和 `effectiveFrom`。没有符合条件的政策时，`winner` 和 `snapshot` 均为 null。各申请按其独立开始时间、审核通过时间和本轮提交顺序解析，参考到期时间供展示。[覆盖规则](../business/override-engine.md)

## 错误

400 参数或审核决定无效，401 未登录，403 角色、租户或自审校验失败，404 申请或审核轮次不可见，409 记录版本、提交轮次或状态已经变化，503 存储不可用。
