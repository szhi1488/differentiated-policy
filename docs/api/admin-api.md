# 采购管理员审核与有效政策接口

更新日期 2026-11-25。以下八个接口已由 `PolicyReviewController` 提供。管理员从「政策管理 → 差异化政策」查看已提交的申请。接口依据会话核对账号、角色和公司租户。申请人转任管理员后，由其他管理员审核其原申请。公共约定见 [申请人接口](policy-api.md)。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/admin/policies` | 分页查看已提交的政策 |
| GET | `/api/admin/policies/{id}` | 当前提交快照、审核结果及已批准的截止调整 |
| GET | `/api/admin/policies/{id}/audit-logs` | 分页查看各轮审核 |
| GET | `/api/admin/policies/{id}/audit-logs/{logId}` | 指定轮次的审核结果和提交快照 |
| POST | `/api/admin/policies/{id}/review` | 通过或驳回当前待审轮次 |
| GET | `/api/admin/policies/{id}/override-preview` | 查看待审轮次假设此刻通过后的覆盖情况 |
| GET | `/api/admin/policies/effective` | 查询渠道、平台和商品在指定时点适用的政策 |
| GET | `/api/admin/policies/subsidy-timeline` | 按机型版本、RAM、ROM、日期及可选平台读取两渠道的实际补贴历史和按已通过决定计算的后续采用安排。[参数和响应](page-api-plan.md#查询补贴历史) |

## 列表与详情

需要查看某轮初审和终审人员、办理情况、退回来源及允许操作时，读取 `GET /api/policy-reviews/{id}?submissionVersion=轮次`。逐段变化以该轮冻结的 `before`、`after` 和 `candidates` 为依据。有完整候选时可对照申请人已选和未选的政策。某轮只记录前后名单时，按已记录的内容展示。初审同意转采购时主表仍为 `PENDING`。改派与终审都校验轮次和记录版本。[站内节点接口](../architecture/policy-workflow-phase-c.md#api-与字段)

`page` 默认 1，`pageSize` 默认 20，范围 1 至 100。支持 `status`（PENDING、APPROVED、REJECTED）、`perspective`、`platformCode`（整单平台）、`verifyType`、`sceneType`、`applicant`、`submittedFrom`、`submittedTo`、`keyword`。提交日期为北京时间 `YYYY-MM-DD`，含所选的首尾日期。关键词匹配单号、申请人和快照机型名。列表按本轮提交时间、提交日志 ID 倒序。`counts` 返回其他筛选条件下各状态的数量，`total` 为当前状态筛选后的行数。

详情的 `policy` 提供记录版本、提交轮次、审核状态和 `reviewable`，`snapshot` 保存本轮提交内容，`audit` 保存当前审核结果。`timeHistory` 返回本张原单首次获批截止、当前截止及历次调整。

列表可按 `reviewStage=PRODUCT_REVIEW` 或 `PURCHASE_REVIEW` 查询当前待审环节，状态计数与分页使用相同条件。终态的阶段为空。列表及详情摘要返回 `reviewStage`、`productReviewerName` 和 `productReviewedAt`。初审姓名和北京时间来自该轮实际签署的指派与决定，尚无决定时两项均为空。

管理员列表、筛选、状态数量统计及详情的 `policy` 摘要统一按 `current_version` 关联最近正式提交快照，读取其中的申请人、渠道、整单平台、核销类型及场景。驳回后申请人保存编辑内容只影响本人可回填的主表，管理员继续展示和筛选原提交内容；重提事务成功后才切换到新轮次。状态、并发版本、归属和审核权限仍按有效主表判断。快照中的可空核销类型和场景返回 null，不将 JSON null 显示为字符串。

查询参数 `sceneType` 仍指定一个场景。列表、总数和各状态计数均按最新提交快照是否包含该场景筛选，一份多选政策只计一条。摘要与详情使用 `sceneTypes` 展示实际选择。百亿场景为空。

目标库已具备政策三表和正式提交数据后，在 Navicat 对照主表与最近提交快照，核对筛选结果、状态计数及查询计划。驳回修改保存后按旧提交条件应命中，重提成功后切换到新条件。目标库结果和执行计划待用户核对。

文件附件下载由 `PolicyAttachmentController` 提供 `GET /api/admin/policies/{id}/audit-logs/{logId}/attachments/{attachmentId}/content`。详情页面使用 `audit.id` 和快照中的文件 ID 生成下载入口，服务端校验管理员身份、申请权限及该文件属于指定提交轮次。字段和删除保护见[附件接口](attachment-api.md)。

「差异化政策」页面默认传 `pageSize=10`。查看和审核先从政策详情接口读取单号、商品、颜色、平台、数量、补贴、备注和附件，再独立读取该轮审核成员。打开历史记录时也按所选轮次读取。成员请求失败时，已读到的政策内容继续显示，用户可在成员区域重试。当前申请、提交轮次或记录版本发生变化时重新读取政策。只有当前轮、采购资格及允许操作均核对通过后才显示终审按钮。[管理员工作台](../architecture/admin-workspace.md#差异化政策)

## 审核

办理人须为本企业有效管理员和本轮当前采购节点的有效指派成员，且不能自审；需要初审的轮次须已由其他人完成产品运营同意。列表及详情的 `reviewable` 和直接调用通过、驳回使用同一资格。缺节点、未指派或已移出返回 403 `POLICY_REVIEW_FORBIDDEN`；已失效的记录或轮次版本返回 409。新轮终审成功在同一事务记录实际签署人、节点决定及同组其余任务的「同组他人已处理」。[本轮指派和采购门控](../architecture/policy-workflow-phase-b.md)

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

工作台收到 409 后重新读取当前列表和详情。如果同组采购已经办理，详情显示实际签署结果并关闭终审入口。如果本轮仍可办理，保留已输入的采购意见，由经办人核对后重新决定。

## 历轮审核

列表接口支持 `page` 和 `pageSize`，默认分别为 1 和 20，每页最多 100 条，按提交轮次倒序。条目中的 `id` 为审核日志 ID，用作单轮接口的 `logId`。单轮接口返回 `log` 和该轮的 `snapshot`，并核对审核日志属于路径中的申请。

管理员「差异化政策」的查看与审核窗口依次显示「当前申请」「时间调整记录」「审核记录」。本轮提出的随单调整可在「当前申请」查看，目标原单的每次获批改期可在「时间调整记录」查看。进入「审核记录」才以 `pageSize=10` 分页。条目展示提交轮次、时间、结果、审核人、审核时间和意见。「查看内容」按该条目的 `id` 读取快照，附件下载使用对应的 `log.id`。工作台按同一轮次读取产品运营及采购成员、实际签署人和结办状态，历史详情为只读。原单详情提供首次及当前截止和独立的调整历史。

`auditStatus` 使用审核日志枚举：`SUBMITTED` 对应页面「待审核」（申请主表状态为 `PENDING`），`APPROVED` 对应「已通过」，`REJECTED` 对应「已驳回」。待审核的意见显示「待审核」；已通过无需填写意见，空值在列表显示「—」，详情隐藏该项并保留审核人和时间；已驳回且意见为空时显示「未填写审核意见」。历史非空意见继续展示，接口及保存逻辑不变。

## 覆盖预览与有效政策

发起单本轮的 `snapshot.consent.endAdjustments` 列出各目标旧单和获批轮次、原截止、新截止、逐单原因和证明文件。独立调整直接在目标原单登记流水。目标可以与本次申请完全没有交集。批准前，对本单和选中旧单涉及的机型加锁，复核每张旧单的原截止和申请人的平台分工。任何一张失去资格或依据已变化，整批保持待审，须驳回后重新确认。生效时原单截止、独立流水和群通知登记同时写入。原单详情 `timeHistory` 分别列出每次办理的原因、前后截止和证明。执行查询按原单号和各时段真实采用决定重放。[整单提前结束](policy-api.md#整单提前结束) · [按发起单或旧单核对快照](../sql/database-design.md#在-navicat-核对整单提前结束记录)


待审单的 `override-preview` 携带 `recordVersion` 和 `submissionVersion`。响应包含 `applicability`（按当前假设通过时点计算的整单分段）、`consent`（本轮提交时的决定）、`requiresResubmission` 与 `nextAction`。正常审核为 `REVIEW`。已出现未确认组合或已无可生效时段时为 `REJECT_FOR_RESUBMISSION`。管理员可通过原审核接口驳回，原申请人编辑、重新确认并重提。

`APPROVE` 在机型行锁内按实际审核时刻再次判定。组合变化返回 409 `POLICY_RECONFIRM_REQUIRED`，已到期返回 409 `POLICY_NO_EFFECTIVE_PERIOD`。两种情况均保持待审，管理员可查看预览或执行驳回。只复核仍可生效的时段。零售同组新增候选也需要重新确认。

`GET /api/admin/policies/effective` 必须提供 `perspective`、`platformCode`、`goodsId`。可选 `at` 使用北京时间 `YYYY-MM-DDTHH:mm:ss+08:00`，默认当前时刻。手写 URL 时将加号编码为 `%2B`。

响应的 `policies[]` 是该时点的全部适用政策。每项的 `winner` 给出单号和日志 ID，`snapshot` 是提交快照，`selections` 提供该平台及商品范围内的补贴和指定商品颜色。查无政策时返回空数组。零售按该平台、商品和时段内最近正式提交且已获批的区间决定，返回冻结的 `selectedPolicyIds` 对应政策，允许只含旧单。该段结束后按其他仍适用的决定计算。百亿二选一只在确认的配置、平台和交叉时段内生效，后续查询和后续新申请都继承该段的选择。

每张政策均以原单号按实际有效区间查询。

## 错误

400 参数或审核决定无效，401 未登录，403 角色、租户或自审校验失败，404 申请或审核轮次不可见，409 记录版本、提交轮次或状态已经变化，503 存储不可用。
