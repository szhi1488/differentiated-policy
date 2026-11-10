# 差异化政策管理

已建立 Vue 3 / Vite 前端和 Java 17 / Spring Boot 后端，并接入飞书 JS-SDK、网页应用用户免登和服务端会话。

当前范围是本地开发接入。用户身份通过飞书授权码交换与用户信息接口核验；管理员权限、业务表单和数据库尚未接入。SDK 鉴权与用户登录是两个独立步骤。

## 本地启动

环境：Node.js 22.12+、npm、JDK 17、Maven 3.8+。本机检查为 Node.js 22.15.0、npm 10.9.2、JDK 17.0.6、Maven 3.8.4。

后端从 `backend/.env.local.properties` 读取私密配置，已在本机配置。支持从项目根目录（包括 IDEA 默认工作目录）或 `backend/` 目录启动；两种工作目录均有回归验证。新环境可将 `backend/.env.example` 复制为该文件并填写密钥，也可使用同名系统环境变量；环境变量优先。不要把密钥放到 `frontend/`、`VITE_*` 变量、源码或可提交的配置中。

在项目根目录打开两个 PowerShell 终端，分别执行：

```powershell
.\scripts\start-backend.ps1
```

```powershell
.\scripts\start-frontend.ps1
```

- 前端：<http://127.0.0.1:5173>
- 后端：<http://127.0.0.1:8080>
- Knife4j 接口文档：<http://127.0.0.1:8080/doc.html>

前端 `/api` 通过 Vite 转发到后端，因此无须开放跨域。默认两个服务都只监听本机。前端端口固定为 5173，端口冲突会停止启动，避免签名来源与实际页面地址不一致。

## 飞书开发者后台配置

应用 ID：`cli_aa64531940e15c1e`。以下为需要在飞书开放平台完成的设置，本次没有修改或发布远端应用。

1. 在应用的「应用能力」添加/开启「网页应用」。
2. 本机电脑端调试时，将「桌面端主页」设为 `http://127.0.0.1:5173`。
3. 在「安全设置 → H5 可信域名」填写 `127.0.0.1:5173`；若使用 localhost，再添加 `localhost:5173`。飞书后台的可信域名不包含协议和路径。
4. 在「安全设置 → 重定向 URL」添加 `http://127.0.0.1:5173/`。使用 localhost 时另添加 `http://localhost:5173/`。**即使 requestAccess 不跳转页面，也必须配置这一项**：飞书会验证调用页面的完整地址，H5 可信域名不能替代重定向 URL。若后续页面路径改变，也需配置实际路径；去掉查询串和 fragment。
5. 确认应用已启用，在测试应用或版本管理中将调试用户加入可用范围；按后台要求保存，并使对应配置在当前测试版本生效。
6. 当前免登只获取基本身份，`tt.requestAccess` 的 `scopeList` 为空；不需额外申请通讯录、手机号、邮箱或 `offline_access` 权限。首次进入如飞书显示授权提示，由用户完成授权。
7. 重新启动本地后端，从当前电脑的飞书客户端（推荐 6.9+）打开网页应用。成功时显示「已登录」及真实姓名；刷新页面应恢复会话，点击「退出当前应用」后可再次授权登录。旧客户端缺少 requestAccess 或返回 errno 103 时，按官方指南使用 requestAuthCode 兼容；授权被拒绝或配置错误时不会降级重试。

当前使用客户端 `requestAccess` 返回 code，无需编写单独的 OAuth 回调页面，但必须把调用页面配置进飞书后台的重定向 URL 列表。普通浏览器扫码登录未实现。若后台界面拒绝本机 HTTP 地址，请使用实际可访问的 HTTPS 测试域名，并同步修改主页、H5 可信域名、重定向 URL 及 `FEISHU_ALLOWED_ORIGINS`。

`2700002` 表示 requestAccess 授权过程失败，是通用外层错误码（取消授权也可能返回），不能仅凭此码判定用户拒绝。页面会显示飞书返回的数字内层错误码，不输出原始错误消息或凭证。先核对上述重定向 URL，再检查应用可用范围、当前飞书企业与授权操作。SDK 的 `333447/333448` 则对应 H5 可信域名配置，应区分处理。

普通浏览器只检查后端配置连接，并显示等待飞书客户端。手机上的 `127.0.0.1` 指向手机自身，因此手机调试需要后续提供手机可访问的 HTTPS 测试地址。部署域名稍后确定，再同步更新桌面端/移动端主页、可信域名及后端 `FEISHU_ALLOWED_ORIGINS`（后端使用带协议的完整 origin，如 `https://policy.example.com`）。正式 HTTPS 部署设置 `SESSION_COOKIE_SECURE=true`，并使用同一站点的 `/api` 反向代理。开发代理不能替代正式部署的反向代理。

## 接入流程

