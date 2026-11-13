# 飞书客户端免登、浏览器扫码与网页授权

更新日期：2026-11-11。飞书客户端使用 JS-SDK 免登。普通浏览器可在应用页用手机飞书扫码，或前往飞书网页授权，两者共用浏览器 OAuth 回调。三种方式均由服务端核验身份、保存用户和建立应用会话。

`FeishuLoginPage.vue` 展示登录入口。`useAuthSession.js` 集中维护会话，供 App 和路由读取。并发登录请求合并，退出或失效后丢弃迟到结果。账号、租户或角色变化、退出及会话失效会清理申请表单和暂存附件。会话探测和授权码交换的 401 由登录流程处理，业务请求 401 会使会话失效，403 和 409 分别提示权限与冲突。[前端规范](frontend-development.md#6-路由请求与业务操作)

当前联调状态见 [验证记录](#验证记录)。

## 普通浏览器登录

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
| `GET /api/auth/session` | 成功返回当前用户与会话到期时间 | 401 表示尚未登录或已到期；前端再显示授权入口 |

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
3. 各登录入口先校验飞书返回的租户与 `FEISHU_TENANT_KEY` 一致，再保存用户并建立会话。旧环境的 `JACKYUN_TENANT_KEY` 仍可作为回退配置。现有身份记录以核验结果 `(tenant_key, open_id)` 幂等写入 `user_info`。新用户默认 `APPLICANT`，再次登录更新姓名、有效 `union_id`，保留角色、部门、停用标记，不从姓名推断部门。
4. 停用账号拒绝登录，存储失败不建会话。前端姓名、open_id 或角色不能作为身份依据。
5. 落库后轮换 Session ID，保存本地用户 ID、角色及核验身份；响应 `{ "user": { ... }, "expiresAt": "..." }`，ID 为字符串，不返回用户 token。

角色是登录快照，管理员实时业务授权尚未完成。用户 token 只在本次服务端调用使用，不落库、不保存刷新令牌。

## 会话与接口保护

| 项目 | 当前行为 |
| --- | --- |
| Cookie | `POLICY_SESSION`，HttpOnly、SameSite=Lax、仅 Cookie 跟踪 |
| 闲置到期 | 30 分钟 |
| 绝对到期 | 不超过 2 小时及上游 token 有效期 |
| 会话读取 | `GET /api/auth/session`，未登录/到期为 401 |
| 退出 | `POST /api/auth/logout`，204、幂等，仅退出本应用 |
| 写请求 | 可信 `Origin` 与 `X-Policy-Request: 1` |
| 默认保护 | `/api/**` 需登录；公开配置、签名、challenge、客户端登录、浏览器授权链接/登录交换、退出按精确路径放行 |

响应禁止缓存；HTTPS 设置 `SESSION_COOKIE_SECURE=true` 并同源代理 `/api`。重启清空会话，多实例需共享；当前未订阅飞书撤销授权事件，已有应用会话不会即时同步失效。页面无退出按钮，API 保留。

## 飞书后台与配置

- 电脑主页 `http://127.0.0.1:5173`，H5 可信域名 `127.0.0.1:5173`。
- 另在「安全设置 → 重定向 URL」加入 `http://127.0.0.1:5173/`。即使 requestAccess 不跳转也必须配置，H5 可信域名不能替代。
- 签名保留 query，而后台重定向 URL 按现有说明配置页面路径，去除 query 和 fragment。localhost 或新路径须单独同步。
- `FEISHU_ALLOWED_ORIGINS` 用带协议的 origin；部署域名确定后同步主页、可信域名、重定向 URL 和白名单。
- Secret 只在服务端环境变量或被忽略本地文件，不进入 `VITE_*`；新环境参考 `backend/.env.example`。
- 手机直接打开应用页面时，需要使用手机可访问的应用地址。测试桌面网页登录时，在桌面浏览器打开已登记的回调地址，由手机飞书扫描桌面显示的二维码。

## 常见错误

`2700002` 是授权过程失败的通用外层码，结合内层数字码定位，不能直接判断用户拒绝。`333447/333448` 检查 H5 可信域名；授权失败先核对重定向 URL、可用范围及企业身份。只展示数值码，不输出 SDK 原始消息、票据或签名。

若飞书扫码框返回 `4401`，核对是否使用上文的 Passport 扫码入口，以及飞书后台登记的重定向 URL 是否与当前首页地址完全一致。

本地错误 `{ "code": "稳定错误码", "message": "中文提示" }`。HTTP：400 参数/签名来源错误，401 state/code/会话失效，403 请求来源或停用账号，502 上游异常，503 缺配置或用户存储不可用。

## 验证记录

| 场景 | 已有结果 | 待联调 |
| --- | --- | --- |
| 飞书客户端免登 | 用户此前确认真实免登成功。登录页改版后，模拟端内验证失败并手动重试通过 | 在当前工作台回归免登及会话刷新 |
| 浏览器网页授权 | 用户曾完成真实授权并进入当时的管理员全部政策页。新版登录页已验证模拟取消及重试 | 用真实账号授权并进入当前工作台，核对会话刷新及授权异常 |
| 浏览器扫码 | 2026-11-11 在 Edge 生产预览中，以 `http://127.0.0.1:5173/` 为回调地址观察到官方二维码。模拟接口已验证扫码回调、角色路由和刷新后的会话恢复 | 用手机飞书扫码并确认，验证真实 OAuth 交换及登录结果 |
| 部署环境 | 本地开发地址已有验证记录 | 部署域名确定后核对重定向 URL、Origin 白名单和同源代理 |

2026-11-11 登录页改版时，前端 `npm.cmd run check` 通过 17 个文件的 65 项测试及生产构建。扫码测试覆盖按需加载、过期刷新、切换网页授权、SDK 加载失败和 iframe 消息校验。后端 `mvn.cmd '-Dmaven.repo.local=.local-cache/maven-repository' verify` 通过 55 项测试、打包及 Spotless，1 项真实 MySQL 专项测试跳过。后端回归验证重新授权后旧 `state` 被拒绝，新 `state` 仍可使用。[前端验证记录](frontend-development.md#9-验证记录与维护)
