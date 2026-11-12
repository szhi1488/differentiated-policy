# 商品管理接口契约

更新日期：2026-11-11。源商品原文的单品牌导入、查询和暂停后继续已实现。清洗、审核和规格颜色仍是设计契约。[数据字典](../sql/database-design.md) · [商品方案](../architecture/catalog-admin-design.md) · [清洗细则](../business/catalog-cleaning.md)

## 公共规则

沿用项目 Session、Origin、X-Policy-Request、DTO/VO 规范。每次管理操作重新检查有效 ADMIN 和 tenant_key；账套和租户绑定来自服务端配置。ID 按字符串，recordVersion 为整数。客户端不指定操作者、账套、租户、游标或分页请求频率。

列表默认每页 20、上限 100，排序和枚举白名单；不一次把全库返回浏览器。错误统一 400/401/403/404/409/503；上游错误以脱敏类别和本地 jobId 表示，不返回凭证或原始报文。

## 已实现：原始导入与来源查询

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | /api/admin/goods-sync/jobs | `brandName` 为 `OPPO手机` 或 `一加手机`，`idempotencyKey` 为小写 UUID；202 返回任务，固定只查询启用商品 |
| GET | /api/admin/goods-sync/jobs | 返回 `configured` 和两个品牌各自最新的 `jobs`（尚未导入的品牌没有任务） |
| POST | /api/admin/goods-sync/jobs/{id}/resume | 带 `recordVersion`；暂停且冷却结束后，从同一任务的断点继续 |
| POST | /api/admin/goods-sync/jobs/{id}/cancel | 带 `recordVersion`；只结束暂停任务，保留已提交商品原文和剩余冷却时间 |
| GET | /api/admin/source-goods | `brandName` 可省略，`keyword` 最长 128，按名称、货号、SKU 名称查子串；`page` 从 1 开始，`size` 默认 20、最大 100；返回 `items` 和 `total` |

任务返回 `id`、`brandName`、`status`、`cleanStatus`、`pageCount`、`readCount`、`contentCount`、`errorCode`、`canResume` 和 `recordVersion`。`status` 为 `QUEUED`、`RUNNING`、`PAUSED`、`SUCCEEDED`、`CANCELLED`。`SUCCEEDED` 表示原文导入完成；当前 `cleanStatus` 为 `NOT_STARTED`。来源列表返回本地 ID、品牌、七字段和 `cleanStatus`。部分提交的原文也可以查询，成功读入条次和去重内容数不是上游 SKU 总量。接口不下发游标、工作令牌或外部 SKU ID。

同一管理员、品牌、幂等键重复提交返回原任务；键冲突、有活动任务、冷却未结束或任务状态已变更返回 409。缺少租户/账套配置或数据库不可用返回 503。实际接口可在 Knife4j `/doc.html` 查看。

## 待实现：清洗和来源审核

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | /api/admin/source-goods/{id} | 原文、当前映射、候选/证据/原因 |
| POST | /api/admin/goods-sync/jobs/{id}/clean-preview | 选规则版本预览待处理及自动结果影响，不修改可用目录 |
| POST | /api/admin/goods-sync/jobs/{id}/clean-retry | 对已成功导入范围的 ERROR/PENDING 显式重试，带 recordVersion 和幂等键；正常导入无需手工触发 |
| GET | /api/admin/catalog/reviews | 分页查询 REVIEW_REQUIRED，按原因、品牌、型号筛选 |
| POST | /api/admin/source-goods/{id}/review | 带 recordVersion、decision、明确目标或纠正后的规范候选及 reason，审核人从会话取 |

品牌固定两品牌，来源原文不可编辑；后续清洗实现后从成功任务自动衔接。规则唯一且校验通过时，不需管理员再逐个建规格颜色或再发布一遍。review decision=APPROVE 时必须得到明确规格及本规格颜色，可选择已有ACTIVE目标，或由后端校验规范候选后原子创建；EXCLUDE 要原因并清除当前映射，原文及历史保留。缺颜色继续待审，不以空值默认全部颜色。客户端不得直接赋值 cleanStatus、mappingOrigin、reviewedBy 或规则可信性。

clean-preview 固定受控 ruleVersion，不接受可执行表达式；重试不覆盖 MANUAL 映射或 EXCLUDED，涉及重新指向、停用对象及人工结果须显式审核。POST 精确重放与运行版本由服务层实现，尚非已完成功能。

## 标准规格与颜色

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET/POST | /api/admin/catalog/variants | 标准规格列表或创建，字段 variantCode、brandName、seriesName、modelName、spec、displayName |
| PUT | /api/admin/catalog/variants/{id} | 带 recordVersion 修改允许的名称字段；不改已引用身份 |
| GET/POST | /api/admin/catalog/variants/{id}/colors | 查询或创建该规格的 colorName |
| PUT | /api/admin/catalog/variants/{id}/colors/{colorId} | 带 recordVersion 修改该规格颜色，父子归属必须一致 |
| POST | /api/admin/catalog/publications/preview、/publish | 明确对象类型、ID、版本集合预览和发布；版本变化返回 409 |
| POST | /api/admin/catalog/variants/{id}/disable、/api/admin/catalog/variants/{id}/colors/{colorId}/disable | 停用，不删除历史引用 |
| GET | /api/admin/catalog/operation-logs | 只读商品操作历史 |

自动创建/复用并启用规格颜色、映射及 SYSTEM 审计同一短事务，不走强制人工发布；publications 仅是异常纠正或手工草稿补充入口。SYSTEM 记录 ruleVersion，operatorId 为空；人工审核记 USER 和真实操作者。日志不存游标、执行令牌、外部SKU标识或完整响应；首期无独立在线规则编辑和历史批次回放接口。

## 申请人目录

GET /api/catalog/brands、/series、/models、/variants 从 ACTIVE 标准规格按上级字段过滤和去重。GET /api/catalog/variants/{id}/colors 仅返回当前租户该 ACTIVE 规格的 ACTIVE 颜色。

选择具体型号配置后明确 colorScope=ALL_COLORS 或 SPECIFIC_COLOR；后者传该规格下 variantColorId，服务端生成 color 名称快照。切换规格须清空旧颜色。
