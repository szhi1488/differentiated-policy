# 页面接口总览

更新日期 2026-11-23。申请人选品、申请及历史接口已接入。管理员可查已提交政策、历轮审核与快照，根据本轮快照直接通过或驳回。预计覆盖范围由申请人在正式提交前确认。接口同时提供单商品有效政策查询。管理员用户管理页面已接入七个后端接口。

## 用户管理页面

管理员 `#/admin/users` 分别加载服务端列表、全量统计和用户详情。确认调整角色或启停前，弹窗读取最新详情，用其中的 roleType、status 作为 expectedRoleType、expectedStatus。写入成功后同步刷新当前列表、统计及已打开的详情。[用户接口](user-api.md) · [页面规则](../architecture/admin-workspace.md#用户管理)

| 页面能力 | 接口 | 当前状态 |
| --- | --- | --- |
| 用户列表 | `GET /api/admin/users` | 后端已实现姓名／部门、角色、状态筛选与分页，页面已接入 |
| 顶部统计 | `GET /api/admin/users/stats` | 后端已实现四项全量计数，独立于列表筛选，页面已接入 |
| 用户详情 | `GET /api/admin/users/{id}` | 后端已实现基础资料及应用内 openId，页面已接入 |
| 查看负责平台 | `GET /api/admin/users/platform-assignments` | 分配弹窗按渠道展示本公司当前负责人 |
| 保存负责平台 | `PATCH /api/admin/users/{id}/platforms` | 按 `expectedPlatforms` 核对该申请人的原分工及所选平台占用，冲突时重新读取。[分工接口](user-api.md#负责平台) |
| 角色调整 | `PATCH /api/admin/users/{id}/role` | 后端已实现角色双向切换及并发、权限保护，页面已接入 |
| 启用／停用 | `PATCH /api/admin/users/{id}/status` | 后端已实现启停及本人和最后有效管理员保护，页面已接入 |

409 或写入结果未知时，页面显示实际错误、重新读取详情并保留操作目标。目标 MySQL 并发和真实飞书会话仍待联调。[页面调用与刷新](user-api.md#页面调用与刷新) · [目标环境联调](user-api.md#目标环境联调)

## 机型补贴查询

管理员页面 `#/admin/model-subsidies` 用目录汇总和补贴时间轴两个只读接口加载页面。首次进入先读取目录，再按首个机型配置、全部平台和北京时间本月 1 日至月末查询。重置时重新计算当前月份，同一工作台切换菜单后返回则用上次已应用的条件重新请求。两图、指针与明细共用一次时间轴响应。[页面规则](../architecture/admin-workspace.md#机型补贴查询)

| 页面动作 | 接口 | 返回内容 |
| --- | --- | --- |
| 选择品牌、机型版本及配置 | `GET /api/admin/catalog/goods/summary?includeModelConfigurations=true` | `series[]`、`models[]` 和按机型去重的 `modelConfigurations[]` |
| 查询、重置或定位当前时间 | `GET /api/admin/policies/subsidy-timeline` | 同一配置的零售与百亿时间轴、原单及选中时点的明细 |
| 联调抽查切换时点 | `GET /api/admin/policies/effective` | 单个渠道、平台、商品及指定秒的实际采用名单 |

### 申请人机型补贴查询

申请人从「政策提交 → 机型补贴查询」进入 `#/apply/model-subsidies`。`GET /api/policies/platforms` 提供当前分工，筛选可选「全部负责平台」或一个具体组合，如「零售 · 拼多多」。「百亿 · 拼多多」需要单独分工。尚未分配平台时显示提示。

| 接口 | 查询范围 |
| --- | --- |
| `GET /api/catalog/subsidy-options` | 以 `brands`、`series`、`models`、`configurations` 返回公司机型与配置，包含已下架机型，支持查询历史补贴。 |
| `GET /api/policies/subsidy-timeline` | 与管理员时间轴共用机型、配置、日期参数和响应格式。省略渠道和平台时查询本人的全部分工。仅传 `platformCode` 时查询本人负责该平台的渠道。页面选择单项时同时传 `perspective` 和 `platformCode`。 |

服务端每次按登录人的当前渠道和平台分工授权，范围内包含前任负责人提交的已通过原单。多平台原单只返回授权平台的补贴、指定数量、历史采用与未采用名单及后续计划。「全部负责平台」展示本人有分工的渠道图，选择具体组合后只展示对应渠道图。无分工时页面提供「刷新分工」，查询全部返回空 `tracks`，显式查询未负责的有效组合返回 403。首次进入及重置使用北京时间本月。[分工与移交验收](../architecture/frontend-harness.md#申请人机型补贴查询)

### 选择机型与配置

管理员汇总接口的 `modelConfigurations[]` 每项含 `spuId`、`ram`、`rom`。机型通过 `seriesId` 关联品牌系列，通过 `spuId` 筛选实际存在的配置。选项覆盖全部销售状态，包含已下架、已停产商品。传输使用目录原值，例如 `1024GB`。省略参数时沿用商品中心原响应。[商品目录](catalog-api.md#已实现的标准商品目录)

### 查询补贴历史

```http
GET /api/admin/policies/subsidy-timeline?spuId=101&ram=16GB&rom=512GB&dateFrom=2026-11-01&dateTo=2026-11-30&platformCode=PDD
```

`spuId` 为正整数，RAM、ROM 使用非零正整数加大写 GB。五项必传参数为 `spuId`、`ram`、`rom`、`dateFrom` 和 `dateTo`。日期为北京时间自然日，含首尾。`platformCode` 省略时查询全部平台。服务端以本次查询时点 `asOf` 为已知历史的上限，配置不存在或无命中时返回 200 和空 `tracks`。查询日期与已通过原单的有效期相交即展示该轮，包括尚未开始的原单。整段查询在未来时，`segments` 为空，`plannedSegments` 返回后续安排。用于解释命中原单期间采用决定的其他已通过轮次也随轨道返回。

| 响应字段 | 含义 |
| --- | --- |
| `asOf`、`tracks[]` | 北京时间，精确到秒并带 `+08:00`。两张图共用一份结果。 |
| `configurationGoodsCount` | 所查机型版本、RAM 和 ROM 对应的标准商品总数，包含下架商品。没有授权范围或配置不存在时为 0。页面按同一平台、同一时段核对实际覆盖的商品 ID 数量，避免将部分颜色误标为整组。 |
| `tracks[].perspective`、`platformCode`、`goodsIds[]` | 零售按具体商品判定，完整历史和方案一致的普通商品合并。新版百亿的空商品列表表示审核决定适用于整组配置。两图的原单申报商品来自 `selections[].products[]`。涉及按具体商品判定的百亿旧轮次时，轨道会返回实际商品 ID 范围。 |
| `tracks[].policies[]` | 每个轮次含字符串 `policyId`、`auditLogId`、对外 `policyCode`、`submissionVersion`、`approvedAt`、`policyFrom`、`policyThrough`、`verifyType`、`sceneTypes`、`estimated`。普通单默认有效至截止日 23:59:59。获批整单提前结束和延期轮次按实际截止秒。百亿预估字段为空。 |
| `policies[].matchesQuery` | 为 true 的轮次绘制政策行，包含原单期内整段未被采用的政策。其余轮次为展示完整原单期间的对照数据，可在相应时点的明细中引用。旧规则切换前还可命中延续适用的旧单。 |
| `policies[].selections[]` | 按原商品卡及平台分配提供 `cardType`（COMMON／SPECIFIED）、商品快照 `products[]`、该平台的 `quantity` 和 `subsidy`。补贴含 `subsidyMode`、`fixedAmount`、`maxSubsidyAmount`、`ratio`、`priceBase`。金额和比例为十进制字符串。指定数量 0 与 null 分别表示零和未填写。商品名称缺失时仍返回实际商品 ID。 |
| `tracks[].segments[]` | 每段含 `startAt`、`endAt` 和采用、未采用两份名单 `adoptedAuditLogIds[]`、`excludedAuditLogIds[]`。在同轨道的政策轮次中按审核日志 ID 取原单和方案。区间左闭右开，末段 `endAt=null` 时仅表示记录到 `asOf` 当秒。实际记录截至 `asOf` 当秒。 |
| `segments[].configurationDecision`、`plannedSegments[].configurationDecision` | 百亿该段按整组配置审核时为 true。零售及按具体商品审核的旧百亿时段为 false。同一查询含新旧百亿政策时，即使轨道带有商品 ID，也按每段的这个标记确定审核范围。审核范围切换时保留分段。 |
| `tracks[].plannedSegments[]` | 字段与历史段相同，每段均有明确的右开结束时间。查询范围包含未来时，按截至 `asOf` 已通过的审核快照回放完整原单期间的后续安排。跨越查询当秒的组合从下一秒开始列入计划。按轨道、审核轮次和确认过的名单区分计划采用与未采用，可只选旧单或叠加多单。之后有新政策获批时，重新查询可取得更新的计划。 |

例如拼多多百亿示例中，201 和 202 分别对应审核日志 `"81"` 和 `"82"`。若某段只采用 201，该段的两份名单分别为 `["81"]` 和 `["82"]`。若两单叠加，则采用名单包含两个 ID，未采用名单为空。前端可在同一份响应内核对两单，移动指针、展开独立商品和缩放都无需再次发起请求。

在百亿按配置审核的时段，普通原单整组申报、另一单只报白色时，交叉段按各原单的商品拆行。每行的采用或未采用状态都取该配置的同一份名单。点击其余颜色也能核对整组批准的政策和各自原单商品。交叉段外恢复按当段原单的商品合并。旧版接口未提供逐段审核范围时，页面沿用接口原有的配置轨或明确返回的商品范围。[页面展示](../architecture/admin-workspace.md#机型补贴查询)

后端按配置和实际平台读取本公司、未删除、当前轮次已通过的申请及审核日志。先读取轮次日期索引，再按命中原单的完整展示窗口分批读快照。超过 `asOf` 的审核记录不参与。存储读取处于同一个只读事务中。参数无效或日期倒置返回 400，会话无效 401、无权限 403、历史涉及无法确定切换时间的旧单 409（`POLICY_CUTOVER_REQUIRED`）、存储不可用 503。

### 区间与时点如何对应

- 各轮从 `max(申请开始时间, 本轮审核通过时间)` 起参与选择。规则上线后，普通原单默认取截止日最后一秒，延期单取所选截止秒。已获批的提前结束调整按每张旧单的实际截止秒限制查询。零售按具体商品、完整场景组合与核销类型分组，并应用该段勾选的政策名单，百亿按配置应用本轮决定。时间轴复用单时点查询的采用规则。[覆盖规则](../business/override-engine.md#5-申请人后端区间试算)
- 查询须带入起始日前已经适用的政策。按商品审核的时段，依各商品的审核轮次、采用名单和补贴参数分组。按整配置审核的时段，同一轮次中方案相同的普通卡可合并显示，金额、上限、比例或计算基数有差异时分行。两图都以 `goodsId` 区分同色的不同商品。指定卡展开或收起只改变可见行，采用名单取自服务端。
- 例如零售京东原先给黑、白两件商品各补 100 元。完整场景组合与核销类型相同的新单只给黑色补 150 元，11 月 10 日 09:00 起生效。假设申请人在黑色的交叉段单选新单且已提前获批，则 08:59:59 两色仍各补 100 元，09:00:00 黑色改为 150 元，白色仍为 100 元。若该段只勾旧单，黑色在 09:00:00 也仍是 100 元。[单商品时点查询](admin-api.md#覆盖预览与有效政策)
- 前端共用一次返回的区间完成悬停、选中色块、时点面板、双图缩放和独立商品开关。「当前时间」沿用现有浏览器北京时间与日期调整规则，重新查询当前所选配置，恢复完整缩放，两图与面板的指针都定位在返回结果可覆盖的当前秒。

### 后端验证

2026-11-23 后端 Maven `verify` 共 229 项测试，227 项通过，吉客云真实调用和外部 MySQL 两项按环境跳过。打包及 Spotless 检查通过。MockMvc 和隔离 H2 验证了配置选项、停产商品历史、待审与逻辑删除过滤、平台筛选、权限、空结果，以及旧规则切换前后一秒。规则测试逐段对照已有时点查询，覆盖百亿选一与叠加、零售只采用旧单、晚审、整段未采用、普通商品合并与分开、单日查询保留原单及对照轮次。目标 MySQL 的历史数据与查询计划仍待联调。

## 政策页面

对外 `policyCode` 使用短单号，例如 `ZC-000005`，由已有申请 ID 生成，至少六位且不截断更长 ID。申请人、管理员和覆盖查询统一使用，驳回重提不变；管理员关键词可查短单号。沿用原表结构与创建幂等标识，无需迁移 SQL。

| 页面 | 接口 | 操作 |
| --- | --- | --- |
| 提交政策 `#/apply` | `GET /api/policies/platforms` | 只列当前渠道分给本人的平台，返回空数组时提示联系管理员 |
| 提交政策 `#/apply` | `GET /api/catalog/options`、`GET /api/catalog/goods` | 品牌、系列、机型版本联动筛选，支持配置多选和「全部配置」，读取实际在售商品 |
| 提交政策 `#/apply` | `POST /api/policies/drafts`、`PUT /api/policies/{id}/draft`、`POST /api/policies/{id}/submit` | 首次保存、继续保存、提交或重提 |
| 申请人提交范围确认 | `GET /api/policies/{id}/submission-preview`、`POST /api/policies/{id}/submission-preview`、`POST /api/policies/{id}/submit` | GET 返回交叉时段、各旧单可提前或延期的权限。确认调整时一次 POST 全部所选旧单及各自的新截止、必填原因和选传证明文件，重算交叉时段，正式提交时送回该次预览的确认凭证及决定。[整单提前结束](policy-api.md#整单提前结束) |
| 我的提交 `#/apply/submissions` | `GET /api/policies/mine`、`GET /api/policies/{id}` | 按状态、渠道、整单平台分页查本人申请，查看详情，`#/apply?policyId=<id>` 继续编辑草稿或驳回申请 |
| 我的提交 `#/apply/submissions` | `DELETE /api/policies/{id}/draft?recordVersion={version}` | 二次确认后物理删除本人未提交草稿，刷新列表和分页并清理该草稿编辑缓存 |
| 我的提交 → 调整政策时间 | `POST /api/policies/{id}/time-adjustment-draft`、`PUT /api/policies/{id}/time-adjustment-date` | 依 `canShorten`、`canExtend` 提供入口。提前结束申请按整单缩短，延期申请自实际截止后一秒接续。原因必填，证明文件选传，均单独送审 |
| 驳回原因（详情与继续编辑页） | `GET /api/policies/{id}/audit-logs?page=1&pageSize=1` | 读取并核对当前驳回原因，页面隐藏内部轮次 |
| 申请人历轮记录（仅后端接口） | `GET /api/policies/{id}/audit-logs`、`GET /api/policies/{id}/audit-logs/{logId}` | 保留日志与快照读取能力，申请人页面不提供历轮入口 |
| 差异化政策 `#/admin/policies` | `GET /api/admin/policies`、`GET /api/admin/policies/{id}`、`POST /api/admin/policies/{id}/review` | 筛选已提交政策，详情传 `includeCoverage=false`，审核通过前复核本轮决定，未确认的组合由管理员驳回后重提 |
| 管理员历轮审核 | `GET /api/admin/policies/{id}/audit-logs`、`GET /api/admin/policies/{id}/audit-logs/{logId}` | 查看与审核窗口的「审核记录」页签分页读取历轮结果，支持查看指定轮次完整快照及附件；历史只读 |
| 管理员覆盖与有效政策查询（后端接口） | `GET /api/admin/policies/{id}/override-preview`、`GET /api/admin/policies/effective` | 预览返回本轮决定及能否继续审核，实际查询返回指定时点的全部适用政策 |

- 两个抽屉均支持配置多选，「全部配置」按当前品牌、系列和机型范围全选。普通卡默认跨页勾选所选配置的在售商品。翻页、搜索或再次选择配置后，手动取消的商品仍保持取消。草稿和正式提交保存最终勾选的 `goodsId`。指定卡单选一件商品，数量按平台选填。[选品规则](../business/perspective-rules.md#选商品与配置)
- 已有普通卡取消配置时，按完整卡片暂存该配置全部商品的移除，确认保存配置后才更新表单；取消编辑不改原卡。申请地址切换复用未保存内容确认，取消导航不发起目标详情请求，确认后继续使用原有 `GET /api/policies/{id}`。两处修复未新增接口或修改 DTO、数据库结构。
- `#/apply` 的「提交申请」先保存表单，再用返回版本读取覆盖预览，无交叉且无调整时可按保存版本直提。有交叉时携带保存版本 `recordVersion`、预览凭证 `confirmationToken` 及分段 `decisions`。随单调整旧政策截止时，还须携带逐单的 `endAdjustments` 和调整后预览的凭证，重算后无交叉则 `decisions` 为空数组。驳回重提沿用原申请 ID 和单号，新增一轮。确认成功后移除地址中的申请 ID，清理表单和本地附件状态并返回新申请页首；失败或结果待核对时保留输入。已提交政策从「我的提交」查看。[申请人接口](policy-api.md)
- 「我的提交」列表不显示提交轮次，详情只展示当前申请，不提供历轮审核或快照入口。后台保留日志和快照。草稿及已驳回申请的编辑入口回填当前表单，待审核和已通过申请只读。[申请人接口](policy-api.md)
- 已驳回申请在详情和继续编辑页顶部直接显示本轮驳回原因、审核人及时间，复用本人审核日志接口读取最新一轮并校验轮次。原因未填写、读取失败和本轮日志缺失分别提示；失败或缺失支持重试。[详情展示](../business/perspective-rules.md#我的提交详情展示)
- 「我的提交」当前申请使用商品及补贴组件，原卡片内按机型版本、配置汇总已选颜色，平台直接对应补贴，指定卡保留各平台数量。页面读取当前详情接口，待审核和已通过内容仍由服务端从提交快照返回。展示规则见[详情展示](../business/perspective-rules.md#我的提交详情展示)，本次实现的测试和模拟接口预览见[验证记录](../architecture/frontend-development.md#2026-11-15-我的提交商品及补贴展示)。
- 管理员「查看」和「审核」使用同一个详情接口。机型、实际勾选的颜色、平台数量和补贴均来自本轮快照。驳回时小窗填写的意见用 `reviewRemark` 提交。[管理员工作台](../architecture/admin-workspace.md#差异化政策)
- 管理员列表、筛选、状态计数及详情摘要统一采用最近正式提交快照。驳回后仅保存的编辑不提前进入管理员查询，重提成功后才切换；申请人本人编辑回填继续使用主表。[查询契约](admin-api.md#列表与详情)
- 申请人核对本公司同渠道、本人负责平台的交叉时段。普通商品按机型版本和配置汇总，同组商品共用该段选择。窗口分页展示全部命中范围，正式提交回传每个原始范围的决定。具体单号、时段与合并示例见[适用范围验收案例](../business/override-engine.md#6-决策表与验收案例)。
- 管理员依据本轮申请内容审核。写入冲突或结果未知时重新读取。通过无需填写意见，驳回原因选填。[审核契约](admin-api.md#审核)

## 现有页面复用接口

| 页面或操作 | 接口 |
| --- | --- |
| 飞书登录、会话与退出 | `GET /api/feishu/config`、`POST /api/feishu/js-sdk/signature`、`POST /api/auth/challenge`、`POST /api/auth/feishu/login`、`POST /api/auth/feishu/browser/authorize`、`POST /api/auth/feishu/browser/login`、`GET /api/auth/session`、`POST /api/auth/logout` |
| 源商品库 `#/admin/catalog/sources` | `GET /api/admin/source-goods`、`GET /api/admin/goods-sync/jobs`、`POST /api/admin/goods-sync/jobs`、`POST /api/admin/goods-sync/jobs/{id}/resume`、`POST /api/admin/goods-sync/jobs/{id}/cancel` |
| 商品清洗与识别 `#/admin/catalog/cleaning` | `GET /api/admin/catalog/cleaning/preview`、`GET /api/admin/catalog/cleaning/results`、`GET /api/admin/catalog/cleaning/jobs`、`GET /api/admin/catalog/cleaning/jobs/active`、`GET /api/admin/catalog/cleaning/jobs/{id}`、`POST /api/admin/catalog/cleaning/jobs` |
| 来源详情、人工审核与重试 | `GET /api/admin/source-goods/{id}`、`POST /api/admin/source-goods/{id}/review`、`POST /api/admin/source-goods/{id}/clean-retry` |
| 商品中心 `#/admin/catalog/center` | `GET /api/admin/catalog/goods`、`GET /api/admin/catalog/goods/summary`、`GET /api/admin/catalog/goods/{id}`、`POST /api/admin/catalog/goods`、`PUT /api/admin/catalog/goods/{id}`、`DELETE /api/admin/catalog/goods/{id}` |
| 商品数据字典 | `GET /api/admin/catalog/dictionary`、`POST /api/admin/catalog/dictionary/{type}`、`PUT /api/admin/catalog/dictionary/{type}/{id}`、`DELETE /api/admin/catalog/dictionary/{type}/{id}` |
| 文件上传与临时文件操作 | `POST /api/attachments`、`GET /api/attachments/{id}/content`、`DELETE /api/attachments/{id}`。已接入申请页上传、失败重试、草稿绑定和引用删除保护 |
| 政策附件下载 | 申请人使用 `GET /api/policies/{id}/attachments/{attachmentId}/content` 或 `GET /api/policies/{id}/audit-logs/{logId}/attachments/{attachmentId}/content`；管理员使用 `GET /api/admin/policies/{id}/audit-logs/{logId}/attachments/{attachmentId}/content` |

参数与权限见 [飞书鉴权](../architecture/feishu-auth-flow.md)、[商品管理接口](catalog-api.md)、[附件接口](attachment-api.md)。商品编辑、删除和系列引用检查使用新基线的 `policy_goods_detail`。商品中心模板下载和当前页导出复用前端现有逻辑。

附件下载入口共用 `AttachmentDownload.vue`，通过 `readAttachmentContent` 调用统一请求层，带当前网页会话读取文件字节，再触发保存。文件读取成功按 Blob 处理，失败按 JSON 读取 HTTP 状态和提示；401 沿用会话失效处理。

## 附件与后续能力

| 能力 | 接口安排 |
| --- | --- |
| 政策附件 | 已实现文件 ID 绑定和回填、当前内容与指定快照下载、历史引用保护。下载路径和权限见[附件接口](attachment-api.md#政策绑定与历史保护)。未绑定文件自动回收待实现 |
| 标准商品批量导入 | 商品中心已有入口，建议 `POST /api/admin/catalog/goods/import`，文件校验、重复及错误行处理待定 |

## 数据写入和实施顺序

| 操作 | 三表处理 |
| --- | --- |
| 保存草稿 | 写 `policy_apply`，保留当前表单、卡片、勾选商品和方案分配 |
| 提交或重提 | 同一事务更新 `policy_apply`，新增 `policy_audit_log` 本轮快照，按轮次、实际平台、最终勾选商品写入 `policy_goods_detail` |
| 审核 | 同一事务更新申请状态和该轮审核结果。继续保留提交快照和商品明细 |
| 查历史和商品引用 | 历史从本轮快照读取，按商品筛选和引用保护使用商品明细 |

下一步在目标环境联调审核、申请人驳回重提和覆盖查询。审核请求携带 `recordVersion` 和 `submissionVersion`。申请页附件已接入，目标环境文件选择与下载仍需联调。[管理员审核接口](admin-api.md)

目标库联调可选同一机型的多个配置，使用「全部配置」核对当前筛选范围。取消一件商品后核对翻页状态、草稿、提交快照和平台明细中的商品 ID。用另一位有效申请人验证列表隔离及详情、历史的访问限制。
