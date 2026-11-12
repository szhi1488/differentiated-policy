# 商品管理接口契约

更新日期：2026-11-12。源商品导入、清洗、来源审核、技术重试及清洗审计接口已实现。标准目录维护和政策查询接口见后续设计。[数据字典](../sql/database-design.md) · [商品方案](../architecture/catalog-admin-design.md) · [清洗细则](../business/catalog-cleaning.md)

## 公共规则

沿用项目 Session、Origin、X-Policy-Request、DTO/VO 规范。每次管理操作重新检查有效 ADMIN 和 tenant_key；账套和租户绑定来自服务端配置。ID 按字符串，recordVersion 为整数。客户端不指定操作者、账套、租户、游标或分页请求频率。

列表默认每页 20、上限 100，排序和枚举白名单；不一次把全库返回浏览器。错误统一 400/401/403/404/409/503；上游错误以脱敏类别和本地 jobId 表示，不返回凭证或原始报文。

## 已实现：原始导入与来源查询

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | /api/admin/goods-sync/jobs | `brandName` 为 `OPPO手机` 或 `一加手机`，`idempotencyKey` 为小写 UUID；`mode` 为 `FULL`（默认）、`CREATED` 或 `MODIFIED`；按日须传 `date`（北京时间 `YYYY-MM-DD`）；包含停用 SKU，202 返回任务 |
| GET | /api/admin/goods-sync/jobs | 返回 `configured` 和两个品牌各自最新的 `jobs`（尚未导入的品牌没有任务） |
| POST | /api/admin/goods-sync/jobs/{id}/resume | 带 `recordVersion`；暂停且冷却结束后，从同一任务的断点继续 |
| POST | /api/admin/goods-sync/jobs/{id}/cancel | 带 `recordVersion`；只结束暂停任务，保留已提交商品原文和剩余冷却时间 |
| GET | /api/admin/source-goods | `brandName` 可省略，`keyword` 最长 128，按名称、货号、SKU 名称或完整分类路径查子串，或按 SKU ID 精确查询；`createdDate` 可按北京时间建档日 `YYYY-MM-DD` 筛选已导入的 SKU；`page` 从 1 开始，`size` 默认 20、最大 100；返回 `items` 和 `total` |

任务返回 `id`、`brandName`、`syncMode`、`syncDate`、`syncPhase`、`status`、`cleanStatus`、`pageCount`、`readCount`、`contentCount`、`errorCode`、`canResume` 和 `recordVersion`。`status` 为 `QUEUED`、`RUNNING`、`PAUSED`、`SUCCEEDED`、`CANCELLED`。新任务在原文导入完成时进入后台清洗，两个状态分别查询。修改日任务依次查询 `GOODS_MODIFIED`、`SKU_MODIFIED`，每阶段保留断点。来源列表返回本地 ID、品牌、原始七字段、字符串 `skuId`、`cateFullName`、毫秒时间戳 `gmtModified` 和 `goodsGmtModified`、可空布尔标记 `isBlockup` 和 `isPackageGood`，以及 `cleanStatus`。`readCount` 为已提交读取条次，`contentCount` 为本任务不同 SKU 数。部分提交的原文可查询，游标和工作令牌只在服务端维护。

同一管理员、品牌、模式、日期和幂等键重复提交返回原任务；键冲突、有活动任务、冷却未结束或任务状态已变更返回 409。缺少租户/账套配置或数据库不可用返回 503。实际接口可在 Knife4j `/doc.html` 查看。

来源列表的 `createdDate` 是吉客云按建档日查询命中的日期，返回 `YYYY-MM-DD`，缺少已确认记录时为空。筛选与品牌、关键词和分页同时生效。旧数据可从首次、最近或清洗关联的按建档日任务识别。新匹配按 SKU 和日期记录在已有商品操作日志中，其他导入模式后续更新 SKU 时可继续查询。

来源列表的 `gmtModified`、`goodsGmtModified` 分别对应已入库的吉客云规格和货品修改时间，均为 Unix 毫秒，库中缺值时各自返回 null。前端按北京时间分别格式化，保留非零毫秒。「导入商品」弹窗发起 `FULL` 或 `CREATED` 任务，服务端同时保留 `MODIFIED` 契约。[管理员工作台](../architecture/admin-workspace.md)

