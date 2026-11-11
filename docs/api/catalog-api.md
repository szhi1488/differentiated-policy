# 商品管理接口契约

更新日期：2026-11-11。本文记录本应用对前端提供的商品 HTTP 接口契约，表内接口均待实现。当前后端已实现 `JackyunGoodsService` 单页只读查询，管理员源商品库使用静态样例。正式导入后自动清洗，明确结果自动进入标准库，异常进入人工审核。[数据字典](../sql/database-design.md) · [商品方案](../architecture/catalog-admin-design.md) · [清洗细则](../business/catalog-cleaning.md)

## 公共规则

沿用项目 Session、Origin、X-Policy-Request、DTO/VO 规范。每次管理操作重新检查有效 ADMIN 和 tenant_key；sourceKey 只能来自服务端已授权账套白名单。ID、长游标按字符串，recordVersion 为整数，客户端不指定操作者和租户。

列表默认每页 20、上限 100，排序和枚举白名单；不一次把全库返回浏览器。错误统一 400/401/403/404/409/503；上游错误以脱敏类别和本地 jobId 表示，不返回凭证或原始报文。

## 同步与来源

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | /api/admin/goods-sync/jobs | 创建两品牌全量任务，显式 sourceKey、includeBlockup 和幂等键；202 返回 jobId |
| GET | /api/admin/goods-sync/jobs、/api/admin/goods-sync/jobs/{id} | 导入 status 和独立 cleanStatus、分区进度、当前分类计数及脱敏错误 |
| POST | /api/admin/goods-sync/jobs/{id}/pause、/resume、/cancel | 带 recordVersion 的任务控制 |
| GET | /api/admin/source-goods、/api/admin/source-goods/{id} | 七字段、品牌、当前生效映射和独立清洗状态/候选/证据/原因，不返回外部 SKU ID |
| POST | /api/admin/goods-sync/jobs/{id}/clean-preview | 选规则版本预览待处理及自动结果影响，不修改可用目录 |
| POST | /api/admin/goods-sync/jobs/{id}/clean-retry | 对已成功导入范围的 ERROR/PENDING 显式重试，带 recordVersion 和幂等键；正常导入无需手工触发 |
| GET | /api/admin/catalog/reviews | 分页查询 REVIEW_REQUIRED，按原因、品牌、型号筛选 |
| POST | /api/admin/source-goods/{id}/review | 带 recordVersion、decision、明确目标或纠正后的规范候选及 reason，审核人从会话取 |

品牌固定两品牌，来源原文不可编辑；导入成功后后端自动衔接清洗。规则唯一且校验通过时，不需管理员再逐个建规格颜色或再发布一遍。review decision=APPROVE 时必须得到明确规格及本规格颜色，可选择已有ACTIVE目标，或由后端校验规范候选后原子创建；EXCLUDE 要原因并清除当前映射，原文及历史保留。缺颜色继续待审，不以空值默认全部颜色。客户端不得直接赋值 cleanStatus、mappingOrigin、reviewedBy 或规则可信性。

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

本轮仅交付契约，不修改 Controller、Service 或前端；实现时同步 Knife4j 及鉴权、归属、并发、幂等和失败恢复测试。
