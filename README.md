# 差异化政策管理

2026-11-11 前端规范化重构：已完成数据边界、页面与样式、基础状态三个阶段的适用整改。布局移至 `frontend/src/layouts/`，商品五类独立路由在 `views/admin/catalog/`；申请表单区块通过事件修改集中状态，切菜单保留内容，身份变化/退出清理；统一 `useAuthSession`、query 白名单、请求错误及取消边界。展示样例集中在 `mocks/`，缺失历史显示空状态，不生成审核或同步结果。无新增依赖，未新增业务接口或启用上传。实现清单见 [前端规范](docs/architecture/frontend-development.md#9-验证记录与维护)。

本轮在 frontend 运行 `npm.cmd run check`，通过 ESLint、Prettier、19 个测试文件的 77 项测试及生产构建。隔离组件预览已检查 1440×1000、768×1000、390×844 的日期到秒显示、弹层边界/滚动、表格横向查看/固定操作列、分页及取消回显；截图保存在 `.local-cache/refactor-ui/`。本轮未重新进行真实飞书授权或 Cookie 恢复/退出联调，未连接数据库、修改后端、运行后端检查或部署。标准目录、共享补贴、持久化、审核和附件契约等仍属后续业务接入，不能据此标记已实现。

已建立 Vue 3 / Vite 前端和 Java 17 / Spring Boot 后端，并接入飞书客户端 JS-SDK 免登、浏览器内飞书扫码、网页 OAuth 授权及服务端会话。

当前包含申请人页面、登录落库及管理员工作台。登录按服务端 `user.roleType` 分流，ADMIN 进入 `#/admin/catalog/sources`，侧栏为「商品 → 源商品库」。管理员可分别导入 OPPO、一加，查询已落库的七字段原文。其他角色进入申请人工作台，侧栏为「政策提交 → 提交政策 / 我的提交」，同一个表单切换零售、百亿。SDK 鉴权与用户登录是两个独立步骤。详见 [管理员工作台](docs/architecture/admin-workspace.md)。

已接入 MyBatis-Plus / MySQL，登录时按 `(tenant_key, open_id)` 幂等保存至 `user_info`，新用户默认为 `APPLICANT`；重复登录更新姓名及有效 union_id，保留已有角色、部门、停用标记。部门不从姓名推断，不额外请求通讯录权限。停用用户拒绝登录，用户存储失败不建立会话。管理员业务接口尚未开发。

后端包结构约定：`controller` 接收请求，`dto` 定义请求对象，`vo` 定义响应对象，`entity` 映射数据库，`mapper` 执行持久化操作。服务接口放在 `service`，对应实现放在 `service.impl` 并命名为 `接口名Impl`；Controller 和服务之间通过接口注入，组件及事务注解放在实现类。`common.config` 负责配置注册，`common.interceptor` 承担登录态与请求来源校验。登录 challenge 响应独立为 `LoginChallengeResponse`。

前端目录约定：`api/request.js` 负责公共请求、超时和错误处理，其他 `api/` 模块定义业务接口；`constants/` 放固定选项，`composables/` 管理表单状态与交互，`utils/` 放纯函数。申请人工作台负责布局，表单、明细表格等拆分在 `views/apply/components/`；通用组件放在 `components/`。列表使用稳定标识，组件通过 props / emit 或 v-model 交互。

前端已引入 Element Plus，组件和样式由 unplugin-vue-components 按需导入，统一中文语言；申请工作台登录后延迟加载。表单、日期范围、明细表格、平台页签、附件选择、按钮、提示和确认框使用组件库，自定义 CSS 负责业务布局与响应式适配。附件选择仍仅暂存页面，政策提交仍提示尚未接入。

源码说明性注释使用中文。根目录 `.editorconfig` 统一编码和缩进；前端通过 ESLint / Prettier 检查未使用变量、控制语句、Vue 组件和格式，后端通过 Spotless 统一 Java 导入与格式。日常检查和格式修复命令见下文。

2026-11-11 Element Plus 替换验证：本轮 `npm.cmd run check` 通过 ESLint、Prettier、10 个测试文件中的 41 项测试及生产构建。组件回归覆盖渠道显隐、取消清除后保留内容、确认清除、平台独立回显、补贴互斥、菜单切换保留、日期秒精度与清空、附件暂存与移除、未接通提交的提示。按需导入并拆分登录页/申请工作台，构建未出现单个 JS 包超过 500 kB 的提示。用户授权后，已在真实浏览器检查 1440px 桌面、768px 平板和 390px 窄屏；修复窄屏时间截断、日期面板溢出和表格空状态行高，并验证日期选择确认、明细输入、渠道清除提示及菜单返回。使用本地临时组件预览，不代表真实飞书登录或业务提交联调；未运行后端检查。

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
7. 重新启动本地后端，从当前电脑的飞书客户端（推荐 6.9+）打开网页应用。成功保存用户后按本地角色进入工作台，刷新通过服务端会话恢复。申请人不显示头像或用户菜单，管理员显示当前账号身份；退出 API 保留。旧客户端缺少 requestAccess 或返回 errno 103 时兼容 requestAuthCode；授权被拒绝或配置错误时不会降级重试。

飞书客户端自动通过 `requestAccess` 验证身份。普通浏览器可点击「显示飞书二维码」，用手机飞书扫码并确认，也可点击「飞书网页授权登录」前往飞书授权页。两种浏览器方式均携带 code/state 回到站点首页。浏览器回调固定为当前 Origin 的 `/`，如 `http://127.0.0.1:5173/` 或 `http://localhost:5173/`，必须与飞书后台的重定向 URL 一致，且 Origin 在 `FEISHU_ALLOWED_ORIGINS` 中。若后台拒绝本机 HTTP 地址，请使用可访问的 HTTPS 测试域名，并同步主页、H5 可信域名、重定向 URL 及 `FEISHU_ALLOWED_ORIGINS`。

`2700002` 表示 requestAccess 授权过程失败，是通用外层错误码（取消授权也可能返回），不能仅凭此码判定用户拒绝。页面会显示飞书返回的数字内层错误码，不输出原始错误消息或凭证。先核对上述重定向 URL，再检查应用可用范围、当前飞书企业与授权操作。SDK 的 `333447/333448` 则对应 H5 可信域名配置，应区分处理。

普通浏览器会先恢复已有应用会话，无会话时显示网页授权入口；客户端和浏览器的 Cookie 独立，需要分别登录。手机上的 `127.0.0.1` 指向手机自身，因此手机调试需要后续提供手机可访问的 HTTPS 测试地址。部署域名稍后确定，再同步更新桌面端/移动端主页、可信域名、重定向 URL 及后端 `FEISHU_ALLOWED_ORIGINS`（后端使用带协议的完整 origin，如 `https://policy.example.com`）。正式 HTTPS 部署设置 `SESSION_COOKIE_SECURE=true`，并使用同一站点的 `/api` 反向代理。开发代理不能替代正式部署的反向代理。

## 接入流程

普通浏览器先恢复应用会话。需要重新登录时，扫码与网页授权均使用 `POST /api/auth/feishu/browser/authorize`，在当前 Cookie 会话中建立 3 分钟内一次有效的 state。二维码由飞书官方组件按需生成，过期后可刷新。授权返回后，前端先清除地址栏中的 code/state/error，再调用 `POST /api/auth/feishu/browser/login`，由服务端校验 state、换取身份并轮换 Session ID。详见 [登录流程](docs/architecture/feishu-auth-flow.md)。

以下为飞书客户端流程：

1. 前端读取 `GET /api/feishu/config`，只取得公开 App ID 和凭证是否已配置的状态。
2. 飞书客户端加载官方固定版本 `h5-js-sdk-1.5.48.js`。
3. 将当前页面完整 URL（保留查询参数、去掉 `#` 及后续内容）POST 至 `/api/feishu/js-sdk/signature`。
4. 后端先验证 URL origin，再从飞书获取并缓存 `tenant_access_token` 和 `jsapi_ticket`，使用毫秒时间戳、随机 nonce、SHA-1 返回签名。
5. 前端注册 `h5sdk.error` 后调用 `h5sdk.config` 和 `h5sdk.ready`；仅在 config 成功且 ready 到达后继续。当前 `jsApiList` 为空；`requestAccess` 本身用于取得登录授权，后续新增需鉴权的 API 时再补充该列表。
6. 先读取 `GET /api/auth/session` 恢复已有会话；无会话时完成 SDK 初始化，通过 `POST /api/auth/challenge` 获取绑定当前 Cookie 的随机 state，再调用 `tt.requestAccess({appID, scopeList: [], state})`。客户端授权码有效期 3 分钟且仅能使用一次。
7. 前端将 `{code,state}` 提交至 `POST /api/auth/feishu/login`。后端消费 state，向 `https://accounts.feishu.cn/oauth/v3/token` 交换 `access_token`，再通过 `GET https://open.feishu.cn/open-apis/authen/v1/user_info` 获取真实身份。
8. 后端先在事务中保存飞书核验的身份至 `user_info`，再轮换 Session ID，保存本地用户 ID、角色、身份及到期时间；前端只收到身份及会话到期时间，不接收飞书 token。刷新时恢复服务端会话；失败后重新取得 state/code，不自动重放旧 code。
9. `POST /api/auth/logout` 使当前应用会话失效，不会退出飞书客户端；当前申请人页面不显示退出入口。

App Secret、token、ticket 不返回前端，也不写入日志；API 响应禁止缓存。SDK 初始化失败支持重试。应用 token 和 ticket 在单后端进程内缓存并在过期前刷新，每次签名生成新的 nonce。用户 token 仅在本次后端调用中使用，不落库、不保留刷新令牌。

应用会话使用 `POLICY_SESSION` Cookie（HttpOnly、SameSite=Lax、仅 Cookie 跟踪），闲置 30 分钟到期，最长不超过 2 小时及飞书 token 返回的有效期；后端重启会清空会话。该会话不是飞书实时登录状态订阅，用户撤销飞书授权不会即时清除既有应用会话；到期重新授权。多实例部署前需接入共享会话和缓存。

所有 `/api/**` 默认需登录，只有公开配置、SDK 签名、登录 challenge/交换和退出接口放行。写请求统一校验 `Origin` 属于 `FEISHU_ALLOWED_ORIGINS` 且有 `X-Policy-Request: 1` 请求头，无跨域放行。使用 Knife4j/调试工具请求 POST 时也需这些请求头。登录 state 在 3 分钟内一次有效，成功后轮换会话 ID；前端不能自行提交姓名、open_id 或角色作为身份。登录创建用户只授予 APPLICANT，已有角色从数据库读取；管理员业务权限另行实现。

## 模块化文档与数据库设计

## 吉客云商品导入

1. 在 Navicat 选中目标库。首次建表执行 [九表建表 SQL](docs/sql/schema.sql)，与 `V1__create_policy_tables.sql` 二选一。若已执行包含 `oppo_status` 和 `oneplus_status` 的旧版九表，且导入任务、来源商品、商品日志均为空，停止后端后执行 [单品牌导入增量升级 SQL](docs/sql/upgrade_empty_goods_sync_to_single_brand.sql)。有商品导入历史的库需单独迁移。
2. 在后端环境变量或忽略的 `backend/.env.local.properties` 配置 `JACKYUN_APP_KEY`、`JACKYUN_APP_SECRET`，ISV 应用另配置 `JACKYUN_TOKEN`。设置 `JACKYUN_TENANT_KEY` 为授权管理员的 `user_info.tenant_key`。`JACKYUN_SOURCE_KEY` 是本地账套标识，默认 `jackyun`；更换实际账套时用新标识，避免混用原文及断点。
3. 确认该 AppKey 的上游额度，再按需设置 `JACKYUN_IMPORT_INTERVAL_SECONDS`（默认 5 秒）与 `JACKYUN_IMPORT_MAX_PAGES`（默认 2000 页）。重启后端后以管理员进入源商品库，点「导入OPPO手机」或「导入一加手机」，完成后再导入另一个品牌。

任务断点和七字段原文按页一起落库，异常暂停，可继续或结束。当前导入完成只表示原文已保存，清洗仍待接入。[商品流程与只读实测](docs/architecture/catalog-admin-design.md) · [管理接口](docs/api/catalog-api.md)

仅核对 API 时，可在 `backend/` 将凭证临时设为进程环境变量，设置 `JACKYUN_LIVE_CHECK=1`，运行 `mvn.cmd '-Dmaven.repo.local=.local-cache/maven-repository' '-Dtest=JackyunGoodsLiveTest' test`。该测试每品牌查询 3 条，只输出字段统计，不连接数据库。

架构、业务规约、状态机、覆盖 SQL 草案及申请人/管理员接口设计统一见 [docs 文档索引](docs/README.md)。接口和覆盖算法文档明确区分已实现能力与待实现草案。

后续功能迭代、缺陷修复和代码审查先读 [AGENTS.md](AGENTS.md) 与 [AI 辅助全栈开发规范](docs/architecture/ai-development.md)，其中包含代码示例、跨层契约、测试要求和迭代需求模板。

前端开发从 [frontend/README.md](frontend/README.md) 进入，目录职责、组件数据流、接口模型、会话路由、样式及整改验收见[前端架构与开发规范](docs/architecture/frontend-development.md)。前三阶段适用整改已实施，政策与商品业务接口接入仍待完成；当前能力以源码和整体架构的实现记录为准。

九张表的关联、全部字段、草稿与审核历史见 [数据库设计与字段字典](docs/sql/database-design.md)。在 Navicat 选中已有空库，执行 [完整建表入口](docs/sql/schema.sql)（与 [V1](docs/sql/V1__create_policy_tables.sql) 完全相同，二选一，不能执行两遍），适用 MySQL 8.0.16+。SQL 不建库、不删表、不插入用户或示例商品；执行前 SELECT VERSION(), DATABASE(); 和 SHOW TABLES; 核对版本、库名和空表状态。

用户最新明确目标库仅有库名、尚未建表，由用户自己执行 SQL；该说明覆盖早前四表存在的历史描述。本轮未连接该目标库。商品、政策业务仍未接入持久化，创建九表不代表接口或页面已可用；覆盖优先级等仍待确认。

本机数据库为 `127.0.0.1:3306` 下的 `differentiated policy`（库名包含空格，JDBC URL 使用 `differentiated%20policy`）。连接使用 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，真实凭证只保存在被忽略的 `backend/.env.local.properties`；新环境参考 `.env.example`。应用不自动建表、不初始化真实业务数据。修改后须重启后端，让新登录经过落库；已有旧进程内会话不代表已写入 user_info。

## 验证与依赖

2026-11-11 管理员展示工作台：本轮 `npm.cmd run check` 通过 ESLint、Prettier、12 个测试文件中的 48 项测试及生产构建，`git diff --check` 无差异格式错误。新增角色分流、路由限制及演示筛选检查；浏览器已检查 1440/768/390px，验证导航、筛选/重置/分页、政策详情和历史、商品原文证据与目录颜色等展示。用户亲自完成浏览器授权后，已观察真实账号进入 `http://127.0.0.1:5173/#/admin/policies`；其余检查使用 5174 隔离组件预览。业务按钮禁用，无真实政策/商品接口联调；取消、超时、刷新恢复等授权场景仍须单独核验。本轮不运行后端检查，不连接数据库或修改远端配置。

2026-11-11 浏览器登录修复：前端 39 项测试及 ESLint、Prettier、生产构建通过；后端 49 项测试、JAR 打包和 Spotless 通过，1 项真实 MySQL 专项测试未启用。新增验证覆盖浏览器授权链接、最小权限、回调参数清理、取消授权、无效/过期/重放 state、服务端回调地址绑定、会话轮换和恢复、失败不建立会话；既有客户端免登测试继续通过。本轮未连接用户目标库，未修改飞书远端配置，真实浏览器扫码/授权需重启服务后联调。

2026-11-11 自动清洗结构更新：在项目缓存目录下独立、无网络监听的 MySQL 8.0.29 临时实例成功执行新版九表 SQL，52 项合成数据约束检查通过，覆盖清洗状态、规则/候选/颜色门槛、审核和 SYSTEM/USER 审计，并回归既有关系约束。旧版27项为此前验证，本轮不冒充真实解析器或页面通过。未连接用户目标库、未调用真实商品接口，未运行前后端全量检查。完整字段及范围见数据库字典。

```powershell
cd frontend
npm.cmd ci --cache ../.local-cache/npm --no-audit --no-fund
npm.cmd run check
```

```powershell
cd backend
mvn.cmd '-Dmaven.repo.local=.local-cache/maven-repository' verify
```

测试覆盖 SDK 时序与失败判定、签名固定向量、URL 白名单/查询串保留、票据缓存/刷新与并发，以及免登最小权限、授权失败/超时/重试、重复请求合并、state 绑定与过期/重放拦截、会话轮换/恢复/退出/到期、接口鉴权与错误脱敏。自动化测试使用虚拟凭证和模拟上游，不依赖真实飞书 API；真实免登结果单独记录如下。

`npm.cmd run check` 依次运行 ESLint、Prettier 格式检查、测试和生产构建；Maven `verify` 执行测试、打包及 Spotless 格式检查。自动修复格式：在 `frontend/` 运行 `npm.cmd run format`，在 `backend/` 运行 `mvn.cmd '-Dmaven.repo.local=.local-cache/maven-repository' spotless:apply`；修复后检查差异。

2026-11-10 本轮规范整理验证：前端静态检查、格式检查、27 项测试及生产构建通过；后端 40 项常规测试、JAR 打包及 Spotless 检查通过，另 1 项真实 MySQL 专项测试未启用、已跳过。新增测试覆盖页面拆分后的菜单切换保留填写内容、各平台独立回显、渠道字段显隐、补贴方式互斥、请求错误和超时，以及 404 / 405 / 415 状态与 500 错误脱敏。

此前验证：真实 MySQL 专项检查通过（临时用户写入全部回滚），确认首次 APPLICANT、重复登录去重及资料更新、已有 ADMIN/部门保留、停用用户拒绝、存储失败不建立会话。独立临时入口已检查真实 Vue 布局，验证后已移除；独立端口 HTTP 检查确认 Cookie 标记、401 / 403 拦截、退出 204 及 OpenAPI 登录接口。用户已确认真实飞书免登成功；远端配置未由 Agent 修改或回读，刷新恢复和退出重登仍仅有自动化验证。真实飞书重新登录写入当前用户须在后端重启后由客户端触发。

后端依赖为 Spring Boot 3.5.7、Knife4j 4.5.0、springdoc 2.8.14、Lombok、MyBatis-Plus 3.5.12 / MySQL Connector。`database` Maven profile 已合并为常规依赖，H2 仅供自动化测试。Knife4j 保留 `doc.html`，关闭不兼容增强插件。前端实际解析版本由 `frontend/package-lock.json` 固定。

真实 MySQL 的回滚验证命令（会读取本地私密配置，不自动建表）：

```powershell
cd backend
mvn.cmd '-Dmaven.repo.local=.local-cache/maven-repository' '-Dpolicy.mysql.verify=true' '-Dtest=UserInfoMySqlTest' test
```

官方依据：[网页应用概述](https://open.feishu.cn/document/client-docs/h5/introduction)、[h5sdk.config](https://open.feishu.cn/document/web-app/gadget-api/open-ability/authentication/h5sdkconfig)、[签名与完整示例](https://open.feishu.cn/document/home/integrating-web-apps-in-5-minutes/debug-and-release)、[创建网页应用](https://open.feishu.cn/document/client-docs/h5/development-guide/step1)。

免登依据：[requestAccess](https://open.feishu.cn/document/web-app/gadget-api/open-ability/login/requestaccess)、[配置重定向 URL](https://open.feishu.cn/document/develop-web-apps/configure-redirect-urls)、[免登及旧客户端兼容示例](https://open.feishu.cn/document/client-docs/h5/development-guide/step-3)、[错误码](https://open.feishu.cn/document/client-docs/gadget/-web-app-api/errno)、[获取 user_access_token（v3）](https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/authentication-management/access-token/get-user-access-token-v3)、[获取用户信息](https://open.feishu.cn/document/server-docs/authentication-management/login-state-management/get)。按 2026-11-10 官方文档实现，不使用已标为历史版本的 v2 token 接口。
