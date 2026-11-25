# 申请人政策接口设计

两种商品卡均按实际选中的 `goodsId` 提交，`spuId + ram + rom` 用于配置确认和筛选。服务端据此生成商品快照。

申请页已接入在售目录。「选择适用商品」中的「选择手机型号」每次确认按整组配置新建一张普通卡，普通卡可通过「编辑」追加配置。「指定适用机型」每次选一个具体商品并单独成卡，数量按平台选填。政策接口按已确认三表设计。[动态表单与实单](../business/perspective-rules.md)

更新日期 2026-11-24。已实现本人负责平台查询、草稿创建、继续保存、提交前覆盖确认、正式提交，以及本人分页列表、当前详情和驳回原因。后台保留历轮审核和单轮提交快照接口，申请人页面不提供历史入口。

## 公共约定

- 前缀 `/api/policies`，登录态使用 `POLICY_SESSION` Cookie，不接收客户端自报身份。写请求须可信 `Origin`、`X-Policy-Request: 1`、`Content-Type: application/json`。
- JSON 使用 camelCase，数据库 snake_case，由 DTO/VO 显式映射。BIGINT ID 用字符串；版本用整数；金额和比例用十进制字符串，空值用 JSON `null`，禁止浮点计算金额。
- 政策起止时间使用 `2026-11-10T09:00:00+08:00`，必须含时分秒及 `+08:00`，服务端按 `Asia/Shanghai` 转 `DATETIME`。既有认证 `expiresAt` 仍使用源码的 Instant 格式。
- 成功响应直接返回 VO，与现有认证接口风格一致；失败 `{ "code": "稳定代码", "message": "中文提示" }`。业务错误见下表。
- 本人列表、详情、日志和写入均由后端按会话用户及租户限制。主表 `is_deleted=0`，明细和日志经主表鉴权，身份字段由会话和服务端确定。

## 接口一览

