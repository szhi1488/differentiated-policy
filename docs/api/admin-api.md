# 采购管理员审核与有效政策接口

更新日期：2026-11-23。以下八个接口已由 `PolicyReviewController` 提供。管理员从「政策管理 → 差异化政策」查看已提交的申请。接口依据会话核对账号、角色和公司租户。申请人转任管理员后，由其他管理员审核其原申请。公共约定见 [申请人接口](policy-api.md)。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/admin/policies` | 分页查看已提交的政策 |
| GET | `/api/admin/policies/{id}` | 当前提交快照、审核结果和覆盖状态 |
| GET | `/api/admin/policies/{id}/audit-logs` | 分页查看各轮审核 |
| GET | `/api/admin/policies/{id}/audit-logs/{logId}` | 指定轮次的审核结果和提交快照 |
| POST | `/api/admin/policies/{id}/review` | 通过或驳回当前待审轮次 |
| GET | `/api/admin/policies/{id}/override-preview` | 查看待审轮次假设此刻通过后的覆盖情况 |
| GET | `/api/admin/policies/effective` | 查询渠道、平台和商品在指定时点适用的政策 |
| GET | `/api/admin/policies/subsidy-timeline` | 按机型版本、RAM、ROM、日期及可选平台读取两渠道的实际补贴历史和按已通过决定计算的后续采用安排。[参数和响应](page-api-plan.md#查询补贴历史) |

## 列表与详情

`page` 默认 1，`pageSize` 默认 20，范围 1 至 100。支持 `status`（PENDING、APPROVED、REJECTED）、`perspective`、`platformCode`（整单平台）、`verifyType`、`sceneType`、`applicant`、`submittedFrom`、`submittedTo`、`keyword`。提交日期为北京时间 `YYYY-MM-DD`，含所选的首尾日期。关键词匹配单号、申请人和快照机型名。列表按本轮提交时间、提交日志 ID 倒序。`counts` 返回其他筛选条件下各状态的数量，`total` 为当前状态筛选后的行数。

详情的 `policy` 提供记录版本、提交轮次、审核状态和 `reviewable`；`snapshot` 保存本轮提交内容；`audit` 保存当前审核结果。省略 `includeCoverage` 时兼容旧客户端，待审核和已通过详情包含 `coverage`。

管理员列表、筛选、状态数量统计及详情的 `policy` 摘要统一按 `current_version` 关联最近正式提交快照，读取其中的申请人、渠道、整单平台、核销类型及场景。驳回后申请人保存编辑内容只影响本人可回填的主表，管理员继续展示和筛选原提交内容；重提事务成功后才切换到新轮次。状态、并发版本、归属和审核权限仍按有效主表判断。快照中的可空核销类型和场景返回 null，不将 JSON null 显示为字符串。接口路径及响应字段保持兼容。

查询参数 `sceneType` 仍指定一个场景。列表、总数和各状态计数均按最新提交快照是否包含该场景筛选，一份多选政策只计一条。摘要与详情使用 `sceneTypes` 展示实际选择。旧快照用 `form.sceneType` 匹配，单选仍返回兼容字段，百亿场景为空。

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

`decision` 取 `APPROVE` 或 `REJECT`，意见选填，最多 512 字。一次决定覆盖当前申请及其本轮随单提交的全部缩短、延期调整。整批驳回时沿用原政策的有效期，整批获批时在同一事务落实各项调整。服务端确定审核人及审核时间并更新主表和当前待审轮次。响应包含 `id`、`status`、`recordVersion`、`submissionVersion`、`reviewTime`。审核记录保留，驳回后可原单重提。

管理页点击「通过」发送 `APPROVE`，`reviewRemark` 为 null。服务端核验权限、状态、版本、轮次，并在机型锁内复核批准时仍有效的组合。结果相同可继续通过；组合或时间调整目标需要重新确认时返回 `POLICY_RECONFIRM_REQUIRED`，本申请已无可生效时段时返回 `POLICY_NO_EFFECTIVE_PERIOD`，两者均保留待审状态。管理员可沿用驳回，由原申请人处理。[复核与生效查询](#覆盖预览与有效政策)

## 历轮审核

列表接口支持 `page` 和 `pageSize`，默认分别为 1 和 20，每页最多 100 条，按提交轮次倒序。条目中的 `id` 为审核日志 ID，用作单轮接口的 `logId`。单轮接口返回 `log` 和该轮的 `snapshot`，并核对审核日志属于路径中的申请。

管理员详情和审核窗口已接入「审核记录」页签，列表传 `pageSize=10`。条目展示 `submissionVersion`、`submittedAt`、`auditStatus`、`auditorName`、`auditTime`、`auditComment`；「查看内容」按条目的 `id` 请求单轮接口。历史展示只使用该响应的 `snapshot` 和 `log`，不混入当前主表内容、审核权限或覆盖状态，文件下载使用该轮 `log.id`。接口权限及数据库结构沿用现有实现。

`auditStatus` 使用审核日志枚举：`SUBMITTED` 对应页面「待审核」（申请主表状态为 `PENDING`），`APPROVED` 对应「已通过」，`REJECTED` 对应「已驳回」。待审核的意见显示「待审核」；已通过无需填写意见，空值在列表显示「—」，详情隐藏该项并保留审核人和时间；已驳回且意见为空时显示「未填写审核意见」。历史非空意见继续展示，接口及保存逻辑不变。

## 覆盖预览与有效政策

发起单本轮的 `snapshot.consent.endAdjustments` 列出各目标旧单和获批轮次、原截止、新截止、逐单原因和证明文件。独立调整申请另在 `snapshot.form.goodsForm.timeAdjustment` 保存其自身调整。目标可以与本次申请完全没有交集。批准前，对本单和选中旧单涉及的机型加锁，复核每张旧单的原截止和申请人的平台分工。任何一张失去资格或依据已变化，整批保持待审，须驳回后重新确认。获批缩短后，旧单详情通过 `approvedEnd` 返回批准来源、原因和实际截止。随单延期在同一事务生成关联原单的已通过延期单，时点和时间轴按延长段已确认的政策组合展示。管理员可从发起申请的对应审核轮次下载证明文件。[整单提前结束](policy-api.md#整单提前结束) · [按发起单或旧单核对快照](../sql/database-design.md#在-navicat-核对整单提前结束记录)


待审单的 `override-preview` 携带 `recordVersion` 和 `submissionVersion`。响应包含 `applicability`（按当前假设通过时点计算的整单分段）、`consent`（本轮提交时的决定）、`requiresResubmission` 与 `nextAction`。正常审核为 `REVIEW`。已出现未确认组合或已无可生效时段时为 `REJECT_FOR_RESUBMISSION`。管理员可通过原审核接口驳回，原申请人编辑、重新确认并重提。

`APPROVE` 在机型行锁内按实际审核时刻再次判定。组合变化返回 409 `POLICY_RECONFIRM_REQUIRED`，已到期返回 409 `POLICY_NO_EFFECTIVE_PERIOD`。两种情况均保持待审，管理员可查看预览或执行驳回。只复核仍可生效的时段。零售同组新增候选也需要重新确认。旧版待审零售单已确认的时段、分组和候选与新预览一致时可继续审核。旧版拆分多场景导致分组变化时，由原申请人按新版分组重提。

`GET /api/admin/policies/effective` 必须提供 `perspective`、`platformCode`、`goodsId`。可选 `at` 使用北京时间 `YYYY-MM-DDTHH:mm:ss+08:00`，默认当前时刻。手写 URL 时将加号编码为 `%2B`。

响应的 `policies[]` 是该时点的全部适用政策。每项的 `winner` 给出单号和日志 ID，`snapshot` 是提交快照，`selections` 提供该平台及商品范围内的补贴和指定商品颜色。单一结果另填顶层 `winner` 与 `snapshot`；叠加时这两个字段为 null，查无政策时数组为空。新版零售按该平台、商品和时段内最近正式提交且已获批的区间决定，返回冻结的 `selectedPolicyIds` 对应政策，允许只含旧单。该段结束后按其他仍适用的决定计算。没有新版决定的历史时点按原规则回查。百亿二选一只在确认的配置、平台和交叉时段内生效，后续查询和后续新申请都继承该段的选择。

旧单在上线时刻起按原快照日期到期退出。查上线前的时点沿用旧单有效口径；业务上线时在各实例配置同一个 `POLICY_APPLICABILITY_CUTOVER_AT`。未配置且指定过去的时点涉及旧轮次时返回 409 `POLICY_CUTOVER_REQUIRED`，配置实际时刻后重查。[环境配置](../architecture/environment-configuration.md)

## 错误

400 参数或审核决定无效，401 未登录，403 角色、租户或自审校验失败，404 申请或审核轮次不可见，409 记录版本、提交轮次或状态已经变化，503 存储不可用。
