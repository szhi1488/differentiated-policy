# 飞书免登与 JS-SDK 鉴权三步流程

更新日期：2026-11-10。本文描述当前实现；SDK 鉴权成功不代表用户已经登录。

## 第一步：网页 JS-SDK 鉴权

1. 读取 `GET /api/feishu/config`，取得公开 App ID 和 `sdkEnabled`；后者只表示凭证完整，不保证登录成功。
2. 飞书客户端加载固定版本 `h5-js-sdk-1.5.48.js`；普通浏览器只显示环境提示。
3. 当前页面 URL 提交至 `POST /api/feishu/js-sdk/signature`，请求体如 `{ "url": "http://127.0.0.1:5173/" }`。保留查询参数原字节，只删 `#` 及后续，不排序、解码或重组。
4. 后端验证来源属于 `FEISHU_ALLOWED_ORIGINS`，获取并缓存 `tenant_access_token`、`jsapi_ticket`，生成毫秒时间戳和 nonce。
5. 按 `jsapi_ticket=…&noncestr=…&timestamp=…&url=…` 拼接，UTF-8 后计算 SHA-1，返回 `appId`、`timestamp`、`nonceStr`、`signature`、`jsApiList`（当前为空）。
6. 注册 `h5sdk.error`，调用 `h5sdk.config`、等待 `h5sdk.ready`；必须 config 成功且 ready 到达才继续，不能单凭 ready 判断。

App Secret、应用 token、ticket 不返回前端、不记日志；签名响应用于 SDK 配置，不持久化或记录日志。票据单进程缓存并提前刷新，每次签名新建 nonce。

## 第二步：取得用户授权码

正常入口先尝试 `GET /api/auth/session` 恢复会话；无会话才执行 SDK 初始化和以下步骤。

1. `POST /api/auth/challenge` 创建绑定当前 Cookie 的随机 `state`，3 分钟内一次有效；新建使旧值失效。
2. 调用 `tt.requestAccess({ appID, scopeList: [], state })`；仅取基本身份，不请求通讯录、手机号、邮箱或 `offline_access`。
3. 校验 code 非空；回传 state 时须一致。code 只能使用一次，交换失败重新授权，不重放旧 code。
4. 仅 `requestAccess` 不存在或 errno `103` 时兼容 `requestAuthCode`；配置错误、拒绝授权、超时不触发降级。

## 第三步：核验身份、落库与会话

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
| 默认保护 | `/api/**` 需登录；公开配置、签名、challenge、登录、退出按精确路径放行 |

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

用户此前确认真实免登成功；签名和会话保护已有自动化验证。远端配置未回读，客户端刷新恢复、退出重登未逐项人工验证。完整设置及官方参考见 [项目 README](../../README.md)。
