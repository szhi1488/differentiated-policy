# 后端环境配置、部署与后台任务

更新日期：2026-11-23。本文记录当前实现及用户确认的配置规则。实际部署方式和正式域名仍以部署时确定的环境为准，不代表已经发布上线。

## 配置文件与读取方式

| 文件 | 用途 | 应用是否读取 |
| --- | --- | --- |
| `backend/.env.example` | 唯一配置样例，供新环境复制；不填写真实凭证 | 否 |
| `backend/.env.local.properties` | 本机实际配置，填写真实值，已被 Git 忽略 | 是 |
| `backend/src/main/resources/application.yml` | 将大写变量映射到 Spring 配置项，并声明通用运行默认值 | 是 |

重复且过时的 `.env.local.properties.example` 已删除。新环境复制 [配置样例](../../backend/.env.example) 后填写，已有实际文件不要被样例覆盖。项目根目录和 `backend/` 两种启动目录均可加载实际配置。

本地配置经 `application.yml` 映射后，由 Java 配置对象、Web 服务或调度器读取并用于实际执行。同名系统环境变量优先于本地文件；修改配置后须重启后端。新增大写配置名时必须同步 YAML 映射、消费代码、样例和本文，不能只在 `.env.local.properties` 添加一个后端未读取的变量。

每个配置项各占一行。布尔值填写 `true` 或 `false`，行末不要添加反斜杠续行。Windows 路径使用正斜杠，含空格时填写完整路径，不加引号。真实密钥、授权 token 和实际企业标识不写入源码、样例、日志或文档。

## 飞书身份与测试转正式