选品抽屉共用 `GET /api/catalog/options` 和 `GET /api/catalog/goods`。商品选择及分页约定见 [申请人目录](catalog-api.md#申请人目录)。

申请地址切换已在前端加载详情前确认未保存内容，取消时不请求目标详情并保留原地址和本地输入；Hash 事件不关闭确认框。普通卡取消配置按完整卡片暂存已有商品的移除，确认保存配置后才改变 DTO 中的最终商品集合；取消编辑保留原卡，重选配置取齐该组当前在售商品。此次修复沿用全部现有路径、DTO、版本校验和权限，不涉及数据库调整。[规则与验证](../architecture/frontend-development.md#2026-11-15-申请地址切换保护与已有配置移除)

| 方法 | 路径 | 用途 | 成功 |
| --- | --- | --- | --- |
| GET | `/api/policies/platforms` | 本人当前负责的渠道平台组合 | 200 |
| POST | `/api/policies/drafts` | 首次保存，可携带当前可不完整的整单表单 | 201 |
| GET | `/api/policies/time-adjustments/options` | 查询当前申请人可以缩短或延长的已通过政策 | 200 |
| POST | `/api/policies/time-adjustments/preview` | 核对整批调整和延期交叉段，返回本批凭证 | 200 |
| POST | `/api/policies/time-adjustments` | 校验凭证、选择和请求标识，逐项生成已通过的调整记录 | 200 |
| PUT | `/api/policies/{id}/draft` | 整体保存本人草稿/已拒绝申请 | 200 |
| DELETE | `/api/policies/{id}/draft?recordVersion={version}` | 物理删除本人未提交草稿 | 204 |
| GET | `/api/policies/{id}/submission-preview?recordVersion={version}` | 核对本人拟提交的整单覆盖范围 | 200 |
| POST | `/api/policies/{id}/submission-preview` | 按所选旧单的新截止时刻重算适用范围，返回确认凭证 | 200 |
| POST | `/api/policies/{id}/submit` | `DRAFT` 提交或 `REJECTED` 重提 | 200 |
| GET | `/api/policies/mine` | 「我的提交」分页查询本人的草稿和提交记录 | 200 |
| GET | `/api/policies/{id}` | 草稿和驳回继续编辑时返回当前表单，待审或已通过时返回对应轮次快照 | 200 |
| GET | `/api/policies/{id}/audit-logs` | 本人申请各轮提交及审核记录 | 200 |
| GET | `/api/policies/{id}/audit-logs/{logId}` | 本人申请某轮提交快照及该轮审核结果 | 200 |

首提与重提共用同一个申请 ID 和提交接口，每次成功新增一个提交轮次。待审申请不开放撤回。已通过的政策使用改期接口修改截止，其余获批内容保留在原轮快照中。

普通申请无交叉且未附带时间调整时直接送审。有交叉时在「确认政策适用范围」逐段核对 `decisions`，也可在该窗选择随单调整的旧单。选定随单调整后，须按整批重新预览，再提交本次凭证。

<a id="延长已通过的政策"></a>

## 在「我的提交」直接调整已通过的政策

### 在原政策上改期

同一张政策可以多次延期或提前结束，每次使用原政策编号。原单的当前截止和记录版本在生效事务内更新，首次获批表单、商品及补贴仍按原轮快照读取。系统为每个改期目标写入一条 `policy_time_change` 记录并登记一条群通知。新的写入流程不产生延期子单或缩短回执单；已有开发测试单不作转换。

1. `GET /api/policies/time-adjustments/options` 返回可操作原单与 `target`。客户端保留 `policyId`、`auditLogId`、`previousEnd`、`recordVersion`。缩短须负责至少一个实际使用平台；延期须由原申请人办理且仍负责原单全部平台。
2. `POST /api/policies/time-adjustments/preview` 接收逐单的目标、新截止、原因（1 至 512 字）和可选证明。延期仅对新增的有效秒核对交叉，与其他政策重叠时确认采用名单。旧截止与新增段衔接且候选、采用安排一致时，后续申请可在同一连续段确认。到期后才续，在办理前已中断的时段保留。
3. `POST /api/policies/time-adjustments` 接收同一批改期、`confirmationToken` 和 `decisions`，并使用 `Idempotency-Key`。成功返回已调整的原单，整批直接生效；存在版本、截止或范围冲突时整批回滚。每项改期分别通知，重试相同请求沿用同一批结果。

每张政策的改期群通知沿用该原单获批的基础信息、商品与补贴明细，标明原政策编号、办理人和办理时间、本次改前及拟定截止和原因。随单获批时注明发起单号。延期另列实际接续起点。提前结束在「截止变更」前列出「调整前政策时段（北京时间）」，从原单获批表单的开始时间到本次调整记录保存的改前截止。先延期再提前时，这里的结束时间是上次延期后的截止。拟定和实际截止不同时另列实际截止。[群公告字段](../architecture/policy-workflow-phase-e.md#终审通过的群公告)

机器人通知已确认统一取消业务跳转，原单改期也适用。当前实现仍有一处待对齐：带 `changeId` 的改期群通知在配置 `FEISHU_POLICY_ERP_URL` 时会附「查看本轮政策」链接。原单改期记录和证明文件由有权限的用户从 ERP 详情查阅。

申请人、管理员及获批轮次的详情包含 `timeHistory`，其中 `initialEnd` 是原轮获批截止，`currentEnd` 是当前实际截止。`changes` 最近一次在前，每项包含改前、拟定及实际截止、方向、原因、办理人、办理时间、生效时间、证明和随单办理时的来源单号。管理员「差异化政策 → 查看」将该原单的流水放在「当前申请」与「审核记录」之间的「时间调整记录」页签。「当前申请」中的「本轮政策时间调整」来自发起轮次的 `consent.endAdjustments`，逐项展示随单申请调整的目标。下载某次已生效调整的证明使用 `GET /api/policy-reviews/{id}/time-changes/{changeId}/attachments/{attachmentId}/content?submissionVersion={version}`，同时校验原单、轮次及该次调整的附件归属。

<a id="整单提前结束"></a>

## 在适用范围确认窗随本单调整政策时间

`GET /api/policies/{id}/submission-preview` 返回 `applicability.adjustmentOptions`。每项给出原单号、渠道 `perspective`、目标申请及审核日志、当前实际截止、`canShorten` 和 `canExtend`。可缩短时 `earliestEnd`、`latestEnd` 分别是含端点的最早和最晚截止秒。申请人可缩短本公司已通过且其渠道至少一个实际使用平台由本人负责的旧单，包括前任负责人的政策。延长限本人提交、全部原单平台仍由本人负责的政策。本次申请与目标旧单可以跨渠道、机型或日期，即使 `scopes` 为空也可主动选择调整。

同批可同时缩短、延长多张旧单。每张旧单出现一次，分别填写新截止及 1 至 512 字原因，可分别上传文件。前端将 `recordVersion` 和所选 `endAdjustments` 发至同一路径的 POST 接口，服务端核对目标获批轮次、原截止、平台分工及附件归属。新截止等于原截止无效。服务端按新截止核对每张目标与历史政策、本单及同批其他调整的交叉。同一范围内，如果原单和新增延期段在时间上紧接、候选和分组相同、其他政策的执行安排也一致，后段带 `continuesPrevious: true`。确认窗将这两段按原单号显示成一个连续时段。一次勾选适用于整个时段，提交时仍按接口返回的各个 `scope`、`through` 和候选标识写入 `decisions`。新增段的原单候选标识为 `extension:<发起单ID>:<原单ID>`。

申请人已勾选的组合可随目标的延期延续，或随提前结束缩短至新截止。初审卡、采购审核和按轮详情的执行时段沿用同一业务边界，`changes` 和摘要按原单号及获批轮次展示，`adjustments` 单列原、新截止。候选、分组或实际执行安排发生变化的范围分别核对。

```json
{
  "recordVersion": 2,
  "endAdjustments": [{
    "target": {"policyId": "118", "auditLogId": "204", "recordVersion": 4, "previousEnd": "2026-11-30T23:59:59+08:00"},
    "endTime": "2026-11-25T14:30:15+08:00",
    "reason": "平台活动提前收官"
  }, {
    "target": {"policyId": "119", "auditLogId": "205", "recordVersion": 3, "previousEnd": "2026-11-28T18:00:00+08:00"},
    "endTime": "2026-12-03T18:00:00+08:00",
    "reason": "平台确认活动延长",
    "attachmentInfo": [{"type": "FILE", "id": "8eb3ded5-8b42-45a0-82d6-9311fb89f353", "originalName": "平台通知.pdf"}]
  }]
}
```

确认调整只重算预览。正式提交须携带该次预览的 `confirmationToken`、相同的 `endAdjustments` 及重算后的 `decisions`。无交叉时 `decisions` 可为空数组。每项目标、原截止、新截止、原因、附件参与本轮确认。目标依据、附件或交叉范围变化后须重新核对。[文件格式、按轮下载及引用保护](attachment-api.md#政策绑定与历史保护)

本批调整写入发起申请本轮 `policy_audit_log.snapshot_data` 的 `$.consent.endAdjustments`。`target.policyId` 与 `target.auditLogId` 为字符串，分别标识旧申请及其获批轮次。`target.previousEnd`、`endTime` 均为北京时间至秒。时间较早时缩短，较晚时延长。`attachmentInfo` 可省略，旧轮次缺少该字段按无调整附件读取。

管理员对当前申请及其附带的全部缩短、延期作一次审核。驳回时整批驳回，各原政策继续按原截止执行，本轮提交内容可回查。批准前重新核对整批目标与逐段范围，有新增未确认范围或目标失效须驳回重提。通过时在同一事务使整批生效。目标原单截止与调整流水在同一事务写入，已有获批快照保留。逐段决定在执行时映射到原政策编号。[在 Navicat 按发起单或旧单核对](../sql/database-design.md#在-navicat-核对整单提前结束记录)

## 删除未提交草稿

「我的提交」仅为 `DRAFT` 且 `submissionVersion=0` 的记录显示删除按钮。确认后携带当前 `recordVersion` 发起 DELETE，版本为必填正整数。

后端核验本公司有效申请人、本人归属、逻辑删除状态、`DRAFT` 状态和提交轮次 0。条件 DELETE 同时匹配记录版本，并排除已存在审核快照或商品明细的申请。成功后政策主表记录从数据库移除，返回 204。不存在或非本人返回 404，状态或版本变化返回 409，非法或缺少版本返回 400。删除、保存与提交争用同一申请行，旧版本请求不能覆盖已提交的状态。

成功后刷新当前筛选及分页，当前页为空且超出末页时回到最后有效页。若删除的是编辑页缓存的同一草稿，一并清理表单和本地附件状态。取消或删除失败时保留输入。结果不明时只读取申请核对，确认不存在后完成清理，继续存在或无法核对时提示刷新，不自动重放删除请求。

草稿删除会解除该申请的附件引用，磁盘文件由附件存储继续保留。文件物理删除仍由独立附件接口检查其他当前表单和历史快照引用。[附件引用保护](attachment-api.md#政策绑定与历史保护)

## 草稿内容 DTO

首次保存与更新使用同一套表单字段。创建最小请求为 `{ "perspective": "RETAIL" }`，创建时省略 `recordVersion`，服务端生成 ID、单号、申请人、状态和版本。创建不能携带任意状态、审核人、提交版本。页面通过 `Idempotency-Key` 发送首次保存 UUID。服务端按本人和该标识生成内部创建幂等标识，继续保存在现有 `policy_no` 字段；对外 `policyCode` 由申请 ID 生成为 `ZC-000005` 这样的短单号，最少六位且不截断更长 ID。同一标识和同一表单可重放未变化的首次草稿，其他冲突返回 409。首次保存结果未知时，页面复用原请求，拿到申请 ID 后再保存后续编辑。

零售请求用 `sceneTypes` 数组传实际勾选项，「全部」展开为三个编码。空数组可保存草稿，提交至少选一项。编码未知、重复或包含空元素返回 400。百亿传 null 或省略。申请详情、管理员列表及历轮快照均返回当时选择的数组。管理员列表的单项筛选参数为 `sceneType`。

草稿保存整体替换当前表单，须传 `recordVersion`。平台减少时同步校验卡片适用平台及方案分配。正式明细由后端在提交时生成。

| DTO 字段 | 数据库 | 规则 |
| --- | --- | --- |
| `perspective` | `channel_type` | `RETAIL` / `BILLION`，创建必填 |
| `selectedPlatforms` | `platforms` | 具体编码数组，去重，不含 ALL |
| `verifyType` | `write_off_type` | 零售 SALES / ACTIVATION；百亿 null |
| `sceneTypes` | `scene_types` | 零售实际选中的场景编码数组，按 NATIONAL_SUB、LOCAL_SUB、SELF_SUB 排序；未选及百亿返回 null |
| `estimated` | `is_estimated` | 零售布尔，映射 0/1；百亿 null |
| `startTime`、`endTime` | `start_time`、`end_time` | 申请起止时刻。草稿可空，同时有值时须满足 start < end。原获批快照保留首次截止，主表的当前截止反映已生效的改期，截止所在秒包含在内 |
| `specialRemark` | `remark` | 可空；必填口径待确认 |
| `attachmentInfo` | `attachment_urls` | 可空数组，最多 10 个 `{ "type": "FILE", "id": "UUID", "originalName": "文件名" }` 和一个 `{ "type": "LINK", "url": "https://…" }`。核验本人文件并以存储名称为准；外部链接限 HTTP/HTTPS，无登录信息 |
| `goodsForm` | `goods_form_json` | 商品卡、选品、平台联动、方案及平台分配 |
| `recordVersion` | `record_version` | 更新必传，服务端条件校验 |

商品卡保留卡片标识、`cardType`（`COMMON` 普通卡或 `SPECIFIED` 指定卡）、`followsOrderPlatforms`、选品 `targets`、卡内方案 `subsidyPlans` 和实际 `platformAssignments`。平台分配按卡内方案标识关联，指定卡独立保存各平台数量。品牌、系列、机型、版本、配置及颜色展示快照由服务端从目录生成，租户由会话确定。

同一申请可包含多张普通卡和指定卡，各卡保存实际选中的商品 ID。普通卡按 `spuId + ram + rom` 在申请内保持唯一归属，普通卡须包含所选配置全部在售商品。预览和提交普通新单或退回重提时，服务端按配置核对完整集合。范围变化返回 409 `POLICY_COMMON_SCOPE_CHANGED`，申请人可编辑并重新核对该组。已有部分草稿可继续保存，延期继承获批原单。所有卡片仍须保证平台与商品 ID 的组合唯一。提交时每个 `goodsId` 必须存在、在售且属于该机型配置。

每个方案按固定或比例方式填写对应字段，保存填写值供查阅。固定方式的比例和基数为空，比例方式的固定金额和上限为空。普通配置数量留空，指定卡按平台选填参考数量，保留空值或 0。同一平台下普通卡与指定卡重复选中同一商品时，返回 400，申请人须调整适用平台或移除重叠范围。

示例保存请求（结构演示，机型及数字不是初始化数据）：

```json
{
  "recordVersion": 2,
  "perspective": "RETAIL",
  "selectedPlatforms": ["PDD"],
  "verifyType": "SALES",
  "sceneTypes": ["NATIONAL_SUB", "LOCAL_SUB"],
  "estimated": false,
  "startTime": "2026-11-10T09:00:00+08:00",
  "endTime": "2026-11-20T18:00:00+08:00",
  "specialRemark": null,
  "attachmentInfo": [],
  "goodsForm": {
    "cards": [
      {
        "key": "card-1",
        "cardType": "COMMON",
        "followsOrderPlatforms": true,
        "targets": [
          { "spuId": "1001", "ram": "16GB", "rom": "512GB", "goodsId": "2001" }
        ],
        "subsidyPlans": [
          { "key": "plan-1", "subsidyMode": "FIXED", "fixedAmount": "50.00", "maxSubsidyAmount": "5000.00" }
        ],
        "platformAssignments": [
          { "platformCode": "PDD", "planKey": "plan-1", "quantity": null }
        ]
      }
    ]
  }
}
```

前端通过白名单适配当前 `channel/platforms/scenes/mode/priceBase` 等字段。将 `remark/links` 分别映射为特别备注及外部链接项。申请内容使用一个特别备注字段 `specialRemark`。文件绑定和下载权限见 [附件接口](attachment-api.md)。

## 保存与提交响应

创建或保存返回至少 `id`、`policyCode`、`status`、`recordVersion`、`submissionVersion`。其中 `policyCode` 由已有申请 ID 格式化为 `ZC-` 加最少六位数字，重提时保持不变；`submissionVersion` 映射 `current_version`，仅供并发和后台日志使用，申请人页面不展示。`policy_no` 沿用内部创建幂等标识，不调整表结构或改写既有数据。申请及管理员列表按申请 ID 展示短单号。正式提交时将该编号写入本轮快照，查看详情或历轮记录时读取该轮保存的编号。详情还返回 `applicantName`、`form` 和目录展示用的 `goods`。草稿和已驳回申请从 `#/apply?policyId=<id>` 回填当前表单。待审及已通过内容读取当前轮次快照，页面按只读展示。

提交前用保存返回的版本请求只读 `submission-preview`，限本人 `DRAFT` 和 `REJECTED`。`snapshot` 是该保存版本的拟提交内容，`applicability` 是确认页和提交使用的整单分段结果，`confirmationToken` 标识本次预览。预览纳入当前负责平台上前任负责人提交的有效政策。

### 按日期试算适用范围

预览响应的 `applicability` 提供整单分段结果。服务端核验草稿归属、记录版本及当前平台分工。对新增补贴的申请另核验商品在售状态。历史候选取本公司同渠道、同适用平台上已通过的当前提交轮次，包含已移交平台前任负责人的记录，以及已经提前获批的未来政策。交叉段 `scopes` 只返回命中范围的商品、补贴、单号及轮次。`adjustmentOptions` 另按本人分工及原单归属返回可缩短或延长的选项。待审核、驳回、草稿和已删除记录均不进入组合。

| 字段 | 含义 |
| --- | --- |
| `dateBasis` | `SNAPSHOT_DATES`。新单用已保存表单，历史政策取获批快照的起止日期。 |
| `asOf / effectiveFrom / effectiveThrough` | 北京时间精确到秒。新单假设在 `asOf` 通过，取申请开始与该时刻较晚者作为起点。历史政策按获批快照及截至核对时已生效的改期流水计算可用区间。 |
| `status / canDirectSubmit` | `DIRECT / true` 表示整单有可生效时段且没有任何命中；`NEEDS_CONFIRMATION / false` 表示至少一段需要核对；`NO_EFFECTIVE_PERIOD / false` 表示假设此刻审核已晚于截止。正式提交在事务内再次执行该判定。 |
| `scopes[].scope` | 仅返回命中的范围。零售按 `platformCode + goodsId`；百亿按 `platformCode + spuId + ram + rom`。商品和机型 ID 使用字符串。 |
| `scopes[].policies[]` | 每个范围内每份政策的当前轮次只出现一次，带 `policyId / policyCode / submissionVersion / auditLogId`、起效时间、核销类型、场景和所选商品行。拟提交轮次的 `proposed=true`，审核时间及日志 ID 为空。每条 `selections[]` 对应一张卡在该范围的商品及实际平台方案；`SPECIFIED` 单列商品、颜色及该平台数量，区分 0 与空值。 |
| `scopes[].segments[]` | `from / through` 均含该秒，仅列时间上确有历史交集的段。零售 `groups[]` 给出完整的 `sceneTypes`、`verifyType` 和本组全部候选 `policyIds`；`eligiblePolicyIds` 给出该段去重后的政策 ID，可在同一范围的 `policies` 找到对应轮次和补贴。 |

| 分段动作 `action` | 申请人需要核对的内容 |
| --- | --- |
| `SELECT_RETAIL_POLICIES` | 零售在本段至少选一张，每个完整场景组合与核销类型组最多一张，可只选旧单或跨组选多张。 |
| `STACK_OR_CHOOSE` | 百亿同配置出现交集，列出该段参与的政策，由申请人选择叠加或只用其中一份。不同颜色也可命中，指定商品在自己的卡片行显示。 |

例如新单 ZC-000113 覆盖 11 月 1–25 日，另两份不同场景的旧单 ZC-000114、ZC-000115 分别覆盖 3–8 日和 15–20 日。两段 `eligiblePolicyIds` 分别引用 113 与 114、113 与 115 的申请 ID，8 日和 20 日的最后一秒均在各自段内。旧单 114 和 115 不会出现在同一段。确认页读取 `applicability.scopes` 展示这两个交叉段。服务端通过 `confirmationToken` 识别本次区间判定，另行核验每段选择。

### 正式提交

正式提交时，服务端按本轮实际核验的内容分流，响应追加 `reviewStage`。有交叉时申请人仍须逐段选单。将所有最终同时采用至少两张政策的确认段合并，收集其中出现的全部政策。每张参与单从自身全部平台的普通卡和指定卡提取目标商品 `goodsId` 并去重。存在实际叠加且所有参与单的集合完全相等时取 `PRODUCT_REVIEW`，否则取 `PURCHASE_REVIEW`。同一配置的不同颜色或 SKU 对应不同商品 ID。未选候选和只在单选段出现的政策不参与整轮比较。服务端冻结本轮依据、审核人员和配置版本，按事务登记投递动作。外部投递由 `POLICY_REVIEW_DELIVERY_ENABLED` 单独控制，响应中的 `reviewStage` 表示 ERP 审核阶段。真实送达以投递回执为准。[各节点的收件人与回执](../architecture/policy-workflow-phase-e.md#各节点的收件人与回执)

关闭自动投递时仍返回本轮的 `reviewStage`。原申请人成功重提时，按原指派结办上轮修改待办。缺设置返回 409 `POLICY_REVIEW_SETTINGS_REQUIRED`，所需组无人合格或无法由两人分别签署返回 409 `POLICY_REVIEW_ASSIGNEES_MISSING`，原状态、轮次和附件保留。[审核设置接口](policy-review-settings.md) · [切换条件](../architecture/policy-workflow-phase-b.md#切换与运行边界)

普通申请无交叉且未选时间调整时，发送 `{ "recordVersion": 3 }` 即可。有随单时间调整时，即使没有交叉也须携带调整预览的 `confirmationToken` 和 `endAdjustments`，此时 `decisions` 可为空数组。独立缩短单的调整内容由服务端从草稿取得，只需回传版本与凭证。有任一交叉段时，送回本次预览的 `confirmationToken`，并按以下格式为每段提交一个决定。将对应的 `scope` 和 `through` 原样送回，零售使用 `selectedPolicyIds` 数组，百亿选一时使用单值 `policyId`。

确认页合并普通商品只改变展示和操作次数。若零售京东同配置白、黑两件商品在同一时段结果相同，点一次确认要生成两条 `decisions`，分别送回各自的 `scope.goodsId` 和原段 `through`。百亿整段以接口返回的配置 `scope` 提交一个决定，特殊商品的颜色和数量随该段展示。[颜色分组验收](../business/override-engine.md#6-决策表与验收案例)

```json
{
  "recordVersion": 3,
  "confirmationToken": "<本次预览标识>",
  "decisions": [{
    "scope": {"platformCode":"PDD","goodsId":null,"spuId":"81","ram":"12GB","rom":"256GB"},
    "through": "2026-11-20T23:59:59+08:00",
    "choice":"CHOOSE_ONE",
    "policyId":"113"
  }]
}
```

| 分段动作 | 接受时的 `choice` |
| --- | --- |
| `SELECT_RETAIL_POLICIES` | `SELECT` 加 `selectedPolicyIds`，例如 `["7","6"]`。只用旧单时也发送数组，例如 `["3"]`。 |
| `STACK_OR_CHOOSE` | `STACK`，或 `CHOOSE_ONE` 加一个候选 `policyId` |

以京东 Reno17 Pro 三色验收为例，三个商品均在第一段勾选 006＋007、第二段只勾 003。申请页显示两项确认，提交请求有六条 `decisions`。下表列出其中一个商品的两条决定，其余两件分别使用预览返回的自身 `scope`。数组元素使用字符串形式的申请 ID。[完整候选和页面截图](../business/override-engine.md#京东-reno17-pro-逐段选单示例)

| `scope` 对应范围 | `through` | `choice` | `selectedPolicyIds` |
| --- | --- | --- | --- |
| 京东、该商品 ID | `2026-11-24T23:59:59+08:00` | `SELECT` | `["6","7"]` |
| 京东、该商品 ID | `2026-11-25T23:59:59+08:00` | `SELECT` | `["3"]` |

零售允许在当前段单用任一候选，也可返回编辑。若客户端已发送 `RETURN_TO_EDIT`，服务端也保留原状态、表单及附件。缺段、额外范围、空选择、重复 ID、越界或零售同组选多张均返回 409 `POLICY_CONFIRMATION_REQUIRED`。成功后 `snapshot.consent` 保存对照单号 ID 和审核轮次、各段候选和最终选中 ID。普通提交沿用版本 2。调整清单仅含缩短时使用版本 3，含随单延期时发起单使用版本 4。直提轮次保存空的决定列表。回看历轮申请时读取该轮冻结的提交内容。

审核时复核仍可生效的时段、分组和候选。依据发生变化时由管理员驳回，申请人重新确认后提交。

提交接口读取已保存表单并完整校验，状态由后端流转。若保存成功但提交失败，页面保留申请 ID、表单和已保存版本供继续编辑。DRAFT/REJECTED 才可提交，后端在同一事务中递增 `current_version` 和 `record_version`、插入本轮审核快照并转 PENDING。新增补贴的申请同时写入商品适用明细，独立缩短申请只保存快照。成功响应示例如下。

```json
{
  "id": "1001",
  "policyCode": "示例单号",
  "status": "PENDING",
  "recordVersion": 4,
  "submissionVersion": 1,
  "reviewStage": "PURCHASE_REVIEW"
}
```

须校验整单平台、商品在售状态及配置归属、时间、零售分类与补贴参数。百亿的零售字段为空。普通卡数量为空，指定卡可选填数量。当前固定上限、附件和备注选填，服务端校验每张卡有适用平台。是否要求整单每个平台均有明细仍待确认。

## 分页、筛选与日志

`mine` 支持以下参数。顺序固定为 `created_at DESC, id DESC`。筛选条件可组合，未知枚举及越界分页参数返回 400。

| 参数 | 取值 |
| --- | --- |
| `page` | 默认 1，正整数 |
| `pageSize` | 默认 20，1 至 100 |
| `status` | 可选，`DRAFT`、`PENDING`、`APPROVED`、`REJECTED` |
| `perspective` | 可选，`RETAIL`、`BILLION` |
| `platformCode` | 可选，具体整单平台编码，例如 `JD` 或 `PDD` |

分页响应形如 `{ "items": [], "total": 0, "page": 1, "pageSize": 20 }`。列表项含 `id`、`policyCode`、`status`、`recordVersion`、`submissionVersion`、`perspective`、`selectedPlatforms`、`createdAt` 和 `updatedAt`。时间调整还用到 `endTime`、`sourcePolicyCode`、`adjustmentOnly`、`earliestEnd`、`canShorten` 和 `canExtend`。`adjustmentOnly` 标记独立缩短申请，`canShorten`、`canExtend` 决定该申请是否可作为新的时间调整目标。整单平台按 `policy_apply.platforms` 中的编码精确匹配。

`audit-logs` 使用同样的分页参数和响应外层，按提交轮次倒序。每条含字符串 `id`、`submissionVersion`、`submittedAt`、`auditStatus`、`auditorName`、`auditComment` 和 `auditTime`。审核状态为 `SUBMITTED`、`APPROVED` 或 `REJECTED`，待审轮次的审核信息为 `null`，未提交的草稿返回空页。

本人列表及详情的 `reviewStage` 在待审时标明产品运营初审或采购终审。申请详情及继续编辑页在状态为 `REJECTED` 时，通过 `audit-logs?page=1&pageSize=1` 读取最新审核记录，核对 `submissionVersion` 与当前申请一致且日志为 `REJECTED`。页面顶部按该记录显示退回环节、原因、实际退回人和北京时间。`rejectedAtStage` 从该轮的退回决定取得，可为 `PRODUCT_REVIEW` 或 `PURCHASE_REVIEW`。未记录环节时页面明示缺失。采购未填意见时提示「审核人未填写驳回原因」，读取失败或轮次不一致时可重试。重提沿用原单与原申请人，服务端重新取得最新名单并冻结新轮。

单轮详情按列表返回的日志 `id` 查询，响应为 `{ "log": { ... }, "snapshot": { ... } }`。`snapshot` 包含原提交轮次的申请 ID、单号、申请人、完整表单与商品名称快照。每次读取先校验父申请的租户、申请人和删除状态，再按该申请匹配日志。草稿及驳回的编辑入口读取当前表单，历轮快照始终取自提交时保存的内容。

### 提交快照的存储与回读

新提交的普通卡核对每个配置全部在售商品后，本轮快照记录 `wholeConfigurationSelection=true`。商品 ID 沿用表单内冻结的 `targets`。后续目录新增商品或调整销售状态，不改变这一轮的整组成员。独立和随单延期沿用对应获批原单的选品口径。早期快照无此标记，查询仍展示当轮实际申报的商品。

一次快照记录某份政策某一轮正式提交时的完整内容。`PolicySnapshotData` 是 Java 的 `record`（记录类），每次提交都有各自的字段值。固定金额、比例这样的有限选项才适合用枚举（`enum`）表达。快照使用现有审核日志的一个 JSON 字段，无需单独建表。[快照模型](../../backend/src/main/java/com/company/policy/entity/PolicySnapshotData.java)

1. **提交。** 服务端校验已保存的表单和在售商品，取得该时点的商品名称、规格和颜色。`PolicySnapshotCodec` 将表单、补贴、附件引用、商品及本轮确认的 `consent` 序列化为 JSON。提交事务将 JSON 写入 MySQL 的 `policy_audit_log.snapshot_data`，同时更新申请状态。新增补贴的申请保存本轮商品明细，独立缩短单保留原申请的商品快照供审核。[快照编解码](../../backend/src/main/java/com/company/policy/support/policy/PolicySnapshotCodec.java)
2. **回读。** 本人已提交详情、管理员审核详情、历轮记录和补贴对照读取所需轮次的快照。当前轮直接读取提交时写入的对外单号、表单、商品与附件。读取器按当前字段解码。例如一轮的表单同时含 `sceneTypes` 和额外的 `sceneType` 时，只使用 `sceneTypes` 展示该轮的实际场景，原快照保持完整。金额与比例保留十进制字符串，数量区分 0 和未填写。[快照往返回归](../../backend/src/test/java/com/company/policy/support/policy/PolicySnapshotCodecTest.java)
3. **重启和迁移。** 后端重启或更换 JAR 后，连接原 MySQL 数据库即可继续读取历轮政策。快照中的文件 ID 和文件名用于定位附件，原文件及元数据保存在附件目录，迁移时须一并保留。[附件存储和配置](attachment-api.md#存储和配置)

## 错误与实现要求

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | POLICY_VALIDATION_FAILED | 枚举、字段组合、精度或正式提交不完整 |
| 401 | LOGIN_REQUIRED | 未登录或会话到期，沿用现有代码 |
| 403 | POLICY_PLATFORM_FORBIDDEN | 所选渠道平台已移交或未分配给本人，刷新分工、调整所选平台后重新保存与预览 |
| 403 | POLICY_ACCESS_DENIED | 当前账号不是本公司有效申请人 |
| 404 | POLICY_NOT_FOUND | 不存在、已删除或非本人申请 |
| 404 | POLICY_AUDIT_NOT_FOUND | 该申请下不存在指定审核记录 |
| 409 | POLICY_CONFIRMATION_REQUIRED | 获取本轮预览，补全所有分段的合法选择 |
| 409 | POLICY_RETURN_TO_EDIT | 返回编辑，原草稿或驳回单及附件已保留 |
| 409 | POLICY_NO_EFFECTIVE_PERIOD | 申请已无可生效时段，返回编辑调整起止时间 |
| 409 | POLICY_ADJUSTMENT_CHANGED | 调整目标、实际截止、延期商品或负责平台已变化，按新预览重新核对旧单 |
| 409 | POLICY_COVERAGE_CHANGED | 整单适用范围、对照轮次或所选商品资料变化，重新获取预览并确认 |
| 409 | POLICY_VERSION_CONFLICT / POLICY_STATE_CONFLICT | 版本过期、重复提交、不允许状态 |
| 409 | POLICY_GOODS_CHANGED | 选中商品下架、删除或配置变化 |
| 409 | POLICY_COMMON_SCOPE_CHANGED | 普通卡缺少该配置的在售商品。编辑并重选整组，或移除该配置后使用指定适用机型，再次预览和提交 |
| 409 | POLICY_CREATE_CONFLICT | 首次保存标识已用于其他内容或申请已变化 |
| 503 | POLICY_STORAGE_UNAVAILABLE | 存储不可用，不显示假成功 |

事务与历史遵循 [状态机](../business/status-machine.md)，字段枚举见 [数据字典](../sql/database-design.md)。已实现接口可通过 Knife4j 查看。

## 申请人负责平台

「机型补贴查询」也按当前分工读取本公司已通过政策，包括平台移交前的记录。申请人目录和时间轴路径、参数及返回范围见[申请人机型补贴查询](page-api-plan.md#申请人机型补贴查询)。

`GET /api/policies/platforms` 返回当前有效申请人的 `[{perspective, platformCode}]`，尚未分配返回空数组。新建、保存草稿、驳回重提的覆盖预览和正式提交均校验整单所选平台归属，卡片平台须属于整单。初次提交同样经过预览及正式提交复核。未授权的平台返回 403 `POLICY_PLATFORM_FORBIDDEN`。平台移交与草稿、提交写入共用申请人行锁；确认后失去权限不能写入审核记录。空平台草稿可保存，正式提交要求至少一个平台。本人列表、详情及附件归属沿用原申请人。接手人的正式预览按公司、渠道和实际平台读取已通过历史，纳入原负责人的有效记录。
