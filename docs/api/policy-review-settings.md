# 政策审核设置与本轮审核人

企业管理员维护本站的两组审核人员。人员来源为 `user_info` 中已核验的本地账号，保存本地用户 ID。候选查询、名单读取和保存均从会话重新核验指定企业、启用状态及当前管理员角色。响应禁止缓存。配置写入还须通过现有可信 Origin 和 `X-Policy-Request: 1` 校验。

## 管理员工作台入口

在「系统管理 → 政策审核设置」打开 `#/admin/review-settings`。分别展开产品运营和采购的「选择人员」，可同时按姓名、部门查询。候选项显示本地用户 ID，查询和翻页均保留已勾选的人员。点击已选人员旁的移除按钮可调整名单。「保存默认名单」一次提交两组及页面显示的配置版本，新版本适用于之后的首提与重提。若保存时已有其他管理员更新，可先核对当前选择，再载入最新名单。

在「政策管理 → 差异化政策」查看在途申请，点击「改派本轮成员」。初审中可调整本轮初审组或后续采购组，进入终审后仅可调整采购组。核对单号、轮次、增减人员并填写原因后保存。保留成员沿用原指派，移除成员即时失去该指派的办理权。[按轮改派契约](../architecture/policy-workflow-phase-c.md#api-与字段)

| 改派时的节点与目标 | 指派变化对应的飞书动作 |
| --- | --- |
| 初审中调整产品运营 | 新增成员各收一张私聊审核卡，移除者的原卡更新为已改派，保留者继续用原卡。 |
| 初审中调整后续采购 | 保存本轮名单与改派记录，转入终审时才给届时合格的采购逐人发私聊办理卡和待办。 |
| 终审中调整采购 | 机器人分别私聊新增和移除成员，新增成员各建一条采购待办，移除成员的原待办按指派结办。 |

## 搜索候选人员

`GET /api/admin/policy-review-settings/candidates`

| 参数 | 规则 |
| --- | --- |
| `reviewGroup` | 必填。产品运营用 `PRODUCT`，候选为本企业启用账号。采购用 `PURCHASE`，候选仅含本企业启用且当前角色为 `ADMIN` 的账号。 |
| `name`、`department` | 可选，分别匹配姓名和部门，同时填写取交集。去除首尾空白，每项最多 128 个 Unicode 字符，按字面子串匹配。 |
| `page`、`pageSize` | 默认 1 和 10，页码从 1 开始，每页 1 至 100 项。按加入时间倒序、ID 倒序分页。 |

响应示例：

```json
{"items":[{"id":"81","name":"王宁","department":"采购二部"}],"total":1}
```

同名人员分别保留各自 ID。部门可为 `null`。空结果和超出页码时 `items` 为空数组，`total` 为符合条件的人数。

## 读取与保存

`GET /api/admin/policy-review-settings` 返回 `version`、`productReviewers` 和 `purchaseReviewers`。两个数组分别包含已配置的人员，每人使用与候选接口相同的 ID、姓名及部门字段。首次读取时版本为 0、两组为空。已配置人员停用或采购被降为申请人后，设置仍可回显原选择，以便管理员修改。

读取及保存设置的响应均附带只读布尔字段 `policyChatConfigured`。服务端的 `FEISHU_POLICY_CHAT_ID` 含有效文本时返回 `true`，未填或只有空白时返回 `false`。页面收到 `false` 时提示部署人员核对该环境变量，两组人员可照常保存。群标识及部署配置由服务端维护。

`PUT /api/admin/policy-review-settings` 一次保存两组人员。下例已读版本为 3：

```json
{
  "expectedVersion": 3,
  "productReviewerUserIds": ["31", "81"],
  "purchaseReviewerUserIds": ["81", "95"]
}
```

两个数组均必传，允许传空数组。ID 为正整数字符串，同组重复 ID 自动去重。同一有效管理员可出现在两组。成功返回与 GET 相同的结构及递增的新版本。服务端固定从部署配置读取业务群，设置接口只保存人员。

| 情形 | HTTP / code | 结果 |
| --- | --- | --- |
| 无登录会话 | 401 `LOGIN_REQUIRED` | 重新登录。 |
| 操作人不是本企业有效管理员、写入来源未通过 | 403 `USER_ADMIN_REQUIRED`、`USER_SCOPE_DENIED` 或 `REQUEST_ORIGIN_DENIED` | 拒绝读取或写入。 |
| 岗位或候选筛选不合法 | 400 `USER_LIST_INVALID_REQUEST` 或 `INVALID_REQUEST` | 修改参数后再查询。 |
| 请求形状无效、所选 ID 越界、跨企业、停用或采购角色不符 | 400 `INVALID_REQUEST` 或 `POLICY_REVIEW_SETTINGS_INVALID` | 原设置及版本保留。 |
| 提交保存时配置版本已变化 | 409 `POLICY_REVIEW_SETTINGS_CONFLICT` | 重新读取后核对两组再保存。 |

## 首提及重提核定

全局名单适用于后续正式提交。在途改派调用 `POST /api/admin/policy-reviews/{id}/reassign`，传入已读的 `recordVersion`、`submissionVersion`、当前阶段 `expectedStage`、目标阶段 `targetStage`、完整的 `assigneeUserIds` 字符串数组和 1 至 512 字的 `reason`。名单实际变化时递增记录版本，轮次或版本已过期返回 409。[改派与审核并发](../architecture/policy-workflow-phase-c.md#api-与字段)

在业务事务中先锁定企业设置行，再按用户 ID 升序锁定所选账号与申请人。保存设置和核定人员共用设置行锁。读取、设置更改及后续轮次的执行关系可按版本核对。首次并发保存也经过同一企业设置行。

- 普通单只核定采购。初审单核定产品运营和采购两组。服务端逐组过滤当前企业、账号状态、岗位及申请人本人，并使用本地 ID 去重。
- 初审时若仅余一名合格采购，保留其终审资格并将其排除在初审签署人中。多名合格采购可与初审组重叠，转终审时另按实际初审签名人核对。
- 已保存设置但本轮所需岗位无人合格，或仅有同一人可签两个环节时，返回 409 `POLICY_REVIEW_ASSIGNEES_MISSING`，提示对应岗位。后续每次提交、重提均重新读取当前版本。版本 0 表示尚未配置人员。未配置人员时提交入口会提示先保存审核名单。

## 飞书投递记录与人工核对

管理员在「政策审核设置 → 飞书投递记录」按单号和提交轮次查询。初审卡、采购办理卡和退回修改卡均登记为 SEND_CARD，实际收件人按个人指派显示。审核类 GROUP_MESSAGE 仅在采购终审通过时登记。重启并加载新版后端后，新群通知的正文显示业务内容和需要点名的人员。后台继续用 `deliveryKey` 防重，管理员仍可在此查看该编号、状态及飞书回执。接口均要求本企业有效管理员会话，写入同时验证 Origin 和 `X-Policy-Request: 1`。[编号与数据库字段对应关系](../sql/database-design.md#群消息投递编号与飞书回执)

核对终审公告时，按获批轮次查看申请人、实际运营初审人（如有）和终审人。该轮确有叠加的，群公告最后一个业务区块为「【叠加政策】」，之后才是群内点名。每张原单每次获批的延期或提前结束单独登记为业务提醒，可在「查询业务提醒」按事件核对。随单改期时，发起申请的终审群公告按该申请及其获批轮次查询。[终审与改期群公告字段](../architecture/policy-workflow-phase-e.md#终审通过的群公告)

需人工验证单张初审卡时，核对本轮指派与收件人，并复制「发送私聊卡」行的完整投递编号。页面的重新入队操作由自动调度领取，指定单卡命令直接处理选定的创建登记。[内网单卡验证](../architecture/policy-workflow-phase-e.md#内网发送一张初审卡)

- `GET /api/admin/policy-review-deliveries?policyId=57&submissionVersion=2` 返回该轮按登记顺序排列的各项投递。每项含字符串 `id`、稳定投递编号 `deliveryKey`、事件、动作、指派和收件人、状态、尝试次数、回执、脱敏错误、人工处理版本 `revision` 和 `canResolve`。
- `POST /api/admin/policy-review-deliveries/{id}/resolve` 接收 `operation`、`expectedStatus`、`expectedAttempts`、`expectedRevision`、`reason` 及可选 `receiptId`。原因必填，最多 512 字。
- `CONFIRM_RECEIPT` 用于已停止自动重试的 UNKNOWN 创建动作。服务端回读外部记录，成功后保存消息 ID 或 GUID。群消息按发送应用、目标群、正文、@ 人员和首次请求时间核对。历史记录若含业务链接，按首次发送时的链接一并核验。同一消息可能属于多笔投递时返回 409，保留待核对状态。升级前发送结果未知的群消息仍可按原消息 ID 和正文编号核对。`RETRY_CONFIRMED` 用于已排除重复风险的 UNKNOWN 或 FAILED 动作，该项恢复为 PENDING，再次领取时重新核对节点及指派。
- 处理记录随该项投递保存。读写旧状态或处理版本冲突返回 409，跨企业或不存在的记录返回 404。正式环境所需的应用权限及配置见[E 阶段交付记录](../architecture/policy-workflow-phase-e.md)。

## 执行期及配套业务提醒

管理员调用 `GET /api/admin/policy-review-deliveries/notices` 查看本企业最近 50 项业务提醒，包含发给原申请人的临期私聊及其他事件的群提醒。下一页传 `before` 为上一页末项的字符串 ID。列表沿用逐项投递模型，`recipient` 标识政策及提醒类型、渠道平台或商品批次，`nextAttemptAt` 为可投递的北京时间。

临期私聊在列表显示为 `SEND_CARD`。以实际截止的北京时间日期为准，机器人在到期前两天、前一天各于 17:00 私发原申请人，正文分别写「后天到期」「明天到期」，带单号及北京时间的实际截止时刻。事件键为 `P/{policyId}/EXPIRY_REMINDER/{reminderDate}`，同一企业同一政策同一提醒日期只登记一次。平台移交后仍发给原申请人。[临期发送与补送](../architecture/policy-workflow-phase-f.md#原申请人的临期私聊)

其他业务群提醒中部分账号停用时，已送达的提醒可同时显示无法点名的账号。单项核对仍调用既有 `/{id}/resolve`，核验企业、管理员、状态、尝试次数及人工处置版本。
