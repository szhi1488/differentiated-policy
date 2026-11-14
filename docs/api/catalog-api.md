# 商品管理接口契约

更新日期：2026-11-14。源商品导入、清洗、审核、技术重试以及标准商品查询、新增、编辑、删除和数据字典维护已接入十四表基线。清洗记录按成员展示任务进度与结果统计。[数据字典](../sql/database-design.md) · [商品方案](../architecture/catalog-admin-design.md) · [清洗细则](../business/catalog-cleaning.md)

## 公共规则

沿用项目 Session、Origin、X-Policy-Request、DTO/VO 规范。每次管理操作重新检查有效 ADMIN 和公司飞书租户。账套来自服务端配置，所有有效管理员访问同一套数据。新基线的商品相关表不保存租户字段。ID 按字符串，recordVersion 为整数。客户端不指定操作者、账套、租户、游标或分页请求频率。

列表默认每页 20、上限 100，排序和枚举白名单；不一次把全库返回浏览器。错误统一 400/401/403/404/409/503；上游错误以脱敏类别和本地 jobId 表示，不返回凭证或原始报文。

## 已实现：原始导入与来源查询

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | /api/admin/goods-sync/jobs | `brandName` 为 `OPPO手机` 或 `一加手机`，`idempotencyKey` 为小写 UUID；`mode` 为 `FULL`（默认）、`CREATED` 或 `MODIFIED`；按日须传 `date`（北京时间 `YYYY-MM-DD`）；包含停用 SKU，202 返回任务 |
| GET | /api/admin/goods-sync/jobs | 返回 `configured` 和两个品牌各自最新的 `jobs`（尚未导入的品牌没有任务） |
| POST | /api/admin/goods-sync/jobs/{id}/resume | 带 `recordVersion`；暂停且冷却结束后，从同一任务的断点继续 |
| POST | /api/admin/goods-sync/jobs/{id}/cancel | 带 `recordVersion`；只结束暂停任务，保留已提交商品原文和剩余冷却时间 |
| GET | /api/admin/source-goods | `brandName` 可省略，`keyword` 最长 128，按名称、货号、SKU 名称或完整分类路径查子串，或按 SKU ID 精确查询；`createdDate` 可按北京时间建档日 `YYYY-MM-DD` 筛选已导入的 SKU；`page` 从 1 开始，`size` 默认 20、最大 100；返回 `items` 和 `total` |

任务返回 `id`、`brandName`、`syncMode`、`syncDate`、`syncPhase`、`status`、`pageCount`、`readCount`、`contentCount`、`errorCode`、`canResume` 和 `recordVersion`。`status` 为 `QUEUED`、`RUNNING`、`PAUSED`、`SUCCEEDED`、`CANCELLED`。导入完成后保留来源，清洗由管理员另行发起。修改日任务依次查询 `GOODS_MODIFIED`、`SKU_MODIFIED`，每阶段保留断点。来源列表返回本地 ID、品牌、原始七字段、字符串 `skuId`、`cateFullName`、毫秒时间戳 `gmtModified` 和 `goodsGmtModified`、可空布尔标记 `isBlockup` 和 `isPackageGood`，以及 `cleanStatus`。`readCount` 为已提交读取条次，`contentCount` 为本任务不同 SKU 数。部分提交的原文可查询，游标和工作令牌只在服务端维护。

同一管理员、品牌、模式、日期和幂等键重复提交返回原任务；键冲突、有活动任务、冷却未结束或任务状态已变更返回 409。缺少租户/账套配置或数据库不可用返回 503。实际接口可在 Knife4j `/doc.html` 查看。

新基线以 `skuId` 唯一匹配源商品。已有 SKU 的吉客云来源未变时保留商品内容、更新时间及清洗状态，导入任务仍推进。

来源列表的 `createdDate` 是吉客云按建档日查询命中的日期，返回 `YYYY-MM-DD`，缺少已确认记录时为空。筛选与品牌、关键词和分页同时生效。日期依据来自商品操作日志及首次、最近导入任务。后续全量或按修改日导入时，已有日期证据仍可查询。

