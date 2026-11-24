# C 阶段站内审核交付（P12–P17）

2026-11-23 完成 P12–P17。隔离环境通过 ERP API 跑通提交、初审退回、原单重提、初审同意和采购批准，并核对同一事务内的节点决定、指派和通知登记。C 阶段沿用 B 阶段的二十二表基线。2026-11-24 按现行分流和飞书投递规则更新下表，原验收数据保留在文末。[D 阶段工作台](policy-workflow-phase-d.md) · [E 阶段各节点的收件人与回执](policy-workflow-phase-e.md#各节点的收件人与回执)

## 已交付的业务动作

| 编号 | ERP 业务行为 | 验收依据 |
| --- | --- | --- |
| P12 正式提交 | 首提和重提按服务端核验的确认段、保留的随单截止调整分流，冻结本轮所需人员、配置版本和依据。两者均无时，登记一条采购群通知和每名采购的终审待办。有任一项时，为每名在办产品运营登记一张私聊卡，群结果在初审决定提交成功后登记。 | 两组各两人的直接采购、需初审样例，草稿、并发重复提交及名单缺人回滚。 |
| P13 初审同意 | 首个成功决定使记录版本加一，整单仍待审，提交轮次与快照保留。转采购时复核账号和岗位，排除实际初审签署人。初审卡更新为只读，群结果点名仍在办的合格采购，每名采购各有一条终审待办。 | 同意与同意并发只产生一个初审签名。资格变化后仍有合格采购则转交，否则返回 409，供管理员改派。 |
| P14 初审退回与重提 | 退回原因去掉首尾空白后必填，最多 512 字。退回写入本轮结果，初审卡分别展示实际办理结果或「同组他人已处理」，群内点名原申请人并为其登记一条修改待办。原单重提成功时结办旧修改待办，重新分流并冻结当时名单。 | 退回原因边界、同意与退回竞办，同单号第二轮由新名单审签，旧轮链接只读。 |
| P15 采购终审 | 原采购接口在锁内核验双版本、当前指派、剩余区间与整批时间调整。首个成功决定结束该轮，记录唯一签名并结办全组。驳回意见选填，退回给原申请人。 | 两名采购并发批准含两张延期的申请。逐一核对子单、批准通知和任务结办次数。复核失败保留待审和办理机会，通知登记故障时回滚业务结果。 |
| P16 按轮详情及附件 | 飞书初审卡根据本轮有效指派办理。详情返回该轮冻结的表单、分流、成员、实际决定和当前操作。附件另核对同一轮次快照的文件引用。 | 现任、同组已办、被移除、仅知悉、原申请人、未选中管理员、被转发人、停用和跨企业账号。 |
| P17 改派 | 管理员按双版本提交当前阶段、目标阶段、新名单和原因。初审组保留者沿用原卡，移除者的卡更新为已改派，新增者各收一张卡。初审期间调整未来采购只更新该轮指派及改派记录，转终审才建采购待办。终审期间改派登记群提醒，保留原有成员的任务，仅给新增者建待办、给移除者结办。 | 一组换一人、单独增减、同人移除再加入、名单无变化、旧版本和改派与首个决定并发。 |

## API 与字段

会话和写入校验沿用项目的 HttpSession、可信 Origin 和 `X-Policy-Request: 1`。读接口按实时公司账号授权，路径或群消息的持有人仍须有该轮阅读权限。ID 对外使用字符串。

| 方法与路径 | 请求与返回 |
| --- | --- |
| `POST /api/policies/{id}/submit` | 沿用原提交契约。成功提交的轮次返回 `reviewStage`。服务端核验可直接提交时返回 `PURCHASE_REVIEW`，需确认整单时返回 `PRODUCT_REVIEW`。[提交参数](../api/policy-api.md#正式提交) |
| `GET /api/policy-reviews/{id}?submissionVersion=2` | 轮次必填。返回所查轮的 `status`、`snapshot`、`settingsVersion`、`changeSummary`、`changes` 和 `adjustments`。另返回该单最新的 `currentSubmissionVersion` 与 `recordVersion`，供客户端识别旧链接。 |
| `GET /api/policy-reviews/{id}/attachments/{attachmentId}/content?submissionVersion=2` | 核验与详情相同的轮次阅读权，文件须被该轮表单、独立调整或随单调整引用，磁盘元数据须属于本企业。成功返回原文件字节。[附件契约](../api/attachment-api.md#政策绑定与历史保护) |
| 飞书 `card.action.trigger` | 「通过」将整单保留为 `PENDING` 并转 `PURCHASE_REVIEW`。退回须有 1 至 512 字原因，整单变为 `REJECTED`。 |
| `POST /api/admin/policies/{id}/review` | 继续使用原采购请求。批准或驳回后返回整单终态、双版本与决定时间。[采购复核及 409](../api/admin-api.md#审核) |
| `POST /api/admin/policy-reviews/{id}/reassign` | 本企业有效管理员提供双版本、`expectedStage`、`targetStage`、完整 `assigneeUserIds` 字符串数组和 1 至 512 字的 `reason`。名单有变化时返回新的记录版本。不改变名单时返回当前版本。 |

详情按 `productReviewers` 与 `purchaseReviewers` 返回历次指派，包含稳定的 `userId`、`assignmentId`、`assignmentNo`、指派时姓名部门和 `memberStatus`。状态可以是 `ACTIVE`、`REMOVED`、`HANDLED` 或 `PEER_HANDLED`。未来采购成员可有有效指派，其待办在实际转采购时登记。只有实际签署的指派带 `decision`、`reason` 和 `decidedAt`。终态的 `reviewStage` 为空，驳回轮由 `rejectedAtStage` 区分初审与终审。

本轮 `changes[].candidates` 是该交叉段在申请人确认时可选的政策，`before` 和 `after` 分别是原执行名单与本轮拟执行名单。从多张候选中只选原单时，`kind` 为 `UNCHANGED`，该段仍计入 `triggerSegments`。查阅历轮依据时，服务端读取指定轮次冻结的快照和分流结论。

仅随单调整需要确认时，`changeSummary.requiresProductReview` 为 `true`，`triggerSegments` 可以是 0。新轮的 `adjustments[].accompanying` 表示该项来自随本轮确认的其他政策时间调整，原单号及新旧截止随项返回。独立调整从其表单读取，同一目标同时保存在提交快照时只列一项。

原申请人、本企业有效管理员可按既有范围查阅。其他有效账号凭该轮未移除的产品运营指派读取，包括本人已办和同组他人已办。已移除成员如另具管理员身份，仍保有管理员阅读权。`allowedActions` 对当前可办者返回 `APPROVE`、`REJECT`，其他情况返回空数组。提交决定时再次鉴权并处理并发或复核冲突。

参数不合法返回 400。无登录会话返回 401。账号失效或无办理权返回 403。政策或轮次不可见、文件不在请求轮次内返回 404。版本或阶段过时、缺合格采购及终审依据变化返回 409。响应未知时按原 ID、原轮次读取详情，可核对实际签署人和结果。

运营初审由飞书回调传入按钮动作。ERP 用点击人和消息回执匹配本轮有效指派，再调用初审事务。卡上退回原因和管理员改派原因去除首尾空白后须为 1 至 512 字。采购终审 HTTP 请求的 `recordVersion`、`submissionVersion` 均须为 JSON 正整数，`reviewRemark` 接受字符串或 null。参数校验失败保留办理机会。[卡上审核与回执](policy-workflow-phase-e.md#各节点的收件人与回执)

## 隔离验证与部署边界

2026-11-23 的 C 阶段验收使用 Spring Boot、MockMvc、H2（MySQL 模式）及临时附件目录。贯通案例从统一名单和实际草稿开始，按服务端预览确认叠加，走完初审退回、改名单、原单重提、初审同意及采购批准。旧轮只读，重提成功结办原修改待办。改派后重放已登记的初审决定，保留首次登记的收件人，后来加入的采购只通过改派获得一条任务。

当日的需初审提交样例核对了一条群内知悉登记和两条运营待办登记。11 月 24 日 E 阶段的同类样例改为核对两名运营各一张私聊卡、决定前零条群结果、决定后一条群结果。[现行投递验收](policy-workflow-phase-e.md#本地验收)

2026-11-23 在 `backend/` 执行 `mvn verify`，309 项测试中 307 项通过，0 失败，0 错误，2 项外部环境测试按配置跳过（真实 MySQL 用户验证、吉客云商品实查）。Spring Boot 打包和 Spotless 检查通过。与本站内流程直接相关的四个测试类共 27 项全部通过。并发批准两张随单延期时，一个请求成功，另一个返回 409，每张原单只生成一张已通过延期子单，父单只登记一条批准群通知。

[提交与贯通用例](../../backend/src/test/java/com/company/policy/service/PolicyReviewSubmissionIntegrationTest.java) · [初审与采购竞办](../../backend/src/test/java/com/company/policy/controller/PolicyPurchaseReviewTest.java) · [按轮详情及轮次附件](../../backend/src/test/java/com/company/policy/controller/PolicyReviewRoundTest.java) · [改派及竞办](../../backend/src/test/java/com/company/policy/controller/PolicyReviewReassignmentTest.java)

每个提交轮次按冻结的节点和指派办理。自动投递默认关闭。D 阶段已在工作台接入按轮回跳，E 阶段已用模拟飞书服务验证发卡、回调、群通知及待办回执。目标 MySQL 行锁、真实企业收件与手机回跳仍待 P32 联调。[B 阶段切换条件](policy-workflow-phase-b.md#切换与运行边界) · [E 阶段目标库及环境配置](policy-workflow-phase-e.md#环境配置与目标库)
