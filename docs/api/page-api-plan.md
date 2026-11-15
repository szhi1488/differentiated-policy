# 页面接口总览

更新日期 2026-11-15。申请人选品、申请及历史接口已接入。管理员可查已提交政策、审核及预览覆盖；接口同时提供历轮快照和单商品有效政策查询。

## 政策页面

对外 `policyCode` 使用短单号，例如 `ZC-000005`，由已有申请 ID 生成，至少六位且不截断更长 ID。申请人、管理员和覆盖查询统一使用，驳回重提不变；管理员关键词可查短单号。沿用原表结构与创建幂等标识，无需迁移 SQL。

| 页面 | 接口 | 操作 |
| --- | --- | --- |
| 提交政策 `#/apply` | `GET /api/catalog/options`、`GET /api/catalog/goods` | 品牌、系列、机型版本联动筛选，支持配置多选和「全部配置」，读取实际在售商品 |
| 提交政策 `#/apply` | `POST /api/policies/drafts`、`PUT /api/policies/{id}/draft`、`POST /api/policies/{id}/submit` | 首次保存、继续保存、提交或重提 |
| 我的提交 `#/apply/submissions` | `GET /api/policies/mine`、`GET /api/policies/{id}` | 按状态、渠道、整单平台分页查本人申请，查看详情，`#/apply?policyId=<id>` 继续编辑草稿或驳回申请 |
| 我的提交 `#/apply/submissions` | `DELETE /api/policies/{id}/draft?recordVersion={version}` | 二次确认后物理删除本人未提交草稿，刷新列表和分页并清理该草稿编辑缓存 |
| 驳回原因（详情与继续编辑页） | `GET /api/policies/{id}/audit-logs?page=1&pageSize=1` | 读取并核对当前驳回原因，页面隐藏内部轮次 |
| 申请人历轮记录（仅后端接口） | `GET /api/policies/{id}/audit-logs`、`GET /api/policies/{id}/audit-logs/{logId}` | 保留日志与快照读取能力，申请人页面不提供历轮入口 |
| 差异化政策 `#/admin/policies` | `GET /api/admin/policies`、`GET /api/admin/policies/{id}`、`POST /api/admin/policies/{id}/review` | 筛选已提交政策，查看本轮快照及覆盖状态，通过或驳回当前轮次 |
| 管理员历轮审核（后端接口） | `GET /api/admin/policies/{id}/audit-logs`、`GET /api/admin/policies/{id}/audit-logs/{logId}` | 分页读取历轮记录及指定轮次快照。当前页面展示本轮审核结果 |
| 管理员覆盖与有效政策查询（后端接口） | `GET /api/admin/policies/{id}/override-preview`、`GET /api/admin/policies/effective` | 分别按假设通过时点预览覆盖、按查询时点读取适用政策。当前页面通过详情接口读取覆盖预览和生效范围 |

- 两个抽屉均支持配置多选，「全部配置」按当前品牌、系列和机型范围全选。普通卡默认跨页勾选所选配置的在售商品。翻页、搜索或再次选择配置后，手动取消的商品仍保持取消。草稿和正式提交保存最终勾选的 `goodsId`。指定卡单选一件商品，数量按平台选填。[选品规则](../business/perspective-rules.md#选商品与配置)
- 已有普通卡取消配置时，按完整卡片暂存该配置全部商品的移除，确认保存配置后才更新表单；取消编辑不改原卡。申请地址切换复用未保存内容确认，取消导航不发起目标详情请求，确认后继续使用原有 `GET /api/policies/{id}`。两处修复未新增接口或修改 DTO、数据库结构。
- `#/apply` 的「提交申请」先保存表单，再传返回的 `recordVersion` 调用提交接口。驳回重提沿用原申请 ID 和单号，新增一轮。确认成功后移除地址中的申请 ID，清理表单和本地附件状态并返回新申请页首；失败或结果待核对时保留输入。已提交政策从「我的提交」查看。[申请人接口](policy-api.md)
- 「我的提交」列表不显示提交轮次，详情只展示当前申请，不提供历轮审核或快照入口。后台保留日志和快照。草稿及已驳回申请的编辑入口回填当前表单，待审核和已通过申请只读。[申请人接口](policy-api.md)
- 已驳回申请在详情和继续编辑页顶部直接显示本轮驳回原因、审核人及时间，复用本人审核日志接口读取最新一轮并校验轮次。原因未填写、读取失败和本轮日志缺失分别提示；失败或缺失支持重试。[详情展示](../business/perspective-rules.md#我的提交详情展示)
- 「我的提交」当前申请使用商品及补贴组件，原卡片内按机型版本、配置汇总已选颜色，平台直接对应补贴，指定卡保留各平台数量。页面读取当前详情接口，待审核和已通过内容仍由服务端从提交快照返回。展示规则见[详情展示](../business/perspective-rules.md#我的提交详情展示)，本次实现的测试和模拟接口预览见[验证记录](../architecture/frontend-development.md#2026-11-15-我的提交商品及补贴展示)。
- 管理员「查看」和「审核」使用同一个详情接口。机型、实际勾选的颜色、平台数量和补贴均来自本轮快照。驳回时小窗填写的意见用 `reviewRemark` 提交。[管理员工作台](../architecture/admin-workspace.md#差异化政策)

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
