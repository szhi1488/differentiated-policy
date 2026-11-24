# 飞书客户端免登、浏览器扫码与网页授权

更新日期 2026-11-24。飞书客户端使用 JS-SDK 免登。普通浏览器可在应用页用手机飞书扫码，或前往飞书网页授权，两者共用浏览器 OAuth 回调。三种方式均由服务端核验身份、保存用户和建立应用会话。

`FeishuLoginPage.vue` 展示登录入口。`useAuthSession.js` 集中维护会话，供 App 和路由读取。并发登录请求合并，退出或失效后丢弃迟到结果。账号、租户或角色变化、退出及会话失效会清理申请表单和暂存附件。会话探测和授权码交换的 401 由登录流程处理，业务请求 401 会使会话失效，403 和 409 分别提示权限与冲突。[前端规范](frontend-development.md#6-路由请求与业务操作)

当前联调状态见 [验证记录](#验证记录)。

## 普通浏览器登录

从政策办理链接发起授权时，扫码和网页授权均在当前标签页将受限的站内目标与本次 state 绑定；固定回调恢复该目标，业务页面用新会话重新核验所查轮次及可执行操作。客户端免登直接沿用原 Hash。[政策办理链接及验证](policy-workflow-phase-d.md#办理链接约定)

1. 页面首先处理 OAuth 回调。无回调时通过 `GET /api/auth/session` 恢复会话。未登录时显示「显示飞书二维码」和「飞书网页授权登录」。
2. 用户选择方式后，`POST /api/auth/feishu/browser/authorize` 使用可信 `Origin` 和 `X-Policy-Request: 1`。服务端从白名单 Origin 生成站点根地址（如 `http://127.0.0.1:5173/`），将该地址和随机 state 绑定当前 Cookie 会话。state 3 分钟内一次有效，重新生成后旧回调返回 401，新 state 仍可使用。
3. 「飞书网页授权登录」直接跳转到 `https://accounts.feishu.cn/open-apis/authen/v1/authorize`。该链接包含 `client_id`、`response_type=code`、`redirect_uri`、`state`。用户选择「显示飞书二维码」时，按需加载飞书官方二维码 SDK `1.0.2`，将相同的参数用于 `https://passport.feishu.cn/suite/passport/oauth/authorize`。仅接收当前 iframe 从 Passport 域发回的临时票据 `tmp_code`，随后跳转该 Passport 授权地址完成 OAuth。扫码加载失败、超时或过期时可刷新二维码，也可切换网页授权。普通浏览器不调用客户端免登 JS-SDK。
4. 回调成功带 `code/state`，取消时带 `error=access_denied`。前端先从地址栏移除授权参数，保留其他参数和 fragment，再通过 `POST /api/auth/feishu/browser/login` 提交 code/state；不把上游原始错误信息显示给用户。
5. 服务端校验并消费 Cookie 绑定的 state，从会话读取发起时的回调地址，将同一 `redirect_uri` 传入 v3 token 交换，之后沿用下述身份核验、停用检查、用户落库及 Session ID 轮换。无会话、过期、旧 state 或重复回调返回 401；失败后点击按钮重新授权，不重放旧 code。
6. 刷新恢复当前浏览器自己的应用会话。飞书客户端和外部浏览器的 Cookie 独立，因此客户端已登录不代表浏览器已经登录。授权页需在 3 分钟内完成，超时可重新发起。

浏览器回调固定为当前 Origin 的 `/`，必须把该完整地址加入飞书后台的重定向 URL，且 Origin 在 `FEISHU_ALLOWED_ORIGINS` 中。保持同源 `/api` 代理，HTTPS 设置 Secure Cookie。官方依据见 [二维码 SDK](https://open.feishu.cn/document/common-capabilities/sso/web-application-sso/qr-sdk-documentation)、[获取授权码](https://open.feishu.cn/document/authentication-management/access-token/obtain-oauth-code)和 [v3 token](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/authentication-management/access-token/get-user-access-token-v3)。

### 浏览器登录接口

| 接口 | 请求与响应 | 校验及失败处理 |
| --- | --- | --- |
| `POST /api/auth/feishu/browser/authorize` | 无请求体；返回 `{ "authorizationUrl": "飞书官方授权地址" }` | 可信 Origin 和专用请求头；创建 Cookie 绑定的 state 和回调地址；403 来源不可信，503 凭证未配置 |
| `POST /api/auth/feishu/browser/login` | `{ "code": "一次性授权码", "state": "本次随机值" }`；成功返回 `{ "user": { ... }, "expiresAt": "..." }` | 同样校验写请求来源；401 state/code 无效，403 账号停用，502 上游异常，503 配置或用户存储不可用；失败不建立新登录会话 |
| `GET /api/auth/session` | 返回当前本地角色与原会话到期时间 | 401 表示需重新授权，403 表示账号停用或公司不符，503 表示暂时无法核对身份 |

以上响应禁止缓存，用户 token 不返回浏览器、不持久化。授权码只能交换一次，登录成功后轮换 Session ID。

### 浏览器真实联调步骤

2026-11-11 前序真实网页授权中，用户完成授权后进入当时的管理员全部政策页。当前管理员入口为 `#/admin/catalog/sources`，已通过模拟会话验证。用真实手机扫码确认后进入工作台的场景待联调。[管理员工作台](admin-workspace.md)

1. 重启前后端，通过实际应用地址打开首页。本机示例为 `http://127.0.0.1:5173/`；使用 `localhost` 或新域名时保持整个授权过程使用同一地址。
2. 在飞书后台「安全设置 → 重定向 URL」核对完整首页地址（含协议、端口、末尾 `/`）；后端 `FEISHU_ALLOWED_ORIGINS` 配置对应 Origin（不含路径），并保持同源 `/api` 代理。HTTPS 同时配置 `SESSION_COOKIE_SECURE=true`。
3. 在未登录的浏览器中点击「飞书网页授权登录」，完成授权并返回工作台。确认管理员进入 `#/admin/catalog/sources`，其他角色进入 `#/apply`。确认地址栏已清理 code/state，刷新后仍能恢复有效会话。
4. 用新的未登录浏览器会话点击「显示飞书二维码」，使用手机飞书「扫一扫」并在手机上确认。检查浏览器进入当前账号对应的工作台。分别核对取消后重试、过期刷新、旧回调和重复回调。后端重启清空会话后需重新授权。
5. 同时回归飞书客户端免登；客户端与浏览器分别建立会话，不假定两者互通。记录真实结果后再更新验证状态。

真实联调按上述步骤逐项记录。此前的管理员网页授权记录适用于当时的本地账号和配置。

## 飞书客户端第一步：网页 JS-SDK 鉴权

1. 读取 `GET /api/feishu/config`，取得公开 App ID 和 `sdkEnabled`；后者只表示凭证完整，不保证登录成功。
2. 飞书客户端加载固定版本 `h5-js-sdk-1.5.48.js`；普通浏览器按上节使用网页授权。
3. 当前页面 URL 提交至 `POST /api/feishu/js-sdk/signature`，请求体如 `{ "url": "http://127.0.0.1:5173/" }`。保留查询参数原字节，只删 `#` 及后续，不排序、解码或重组。
4. 后端验证来源属于 `FEISHU_ALLOWED_ORIGINS`，获取并缓存 `tenant_access_token`、`jsapi_ticket`，生成毫秒时间戳和 nonce。
5. 按 `jsapi_ticket=…&noncestr=…&timestamp=…&url=…` 拼接，UTF-8 后计算 SHA-1，返回 `appId`、`timestamp`、`nonceStr`、`signature`、`jsApiList`（当前为空）。
6. 注册 `h5sdk.error`，调用 `h5sdk.config`、等待 `h5sdk.ready`；必须 config 成功且 ready 到达才继续，不能单凭 ready 判断。

App Secret、应用 token、ticket 不返回前端、不记日志；签名响应用于 SDK 配置，不持久化或记录日志。票据单进程缓存并提前刷新，每次签名新建 nonce。

## 飞书客户端第二步：取得用户授权码

正常入口先尝试 `GET /api/auth/session` 恢复会话；无会话才执行 SDK 初始化和以下步骤。

端内登录页显示自动验证进度。验证失败时保留错误提示和「重新验证飞书身份」操作。

1. `POST /api/auth/challenge` 创建绑定当前 Cookie 的随机 `state`，3 分钟内一次有效；新建使旧值失效。
2. 调用 `tt.requestAccess({ appID, scopeList: [], state })`；仅取基本身份，不请求通讯录、手机号、邮箱或 `offline_access`。
3. 校验 code 非空；回传 state 时须一致。code 只能使用一次，交换失败重新授权，不重放旧 code。
4. 仅 `requestAccess` 不存在或 errno `103` 时兼容 `requestAuthCode`；配置错误、拒绝授权、超时不触发降级。

## 飞书客户端第三步：核验身份、落库与会话

1. `POST /api/auth/feishu/login` 提交 `{ "code": "授权码", "state": "本次随机值" }`。
2. 服务端一次性消费 state，经 `https://accounts.feishu.cn/oauth/v3/token` 交换用户 token，再请求 `https://open.feishu.cn/open-apis/authen/v1/user_info`。
3. 各登录入口先校验飞书返回的租户与 `FEISHU_TENANT_KEY` 一致，再保存用户并建立会话。租户配置只读取该名称，未填写则拒绝登录；测试环境使用测试企业标识，正式环境使用正式公司标识。现有身份记录以核验结果 `(tenant_key, open_id)` 幂等写入 `user_info`。新用户默认 `APPLICANT`，再次登录更新姓名、有效 `union_id` 和有效 HTTPS `avatar_url`，保留角色、部门、停用标记，不从姓名推断部门。

头像取自[飞书获取登录用户信息接口](https://open.feishu.cn/document/server-docs/authentication-management/login-state-management/get)，仅保存不超过 2048 字符的 HTTPS 地址，不代理下载图片，不保存用户 token。缺失、空值或无效地址保留原头像，新用户为空；停用用户保持原资料并拒绝登录。登录及 `GET /api/auth/session` 返回 `user.avatarUrl`，恢复会话沿用登录时的头像，重新登录才同步最新头像。两端共用顶部栏展示，地址为空或图片加载失败时显示姓名首字。两份空库建表脚本均已包含头像字段。真实飞书头像及目标 MySQL 留待联调。
4. 停用账号拒绝登录，存储失败不建会话。前端姓名、open_id 或角色不能作为身份依据。
5. 落库后轮换 Session ID，保存本地用户 ID、角色及核验身份；响应 `{ "user": { ... }, "expiresAt": "..." }`，ID 为字符串，不返回用户 token。

## 角色调整后重新进入工作台

旧版 `GET /api/auth/session` 返回登录时保存的角色。管理员已将账号从申请人改为管理员时，关闭应用再打开仍可能复用原会话，因而继续显示申请人页面。

现在，重新打开、刷新、从浏览器页面缓存恢复或将已登录页面切回前台时，前端会重新读取会话。服务端用会话中的公司和 openId 查找账号，核对本地用户 ID 及启用状态，返回当前 `user.roleType` 和原会话的到期时间。商品、政策及用户管理接口在每次业务请求时校验权限。[角色调整契约](../api/user-api.md#调整用户角色)

| 目标账号的操作 | 预期结果 |
| --- | --- |
| 升为管理员后，从原 `#/apply` 或带 `policyId` 的申请地址进入 | 打开「商品 → 源商品库」`#/admin/catalog/sources` |
| 降为申请人后，从原管理员地址进入 | 打开「政策提交 → 提交政策」`#/apply` |
| 同一账号、同一角色，将仍然打开的页面从后台切回 | 保留当前页面及表单中尚未保存的输入 |

**本地复测。** 使用新版前端，重启本地后端，再用已调整角色的账号从飞书重新打开应用，按上表核对页面。已保存的角色直接生效，无需再次调整角色或执行 SQL。服务重启会清空本地内存会话；如要单独验证旧会话恢复，在重启后先让测试账号登录，再由另一位管理员调整其角色，随后关闭并重开页面。[隔离环境验证记录](#验证记录)

## 会话与接口保护

| 项目 | 当前行为 |
| --- | --- |
| Cookie | `POLICY_SESSION`，HttpOnly、SameSite=Lax、仅 Cookie 跟踪 |
| 闲置到期 | 30 分钟 |
| 绝对到期 | 不超过 2 小时及上游 token 有效期 |
| 会话读取 | `GET /api/auth/session` 返回有效账号的当前角色。未登录、到期、原账号消失或本地 ID 变更返回 401，账号停用或公司不符返回 403 |
| 退出 | `POST /api/auth/logout`，204、幂等，仅退出本应用 |
| 写请求 | 可信 `Origin` 与 `X-Policy-Request: 1` |
| 默认保护 | `/api/**` 需登录；公开配置、签名、challenge、客户端登录、浏览器授权链接/登录交换、退出按精确路径放行 |

附件下载由当前页面使用同源会话读取字节后触发保存。飞书内点击文件名使用 `AttachmentDownload.vue`，防止下载跳转到缺少该客户端会话的外部浏览器。读取返回 401 时沿用登录失效流程。实现与验证范围见[附件接口](../api/attachment-api.md)。

响应禁止缓存；HTTPS 设置 `SESSION_COOKIE_SECURE=true` 并同源代理 `/api`。重启清空会话，多实例需共享；当前未订阅飞书撤销授权事件，已有应用会话不会即时同步失效。页面无退出按钮，API 保留。

## 飞书后台与配置

内网部署时，浏览器授权仍回到当前 ERP 来源的根路径，例如 `http://172.50.50.32:5173/`。另在「事件与回调 → 回调配置」为运营审核卡选择「使用长连接接收回调」，由常驻后端接收 `card.action.trigger`。卡上决定通过长连接提交。点「查看本轮详情」打开内网页面后，页面按当前登录方式恢复或建立 ERP 会话。[内网部署的飞书审核卡](environment-configuration.md#内网部署的飞书审核卡)

- 配置文件分工、飞书测试企业切换正式公司、完整环境参数及正式监听地址统一见[环境配置、部署与后台任务](environment-configuration.md)。飞书企业标识只填 `FEISHU_TENANT_KEY`，吉客云请求不使用该值。新企业身份不会自动继承测试身份的管理员角色。
- 仅本机调试时，电脑主页示例为 `http://127.0.0.1:5173`，H5 可信域名示例为 `127.0.0.1:5173`。同事从内网访问时，两项都应按实际前端入口填写。
- 另在「安全设置 → 重定向 URL」加入 `http://127.0.0.1:5173/`。即使 requestAccess 不跳转也必须配置，H5 可信域名不能替代。
- 签名保留 query，而后台重定向 URL 按现有说明配置页面路径，去除 query 和 fragment。localhost 或新路径须单独同步。
- `FEISHU_ALLOWED_ORIGINS` 用带协议的 origin；部署域名确定后同步主页、可信域名、重定向 URL 和白名单。
- `backend/.env.example` 是唯一配置样例，不由应用读取；真实值填写在被忽略的 `backend/.env.local.properties` 或同名系统环境变量，环境变量优先。Secret 不进入 `VITE_*`，修改配置后重启后端。
- `FEISHU_APP_ID`、`FEISHU_APP_SECRET` 和 `FEISHU_TENANT_KEY` 必须明确填写，应用 ID 不再内置测试值，租户不再兼容其他配置名。已有环境升级前将原租户值迁入 `FEISHU_TENANT_KEY`；本次本机配置已保留原值完成改名。
- 手机直接打开应用页面时，需要使用手机可访问的应用地址。测试桌面网页登录时，在桌面浏览器打开已登记的回调地址，由手机飞书扫描桌面显示的二维码。

## 常见错误

`2601002` 是 JS-SDK 鉴权失败的通用外层码，`2700002` 是授权过程失败的通用外层码，均须结合内层数字码定位，不能直接判断具体原因或用户拒绝。页面从数字错误字段或限定格式的错误消息中提取内层码，优先给出该内层码对应的提示。`333447/333448` 检查 H5 可信域名；授权失败先核对重定向 URL、可用范围及企业身份。只展示数值码，不输出 SDK 原始消息、票据或签名。

若飞书扫码框返回 `4401`，核对是否使用上文的 Passport 扫码入口，以及飞书后台登记的重定向 URL 是否与当前首页地址完全一致。

局域网使用 HTTP 地址时，`SESSION_COOKIE_SECURE` 应设为 `false`；HTTPS 地址设为 `true`。若 HTTP 局域网响应仍给会话 Cookie 加 `Secure`，客户端无法正常带回 Cookie，`challenge` 与登录交换不能保持同一会话，可能出现「登录请求已失效」。修改后重启后端，再关闭旧页面并重新发起登录；不要删除 state 或 Cookie 绑定校验来绕过错误。

本地错误 `{ "code": "稳定错误码", "message": "中文提示" }`。HTTP：400 参数/签名来源错误，401 state/code/会话失效，403 请求来源或停用账号，502 上游异常，503 缺配置或用户存储不可用。

## 验证记录

### 2026-11-18 环境参数补齐

实际配置和唯一样例补齐服务监听地址、端口、附件目录、导入及清洗开关、后台轮询间隔和 HTTP 超时，均通过 `application.yml` 显式读取大写配置名。数据库 URL 和允许访问的 Origin 不再回退到具体的本机库及开发地址。原有凭证、租户、数据库和白名单值保持不变，附件目录仍指向原项目 `data`；默认任务频率和请求超时保持原行为。配置项及范围见 [本地配置说明](../../README.md#本地启动)。

本轮 `mvn.cmd '-Dmaven.repo.local=.local-cache/maven-repository' verify` 通过：共 202 项测试，200 项通过、2 项外部环境测试跳过，打包和 Spotless 通过。配置探针覆盖两种启动目录、带空格的附件路径、本地文件及环境变量优先级、旧租户名称无效、缺少环境身份配置时不回退开发值、非法超时和轮询间隔；请求测试验证飞书 POST/GET 和吉客云使用配置的超时。探针不初始化数据库或任务。本轮未重启实际后端、未执行目标 MySQL 操作，也未重新进行真实飞书登录和吉客云调用。

### 2026-11-18 后端配置规范化

保留 `backend/.env.example` 作为唯一样例，真实配置只填写在 `backend/.env.local.properties` 或系统环境变量，删除重复的 `.env.local.properties.example`。飞书企业只读取 `FEISHU_TENANT_KEY`，取消旧名称回退；本机已将原租户值原样迁入飞书配置分组，并逐项核对其他配置值未变。样例和默认配置均移除内置测试 App ID，应用凭证须按当前环境明确填写。实际配置文件继续被 Git 忽略，修改后须重启后端。

`LocalConfigLoadingTest` 验证项目根目录和 `backend/` 两种启动目录、新租户配置加载、旧名称在文件及环境变量中均不生效，以及环境变量优先级。配置探针只加载属性，不初始化数据库或后台任务。全量后端测试共 193 项，191 项通过，2 项外部环境测试跳过；修正一处测试换行格式后，以 `mvn.cmd '-Dmaven.repo.local=.local-cache/maven-repository' '-DskipTests' verify` 完成打包和 Spotless 检查。本轮未重启实际后端、未执行目标 MySQL 操作，也未进行真实飞书登录。

### 既往验证

2026-11-17 角色恢复回归先复现了申请人升为管理员后原会话仍返回 `APPLICANT`。修复后，前端 37 个文件共 204 项测试通过，生产构建通过。后端 `verify` 共 191 项，189 项通过、2 项外部环境测试跳过，打包和 Spotless 通过。

Chrome 生产构建分别在 1440×1000、768×1000、390×844 下连接 Spring Boot 和隔离 H2，以模拟飞书身份建立两个独立会话。关闭目标账号的标签页后，由管理员通过角色接口升权，再用同一浏览器从带 `policyId` 的旧申请地址重开，均进入源商品库，账号 ID 和会话到期时间与升权前一致。随后降为申请人，触发页面可见事件后均回到提交政策页。真实飞书客户端和目标 MySQL 的联调仍待完成。

2026-11-15 局域网鉴权排查：本机和局域网 Origin 的签名接口均返回 HTTP 200，仅代表服务端成功生成签名，不代表客户端已通过鉴权。补充 `2601002` 内层数字码提取与对应提示，保留错误原文脱敏。前端 `npm.cmd run check` 通过 ESLint、Prettier、20 个文件的 88 项测试和生产构建。本轮未完成真实飞书客户端重新鉴权，截图中的实际失败原因仍须结合重试后的内层码、当前页面地址及飞书后台配置核对。

| 场景 | 已有结果 | 待联调 |
| --- | --- | --- |
| 飞书客户端免登 | 用户此前确认真实免登成功。登录页改版后，模拟端内验证失败并手动重试通过 | 在当前工作台回归免登及会话刷新 |
| 浏览器网页授权 | 用户曾完成真实授权并进入当时的管理员全部政策页。新版登录页已验证模拟取消及重试 | 用真实账号授权并进入当前工作台，核对会话刷新及授权异常 |
| 浏览器扫码 | 2026-11-11 在 Edge 生产预览中，以 `http://127.0.0.1:5173/` 为回调地址观察到官方二维码。模拟接口已验证扫码回调、角色路由和刷新后的会话恢复 | 用手机飞书扫码并确认，验证真实 OAuth 交换及登录结果 |
| 部署环境 | 本地开发地址已有验证记录 | 部署域名确定后核对重定向 URL、Origin 白名单和同源代理 |

2026-11-11 登录页改版时，前端 `npm.cmd run check` 通过 17 个文件的 65 项测试及生产构建。扫码测试覆盖按需加载、过期刷新、切换网页授权、SDK 加载失败和 iframe 消息校验。后端 `mvn.cmd '-Dmaven.repo.local=.local-cache/maven-repository' verify` 通过 55 项测试、打包及 Spotless，1 项真实 MySQL 专项测试跳过。后端回归验证重新授权后旧 `state` 被拒绝，新 `state` 仍可使用。[前端验证记录](frontend-development.md#9-验证记录与维护)
