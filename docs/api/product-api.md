# 商品查询与管理员更新接口设计

更新日期：2026-11-10。以下全部为待实现契约草案，本轮仅完成 SQL 与文档设计。身份、来源保护、ID 字符串和错误格式沿用 [政策接口](policy-api.md)。业务规则见 [商品目录同步](../business/product-catalog.md)。

## 申请人查询

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/products` | 本地可选商品分页搜索，不请求飞书 |
| GET | `/api/products/{id}` | 本租户、配置来源下的本地商品详情与当前版本 |

`GET /api/products` 参数拟为 `keyword`、`page`（默认 1）、`pageSize`（默认 20，最大 100）。关键词匹配型号、单型号和配置，参数化查询并将 LIKE 的 `%`、`_`、转义字符按普通字符处理；默认 `id ASC`，排序和分页由后端控制。前端搜索可防抖，不一次加载全部商品。

响应含 `items`、`total`、`page`、`pageSize`、`lastSuccessfulSyncAt`。每项为 `productId`（字符串）、`productVersion`（正整数）、`modelFullName`、`singleModel`、`spec`。最近成功同步时间来自当前来源 SUCCESS 批次，不用最近失败时间替代；未成功同步为 null，界面提示「商品目录尚未更新」，不是上游故障时伪报空表。

列表只返回可选商品；详情可返回同范围内停用商品并包含 `available: false`，供草稿提示重新选择，不可用于新选择。其他租户/来源或不存在 ID 返回 404。历史政策读取快照，不依赖此商品详情接口。

## 管理员更新与结果查询

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/admin/products/sync` | 为本租户配置来源发起手动同步 |
| GET | `/api/admin/products/sync-logs` | 分页读取本租户配置来源的同步记录 |
| GET | `/api/admin/products/sync-logs/{id}` | 查询本批状态与结果 |

每次请求重新核验数据库中的有效用户、ADMIN 与租户；更新使用可信 Origin、`X-Policy-Request: 1`、JSON `{}`。客户端不能传 token、任意源链接、租户或操作者。应用身份访问飞书的权限、Base 来源和 field ID 由后端环境或被忽略的本地配置注入。

同步拟采用后台任务；工作任务取得本范围锁、修复旧 RUNNING 并持久化新 RUNNING 后才通知请求线程响应 202，包含 `syncId`（字符串）、`status`、`startedAt`；202 只表示已接受，不显示「更新成功」。未能取得同来源锁返回 409，不创建新批次。任务调度失败且尚未创建批次时直接返回 503；已经创建批次后执行失败须写 FAILED；进程中断后的 RUNNING 按业务文档恢复，不能永久显示执行中。任务执行器、专用连接、锁生命周期尚未实现，接入时必须验证。

详情返回 `syncId`、`status`、`operatorName`、`startedAt`、`finishedAt`、`fetchedCount`、`createdCount`、`updatedCount`、`disabledCount`、`unchangedCount`、`errorCode`。终态前按需轮询结果；只有 SUCCESS 才更新成功时间并显示已应用数量。日志列表 `page/pageSize` 规则同商品列表，按 `id DESC`，不返回访问凭证、原始飞书响应或底层异常堆栈。

## 保存与提交的关联

政策 detail 请求携带 `productId`、`productVersion`。商品三列文本由服务端派生，详情与提交快照返回三个字段；更换商品时联动更新，不能仅改配置。读取/核验针对本地目录，飞书在管理员更新前发生的变化不会即时反映到政策提交。

保存或提交若商品停用、商品版本不同，返回 409 `PRODUCT_UNAVAILABLE` / `PRODUCT_VERSION_CONFLICT`，保留表单填写内容，提示重新确认商品。商品版本不同于政策 `recordVersion`，两个版本各自校验。

## 错误与验收

| HTTP / 结果 | 建议代码 | 场景 |
| --- | --- | --- |
| 400 | PRODUCT_VALIDATION_FAILED | 搜索、分页、商品 ID 或版本参数非法 |
| 401 | LOGIN_REQUIRED | 未登录或会话到期 |
| 403 | PRODUCT_ACCESS_DENIED | 非管理员更新、停用账号或来源不可信 |
| 404 | PRODUCT_NOT_FOUND / PRODUCT_SYNC_NOT_FOUND | 不存在或超出查询范围 |
| 409 | PRODUCT_SYNC_RUNNING | 同范围已有同步 |
| 409 | PRODUCT_UNAVAILABLE / PRODUCT_VERSION_CONFLICT | 政策选择的商品停用或版本变化 |
| 503 | PRODUCT_SOURCE_NOT_CONFIGURED / PRODUCT_STORAGE_UNAVAILABLE | 配置缺失或数据库不可用 |
| FAILED 批次 | SYNC_UPSTREAM_FAILED / SYNC_DATA_INVALID / SYNC_SOURCE_UNVERIFIED / SYNC_EMPTY_SOURCE / SYNC_INTERRUPTED | 受理后的上游失败、数据不合法、范围无法确认、空结果或进程中断 |

同步失败不删除旧目录、不改历史政策，FAILED 数量不冒充成功应用数量。实现时补 Knife4j、参数与权限校验、全分页及回滚/并发测试；目前没有运行期能力及真实接口验证结果。
