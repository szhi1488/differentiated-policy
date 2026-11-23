# C 阶段站内审核交付（P12–P17）

2026-11-23。申请人、产品运营和采购可以通过 ERP API 完成提交、退回、重提和两级审核。工作流沿用 B 阶段的二十二表基线和按轮切换标记。隔离测试启用 `POLICY_REVIEW_WORKFLOW_ENABLED=true`，检查同一业务事务登记的群事件、个人建待办与结办动作。工作台页面属于 D 阶段，飞书实际投递属于 E 阶段。

## 已交付的业务动作

| 编号 | 站内行为 | 验收依据 |
| --- | --- | --- |
| P12 正式提交 | 新轮冻结当时的配置、两组实际指派和逐段依据，返回首节点。普通单登记一条群通知和每名采购的终审待办。变化单在一条群通知内区分产品运营办理、采购知悉，只给产品运营建待办。 | 两组各两人的普通、叠加样例，保存草稿、相同版本并发提交和名单缺人回滚。 |
| P13 初审同意 | 当前成员首个成功决定使记录版本加一，整单仍待审，原提交轮次与快照保留。转采购时重新核对账号和岗位，实际初审签署人排除在终审之外。 | 同意与同意并发只产生一个初审签名。资格变化后仍有合格采购则转交，否则返回 409，供管理员改派。 |
| P14 初审退回与重提 | 退回原因去掉首尾空白后必填，最多 512 字。退回写本轮审核结果，结办全组任务，登记给原申请人的群提醒与一条修改待办。原单重提成功时结办旧修改任务，重新分流并冻结当时名单。 | 退回原因边界、同意与退回竞办，同单号第二轮由新名单审签，旧轮链接只读。 |
| P15 采购终审 | 原采购接口在锁内核验双版本、当前指派、剩余区间与整批时间调整。首个成功决定结束该轮，记录唯一签名并结办全组。驳回意见选填，退回给原申请人。 | 两名采购并发批准含两张延期的申请。逐一核对子单、批准通知和任务结办次数。复核失败保留待审和办理机会，通知登记故障时回滚业务结果。 |
| P16 个人待审与按轮详情 | 当前待我审核列表与写入使用相同的成员办理条件。详情返回该轮冻结的表单、分流、成员、实际决定和当前操作。附件另核对同一轮次快照的文件引用。 | 现任、同组已办、被移除、仅知悉、原申请人、未选中管理员、被转发人、停用和跨企业账号。 |
| P17 改派 | 管理员指定当前阶段、目标阶段和完整的新名单。同组保留者沿用指派与待办，移除者结办，新增当前成员各建待办。初审期间改未来采购时只登记群内知悉变更。 | 一组换一人、单独增减、同人移除再加入、名单无变化、旧版本和改派与首个决定并发。 |

## API 与字段

会话和写入校验沿用项目的 HttpSession、可信 Origin 和 `X-Policy-Request: 1`。读接口按实时公司账号授权，路径或群消息的持有人仍须有该轮阅读权限。ID 对外使用字符串。