1. 前端读取 `GET /api/feishu/config`，只取得公开 App ID 和凭证是否已配置的状态。
2. 飞书客户端加载官方固定版本 `h5-js-sdk-1.5.48.js`。
3. 将当前页面完整 URL（保留查询参数、去掉 `#` 及后续内容）POST 至 `/api/feishu/js-sdk/signature`。
4. 后端先验证 URL origin，再从飞书获取并缓存 `tenant_access_token` 和 `jsapi_ticket`，使用毫秒时间戳、随机 nonce、SHA-1 返回签名。
5. 前端注册 `h5sdk.error` 后调用 `h5sdk.config` 和 `h5sdk.ready`；仅在 config 成功且 ready 到达后继续。当前 `jsApiList` 为空；`requestAccess` 本身用于取得登录授权，后续新增需鉴权的 API 时再补充该列表。
6. 先读取 `GET /api/auth/session` 恢复已有会话；无会话时完成 SDK 初始化，通过 `POST /api/auth/challenge` 获取绑定当前 Cookie 的随机 state，再调用 `tt.requestAccess({appID, scopeList: [], state})`。客户端授权码有效期 3 分钟且仅能使用一次。
7. 前端将 `{code,state}` 提交至 `POST /api/auth/feishu/login`。后端消费 state，向 `https://accounts.feishu.cn/oauth/v3/token` 交换 `access_token`，再通过 `GET https://open.feishu.cn/open-apis/authen/v1/user_info` 获取真实身份。
8. 后端轮换 Session ID，保存 `tenantKey + openId`、姓名及到期时间；前端只收到身份及会话到期时间，不接收飞书 token。刷新时恢复服务端会话；失败后重新取得 state/code，不自动重放旧 code。
9. `POST /api/auth/logout` 使当前应用会话失效，不会退出飞书客户端。退出后本页不立即自动免登，用户可主动点击重新登录。

App Secret、token、ticket 不返回前端，也不写入日志；API 响应禁止缓存。SDK 初始化失败支持重试。应用 token 和 ticket 在单后端进程内缓存并在过期前刷新，每次签名生成新的 nonce。用户 token 仅在本次后端调用中使用，不落库、不保留刷新令牌。

应用会话使用 `POLICY_SESSION` Cookie（HttpOnly、SameSite=Lax、仅 Cookie 跟踪），闲置 30 分钟到期，最长不超过 2 小时及飞书 token 返回的有效期；后端重启会清空会话。该会话不是飞书实时登录状态订阅，用户撤销飞书授权不会即时清除既有应用会话；到期重新授权。多实例部署前需接入共享会话和缓存。

所有 `/api/**` 默认需登录，只有公开配置、SDK 签名、登录 challenge/交换和退出接口放行。写请求统一校验 `Origin` 属于 `FEISHU_ALLOWED_ORIGINS` 且有 `X-Policy-Request: 1` 请求头，无跨域放行。使用 Knife4j/调试工具请求 POST 时也需这些请求头。登录 state 在 3 分钟内一次有效，成功后轮换会话 ID；前端不能自行提交姓名、open_id 或角色作为身份。当前只确定应用内身份键，不创建数据库用户，不授予任何人管理员身份；管理员名单及业务权限规则后续确认后再实现。

## 验证与依赖

```powershell
cd frontend
npm.cmd ci --cache ../.local-cache/npm --no-audit --no-fund
npm.cmd test
npm.cmd run build
```

```powershell
cd backend
mvn.cmd '-Dmaven.repo.local=.local-cache/maven-repository' test
```

测试覆盖 SDK 时序与失败判定、签名固定向量、URL 白名单/查询串保留、票据缓存/刷新与并发，以及免登最小权限、授权失败/超时/重试、重复请求合并、state 绑定与过期/重放拦截、会话轮换/恢复/退出/到期、接口鉴权与错误脱敏。自动化测试使用虚拟凭证和模拟上游，不依赖真实飞书 API；真实免登结果单独记录如下。

2026-11-10 本机验证：前端现为 17 项测试与生产构建通过，新增 SDK 错误码脱敏、内层错误码提取与旧客户端兼容验证；后端此前 33 项测试及 JAR 打包通过，包括登录流程与既有配置加载、签名和文档回归。新 JAR 独立端口 HTTP 检查确认 Cookie 含 HttpOnly/SameSite=Lax，未登录与错误 state 返回 401，非可信来源返回 403，退出返回 204，OpenAPI 含登录接口。排查时实际读取飞书页面确认 SDK 鉴权通过、requestAccess 返回 2700002，核对官方文档后纠正了遗漏重定向 URL 的配置说明；随后用户明确确认「现在已经登录上了」，真实客户端免登已由用户验证成功。远端配置未由 Agent 操作，未回读配置明细；刷新恢复、退出后重登仍仅有自动化验证，不宣称已在客户端逐项实测。

后端依赖为 Spring Boot 3.5.7、Knife4j 4.5.0、springdoc 2.8.14、Lombok。Knife4j 保留 `doc.html` 文档界面，`knife4j.enable=false` 关闭与当前 springdoc 不兼容的增强插件，避免文档接口返回 500。MyBatis-Plus 3.5.12 / MySQL Connector 放在 Maven `database` profile 中，待核对实际数据库再启用；当前启动不连接数据库。前端实际解析版本由 `frontend/package-lock.json` 固定。

官方依据：[网页应用概述](https://open.feishu.cn/document/client-docs/h5/introduction)、[h5sdk.config](https://open.feishu.cn/document/web-app/gadget-api/open-ability/authentication/h5sdkconfig)、[签名与完整示例](https://open.feishu.cn/document/home/integrating-web-apps-in-5-minutes/debug-and-release)、[创建网页应用](https://open.feishu.cn/document/client-docs/h5/development-guide/step1)。

免登依据：[requestAccess](https://open.feishu.cn/document/web-app/gadget-api/open-ability/login/requestaccess)、[配置重定向 URL](https://open.feishu.cn/document/develop-web-apps/configure-redirect-urls)、[免登及旧客户端兼容示例](https://open.feishu.cn/document/client-docs/h5/development-guide/step-3)、[错误码](https://open.feishu.cn/document/client-docs/gadget/-web-app-api/errno)、[获取 user_access_token（v3）](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/authentication-management/access-token/get-user-access-token-v3)、[获取用户信息](https://open.feishu.cn/document/server-docs/authentication-management/login-state-management/get)。按 2026-11-10 官方文档实现，不使用已标为历史版本的 v2 token 接口。
