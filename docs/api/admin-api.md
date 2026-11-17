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

详情的 `policy` 提供记录版本、提交轮次、审核状态和 `reviewable`；`snapshot` 保存本轮提交内容；`audit` 保存当前审核结果。待审核和已通过详情包含 `coverage`。

管理员列表、筛选、状态数量统计及详情的 `policy` 摘要统一按 `current_version` 关联最近正式提交快照，读取其中的申请人、渠道、整单平台、核销类型及场景。驳回后申请人保存编辑内容只影响本人可回填的主表，管理员继续展示和筛选原提交内容；重提事务成功后才切换到新轮次。状态、并发版本、归属和审核权限仍按有效主表判断。快照中的可空核销类型和场景返回 null，不将 JSON null 显示为字符串。接口路径及响应字段保持兼容。

目标库只读核对脚本为 [verify_admin_submission_view.sql](../sql/verify_admin_submission_view.sql)：已有最新十四表及正式提交数据后，在 Navicat 填写申请 ID、企业标识及筛选参数，依次运行主表/快照对照、状态计数和 `EXPLAIN`。驳回修改保存后按旧提交条件应命中，重提成功后切换到新条件。脚本不变更结构或业务数据；目标库结果和执行计划待用户核对。

文件附件下载由 `PolicyAttachmentController` 提供 `GET /api/admin/policies/{id}/audit-logs/{logId}/attachments/{attachmentId}/content`。详情页面使用 `audit.id` 和快照中的文件 ID 生成下载入口，服务端校验管理员身份、申请权限及该文件属于指定提交轮次。字段和删除保护见[附件接口](attachment-api.md)。

「差异化政策」页面默认传 `pageSize=10`。查看和审核共用详情接口，从响应的 `coverage` 展示预览或生效范围。普通机型的已选颜色、指定机型的平台及数量均从该轮快照读取。单独的覆盖预览、有效政策查询和历轮审核接口可独立调用。[管理员工作台](../architecture/admin-workspace.md#差异化政策)

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

管理页点击「通过」时再次调用详情接口，核对同一申请的 `recordVersion`、`submissionVersion`、待审状态及 `reviewable`。核对成功才允许最终确认；覆盖范围、结果、命中政策轮次或旧补贴变化时，展示最新预览并要求管理员勾选已核对。仅查询时点推进、条目排序或金额尾零不同不提示业务变化。点击「确认通过」才发送审核请求，`reviewRemark` 为 null。读取失败、状态冲突或写入结果未知时不自动重发。

本次仅增加前端批准复核及确认入口，不增加 API 字段或覆盖锁；其他申请仍可在复核与最终提交之间通过。最终适用结果继续按实际审核时间和原覆盖算法计算。

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

前端按全部 `items` 统计商品平台范围，并支持结果、平台、机型/配置/颜色/商品 ID 筛选。普通选品先按同机型版本、配置和平台汇总，混合结果显示「部分覆盖」及分项数量；展开查看逐商品结果、补贴及对照入口。新旧补贴或对照轮次不一致不再拆行，仅禁用整组对照、保留逐项对照。指定卡保持单品，规格证据缺失或不匹配时不合并，缺少旧补贴仍可汇总。先筛选实际商品后汇总，每页 10 组，数量和汇总结果对应当前筛选范围且仅包含本申请已选商品。此为前端展示调整，接口及同渠道、同平台、同商品的覆盖判断不变。

「对比补贴」或对照单号按需调用 `GET /api/admin/policies/{winner.id}/audit-logs/{winner.auditLogId}`，核对返回轮次与 `winner.submissionVersion` 后，从快照中读取同商品同平台的方案。合并行再次校验所有成员的旧补贴一致后才展示共同差异。请求失败可重试，关闭或切换时取消旧读取；完整快照仍按需读取。对照弹窗只读且保留原审核上下文。

覆盖展示增强只增加 `winner.auditLogId` 和 `items[].existingSubsidy` 响应字段，原接口、覆盖算法和数据库结构不变。旧前端可忽略新字段；部署时先更新后端再更新前端，新前端遇缺少轮次 ID 时提示刷新，缺少旧补贴时保留单品，不退回读取当前申请内容。

`GET /api/admin/policies/effective` 必须提供 `perspective`、`platformCode`、`goodsId`。可选 `at` 格式为 `YYYY-MM-DDTHH:mm:ss+08:00`，默认当前北京时间。手写查询字符串时，时区中的 `+` 编码为 `%2B`。

响应包含查询时点 `at`、生效政策 `winner` 和该轮 `snapshot`。`winner` 包含 `id`、`policyCode`、`auditLogId`、`submissionVersion`、`submittedAt` 和 `effectiveFrom`。没有符合条件的政策时，`winner` 和 `snapshot` 均为 null。各申请按其独立开始时间、审核通过时间和本轮提交顺序解析，参考到期时间供展示。[覆盖规则](../business/override-engine.md)

## 错误

400 参数或审核决定无效，401 未登录，403 角色、租户或自审校验失败，404 申请或审核轮次不可见，409 记录版本、提交轮次或状态已经变化，503 存储不可用。
