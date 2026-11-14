# 本地附件接口

更新日期：2026-11-11。对应已实现的独立附件功能，政策草稿和提交已接入。外部链接可随表单保存，上传文件绑定待接入。

2026-11-11 页面现状：Element Plus 申请表单的 Upload 已关闭自动上传，仅选择、暂存和移除本地文件，没有使用 `useAttachments` 调用本文接口；提示为“文件仅暂存于当前页面，尚未上传”。独立接口和上传 composable 的存在不等于申请页已接通上传，不得将页面选中文件描述成上传成功。

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

- 每请求一个非空文件，最大 20MiB，multipart 总大小最大 21MiB。独立 `useAttachments` 流程设最多 10 个、单文件 20MB 等前端限制；当前申请页仅暂存文件，未接入该 composable，因此这些限制不能描述成当前申请页已执行的校验，也不是服务端用户总配额或未来政策业务限制。
- 支持 `pdf/doc/docx/xls/xlsx/png/jpg/jpeg`；文件名最多 180 字符，拒绝目录分隔符、控制字符及 Windows 非法字符。
- 后端检查扩展名和文件头；docx/xlsx 另检查 ZIP 目录中对应文档入口并拒绝 VBA 包。不执行、不解压、不在线预览文件；这些检查不等于病毒扫描或完整格式校验，旧版 doc/xls 仅检查 OLE 文件头。
- 每次操作查询当前用户记录，拒绝停用或身份失效账号。仅上传者可下载或移除，ADMIN 也不能直接访问他人的独立附件。
- 独立上传文件尚未关联 `policy_apply.attachment_urls`，上传不表示政策保存或提交成功；刷新后列表不自动恢复，磁盘文件仍保留。当前无独立附件列表、用户配额或自动清理，人工整理须核对元数据及附件 ID，不能按文件年龄盲删。
- 超时或网络中断时结果可能不明，不自动重放上传。后续接入草稿时应增加附件绑定、列表恢复、幂等上传和未绑定文件清理，并按父申请校验访问与删除权限；已提交和历史附件不可沿用当前直接删除规则。
- 保存表单时可带一个 HTTP/HTTPS 外部链接，拒绝含登录信息的 URL。服务端不抓取远端文件。

## 政策接入规划

- 申请页复用 `POST /api/attachments` 上传，草稿保存随表单传文件 ID 或外部链接。链接及附件回显从当前表单或该轮快照读取。
- 管理员审核下载增加按申请和提交轮次鉴权的入口，建议使用 `GET /api/admin/policies/{id}/audit-logs/{logId}/attachments/{attachmentId}/content`。服务端须校验该轮属于该申请、文件 ID 属于该轮快照，以及管理员有权访问该申请。
- 现有附件删除接口用于未绑定文件。移除草稿中的引用通过保存草稿完成，物理删除须排除任何当前表单和历史快照引用。政策绑定和历史保护仍待实现。

## 已实现接口

使用现有 HttpSession；写请求需可信 `Origin` 和 `X-Policy-Request: 1`。响应禁止缓存，不暴露磁盘路径和上传者信息。无需建表或修改 SQL。

| 方法 | 路径 | 请求 | 成功 |
| --- | --- | --- | --- |
| POST | `/api/attachments` | multipart 字段 `file` | 201，附件信息 |
| GET | `/api/attachments/{id}/content` | 本人附件 UUID | 200，原始字节，强制下载 |
| DELETE | `/api/attachments/{id}` | 本人独立附件 UUID | 204 |

上传返回 `id`、`originalName`、`size`（字节）、`contentType`（服务端推导）、`uploadedAt`（Instant）。下载固定返回 `application/octet-stream`、`Content-Disposition: attachment` 和 `X-Content-Type-Options: nosniff`。

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | ATTACHMENT_INVALID | 文件为空、缺 file、文件名/类型/内容不符或 multipart 错误 |
| 401 | LOGIN_REQUIRED | 未登录或会话失效 |
| 403 | REQUEST_ORIGIN_DENIED / ATTACHMENT_ACCESS_DENIED | 请求来源不可信或账号不可用 |
| 404 | ATTACHMENT_NOT_FOUND | ID 无效、不存在或非本人/跨租户，隐藏存在性 |
| 413 | ATTACHMENT_TOO_LARGE | 文件或请求超限 |
| 503 | ATTACHMENT_STORAGE_UNAVAILABLE | 文件存储、元数据或账号存储不可用 |

自动化使用临时目录和模拟账号/会话，不写真实业务表。真实飞书客户端文件选择和下载需在后端重启后人工验证。