来源列表的 `gmtModified`、`goodsGmtModified` 分别对应已入库的吉客云规格和货品修改时间，均为 Unix 毫秒，库中缺值时各自返回 null。前端按北京时间分别格式化，保留非零毫秒。「导入商品」弹窗发起 `FULL` 或 `CREATED` 任务，服务端同时保留 `MODIFIED` 契约。[管理员工作台](../architecture/admin-workspace.md)

## 已实现的清洗和来源审核

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | /api/admin/catalog/cleaning/results | 按 `brandName`、`createdDate`、`unknownDate`、`keyword`、`status` 和分页查询当前 SKU，同时返回同范围的状态计数 |
| GET | /api/admin/catalog/cleaning/preview | `brandName` 必填，`createdDate` 可选，`includeReviews` 默认 false。返回 `items`、`total`、`eligible`。关键词仅影响列表和 total |
| POST | /api/admin/catalog/cleaning/jobs | 用 `brandName`、`createdDate`、`includeReviews`、`expectedTotal`、`requestKey` 创建任务，返回 202 |
| GET | /api/admin/catalog/cleaning/jobs | 分页查看本账套清洗历史 |
| GET | /api/admin/catalog/cleaning/jobs/active | 获取进行中任务，无活动任务返回 204 |
| GET | /api/admin/catalog/cleaning/jobs/{id} | 查看任务范围、发起人、状态、进度和统计 |
| GET | /api/admin/source-goods/{id} | 查看原文、候选、命中证据、当前标准商品映射（`catalogBrandId`、`goodsId`）及最近审核信息 |
| POST | /api/admin/source-goods/{id}/review | 带 `recordVersion` 和 `decision` 审核，支持通过 `APPROVE` 和不通过 `EXCLUDE` |
| POST | /api/admin/source-goods/{id}/clean-retry | 带 `recordVersion` 和 `requestKey` 重试一条 `ERROR`，返回 202 |

品牌可选 OPPO手机、一加手机。查询结果省略品牌汇总两品牌，省略状态显示全部。日期为北京时间 `YYYY-MM-DD`，`unknownDate=true` 与具体日期互斥。状态查询支持 `PENDING`（包括旧处理中状态）、`PASSED`（自动和人工通过）、`REVIEW_REQUIRED`、`ERROR`、`EXCLUDED`。关键词最多 128 字符，查商品名或货号。页码从 1 开始，每页 1 至 100 条。计数按品牌、建档日和关键词计算，和当前页签无关。

开始清洗指定一个品牌，日期为空时涵盖该品牌全部已入库 SKU。实际纳入已完成导入或已结束导入中已落库的待清洗 SKU，可选未映射的待审 SKU。进行中或暂停的最近导入需先完成或结束。确认数量必须大于 0；实际数量变化返回 `409 CATALOG_CLEAN_SCOPE_CHANGED`。幂等请求键同账套唯一，重复请求返回已有任务，活动任务范围相同可直接返回该任务。其他范围在同账套有运行任务时返回 `409 CATALOG_CLEAN_ACTIVE`。

清洗任务独立于导入任务，ID 为字符串。任务返回 `brandName`、`createdDate`、`includeReviews`、`targetSourceId`、`status`、`requesterName`、`createdAt`、`total`、`processed`、`progress` 和 `resultCounts`。新基线任务状态为 `RUNNING`、`COMPLETED`、`PARTIAL`、`FAILED`。待人工审核算作已分流，确认后发生来源变化的成员记为 `SKIPPED`，保留在本轮统计中。

结果每行返回来源 `id`、品牌、商品名、货号、SKU 名称、建档日、状态、系列、型号、配置、颜色、颜色候选、识别说明、原因码、建议操作及 `recordVersion`。审核 `APPROVE` 须提交完整 `target`，包含 `seriesName`、`modelName`、`spec`、`colorName`。审核不通过用 `EXCLUDE`，商品进入已排除。命中排除规则的来源只允许审核不通过。兼容旧请求的可选 `reason`，省略或留空时服务端按决定记录「审核通过」或「审核不通过」。旧记录版本或不允许的状态返回 409。

