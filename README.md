# 差异化政策管理

已建立 Vue 3 / Vite 前端和 Java 17 / Spring Boot 后端，并接入飞书网页应用 JS-SDK 的服务端签名与前端初始化。

当前范围是本地开发接入。用户身份、管理员权限、业务表单和数据库尚未接入；SDK 就绪不代表完成用户登录。

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
4. 按后台要求保存配置，并在测试应用或版本管理中确认当前用户处于可用范围。然后从当前电脑的飞书客户端打开网页应用，查看初始化状态。

普通浏览器只检查后端配置连接，并显示等待飞书客户端。手机上的 `127.0.0.1` 指向手机自身，因此手机调试需要后续提供手机可访问的 HTTPS 测试地址。部署域名稍后确定，再同步更新桌面端/移动端主页、可信域名及后端 `FEISHU_ALLOWED_ORIGINS`（后端使用带协议的完整 origin，如 `https://policy.example.com`）。开发代理不能替代正式部署的 `/api` 反向代理。

## 接入流程

1. 前端读取 `GET /api/feishu/config`，只取得公开 App ID 和凭证是否已配置的状态。
2. 飞书客户端加载官方固定版本 `h5-js-sdk-1.5.48.js`。
3. 将当前页面完整 URL（保留查询参数、去掉 `#` 及后续内容）POST 至 `/api/feishu/js-sdk/signature`。
4. 后端先验证 URL origin，再从飞书获取并缓存 `tenant_access_token` 和 `jsapi_ticket`，使用毫秒时间戳、随机 nonce、SHA-1 返回签名。
5. 前端注册 `h5sdk.error` 后调用 `h5sdk.config` 和 `h5sdk.ready`；仅在 config 成功且 ready 到达后显示就绪。当前 `jsApiList` 为空，待实际功能接入时添加所需 API。

App Secret、token、ticket 不返回前端，也不写入日志；签名接口返回 `Cache-Control: no-store`。SDK 初始化失败支持重试。token 和 ticket 在单后端进程内缓存并在过期前刷新，每次签名生成新的 nonce；多实例部署时再接入共享缓存。真实登录需要另行实现授权码交换、服务端会话和用户映射。

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

测试覆盖 SDK 时序与失败判定、签名固定向量、URL 白名单/查询串保留、票据缓存/刷新与并发、接口校验与错误脱敏。测试使用虚拟凭证，不依赖真实飞书 API；客户端内鉴权需以上述配置完成后实测。

2026-11-10 本机验证：前端 6 项测试与生产构建通过；后端现为 25 项测试与 JAR 打包通过，新增项目根目录和 backend 目录加载私密配置的检查。排查连接失败时发现 IDEA 默认工作目录未加载私密配置，同时本地文件中的应用标识已变更；已按用户最新提供的凭证修正并重启。经前端代理调用签名接口，真实飞书 token / ticket 获取成功，签名返回 HTTP 200；页面显示正确 App ID、凭证已加载，普通浏览器显示等待飞书客户端。非允许来源返回 HTTP 400；OpenAPI 文档正常。源码、交付文档与前端构建产物的密钥扫描通过。远端应用主页/可信域名尚未修改，飞书客户端内的 `h5sdk.config` 鉴权尚待实测。

后端依赖为 Spring Boot 3.5.7、Knife4j 4.5.0、springdoc 2.8.14、Lombok。Knife4j 保留 `doc.html` 文档界面，`knife4j.enable=false` 关闭与当前 springdoc 不兼容的增强插件，避免文档接口返回 500。MyBatis-Plus 3.5.12 / MySQL Connector 放在 Maven `database` profile 中，待核对实际数据库再启用；当前启动不连接数据库。前端实际解析版本由 `frontend/package-lock.json` 固定。

官方依据：[网页应用概述](https://open.feishu.cn/document/client-docs/h5/introduction)、[h5sdk.config](https://open.feishu.cn/document/web-app/gadget-api/open-ability/authentication/h5sdkconfig)、[签名与完整示例](https://open.feishu.cn/document/home/integrating-web-apps-in-5-minutes/debug-and-release)、[创建网页应用](https://open.feishu.cn/document/client-docs/h5/development-guide/step1)。
