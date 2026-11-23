# 页面接口总览

更新日期 2026-11-20。申请人选品、申请及历史接口已接入。管理员可查已提交政策、历轮审核与快照，根据本轮快照直接通过或驳回。预计覆盖范围由申请人在正式提交前确认。接口同时提供单商品有效政策查询。管理员用户管理页面已接入七个后端接口。

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

管理员页面 `#/admin/model-subsidies` 当前只展示内置示例。以下是接入真实数据的**接口草案**。页面主流程需要两个只读接口，分别用于选配置和查补贴历史。[页面范围](../architecture/admin-workspace.md#机型补贴查询静态预览) · [当前时间行为](../architecture/frontend-development.md#2026-11-16-紧凑查询区与当前时间定位)

| 页面动作 | 接口 | 状态与用途 |
| --- | --- | --- |
| 进入页面，选择品牌、机型／版本和配置 | `GET /api/admin/catalog/goods/summary` | 已有。建议增加可选参数 `includeModelConfigurations=true`，一次取得每个机型实际存在的配置 |
| 查询、重置或点击「当前时间」 | `GET /api/admin/policies/subsidy-timeline` | 拟新增。一次返回该配置在零售、百亿各平台的适用区间、补贴参数和实际商品，供两张图与右侧明细共用 |
| 联调时抽查一个切换时点 | `GET /api/admin/policies/effective` | 已有。传 `perspective`、`platformCode`、`goodsId`、`at`，核对命中的政策轮次。移动指针时直接匹配已取回的区间 |
| 如后续提供查看整单入口 | `GET /api/admin/policies/{id}/audit-logs/{logId}` | 已有。用色块对应的申请 ID 和审核日志 ID 读取原轮次。现有右侧明细所需字段由时间轴直接提供 |

### 选择机型与配置

现有汇总的 `series[]` 提供 `brandName` 和 `seriesId`，`models[]` 通过 `seriesId` 关联，提供 `spuId`、`modelName`、`edition`。现有 `configurations[]` 只有全目录的 `ram`、`rom`，无法确定哪台机型有哪种配置。建议仅在请求上述可选参数时附加去重的 `modelConfigurations[]`，每项如 `{"spuId":"101","ram":"16GB","rom":"1024GB"}`。商品中心仍可按原契约读取全目录配置。[商品目录接口](catalog-api.md#已实现的标准商品目录)

前端按品牌筛机型，按 `spuId` 筛配置。查询使用目录原值，`1024GB` 可显示成 `1T`。建议选项覆盖全部销售状态，以便查到已下架、已停产商品的政策历史。申请人的 `GET /api/catalog/options` 只面向申请人和在售商品。若先用现有管理员接口联调，可按 `spuId` 查询 `GET /api/admin/catalog/goods`，省略销售状态，取齐该机型各页再按 RAM、ROM 去重。

### 查询补贴历史

```http
GET /api/admin/policies/subsidy-timeline?spuId=101&ram=16GB&rom=512GB&dateFrom=2026-11-01&dateTo=2026-11-17
```

五项均必填。`spuId` 为机型版本 ID，`ram`、`rom` 成对传入，两个日期按北京时间自然日包含首尾。服务端以本次查询的当前时点 `asOf` 为历史上限，返回与所选日期相交的全部适用段。结束日是今天时包含截至 `asOf` 这一秒的结果。整个范围在未来时返回空集合。

| 建议响应字段 | 用途 |
| --- | --- |
| `asOf`、`segments[]` | 数据截至时点及区间列表。所有时间采用带 `+08:00` 的 ISO 8601 字符串，精确到秒。合法配置无适用历史时返回 200 和空数组 |
| `segments[].segmentId`、`perspective`、`platformCode`、`cardType` | `segmentId` 唯一标识可点击区间。按渠道 `RETAIL`／`BILLION` 与实际平台分轨。卡片沿用正式快照的 `COMMON`／`SPECIFIED` |
| `startAt`、`endAt` | 实际适用区间，含开始、不含结束。`endAt=null` 表示截至 `asOf` 仍适用。即使色块被查询窗口裁切，明细也保留真实起点和截至 `asOf` 已知的结束时点 |
| `policyId`、`policyCode`、`auditLogId`、`submissionVersion`、`auditStatus` | 定位命中的正式提交轮次。ID 用字符串，单号如 `ZC-000005`，审核状态为 `APPROVED` |
| `subsidyMode`、`fixedAmount`、`maxSubsidyAmount`、`ratio`、`priceBase` | `FIXED` 返回金额和可空上限，`RATIO` 返回百分数和计算基数。金额与比例用十进制字符串，另一种方式的字段为 null |
| `goods[]`、`quantity` | 每件返回 `goodsId`、`goodsName`、`colorName`，展示命中轮次的商品快照。指定卡单件成段，数量取该平台的分配，整数 0 与 null 分别展示「0」与「未填写」。普通卡数量为 null |

服务端按配置从 `policy_goods_detail` 取实际商品与平台，经 `policy_apply` 限定本公司、未删除且已通过的申请，关联同一申请、同一版本的已通过 `policy_audit_log`。补贴从命中快照里按商品卡、平台分配和方案键读取。旧快照缺少名称或颜色时保留商品 ID 并标明缺项。

两个主流程接口都以当前会话核验本公司有效管理员。参数无效或日期倒置返回 400，会话无效 401，权限不足 403，存储不可用 503。页面区分查询失败和无适用历史，失败可按原条件重试。

### 区间与时点如何对应

- 各轮从 `max(申请开始时间, 本轮审核通过时间)` 起参与选择。规则上线起，至快照截止日最后一秒有效。零售按具体商品、完整场景组合与核销类型分组，并应用该段勾选的政策名单，百亿按配置应用本轮决定。时间轴接入时以各时点的实际生效集合为准。[覆盖规则](../business/override-engine.md#5-申请人后端区间试算)
- 查询须带入起始日前已经适用的政策。先逐件判定，再合并普通卡中渠道、平台、实际起止、轮次和方案都相同的区间。同一时点若同平台存在多种实际补贴，图表及右侧应分别列出各自商品和金额。同色不同商品按 `goodsId` 区分。指定卡展开或收起只改变可见轨道，已被覆盖的普通补贴不会重新出现。
- 例如零售京东原先给黑、白两件商品各补 100 元。完整场景组合与核销类型相同的新单只给黑色补 150 元，11 月 10 日 09:00 起生效。假设申请人在黑色的交叉段单选新单且已提前获批，则 08:59:59 两色仍各补 100 元，09:00:00 黑色改为 150 元，白色仍为 100 元。若该段只勾旧单，黑色在 09:00:00 也仍是 100 元。[单商品时点查询](admin-api.md#覆盖预览与有效政策)
- 前端共用一次返回的区间完成悬停、选中色块、时点面板、双图缩放和独立商品开关。「当前时间」沿用现有浏览器北京时间与日期调整规则，重新查询当前所选配置，恢复完整缩放，两图与面板的指针都定位在返回结果可覆盖的当前秒。

## 政策页面

对外 `policyCode` 使用短单号，例如 `ZC-000005`，由已有申请 ID 生成，至少六位且不截断更长 ID。申请人、管理员和覆盖查询统一使用，驳回重提不变；管理员关键词可查短单号。沿用原表结构与创建幂等标识，无需迁移 SQL。

| 页面 | 接口 | 操作 |
| --- | --- | --- |
| 提交政策 `#/apply` | `GET /api/policies/platforms` | 只列当前渠道分给本人的平台，返回空数组时提示联系管理员 |
| 提交政策 `#/apply` | `GET /api/catalog/options`、`GET /api/catalog/goods` | 品牌、系列、机型版本联动筛选，支持配置多选和「全部配置」，读取实际在售商品 |
| 提交政策 `#/apply` | `POST /api/policies/drafts`、`PUT /api/policies/{id}/draft`、`POST /api/policies/{id}/submit` | 首次保存、继续保存、提交或重提 |
| 申请人提交范围确认 | `GET /api/policies/{id}/submission-preview`、`POST /api/policies/{id}/submit` | 申请页与后端均按公司范围支持直提、逐段确认和驳回重提。GET 返回 [applicability](policy-api.md#按日期试算适用范围) |
| 我的提交 `#/apply/submissions` | `GET /api/policies/mine`、`GET /api/policies/{id}` | 按状态、渠道、整单平台分页查本人申请，查看详情，`#/apply?policyId=<id>` 继续编辑草稿或驳回申请 |
| 我的提交 `#/apply/submissions` | `DELETE /api/policies/{id}/draft?recordVersion={version}` | 二次确认后物理删除本人未提交草稿，刷新列表和分页并清理该草稿编辑缓存 |
| 驳回原因（详情与继续编辑页） | `GET /api/policies/{id}/audit-logs?page=1&pageSize=1` | 读取并核对当前驳回原因，页面隐藏内部轮次 |
| 申请人历轮记录（仅后端接口） | `GET /api/policies/{id}/audit-logs`、`GET /api/policies/{id}/audit-logs/{logId}` | 保留日志与快照读取能力，申请人页面不提供历轮入口 |
| 差异化政策 `#/admin/policies` | `GET /api/admin/policies`、`GET /api/admin/policies/{id}`、`POST /api/admin/policies/{id}/review` | 筛选已提交政策，详情传 `includeCoverage=false`，审核通过前复核本轮决定，未确认的组合由管理员驳回后重提 |
| 管理员历轮审核 | `GET /api/admin/policies/{id}/audit-logs`、`GET /api/admin/policies/{id}/audit-logs/{logId}` | 查看与审核窗口的「审核记录」页签分页读取历轮结果，支持查看指定轮次完整快照及附件；历史只读 |
| 管理员覆盖与有效政策查询（后端接口） | `GET /api/admin/policies/{id}/override-preview`、`GET /api/admin/policies/effective` | 预览返回本轮决定及能否继续审核，实际查询返回指定时点的全部适用政策 |

- 两个抽屉均支持配置多选，「全部配置」按当前品牌、系列和机型范围全选。普通卡默认跨页勾选所选配置的在售商品。翻页、搜索或再次选择配置后，手动取消的商品仍保持取消。草稿和正式提交保存最终勾选的 `goodsId`。指定卡单选一件商品，数量按平台选填。[选品规则](../business/perspective-rules.md#选商品与配置)
- 已有普通卡取消配置时，按完整卡片暂存该配置全部商品的移除，确认保存配置后才更新表单；取消编辑不改原卡。申请地址切换复用未保存内容确认，取消导航不发起目标详情请求，确认后继续使用原有 `GET /api/policies/{id}`。两处修复未新增接口或修改 DTO、数据库结构。
- `#/apply` 的「提交申请」先保存表单，再用返回版本读取覆盖预览，无命中直接按保存版本提交，有交叉时携带 `recordVersion`、`confirmationToken` 及全单 `decisions` 提交。驳回重提沿用原申请 ID 和单号，新增一轮。确认成功后移除地址中的申请 ID，清理表单和本地附件状态并返回新申请页首；失败或结果待核对时保留输入。已提交政策从「我的提交」查看。[申请人接口](policy-api.md)
- 「我的提交」列表不显示提交轮次，详情只展示当前申请，不提供历轮审核或快照入口。后台保留日志和快照。草稿及已驳回申请的编辑入口回填当前表单，待审核和已通过申请只读。[申请人接口](policy-api.md)
- 已驳回申请在详情和继续编辑页顶部直接显示本轮驳回原因、审核人及时间，复用本人审核日志接口读取最新一轮并校验轮次。原因未填写、读取失败和本轮日志缺失分别提示；失败或缺失支持重试。[详情展示](../business/perspective-rules.md#我的提交详情展示)
- 「我的提交」当前申请使用商品及补贴组件，原卡片内按机型版本、配置汇总已选颜色，平台直接对应补贴，指定卡保留各平台数量。页面读取当前详情接口，待审核和已通过内容仍由服务端从提交快照返回。展示规则见[详情展示](../business/perspective-rules.md#我的提交详情展示)，本次实现的测试和模拟接口预览见[验证记录](../architecture/frontend-development.md#2026-11-15-我的提交商品及补贴展示)。
- 管理员「查看」和「审核」使用同一个详情接口。机型、实际勾选的颜色、平台数量和补贴均来自本轮快照。驳回时小窗填写的意见用 `reviewRemark` 提交。[管理员工作台](../architecture/admin-workspace.md#差异化政策)
- 管理员列表、筛选、状态计数及详情摘要统一采用最近正式提交快照。驳回后仅保存的编辑不提前进入管理员查询，重提成功后才切换；申请人本人编辑回填继续使用主表。[查询契约](admin-api.md#列表与详情)
- 申请人核对本公司同渠道、本人负责平台的交叉时段。普通商品按机型版本和配置汇总；整单必须覆盖所有原始范围，零售按颜色筛选后仅确认可见商品。具体单号、时段与合并示例见[适用范围验收案例](../business/override-engine.md#6-决策表与验收案例)。
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