## 已实现的标准商品目录

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | /api/admin/catalog/goods | 新增标准商品，成功返回 201 和商品详情 |
| GET | /api/admin/catalog/goods | 按 `brandName` 精确匹配品牌，`seriesName`、`modelName` 按子串匹配。可按 `keyword` 搜索，按 `seriesId` 精确筛选系列，按 `spuId` 精确筛选机型版本，`ram` 和 `rom` 同时传入时精确筛选配置。支持销售状态 `status` 及 `page`、`size`，返回 `items` 和 `total` |
| GET | /api/admin/catalog/goods/summary | 返回商品总数、在售数、停产数、品牌系列及机型版本的总数和在售数、可选配置组合 |
| PUT | /api/admin/catalog/goods/{id} | 保存商品编辑，成功返回 200 和最新商品详情 |
| DELETE | /api/admin/catalog/goods/{id} | 永久删除未被引用的标准商品。成功或商品已不存在时返回 204，存在引用时返回 409 |
| GET | /api/admin/catalog/goods/{id} | 按标准商品 ID 查详情，商品不存在返回 404。详情和列表均返回代表性关联来源的 `sourceSkuId`、`sourceName` |

`keyword` 最多 128 字符，输入中的全角英数字会转为半角，英文字母不区分大小写。忽略空白输入，多个词以空格分隔，每个词须命中同一商品的至少一个可搜索字段。可搜索字段包括标准商品名称、品牌、系列、机型、版本、颜色、货号、标准 SKU 和关联来源 SKU。商品名和机型中的普通空格可省略，来源 SKU 仅用 1 至 19 位数字匹配，支持部分号码。关键词、其他筛选及分页同时生效。

列表和统计基于当前标准商品记录。销售 `status` 取 1 在售、2 待上架、3 已下架、4 已停产，响应可为 null（未知）。分页从 1 开始，默认 20 条，最多 100 条。按更新时间和 ID 降序，`ascending=true` 时升序。更新时间按北京时间精确到秒。

商品中心以 `goods.id` 标识商品，关联品牌、系列、机型版本和独立颜色表返回名称。商品响应包含本地 SKU、货品编号、标准商品名称、RAM、ROM、销售状态。响应中的 `skuId` 为本地标准 SKU，`sourceSkuId` 为关联来源的吉客云 SKU 字符串。多条来源归并时，`sourceSkuId` 与 `sourceName` 均取本地来源 ID 最小的一条，名称使用来源原文 `goodsName`。缺少来源时两项均为 null。来源货号及其他原文可通过已授权的来源详情查询。商品列表按品牌、系列、机型版本、关键词和状态分页，服务端返回公司共用目录。

删除在事务内检查引用。关联来源 SKU 时返回 `CATALOG_GOODS_SOURCE_BOUND`，商品或其配置被政策引用时返回 `CATALOG_GOODS_POLICY_BOUND`，数据库拒绝删除其他已关联记录时返回 `CATALOG_GOODS_REFERENCED`。删除成功释放规格唯一键，原商品 ID 查询详情返回 404。

自动清洗通过时，标准目录、来源 `goodsId` 和任务成员结果同事务写入。人工审核通过时，同事务保存标准目录、来源映射和审核信息，本轮任务成员保留当时的分流结果。自动清洗的审核人为空，人工审核记录实际操作人。重复处理复用同配置同颜色的商品。修改现有映射按来源版本校验，冲突返回 409。标准商品批量导入接口待接入。

新增商品提交 `name`、`brandId`、`seriesId`、`modelName`、`ram`、`rom`、`colorId` 和销售 `status`，`edition` 可空。RAM 支持正整数 GB，ROM 支持正整数 GB、T 或 TB，省略单位时按 GB 处理。ROM 的 1T 与 1024GB 统一存为 1024GB。同品牌同机型版本必须归属同一系列，同机型版本、RAM、ROM 和颜色重复返回 409。新增商品由服务端生成本地标准 SKU，来源关联和货号留空。

### 商品编辑