飞书客户端免登、浏览器扫码和网页授权共用后端身份校验。后端取得飞书用户信息后，将其中的 `tenant_key` 与 `FEISHU_TENANT_KEY` 精确比较；不匹配返回 `403 / FEISHU_TENANT_DENIED`，未配置公司值返回 `503 / FEISHU_TENANT_NOT_CONFIGURED`。本地停用账号也不能登录。[登录流程](feishu-auth-flow.md#飞书客户端第三步核验身份落库与会话)

租户只使用 `FEISHU_TENANT_KEY`，已移除旧名 `JACKYUN_TENANT_KEY` 的回退。旧名仅在负向测试中保留，用于确认它不再生效。测试环境也必须配置测试企业标识；从测试企业切换到正式公司时，改为正式公司的标识，并核对正式应用的 App ID、Secret、权限、可用范围和回调地址。`tenant_key` 标识企业，不是应用版本号。

用户按 `tenant_key + open_id` 识别。正式企业的新身份首次登录默认成为申请人，不自动继承测试身份的管理员角色，需要配置正式管理员。修改环境配置不会自动迁移原企业的用户、角色或政策数据。

吉客云商品接口使用 AppKey、AppSecret 签名及按需配置的商家授权 token，不发送飞书的 `tenant_key`。`JACKYUN_SOURCE_KEY` 是本地商品账套标识，与飞书企业标识不同；已有任务和来源数据后保持稳定，不随飞书测试转正式而顺手改名。[商品同步说明](catalog-admin-design.md#4-来源字段与同步)

### 政策通知群与审核人员

在 `backend/.env.local.properties` 或部署环境中填写 `FEISHU_POLICY_CHAT_ID`，值为该环境的通知群 `chat_id`（`oc_` 开头）。变更后重启后端。后端已支持读取此项，飞书群发送和待办仍待业务接入。

按已确认的开发方案，有配置权限的管理员在管理后台按姓名分别多选产品运营和采购，后续政策沿用名单。采购候选限本企业已启用的管理员。环境文件只指定消息的目标群，系统从本轮选中账号解析飞书 `open_id` 完成点名。[办理及选人规则](policy-workflow-feishu-technical-design.md#assignees)

## 环境参数清单

环境配置项如下。表中的默认值是程序的通用运行默认值；必须按环境填写的身份、数据库与站点信息不再回退到内置测试值。

| 配置项 | 用途、默认值与填写要求 |
| --- | --- |
| `SERVER_ADDRESS` | 后端监听地址，默认 `127.0.0.1`，按下节部署方式选择 |
| `SERVER_PORT` | 后端 HTTP 端口，默认 `8080` |
| `FEISHU_APP_ID` | 当前环境的飞书应用 ID，必填，无内置测试 ID |
| `FEISHU_APP_SECRET` | 同一飞书应用的密钥，必填，仅服务端保存 |
| `FEISHU_TENANT_KEY` | 当前环境的飞书企业标识，必填，不兼容旧配置名 |
| `FEISHU_POLICY_CHAT_ID` | 当前环境的政策通知群 `chat_id`（`oc_…`）。测试环境填测试群，正式环境填正式群。可留空，已接入服务端配置读取，群消息与待办发送按开发技术书另行接入。 |
| `POLICY_REVIEW_WORKFLOW_ENABLED` | 默认为 `false`。部署二十二表基线、维护审核名单并完成初审办理验收后，所有实例统一设为 `true`，使后续首提和重提按轮指派。既有轮次以持久化标记判断权限。飞书外部投递另按后续阶段启用。 |
| `FEISHU_ALLOWED_ORIGINS` | 允许的前端 Origin，必填；含协议，多个用英文逗号分隔，不带路径及末尾 `/`；无本地地址回退 |
| `JACKYUN_APP_KEY` | 吉客云应用标识，调用商品接口时填写 |
| `JACKYUN_APP_SECRET` | 吉客云请求签名密钥，调用商品接口时填写 |
| `JACKYUN_TOKEN` | ISV 商家授权 token，自研应用可留空 |
| `JACKYUN_SOURCE_KEY` | 本地商品账套标识，默认 `jackyun`；已有数据后保持稳定 |
| `JACKYUN_IMPORT_INTERVAL_SECONDS` | 吉客云导入分页请求间隔，默认 5 秒，允许 2～600 秒 |
| `JACKYUN_IMPORT_MAX_PAGES` | 单个导入任务页数保护上限，默认 2000，须为正整数；达到后暂停 |
| `JACKYUN_IMPORT_SCHEDULING_ENABLED` | 导入后台执行开关，默认 `true` |
| `JACKYUN_CLEAN_SCHEDULING_ENABLED` | 清洗后台执行开关，默认 `true` |
| `JACKYUN_IMPORT_POLL_INTERVAL_MS` | 导入执行器检查任务的间隔，默认 2000 毫秒，允许 1000～60000 毫秒 |
| `JACKYUN_CLEAN_POLL_INTERVAL_MS` | 清洗执行器检查任务的间隔，默认 2000 毫秒，允许 1000～60000 毫秒 |
| `HTTP_CONNECT_TIMEOUT_SECONDS` | 共用 HTTP 连接超时，默认 5 秒，允许 1～60 秒 |
| `FEISHU_REQUEST_TIMEOUT_SECONDS` | 飞书 POST/GET 请求超时，默认 10 秒，允许 1～60 秒 |
| `JACKYUN_REQUEST_TIMEOUT_SECONDS` | 吉客云请求超时，默认 20 秒，允许 1～60 秒 |
| `SESSION_COOKIE_SECURE` | 浏览器通过 HTTPS 访问时设为 `true`，本地 HTTP 调试设为 `false`，默认 `false` |
| `DB_URL` | 实际 MySQL JDBC 地址，必填，无内置本机地址或库名回退 |
| `DB_USERNAME` | 实际数据库账号 |
| `DB_PASSWORD` | 实际数据库密码，仅服务端保存 |
| `POLICY_APPLICABILITY_CUTOVER_AT` | 新规则首次上线的北京时间（含该秒），格式 `YYYY-MM-DDTHH:mm:ss+08:00`。有旧单的环境按实际发布时间配置，所有实例及后续重启沿用同一值。 |
| `POLICY_STORAGE_ROOT` | 附件存储根目录，不含 `attachments`；部署填写稳定绝对路径，留空兼容项目根目录或 `backend/` 启动时使用项目 `data` |

HTTP 超时及后台轮询间隔超出上述范围时，启动校验失败。导入的请求间隔与最大页数还会在发起任务时校验。轮询间隔是执行器本轮完成后的等待时间，不保证严格按墙上时钟同时触发，也不能替代导入请求间隔和限频冷却规则。

`POLICY_STORAGE_ROOT` 已显式映射到 `policy.storage.root`，写在本地 properties 文件中能够生效。本机继续使用原项目 `data`，未移动已有附件。[附件目录与迁移](../api/attachment-api.md#存储和配置)

权限、金额精度、业务时区、上传限制、会话时长、任务租约和识别规则继续由代码及固定配置维护；本次环境配置整理未调整这些规则。

## 正式部署时的监听地址

`SERVER_ADDRESS` 表示后端监听的网络地址，`SERVER_PORT` 表示后端端口。正式域名填写在 `FEISHU_ALLOWED_ORIGINS`，并配置飞书应用入口和回调地址。

| 部署方式 | `SERVER_ADDRESS` | `SERVER_PORT` | 访问关系 |
| --- | --- | --- | --- |
| Nginx 和后端直接运行在同一台服务器 | `127.0.0.1` | `8080` | 用户访问 HTTPS 域名，Nginx 将 `/api` 转发到本机后端 |
| 后端位于独立 Docker 容器，需要接收容器外转发 | 通常为 `0.0.0.0` | 容器内可继续使用 `8080` | 容器网络、端口映射和代理目标保持一致 |
| 局域网设备或其他服务器需直接连接后端 | 可填写实际内网接口地址，或按部署要求使用 `0.0.0.0` | 可继续使用 `8080` | 连接目标为该服务器可访问的地址及端口 |

`127.0.0.1` 也适用于正式环境的同机代理部署，并非只用于开发。`0.0.0.0` 表示监听所有 IPv4 接口，不是浏览器访问地址。端口有冲突时可修改，同时更新反向代理目标。

例如采用同机 Nginx 转发时：

```properties
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080
```

正式 HTTPS 站点另配置实际 Origin 和 `SESSION_COOKIE_SECURE=true`。飞书浏览器 OAuth 回调仍使用该 Origin 的首页 `/`，在飞书后台登记时带末尾 `/`；白名单 Origin 本身不带末尾 `/`。使用普通浏览器或飞书客户端均须保持前端与 `/api` 同源。[回调配置](feishu-auth-flow.md#普通浏览器登录)

### 局域网申请人打不开登录页

2026-11-23，清理政策数据、重启后端后，服务机能打开登录页，另一台申请人电脑访问同一地址却收到 `curl: (52) Empty reply from server` 和 `HTTP 000`。在**申请人电脑**上查看路由，Windows 为服务机地址选择了 `Meta` 虚拟网卡，下一跳为 `198.18.0.2`。申请人临时关闭 TUN 后，确认浏览器已能打开页面。

遇到相同现象，可在申请人电脑的 PowerShell 输入服务机的实际局域网 IPv4，核对首页响应和出站网卡。此处的 5173 是本地前端端口。

```powershell
$serverIp = Read-Host '请输入服务机的局域网 IPv4 地址'
curl.exe --noproxy "*" --max-time 5 -sS -o NUL -w "HTTP %{http_code}\n" "http://${serverIp}:5173/"
Find-NetRoute -RemoteIPAddress $serverIp | Format-List InterfaceAlias, IPAddress, NextHop
```

`HTTP 000` 表示 curl 未收到 HTTP 状态码。即使绕过应用层代理，系统路由仍可能走 TUN。若关闭 TUN 后同一网址恢复，需要继续使用代理时，可为服务机的单个 IPv4 地址配置 TUN 路由排除（`/32`），重新开启后再用浏览器验证。[Mihomo 路由排除配置](https://wiki.metacubex.one/config/inbound/tun/#route-exclude-address)

首页恢复后，如果系统提示登录，按飞书登录流程重新建立会话。后端重启可能使原 `HttpSession` 失效，申请人账号及分工仍从数据库读取。局域网环境由 IDEA 启动后端时，更新服务端代码后由操作者择时重新运行 `PolicyApplication`，再刷新前端验证。[政策数据清理范围](../sql/database-design.md#清空政策提交后重新验证)

## 后台任务与关闭页面

两个调度开关默认均为 `true`，正常运行时保持开启：

```properties
JACKYUN_IMPORT_SCHEDULING_ENABLED=true
JACKYUN_CLEAN_SCHEDULING_ENABLED=true
```

管理员仍需在页面手动发起导入或清洗，是否创建成功以服务端记录为准。开启执行器只会处理已创建且符合执行条件的任务，不会自动创建全量导入或清洗任务。

任务成功创建后，由后端执行器读取数据库中的任务、断点和明细持续处理。关闭网页、退出飞书客户端或关闭进度弹窗不会取消任务；重新打开页面后查询最新进度即可。

继续执行的前提是后端进程及其运行机器正常工作、对应开关开启、任务处于可执行状态，且数据库和所需外部接口可用。任务暂停、结束或发生需人工处理的异常时，按已有状态及重试规则处理。关闭后端程序、服务停机、宿主机关机或休眠会中断处理；后端在本机运行时，关闭客户端和关闭后端是两种不同操作。

设为 `false` 并重启后端后，不会自动领取或处理对应后台任务，但不会自动删除、取消或清空已经保存的任务。恢复为 `true` 并重启后，能否继续执行以任务状态、断点和租约为准，已暂停或结束的任务不会因此无条件重启。[导入与清洗执行规则](catalog-admin-design.md#5-后台和发布) · [清洗并发规则](../business/catalog-cleaning.md#4-执行审核与并发)

## 实现与验证边界

2026-11-18 环境配置补齐轮次已运行 Maven `verify`：共 202 项测试，200 项通过，2 项外部环境测试跳过，打包和 Spotless 通过。配置探针验证两种启动目录、带空格的路径、环境变量优先级、旧租户名称失效、缺少环境身份配置时不回退开发值及非法运行参数；请求测试核对配置的超时已用于实际请求对象。[原验证记录](feishu-auth-flow.md#2026-11-18-环境参数补齐)

本次同步讨论结果仅更新文档及项目协作指令，检查链接、配置名和实现一致性，未重新运行业务测试，未重启实际后端、未操作目标 MySQL，也未进行正式部署、真实飞书登录或吉客云调用。部署后关闭客户端的完整任务流程仍需在实际运行环境核对，不能把既往自动化测试写成该场景已经完成实测。
