# 采购管理员审核与有效政策接口

更新日期：2026-11-15。以下七个接口已由 `PolicyReviewController` 提供。管理员从「政策管理 → 差异化政策」查看已提交的申请。接口依据会话核对账号、角色和公司租户。申请人转任管理员后，由其他管理员审核其原申请。公共约定见 [申请人接口](policy-api.md)。

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

## 历轮审核

列表接口支持 `page` 和 `pageSize`，默认分别为 1 和 20，每页最多 100 条，按提交轮次倒序。条目中的 `id` 为审核日志 ID，用作单轮接口的 `logId`。单轮接口返回 `log` 和该轮的 `snapshot`，并核对审核日志属于路径中的申请。

## 覆盖预览与有效政策

预览请求携带 `recordVersion` 和 `submissionVersion`。返回比较时点 `at`、该申请最早满足生效条件的时点 `effectiveFrom`，以及每个商品和平台的结果 `items`。

| 场景 | 结果 |
| --- | --- |
| 待审核 | `NEW` 新增，`REPLACES` 覆盖现有政策，`RETAINED` 由较新的申请继续适用 |
| 已通过 | `SCHEDULED` 等待本申请开始时间，`EFFECTIVE` 当前生效，`SUPERSEDED` 已被较新申请覆盖 |

条目包含 `platformCode`、`goodsId`、`outcome` 及对照政策 `winner`。确认审核使用实际通过时间。预览按当时已通过的记录计算。

`GET /api/admin/policies/effective` 必须提供 `perspective`、`platformCode`、`goodsId`。可选 `at` 格式为 `YYYY-MM-DDTHH:mm:ss+08:00`，默认当前北京时间。手写查询字符串时，时区中的 `+` 编码为 `%2B`。

响应包含查询时点 `at`、生效政策 `winner` 和该轮 `snapshot`。`winner` 包含 `id`、`policyCode`、`submissionVersion`、`submittedAt` 和 `effectiveFrom`。没有符合条件的政策时，`winner` 和 `snapshot` 均为 null。各申请按其独立开始时间、审核通过时间和本轮提交顺序解析，参考到期时间供展示。[覆盖规则](../business/override-engine.md)

## 错误

400 参数或审核决定无效，401 未登录，403 角色、租户或自审校验失败，404 申请或审核轮次不可见，409 记录版本、提交轮次或状态已经变化，503 存储不可用。