## 已实现的清洗和来源审核

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | /api/admin/catalog/cleaning/latest | 最近成功导入的批次，`brandName` 可选；没有成功批次时返回 204 |
| GET | /api/admin/goods-sync/jobs/{id}/cleaning | 指定导入批次的清洗状态、进度和分流计数 |
| POST | /api/admin/goods-sync/jobs/{id}/clean-start | 用任务 `recordVersion` 启动历史成功批次，返回 202；重复启动返回当前任务 |
| POST | /api/admin/goods-sync/jobs/{id}/clean-preview | 预览来源文本的规则候选；查询参数 `ruleVersion` 可省略，支持 `page`、`size` |
| GET | /api/admin/catalog/cleaning/results | 按 `importJobId`、`brandName`、`status`、`ruleVersion`、`keyword` 和分页筛选 |
| GET | /api/admin/source-goods/{id} | 原文、候选、命中证据、当前规格及颜色映射 |
| POST | /api/admin/source-goods/{id}/review | 用来源 `recordVersion` 审核，支持 `APPROVE` 和 `EXCLUDE` |
| POST | /api/admin/source-goods/{id}/clean-retry | 用来源 `recordVersion` 重试一条 `ERROR`，返回 202 |
| POST | /api/admin/goods-sync/jobs/{id}/clean-retry | 用任务 `recordVersion` 重试本批次的 `ERROR`，返回 202 |
| POST | /api/admin/goods-sync/jobs/{id}/clean-reprocess | 用任务 `recordVersion` 批量重新识别当前批次中使用旧规则且尚未映射的待审记录，返回 202 |
| GET | /api/admin/goods-sync/jobs/{id}/clean-logs | 按时间倒序分页查询本批次的 SKU 原文变更及清洗审计 |

前端先用 `latest?brandName=OPPO手机` 或 `latest?brandName=一加手机` 读取成功批次。历史批次的状态为 `NOT_STARTED` 时，调用 `clean-start`。所有任务路径中的 `{id}` 都传返回的 `importJobId`，`id` 是展示用的 `CLN-<导入ID>`。任务返回 `brandName`、`status`、`ruleVersion`、`recordVersion`、`createdAt`、`total`、`processed`、`progress`、`resultCounts`。`createdAt` 为关联导入任务创建时间，按北京时间精确到秒。状态为 `RUNNING` 时轮询详情，完成后刷新结果和日志。

任务状态为 `NOT_STARTED`、`RUNNING`、`COMPLETED`、`PARTIAL`、`FAILED`。进度按已分流数量计算，待审也属于已分流。`reprocessable` 为当前批次可用新规则重新识别的旧待审条数。结果状态可选 `PENDING`、`PROCESSING`、`AUTO_PASSED`、`REVIEW_REQUIRED`、`MANUAL_PASSED`、`ERROR`、`EXCLUDED`。结果列表返回 `items` 与 `total`，每行有来源 `id`、`importJobId`、原始商品名与货号、系列/型号/配置、`colorName`、`colorCandidates`、`evidence`、`issueCode`、`suggestedAction` 和 `recordVersion`。待审项可显示单个颜色候选，多个颜色时 `colorName` 为 null，候选见 `colorCandidates`。没有颜色线索时返回空数组。可用 `status=REVIEW_REQUIRED` 查询待审列表。批次统计来自当前来源记录。

命中业务排除规则的来源返回 `REVIEW_REQUIRED` 和 `suggestedAction=EXCLUDE`，拦在标准目录外。审核请求体均需 `recordVersion` 和 `reason`。`APPROVE` 另需 `target`，包含 `seriesName`、`modelName`、`spec`、`colorName`。已命中排除规则的来源只能排除。`EXCLUDE` 保留原文和历史。重试仅处理当前成功批次中的技术失败，版本过期、批次正在运行、来源已迁到新批次或无可重试记录时返回 409。规则版本由服务端固定，预览结果按来源文本预测，实际写入仍校验目标目录及其启用状态。

## 待实现的标准规格与颜色维护

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET/POST | /api/admin/catalog/variants | 标准规格列表或创建，字段 variantCode、brandName、seriesName、modelName、spec、displayName |
| PUT | /api/admin/catalog/variants/{id} | 带 recordVersion 修改允许的名称字段；不改已引用身份 |
| GET/POST | /api/admin/catalog/variants/{id}/colors | 查询或创建该规格的 colorName |
| PUT | /api/admin/catalog/variants/{id}/colors/{colorId} | 带 recordVersion 修改该规格颜色，父子归属必须一致 |
| POST | /api/admin/catalog/publications/preview、/publish | 明确对象类型、ID、版本集合预览和发布；版本变化返回 409 |
| POST | /api/admin/catalog/variants/{id}/disable、/api/admin/catalog/variants/{id}/colors/{colorId}/disable | 停用，不删除历史引用 |

自动创建/复用并启用规格颜色、映射及 SYSTEM 审计同一短事务，不走强制人工发布；publications 仅是异常纠正或手工草稿补充入口。SYSTEM 记录 ruleVersion，operatorId 为空；人工审核记 USER 和真实操作者。日志不存游标、执行令牌、外部SKU标识或完整响应；首期无独立在线规则编辑和历史批次回放接口。

## 待接入的申请人目录

GET /api/catalog/brands、/series、/models、/variants 从 ACTIVE 标准规格按上级字段过滤和去重。GET /api/catalog/variants/{id}/colors 仅返回当前租户该 ACTIVE 规格的 ACTIVE 颜色。

选择具体型号配置后明确 colorScope=ALL_COLORS 或 SPECIFIC_COLOR；后者传该规格下 variantColorId，服务端生成 color 名称快照。切换规格须清空旧颜色。
