# 政策审核设置与本轮审核人

企业管理员维护本站的两组审核人员。人员来源为 `user_info` 中已核验的本地账号，保存本地用户 ID。三个 HTTP 接口均从会话重新核验指定企业、启用状态及当前管理员角色。响应禁止缓存。配置写入还须通过现有可信 Origin 和 `X-Policy-Request: 1` 校验。

## 管理员工作台入口

在「系统管理 → 政策审核设置」打开 `#/admin/review-settings`。分别展开产品运营和采购的「选择人员」，可同时按姓名、部门查询。候选项显示本地用户 ID，查询和翻页均保留已勾选的人员。点击已选人员旁的移除按钮可调整名单。「保存默认名单」一次提交两组及页面显示的配置版本，新版本适用于之后的首提与重提。若保存时已有其他管理员更新，可先核对当前选择，再载入最新名单。

在「政策管理 → 差异化政策」查看在途申请，点击「改派本轮成员」。初审中可调整本轮初审组或后续终审组，进入终审后仅可调整本轮终审组。核对单号、轮次、所增减人员并填写原因后保存。保留成员沿用原指派，服务端按实际增减登记任务变化。旧流程及已结束的轮次没有改派入口。[按轮改派契约](../architecture/policy-workflow-phase-c.md)

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

读取及保存设置的响应均附带只读布尔字段 `policyChatConfigured`。服务端的 `FEISHU_POLICY_CHAT_ID` 含有效文本时返回 `true`，未填或只有空白时返回 `false`。页面收到 `false` 时提示部署人员核对该环境变量，两组人员可照常保存。旧版响应省略此字段时，页面沿用原有名单界面。群标识及部署配置由服务端维护。

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

全局名单适用于后续正式提交。在途节点改派使用 `POST /api/admin/policy-reviews/{id}/reassign`，请求传入已读的主单记录版本、提交轮次、阶段和目标组的新名单。[改派与逐人待办](../architecture/policy-workflow-phase-c.md)

在业务事务中先锁定企业设置行，再按用户 ID 升序锁定所选账号与申请人。保存设置和核定人员共用设置行锁。读取、设置更改及后续轮次的执行关系可按版本核对。首次并发保存也经过同一企业设置行。

- 普通单只核定采购。初审单核定产品运营和采购两组。服务端逐组过滤当前企业、账号状态、岗位及申请人本人，并使用本地 ID 去重。
- 初审时若仅余一名合格采购，保留其终审资格并将其排除在初审签署人中。多名合格采购可与初审组重叠，转终审时另按实际初审签名人核对。
- 已保存设置但本轮所需岗位无人合格，或仅有同一人可签两个环节时，返回 409 `POLICY_REVIEW_ASSIGNEES_MISSING`，提示对应岗位。后续每次提交、重提均重新读取当前版本。版本 0 表示尚未配置人员；流程启用后的未配置错误由提交入口处理。
