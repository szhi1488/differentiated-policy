# 本地附件接口

更新日期：2026-11-15。申请页已接通文件上传、草稿绑定与回填、提交快照，以及申请人和管理员的政策附件下载。

Element Plus Upload 负责选文件，`useAttachments` 通过统一请求层逐个上传。页面按实际响应展示上传中、已上传、失败和重试。上传期间锁定编辑与写操作，失败文件须重试或移除后再保存或提交。账号变化或退出会丢弃迟到响应并清理本地文件状态。

## 存储和配置

用户指定项目根目录 `data` 为存储根目录，本机最终位置：

```text
C:/Users/admin/Desktop/differentiated policy/data/
  attachments/
    <随机 UUID>/
      content          文件原始内容，下载时恢复原文件名
      metadata.json    上传者 ID、租户及文件展示信息
```

从项目根目录或 `backend/` 启动均定位到同一个 `data`，首次上传自动创建目录。其他工作目录或部署环境通过 `POLICY_STORAGE_ROOT` 指定绝对路径；值不含 `attachments`。Windows properties 使用正斜杠，例如 `POLICY_STORAGE_ROOT=C:/Users/admin/Desktop/differentiated policy/data`。

`data/` 已加入 Git 忽略，不公开为静态资源。更新应用时保留整个目录，备份同时包含内容及元数据；不能仅复制 `content`。随机命名避免同名覆盖，原文件名不参与路径拼接。上传先写 `.upload-<UUID>`，验证和元数据写入完成后移动至最终目录；常规失败清理临时文件。强制结束进程可能遗留 `.upload-*`，不对外提供访问；管理员应在服务停止后核对清理。

## 当前工程限制

- 沿用现有工程限制，每份申请最多 10 个文件，单文件最大 20MiB，multipart 总大小最大 21MiB。前后端均校验文件数量或大小。这些限制不是用户总配额，最终业务容量要求仍可另行确认。
- 支持 `pdf/doc/docx/xls/xlsx/png/jpg/jpeg`；文件名最多 180 字符，拒绝目录分隔符、控制字符及 Windows 非法字符。
- 后端检查扩展名和文件头；docx/xlsx 另检查 ZIP 目录中对应文档入口并拒绝 VBA 包。不执行、不解压、不在线预览文件；这些检查不等于病毒扫描或完整格式校验，旧版 doc/xls 仅检查 OLE 文件头。
- 每次操作查询当前用户记录，拒绝停用或身份失效账号。独立接口仅供上传者操作，管理员通过按申请和提交轮次鉴权的接口下载。
- 上传成功后取得文件 ID，保存草稿将引用写入 `policy_apply.attachment_urls`，刷新从草稿恢复文件名和下载入口，正式提交继续写入本轮快照。上传响应和政策保存、提交分别展示结果。
- 超时或网络中断时结果可能不明，不自动重放上传。人工重试可能留下额外的未绑定文件。当前没有独立附件列表、幂等上传、用户总配额或未绑定文件自动清理。人工整理须核对元数据、当前表单和所有历史快照引用。
- 保存表单时可带一个 HTTP/HTTPS 外部链接，拒绝含登录信息的 URL。服务端不抓取远端文件。

## 政策绑定与历史保护

- 草稿 `attachmentInfo` 接受最多 10 个 `{ "type": "FILE", "id": "上传返回的 UUID", "originalName": "文件名" }` 和一个外部链接。服务端核验上传者、租户、文件存在性和重复 ID，文件名以存储元数据为准，客户端名称不会覆盖服务端名称。沿用现有 JSON 字段和本地存储。
- 申请人下载须校验本人归属、申请逻辑删除状态和文件属于所查当前表单或指定快照。管理员下载须校验有效管理员、公司租户、该轮属于该申请和文件属于该轮快照。独立附件接口继续按上传者授权。
- 从已保存草稿移除文件时，页面先移除引用，再通过保存草稿更新。已绑定文件不由页面直接物理删除。未绑定文件的移除调用独立删除接口。
- 本人未提交草稿可通过政策接口物理删除，其附件引用随主表记录解除。磁盘文件继续保留，其他草稿或历史快照引用仍由附件删除接口保护。
- 物理删除同时检查全部政策当前表单和历史快照引用，含已删除申请。仍被引用时返回 409 `ATTACHMENT_REFERENCED`。绑定和删除在事务内锁定同一上传者的用户行，防止保存提交前的引用检查窗口造成文件丢失。移除引用后文件保留在磁盘，自动回收仍待实现。

