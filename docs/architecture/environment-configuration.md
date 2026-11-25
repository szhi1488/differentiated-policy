# 后端环境配置、部署与后台任务

更新于 2026-11-25。本文记录开发环境的实配和后续部署方式。项目仍在研发，正式发布待部署时验证。

## 政策改期群通知的详情入口

已确认所有机器人群通知、私聊卡和个人待办都取消业务跳转。运营在初审卡上点「通过」或「拒绝」。采购从飞书工作台打开「差异化政策管理系统」，进入「政策管理」→「差异化政策」按单号办理。退回后，原申请人从系统的「我的提交」修改。[审核通知与办理位置](policy-workflow-phase-e.md#各节点的收件人与回执)

改期群通知的链接生成逻辑尚待清理。携带 `changeId` 的通知在 `FEISHU_POLICY_ERP_URL` 配置有效根地址时仍会追加「查看本轮政策」。按已确认规则部署时，此项应留空，并核对同名系统环境变量。改期通知照常显示原单号、截止变更、原因及政策详情。飞书工作台桌面端和移动端的应用主页在飞书后台单独配置。[改期链接生成位置](../../backend/src/main/java/com/company/policy/service/impl/PolicyFeishuSenderImpl.java)

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

在 `backend/.env.local.properties` 或部署环境中填写 `FEISHU_POLICY_CHAT_ID`，值为该环境的通知群 `chat_id`（`oc_` 开头）。变更后重启后端。私聊卡、群消息和任务分别记录回执，开关与联调步骤见[E 阶段交付记录](policy-workflow-phase-e.md)。

有配置权限的管理员在管理后台分别选择产品运营和采购。采购候选限本企业已启用的管理员。系统从本轮指派解析个人的飞书 `open_id`，审核卡、采购办理提醒和退回原因由机器人逐人私发，终审通过的政策公告发往本环境配置的业务群。[办理及选人规则](policy-workflow-feishu-technical-design.md#assignees)

2026-11-24 核对本机配置时，自动投递 `POLICY_REVIEW_DELIVERY_ENABLED` 已启用。仓库样例和程序默认关闭自动投递。所有实例均对提交和重提执行按轮指派。F 阶段共用该开关。启用后按当前窗口登记执行期提醒；平台交接和商品批次通知在业务事务中登记，由同一后台领取。已有 E 阶段库先完成 [F 阶段增量升级](../sql/database-design.md#已有库升级执行期与业务提醒)。实际配置变更后须重启后端。审核通知按政策单号与提交轮次核对，执行期提醒在同页的「查询业务提醒」中核对。[投递记录与人工核对](../api/policy-review-settings.md#飞书投递记录与人工核对)

### 内网部署的飞书审核卡

开发机和后续内网服务器都可以使用 `long_connection`，由常驻后端主动连接飞书接收审核决定。采购和申请人从飞书工作台进入 ERP 办理，设备需能访问该端主页及同源 `/api`。

1. 在实际后端配置中指定 `FEISHU_POLICY_CALLBACK_MODE=long_connection`。`FEISHU_APP_ID` 使用员工工作台中的自建应用 ID，在该应用的「网页应用」能力中配置桌面端和移动端主页，选择在飞书内打开，发布给目标人员。将主页的实际 Origin 加入 `FEISHU_ALLOWED_ORIGINS`。长连接模式的 `FEISHU_CALLBACK_ENCRYPT_KEY` 和 `FEISHU_CALLBACK_VERIFICATION_TOKEN` 可留空。
2. 启动新版后端，等日志出现「飞书卡片回调长连接已建立」，在同一飞书应用的「事件与回调 → 回调配置」选择「使用长连接接收回调」，保存 `card.action.trigger` 订阅，并按控制台提示发布。`5173` 提供前端页面，回调配置走已建立的连接。
3. 自动投递开启后，服务重启便会处理符合条件的待发记录，默认每 3000 毫秒轮询一次。如需只验证一张运营卡，先在常驻后端的实际配置中将 `POLICY_REVIEW_DELIVERY_ENABLED` 设为 `false`，重启后端，再按投递编号运行单卡命令。单卡验证时 `FEISHU_POLICY_CHAT_ID` 可留空，发送群结果通知时再填写机器人所在业务群的 `chat_id`。[单卡验证与回执核对](policy-workflow-phase-e.md#内网发送一张初审卡)

## 环境参数清单

环境配置项如下。表中的默认值是程序的通用运行默认值；必须按环境填写的身份、数据库与站点信息不再回退到内置测试值。

| 配置项 | 用途、默认值与填写要求 |
| --- | --- |
| `SERVER_ADDRESS` | 后端监听地址，默认 `127.0.0.1`，按下节部署方式选择 |
| `SERVER_PORT` | 后端 HTTP 端口，默认 `8080` |
| `FEISHU_APP_ID` | 当前环境的飞书应用 ID，供客户端身份核验和机器人投递使用。该应用需配置桌面端和移动端网页应用主页。 |
| `FEISHU_APP_SECRET` | 同一飞书应用的密钥，必填，仅服务端保存 |
| `FEISHU_TENANT_KEY` | 当前环境的飞书企业标识，必填，不兼容旧配置名 |
| `FEISHU_POLICY_CHAT_ID` | 当前环境的政策通知群 `chat_id`（`oc_…`）。群投递缺配时记录故障，修正后可逐项补发。临期私聊使用原申请人的飞书身份，独立于群配置。 |
| `FEISHU_POLICY_ERP_URL` | 默认留空。现有改期群通知仍会使用非空地址生成详情链接，见[改期通知的实现差距](#政策改期群通知的详情入口)。 |
| `FEISHU_POLICY_CALLBACK_MODE` | 默认 `http`。仅有内网地址时可设为 `long_connection`，由运行中的 ERP 主动向飞书建立加密连接。回调接收和外发调度分别受此项与 `POLICY_REVIEW_DELIVERY_ENABLED` 控制。[内网自动发卡与卡上审核](policy-workflow-phase-e.md#内网自动发卡与卡上审核) |
| `FEISHU_CALLBACK_ENCRYPT_KEY`、`FEISHU_CALLBACK_VERIFICATION_TOKEN` | 加密 HTTP 回调所用的 Encrypt Key 和 Verification Token。网关将 `POST /callbacks/feishu/policy-review` 转发给后端，并保留请求正文及飞书签名头。长连接使用当前应用的 App ID 和 App Secret 建立连接。 |
| `POLICY_REVIEW_DELIVERY_ENABLED`、`POLICY_REVIEW_DELIVERY_POLL_MS` | 后台对外投递默认关闭，轮询默认间隔 3000 毫秒。暂停或恢复后重启相应实例，ERP 仍可办理在途轮次。 |
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

### 从飞书群打开内网政策链接

当前采购和申请人直接从飞书工作台进入 ERP。若应用首页持续转圈，核对桌面端或移动端主页地址、应用可用范围、ERP 服务及办公网或 VPN 连通性。下表记录旧版群消息中普通网页地址的排查经过。

2026-11-24，两台电脑从旧版群公告的普通网址点击「查看本轮政策」后都停在 ERP 页面载入之前。链接中的 `/#/reviews/1?round=1` 打开第 1 单第 1 轮的只读详情，`/#/apply?policyId=2&round=1` 供原申请人修改第 2 单第 1 轮的退回申请。浏览器先向 `http://172.50.50.32:5173/` 请求首页，再由前端解析 `#` 后的单据与轮次。[按轮链接与登录回跳](policy-workflow-phase-d.md#办理链接约定)

| 设备 | 现场证据 | 处理方法 |
| --- | --- | --- |
| ERP 服务机 | 前端监听 `0.0.0.0:5173`。用以太网地址直连约 2 毫秒返回 HTTP 200，显式经过本机 Clash 系统代理则等待 9 秒仍未收到响应。 | 本机临时可用 `http://localhost:5173/` 加原 Hash 打开链接。其他电脑继续使用服务机地址。 |
| 同事电脑 | 浏览器显示 `ERR_PROXY_CONNECTION_FAILED`，说明该次连接代理失败。这台电脑的直连结果和实际出站网卡仍待核对。 | 在该电脑运行上节的 `curl.exe --noproxy "*"` 和 `Find-NetRoute`。若直连返回 200，把服务机 IP 加入生效的代理例外后重开原链接。若为 000，检查办公网连接及路由。路由经过 TUN 时可临时停用 TUN 复测。 |

内网入口的可达性可按公司网络条件集中处理。以下方式均待现场配置和验证。

- **排查历史群链接。** 办公网能够直连 `172.50.50.32:5173` 时，IT 可通过公司统一的代理自动配置（PAC）或组策略，为该 IP 下发直连规则。若终端使用 TUN，还需在相应策略中排除该主机路由。这也覆盖已经发到群里的旧地址。
- **使用固定 HTTPS 入口。** 选择同事可访问的公司域名或应用网关，部署前端并同源转发 `/api`。在后端更新 `FEISHU_ALLOWED_ORIGINS`、`SESSION_COOKIE_SECURE=true`，在飞书后台登记并发布桌面端与移动端新主页和浏览器 OAuth 首页回调，再重启后端。已发普通网页链接保留发送时的地址。若需在办公网外使用，还需验证该入口通过公司 VPN 或允许的外部网络可达。[正式部署时的监听地址](#正式部署时的监听地址) · [浏览器登录](feishu-auth-flow.md#普通浏览器登录)

## 后台任务与关闭页面

两个调度开关默认均为 `true`，正常运行时保持开启：

```properties
JACKYUN_IMPORT_SCHEDULING_ENABLED=true
JACKYUN_CLEAN_SCHEDULING_ENABLED=true
```

管理员仍需在页面手动发起导入或清洗，是否创建成功以服务端记录为准。开启执行器只会处理已创建且符合执行条件的任务，不会自动创建全量导入或清洗任务。

政策临期提醒共用 `POLICY_REVIEW_DELIVERY_ENABLED` 和投递队列。开启后，后端按北京时间在实际截止日前两天及前一天的 17:00 起领取当日私聊，申请人无需打开 ERP。服务在当天晚于 17:00 恢复时可补发当日提醒，跨过当天后跳过该档。定时实发与回执须在目标环境核对。[临期私聊示例](policy-workflow-phase-f.md#原申请人的临期私聊)

任务成功创建后，由后端执行器读取数据库中的任务、断点和明细持续处理。关闭网页、退出飞书客户端或关闭进度弹窗不会取消任务；重新打开页面后查询最新进度即可。

继续执行的前提是后端进程及其运行机器正常工作、对应开关开启、任务处于可执行状态，且数据库和所需外部接口可用。任务暂停、结束或发生需人工处理的异常时，按已有状态及重试规则处理。关闭后端程序、服务停机、宿主机关机或休眠会中断处理；后端在本机运行时，关闭客户端和关闭后端是两种不同操作。

设为 `false` 并重启后端后，不会自动领取或处理对应后台任务，但不会自动删除、取消或清空已经保存的任务。恢复为 `true` 并重启后，能否继续执行以任务状态、断点和租约为准，已暂停或结束的任务不会因此无条件重启。[导入与清洗执行规则](catalog-admin-design.md#5-后台和发布) · [清洗并发规则](../business/catalog-cleaning.md#4-执行审核与并发)

## 实现与验证边界

2026-11-18 环境配置补齐轮次已运行 Maven `verify`：共 202 项测试，200 项通过，2 项外部环境测试跳过，打包和 Spotless 通过。配置探针验证两种启动目录、带空格的路径、环境变量优先级、旧租户名称失效、缺少环境身份配置时不回退开发值及非法运行参数；请求测试核对配置的超时已用于实际请求对象。[原验证记录](feishu-auth-flow.md#2026-11-18-环境参数补齐)

本次同步讨论结果仅更新文档及项目协作指令，检查链接、配置名和实现一致性，未重新运行业务测试，未重启实际后端、未操作目标 MySQL，也未进行正式部署、真实飞书登录或吉客云调用。部署后关闭客户端的完整任务流程仍需在实际运行环境核对，不能把既往自动化测试写成该场景已经完成实测。
