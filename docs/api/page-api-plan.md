# 页面接口总览

更新日期 2026-11-14。政策基础接口共 14 个。申请人选品 2 个、申请与历史 7 个均已接入，管理员审核与历史 5 个待实现。现有登录、商品管理及独立附件接口按页面复用。

## 政策页面

| 页面 | 接口 | 操作 |
| --- | --- | --- |
| 提交政策 `#/apply` | `GET /api/catalog/options`、`GET /api/catalog/goods` | 品牌、系列、机型版本联动筛选，支持配置多选和「全部配置」，读取实际在售商品 |
| 提交政策 `#/apply` | `POST /api/policies/drafts`、`PUT /api/policies/{id}/draft`、`POST /api/policies/{id}/submit` | 首次保存、继续保存、提交或重提 |
| 我的提交 `#/apply/submissions` | `GET /api/policies/mine`、`GET /api/policies/{id}` | 按状态、渠道、整单平台分页查本人申请，查看详情，`#/apply?policyId=<id>` 继续编辑草稿或驳回申请 |
| 我的提交 `#/apply/submissions` | `GET /api/policies/{id}/audit-logs`、`GET /api/policies/{id}/audit-logs/{logId}` | 查本人各轮审核结果及该轮快照 |
| 管理员政策审核 `#/admin/policies`（规划） | `GET /api/admin/policies`、`GET /api/admin/policies/{id}` | 按待审、通过和驳回等状态筛选，查看当前提交快照 |
| 管理员政策审核 `#/admin/policies`（规划） | `GET /api/admin/policies/{id}/audit-logs`、`GET /api/admin/policies/{id}/audit-logs/{logId}`、`POST /api/admin/policies/{id}/review` | 查历轮记录，审核当前轮次 |

- 两个抽屉均支持配置多选，「全部配置」按当前品牌、系列和机型范围全选。普通卡默认跨页勾选所选配置的在售商品。翻页、搜索或再次选择配置后，手动取消的商品仍保持取消。草稿和正式提交保存最终勾选的 `goodsId`。指定卡单选一件商品，数量按平台选填。[选品规则](../business/perspective-rules.md#选商品与配置)
- `#/apply` 的「提交申请」先保存表单，再传返回的 `recordVersion` 调用提交接口。驳回重提沿用原申请 ID 和单号，新增一轮。[申请人接口](policy-api.md)
- 「我的提交」详情可切换到「历轮审核」，查看审核结果及每轮原始快照。草稿及已驳回申请的编辑入口回填当前表单，待审核和已通过申请只读。[申请人接口](policy-api.md)

## 现有页面复用接口

| 页面或操作 | 接口 |
| --- | --- |
| 飞书登录、会话与退出 | `GET /api/feishu/config`、`POST /api/feishu/js-sdk/signature`、`POST /api/auth/challenge`、`POST /api/auth/feishu/login`、`POST /api/auth/feishu/browser/authorize`、`POST /api/auth/feishu/browser/login`、`GET /api/auth/session`、`POST /api/auth/logout` |
| 源商品库 `#/admin/catalog/sources` | `GET /api/admin/source-goods`、`GET /api/admin/goods-sync/jobs`、`POST /api/admin/goods-sync/jobs`、`POST /api/admin/goods-sync/jobs/{id}/resume`、`POST /api/admin/goods-sync/jobs/{id}/cancel` |
| 商品清洗与识别 `#/admin/catalog/cleaning` | `GET /api/admin/catalog/cleaning/preview`、`GET /api/admin/catalog/cleaning/results`、`GET /api/admin/catalog/cleaning/jobs`、`GET /api/admin/catalog/cleaning/jobs/active`、`GET /api/admin/catalog/cleaning/jobs/{id}`、`POST /api/admin/catalog/cleaning/jobs` |
| 来源详情、人工审核与重试 | `GET /api/admin/source-goods/{id}`、`POST /api/admin/source-goods/{id}/review`、`POST /api/admin/source-goods/{id}/clean-retry` |
| 商品中心 `#/admin/catalog/center` | `GET /api/admin/catalog/goods`、`GET /api/admin/catalog/goods/summary`、`GET /api/admin/catalog/goods/{id}`、`POST /api/admin/catalog/goods`、`PUT /api/admin/catalog/goods/{id}`、`DELETE /api/admin/catalog/goods/{id}` |
| 商品数据字典 | `GET /api/admin/catalog/dictionary`、`POST /api/admin/catalog/dictionary/{type}`、`PUT /api/admin/catalog/dictionary/{type}/{id}`、`DELETE /api/admin/catalog/dictionary/{type}/{id}` |
| 文件上传与临时文件操作 | `POST /api/attachments`、`GET /api/attachments/{id}/content`、`DELETE /api/attachments/{id}`。独立接口已实现，申请页上传和政策绑定待接入 |

参数与权限见 [飞书鉴权](../architecture/feishu-auth-flow.md)、[商品管理接口](catalog-api.md)、[附件接口](attachment-api.md)。商品编辑、删除和系列引用检查使用新基线的 `policy_goods_detail`。商品中心模板下载和当前页导出复用前端现有逻辑。

## 后续能力

| 能力 | 接口安排 |
| --- | --- |
| 政策附件 | 保存表单时绑定上传文件 ID 或外部链接。管理员按申请和提交轮次下载，校验该文件属于所查快照。历史附件保留按[附件接口](attachment-api.md#政策接入规划)实施 |
| 覆盖预览 | `GET /api/admin/policies/{id}/override-preview`，按同平台同商品和时间交集查询候选。时间端点、优先级及替换规则确认后接入对应审核和生效逻辑 |
| 有效政策查询 | 先复用管理员列表筛选。业务口径确认后，再定查询时点、平台、`goodsId` 与最终适用政策的响应契约 |
| 标准商品批量导入 | 商品中心已有入口，建议 `POST /api/admin/catalog/goods/import`，文件校验、重复及错误行处理待定 |

## 数据写入和实施顺序

| 操作 | 三表处理 |
| --- | --- |
| 保存草稿 | 写 `policy_apply`，保留当前表单、卡片、勾选商品和方案分配 |
| 提交或重提 | 同一事务更新 `policy_apply`，新增 `policy_audit_log` 本轮快照，按轮次、实际平台、最终勾选商品写入 `policy_goods_detail` |
| 审核 | 同一事务更新申请状态和该轮审核结果。继续保留提交快照和商品明细 |
| 查历史和商品引用 | 历史从本轮快照读取，按商品筛选和引用保护使用商品明细 |

下一步接入管理员政策审核，先对齐管理员可见范围、自审和驳回意见要求，再联调各轮审核结果在「我的提交」中的回显。审核请求需携带 `recordVersion` 和 `submissionVersion`。附件上传及覆盖查询分别按上表推进。[管理员审核接口](admin-api.md)

目标库联调可选同一机型的多个配置，使用「全部配置」核对当前筛选范围。取消一件商品后核对翻页状态、草稿、提交快照和平台明细中的商品 ID。用另一位有效申请人验证列表隔离及详情、历史的访问限制。
