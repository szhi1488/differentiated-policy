# 飞书客户端免登与浏览器网页授权

更新日期：2026-11-11。本文描述当前实现；SDK 鉴权成功不代表用户已经登录。飞书客户端使用 JS-SDK 免登，普通浏览器使用网页 OAuth 授权，共用身份核验、用户落库和应用会话。

## 普通浏览器登录

1. 页面首先处理 OAuth 回调；无回调则通过 `GET /api/auth/session` 恢复已有会话，无会话时显示「通过飞书登录」。普通浏览器不加载飞书 JS-SDK。
2. 点击按钮后，`POST /api/auth/feishu/browser/authorize`，仍需可信 `Origin` 和 `X-Policy-Request: 1`。服务端从经过白名单校验的 Origin 生成站点根地址（如 `http://127.0.0.1:5173/`），将该地址和随机 state 绑定当前 Cookie 会话。state 3 分钟内一次有效，新建会使旧值失效。
3. 页面跳转到 `https://accounts.feishu.cn/open-apis/authen/v1/authorize`，传入 `client_id`、`response_type=code`、`redirect_uri`、`state`，不申请额外 scope 或 offline_access。用户在飞书授权页完成登录及授权后返回站点首页。
4. 回调成功带 `code/state`，取消时带 `error=access_denied`。前端先从地址栏移除授权参数，保留其他参数和 fragment，再通过 `POST /api/auth/feishu/browser/login` 提交 code/state；不把上游原始错误信息显示给用户。
5. 服务端校验并消费 Cookie 绑定的 state，从会话读取发起时的回调地址，将同一 `redirect_uri` 传入 v3 token 交换，之后沿用下述身份核验、停用检查、用户落库及 Session ID 轮换。无会话、过期、旧 state 或重复回调返回 401；失败后点击按钮重新授权，不重放旧 code。
6. 刷新恢复当前浏览器自己的应用会话。飞书客户端和外部浏览器的 Cookie 独立，因此客户端已登录不代表浏览器已经登录。授权页需在 3 分钟内完成，超时可重新发起。

浏览器回调固定为当前 Origin 的 `/`，必须把该完整地址加入飞书后台的重定向 URL，且 Origin 在 `FEISHU_ALLOWED_ORIGINS` 中；保持同源 `/api` 代理，HTTPS 设置 Secure Cookie。当前前端部署于站点根路径，不支持子目录回调配置。官方依据：[获取授权码](https://open.feishu.cn/document/authentication-management/access-token/obtain-oauth-code)、[v3 token](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/authentication-management/access-token/get-user-access-token-v3)。

### 浏览器登录接口

| 接口 | 请求与响应 | 校验及失败处理 |
| --- | --- | --- |
| `POST /api/auth/feishu/browser/authorize` | 无请求体；返回 `{ "authorizationUrl": "飞书官方授权地址" }` | 可信 Origin 和专用请求头；创建 Cookie 绑定的 state 和回调地址；403 来源不可信，503 凭证未配置 |
| `POST /api/auth/feishu/browser/login` | `{ "code": "一次性授权码", "state": "本次随机值" }`；成功返回 `{ "user": { ... }, "expiresAt": "..." }` | 同样校验写请求来源；401 state/code 无效，403 账号停用，502 上游异常，503 配置或用户存储不可用；失败不建立新登录会话 |
| `GET /api/auth/session` | 成功返回当前用户与会话到期时间 | 401 表示尚未登录或已到期；前端再显示授权入口 |

以上响应禁止缓存，用户 token 不返回浏览器、不持久化。授权码只能交换一次，登录成功后轮换 Session ID。

### 浏览器真实联调步骤（尚未全部完成）

2026-11-11 管理员展示页联调：用户修改本地角色并亲自完成浏览器授权后，已在 `http://127.0.0.1:5173/#/admin/policies` 观察到真实登录身份、采购管理员导航及页面。这确认该账号本次登录后的管理员分流，未逐项验证取消、超时、退出重登或部署环境，不代表所有真实授权场景已通过。

1. 重启前后端，通过实际应用地址打开首页。本机示例为 `http://127.0.0.1:5173/`；使用 `localhost` 或新域名时保持整个授权过程使用同一地址。
2. 在飞书后台「安全设置 → 重定向 URL」核对完整首页地址（含协议、端口、末尾 `/`）；后端 `FEISHU_ALLOWED_ORIGINS` 配置对应 Origin（不含路径），并保持同源 `/api` 代理。HTTPS 同时配置 `SESSION_COOKIE_SECURE=true`。
3. 在未登录的浏览器中点击「通过飞书登录」，在飞书官方授权页完成登录与授权，确认返回应用工作台，地址栏不保留 code/state；刷新后仍能恢复当前有效会话。
4. 分别验证取消授权后可手动重试、超过 3 分钟后旧请求被拒绝并可重新发起授权、重复回调不能再次兑换授权码。后端重启清空会话后需重新授权。
5. 同时回归飞书客户端免登；客户端与浏览器分别建立会话，不假定两者互通。记录真实结果后再更新验证状态。

以上是完整验收步骤，尚未逐项执行；本轮只确认上面记录的真实登录后页面，不表示 Agent 修改了远端配置。

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

1. `POST /api/auth/challenge` 创建绑定当前 Cookie 的随机 `state`，3 分钟内一次有效；新建使旧值失效。
2. 调用 `tt.requestAccess({ appID, scopeList: [], state })`；仅取基本身份，不请求通讯录、手机号、邮箱或 `offline_access`。
3. 校验 code 非空；回传 state 时须一致。code 只能使用一次，交换失败重新授权，不重放旧 code。
4. 仅 `requestAccess` 不存在或 errno `103` 时兼容 `requestAuthCode`；配置错误、拒绝授权、超时不触发降级。

## 飞书客户端第三步：核验身份、落库与会话

1. `POST /api/auth/feishu/login` 提交 `{ "code": "授权码", "state": "本次随机值" }`。
2. 服务端一次性消费 state，经 `https://accounts.feishu.cn/oauth/v3/token` 交换用户 token，再请求 `https://open.feishu.cn/open-apis/authen/v1/user_info`。
3. 以核验结果 `(tenant_key, open_id)` 幂等写入 `user_info`。新用户默认 `APPLICANT`，再次登录更新姓名、有效 `union_id`，保留角色、部门、停用标记，不从姓名推断部门。
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
- 本次未修改远端设置。手机 `127.0.0.1` 指向手机自身，手机调试需可访问地址。

## 错误与验证

`2700002` 是授权过程失败的通用外层码，结合内层数字码定位，不能直接判断用户拒绝。`333447/333448` 检查 H5 可信域名；授权失败先核对重定向 URL、可用范围及企业身份。只展示数值码，不输出 SDK 原始消息、票据或签名。

本地错误 `{ "code": "稳定错误码", "message": "中文提示" }`。HTTP：400 参数/签名来源错误，401 state/code/会话失效，403 请求来源或停用账号，502 上游异常，503 缺配置或用户存储不可用。

用户此前确认真实客户端免登成功。2026-11-11 新增浏览器网页授权、回调处理及会话恢复自动化验证；本轮前端 39 项测试及 lint、格式、构建通过，后端 49 项测试及打包、Spotless 通过，1 项真实 MySQL 专项测试未启用。使用模拟上游和测试数据，未连接用户目标库；真实浏览器扫码/授权、远端重定向 URL 和客户端刷新恢复、退出重登尚未逐项人工联调。完整设置及官方参考见 [项目 README](../../README.md)。