打开编辑时先请求 `GET /api/admin/catalog/goods/{id}`。提交 `PUT /api/admin/catalog/goods/{id}` 时，将详情返回的 `editVersion` 原样传为 `expectedVersion`。这是服务端根据商品当前字段和更新时间生成的 64 位小写十六进制字符串。每次成功后使用响应中的最新版本继续编辑。

`product` 提交与新增相同的一组字段，ID 使用字符串，`edition` 可空。当前销售状态为空时可传 `status: null`，已有明确状态时须传 1 至 4。以下是请求体示例，提交时替换为实际详情版本和字典 ID。

```json
{
  "expectedVersion": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "product": {
    "name": "Find X9s Pro 12GB+256GB 钛色",
    "brandId": "4",
    "seriesId": "7",
    "modelName": "Find X9s Pro",
    "edition": "",
    "ram": "12GB",
    "rom": "256GB",
    "colorId": "3",
    "status": 1
  }
}
```

成功返回 200 和最新详情。商品 ID、本地 SKU、货号和来源映射均由服务端保留。编辑机型或版本时，复用匹配的机型版本或新建该机型版本，共享机型与字典记录保留原值。规范化后的字段与当前值相同时，返回当前详情和版本。

编辑限公司有效管理员，遵循现有写请求来源校验。参数无效返回 400，会话无效返回 401，权限或写请求来源校验失败返回 403，商品不存在返回 404。以下情况返回 409。

| 错误码 | 触发条件 |
| --- | --- |
| `CATALOG_GOODS_CHANGED` | 读取后商品已被修改，或并发更新冲突 |
| `CATALOG_GOODS_DUPLICATE` | 其他商品已占用目标机型版本、配置和颜色 |
| `CATALOG_SERIES_CONFLICT` | 目标机型版本已归属同品牌的其他系列 |
| `CATALOG_GOODS_SOURCE_BOUND` | 已关联源商品的标准商品试图改品牌 |
| `CATALOG_GOODS_POLICY_BOUND` | 已被政策引用的商品试图改规格或颜色，或已被政策引用的配置试图改机型版本或容量 |

收到版本冲突时，保留当前输入并重新读取详情核对，确认后再用新版本提交。

## 商品数据字典

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | /api/admin/catalog/dictionary | 返回品牌、系列和颜色，含字符串 `id`、`parentId`、`type`、`name` 和引用标记 `inUse` |
| POST | /api/admin/catalog/dictionary/{type} | 新增字典项。系列须传所属品牌的 `parentId`。成功返回 204 |
| PUT | /api/admin/catalog/dictionary/{type}/{id} | 修改未被引用字典项的名称，提交 `name` 和读取时的 `expectedName`。成功返回 204 |
| DELETE | /api/admin/catalog/dictionary/{type}/{id} | 删除未被引用的字典项，查询参数 `expectedName` 为读取时的名称。成功返回 204 |

`type` 取 `brand`、`series`、`color`。系列创建后固定所属品牌。同名、引用、旧名称或锁冲突返回 409，查询超时和数据库不可用返回 `503 CATALOG_STORAGE_UNAVAILABLE`。修改和删除在事务内检查当前名称和引用关系，实际新增、改名或删除须影响一行，否则返回 `409 CATALOG_DICTIONARY_CHANGED`。外键保护已有映射，字典读写沿用公司管理员权限与写请求来源校验。

系列的 `inUse` 表示该系列的机型仍被商品或政策明细引用。只剩无引用机型时，系列可以改名或删除。删除系列时，后台在同一事务清理这些机型。发现引用时返回 `409 CATALOG_DICTIONARY_IN_USE`。

## 待接入的申请人目录

按品牌、系列、机型版本筛选，从标准商品按 `spuId`、`ram`、`rom` 去重得到配置。选配置后返回该配置下标准商品的 `goodsId` 和字典颜色名称。切换配置清空旧颜色。

选择 ALL_COLORS 时提交配置和颜色范围。选择 SPECIFIC_COLOR 时另传该配置下的 `goodsId`，颜色快照由服务端生成。请求契约见 [政策接口](policy-api.md)。