| 方法与路径 | 请求与返回 |
| --- | --- |
| `POST /api/policies/{id}/submit` | 沿用原提交契约。启用新流程的轮次追加 `reviewStage`。E 阶段补充的条件为直接提交进 `PURCHASE_REVIEW`，需要确认整单的申请进 `PRODUCT_REVIEW`。[提交参数](../api/policy-api.md#正式提交) |
| `GET /api/policy-reviews/mine` | `page` 从 1 开始，`pageSize` 默认 20，上限 100。分页返回本人当前可办理的指派，含 `id`、`policyCode`、`submissionVersion`、`recordVersion`、`reviewStage`、分流短句 `summary` 和北京时间 `submittedAt`。旧流程采购仍可使用管理员政策列表。 |
| `GET /api/policy-reviews/{id}?submissionVersion=2` | 轮次必填。返回所查轮的 `status`、`snapshot`、`settingsVersion`、`changeSummary`、`changes` 和 `adjustments`。另返回该单最新的 `currentSubmissionVersion` 与 `recordVersion`，供客户端识别旧链接。 |
| `GET /api/policy-reviews/{id}/attachments/{attachmentId}/content?submissionVersion=2` | 核验与详情相同的轮次阅读权，文件须被该轮表单、独立调整或随单调整引用，磁盘元数据须属于本企业。成功返回原文件字节。[附件契约](../api/attachment-api.md#政策绑定与历史保护) |
| `POST /api/policy-reviews/{id}/product-review` | 与采购共用请求字段 `submissionVersion`、`recordVersion`、`decision` 和 `reviewRemark`。同意传 `APPROVE`，退回传 `REJECT` 并填原因。同意返回 `PENDING` 和 `PURCHASE_REVIEW`，退回返回 `REJECTED`。 |
| `POST /api/admin/policies/{id}/review` | 继续使用原采购请求。批准或驳回后返回整单终态、双版本与决定时间。[采购复核及 409](../api/admin-api.md#审核) |
| `POST /api/admin/policy-reviews/{id}/reassign` | 本企业有效管理员提供双版本、`expectedStage`、`targetStage`、完整 `assigneeUserIds` 字符串数组和 1 至 512 字的 `reason`。名单有变化时返回新的记录版本。不改变名单时返回当前版本。 |

详情按 `productReviewers` 与 `purchaseReviewers` 返回历次指派，包含稳定的 `userId`、`assignmentId`、`assignmentNo`、指派时姓名部门和 `memberStatus`。状态可以是 `ACTIVE`、`REMOVED`、`HANDLED` 或 `PEER_HANDLED`。未来采购成员可有有效指派，其待办在实际转采购时登记。只有实际签署的指派带 `decision`、`reason` 和 `decidedAt`。终态的 `reviewStage` 为空，驳回轮由 `rejectedAtStage` 区分初审与终审。

新提交轮次的 `changes[].candidates` 是该交叉段在申请人确认时可选的政策，`before` 和 `after` 分别是原执行名单与本轮拟执行名单。从多张候选中只选原单时，`kind` 为 `UNCHANGED`，该段仍计入 `triggerSegments`。普通无交叉段及历史上未冻结候选的轮次返回空候选数组。查阅历史轮次直接使用当时保存的分流结论。

仅随单调整需要确认时，`changeSummary.requiresProductReview` 为 `true`，`triggerSegments` 可以是 0。新轮的 `adjustments[].accompanying` 表示该项来自随本轮确认的其他政策时间调整，原单号及新旧截止随项返回。独立调整从其表单读取，同一目标同时保存在提交快照时只列一项。

原申请人、本企业有效管理员可按既有范围查阅。其他有效账号凭该轮未移除的产品运营指派读取，包括本人已办和同组他人已办。已移除成员如另具管理员身份，仍保有管理员阅读权。`allowedActions` 对当前可办者返回 `APPROVE`、`REJECT`，其他情况返回空数组。提交决定时再次鉴权并处理并发或复核冲突。

参数不合法返回 400。无登录会话返回 401。账号失效或无办理权返回 403。政策或轮次不可见、文件不在请求轮次内返回 404。版本或阶段过时、缺合格采购及终审依据变化返回 409。响应未知时按原 ID、原轮次读取详情，可核对实际签署人和结果。

## 隔离验证与部署边界

Spring Boot、MockMvc、H2（MySQL 模式）及临时附件目录覆盖上述 API。贯通案例从统一名单和实际草稿开始，按服务端预览确认叠加，走完初审退回、改名单、原单重提、初审同意及采购批准。核对首轮和新轮快照、个人办理权、唯一签名和各指派的投递登记。

2026-11-23 在 `backend/` 执行 `mvn verify`，309 项测试中 307 项通过，0 失败，0 错误，2 项外部环境测试按配置跳过（真实 MySQL 用户验证、吉客云商品实查）。Spring Boot 打包和 Spotless 检查通过。与本站内流程直接相关的四个测试类共 27 项全部通过。并发批准两张随单延期时，一个请求成功，另一个返回 409，每张原单只生成一张已通过延期子单，父单只登记一条批准群通知。

[提交与贯通用例](../../backend/src/test/java/com/company/policy/service/PolicyReviewSubmissionIntegrationTest.java) · [初审与采购竞办](../../backend/src/test/java/com/company/policy/controller/PolicyPurchaseReviewTest.java) · [个人列表、详情及轮次附件](../../backend/src/test/java/com/company/policy/controller/PolicyReviewInboxTest.java) · [改派及竞办](../../backend/src/test/java/com/company/policy/controller/PolicyReviewReassignmentTest.java)

目标 MySQL 升级与实际行锁仍待在指定环境核对。业务开关默认关闭，已创建的轮次依据自己的 `workflow_version` 办理。真实群点名、待办回执和客户端回跳按 D、E 阶段及测试企业联调计划验收。[B 阶段切换条件](policy-workflow-phase-b.md#切换与运行边界)