## 已实现接口

使用现有 HttpSession；写请求需可信 `Origin` 和 `X-Policy-Request: 1`。响应禁止缓存，不暴露磁盘路径和上传者信息。无需建表或修改 SQL。

页面的文件名使用共用 `AttachmentDownload.vue` 触发下载，通过 `api/request.js` 在当前网页会话读取文件字节，再以 Blob 临时地址保存并释放临时地址。管理员详情、本人申请详情和提交页均使用该入口，下载期间防重复点击。管理员及申请详情的错误显示在文件名下，提交页通过 `popupError` 使用 Message 弹出错误。离开页面时取消未完成的读取。

飞书客户端与外部浏览器的 Cookie 各自独立。客户端内的下载先由当前页面带会话获取文件，避免外部浏览器直接请求授权接口时返回 401。读取文件成功时按二进制解析，401、403、404 等错误继续解析 JSON 并保留状态和错误信息。401 沿用应用的会话失效流程。

| 方法 | 路径 | 请求 | 成功 |
| --- | --- | --- | --- |
| POST | `/api/attachments` | multipart 字段 `file` | 201，附件信息 |
| GET | `/api/attachments/{id}/content` | 本人附件 UUID | 200，原始字节，强制下载 |
| DELETE | `/api/attachments/{id}` | 本人独立附件 UUID | 204 |
| GET | `/api/policies/{id}/attachments/{attachmentId}/content` | 本人申请及当前内容的文件 ID | 200，强制下载 |
| GET | `/api/policies/{id}/audit-logs/{logId}/attachments/{attachmentId}/content` | 本人申请、提交日志及文件 ID | 200，强制下载 |
| GET | `/api/admin/policies/{id}/audit-logs/{logId}/attachments/{attachmentId}/content` | 管理员可读申请、提交日志及文件 ID | 200，强制下载 |

上传返回 `id`、`originalName`、`size`（字节）、`contentType`（服务端推导）、`uploadedAt`（Instant）。下载固定返回 `application/octet-stream`、`Content-Disposition: attachment` 和 `X-Content-Type-Options: nosniff`。

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | ATTACHMENT_INVALID | 文件为空、缺 file、文件名/类型/内容不符或 multipart 错误 |
| 401 | LOGIN_REQUIRED | 未登录或会话失效 |
| 403 | REQUEST_ORIGIN_DENIED / ATTACHMENT_ACCESS_DENIED | 请求来源不可信或账号不可用 |
| 404 | ATTACHMENT_NOT_FOUND | ID 无效、不存在或非本人/跨租户，隐藏存在性 |
| 413 | ATTACHMENT_TOO_LARGE | 文件或请求超限 |
| 409 | ATTACHMENT_REFERENCED | 当前表单或历史快照仍引用文件 |
| 503 | ATTACHMENT_STORAGE_UNAVAILABLE | 文件存储、元数据或账号存储不可用 |

2026-11-15 验证使用临时文件目录、内存 H2 和模拟账号会话。MockMvc 覆盖上传、绑定、回填、提交、服务端文件名、越权、轮次不匹配及历史删除保护，并验证删除请求等待绑定事务提交后返回 409。真实 HTTP 测试验证 2MiB 和 20MiB 文件上传及下载字节一致，20MiB 加 1 字节返回 413。Spring multipart 限制已与附件服务对齐为 20MiB/21MiB。

生产构建页面已通过真实 HTTP 联调上传、损坏文件拒绝、保存阻止、刷新回填、正式提交、本人当前内容和历史快照下载、管理员下载，并检查 1440×1000、768×1000、390×844。前端检查通过 20 个文件的 85 项测试和构建，后端 Maven `verify` 共 95 项，93 项通过、2 项外部环境测试跳过。目标 MySQL 和真实飞书客户端文件选择、下载仍待联调。

2026-11-15 修复下载跳转造成的会话丢失。前端检查通过 21 个文件的 94 项测试及生产构建。本机 HTTP 预览使用独立的测试 Cookie 和模拟账号，验证无 Cookie 的直接请求返回 401，页面取得的下载字节与原文件一致。Chrome 在 1440×1000、768×1000、390×844 核对管理员下载、原页保留及失败反馈，并验证申请人草稿与详情下载、会话过期后的登录提示。飞书客户端实际保存文件待用户复测。
