# 系统整体架构

更新日期：2026-11-11。源码现状与已确认但未实现能力分别标注。

用户确认流程是两品牌七字段原始导入 → 系统标准化（含颜色）→ 唯一明确结果自动入可用标准库、异常才人工审核 → 政策按规格不限颜色或指定颜色。详见 [商品方案](catalog-admin-design.md)和[清洗细则](../business/catalog-cleaning.md)。保留九表并补来源清洗字段、任务清洗阶段及SYSTEM审计；功能未实现。目标库仍按用户最新说明未建表，由用户自行执行SQL；本轮不连接目标库。

## 产品与运行方式

系统是支持飞书客户端和普通浏览器访问的差异化政策网页应用，包含申请人与采购管理员两类角色。客户端通过 JS-SDK 免登，浏览器通过飞书网页 OAuth 授权，使用同一套服务端身份核验及会话。先完成申请、审核、查询闭环；不扩展结算、打款、库存或多级审批。

飞书登录后按服务端 `user.roleType` 分流：ADMIN 进入管理员全部政策页，其他角色沿用申请人工作台。申请人侧栏仍为「政策提交 → 提交政策 / 我的提交」，同一表单切换零售和百亿。管理员已完成展示工作台，包含政策、商品及系统信息九个路由，业务操作均未接入；详见 [管理员工作台](admin-workspace.md)。

```mermaid
flowchart LR
    F[飞书客户端] --> V[Vue 3 页面]
    B[普通浏览器] --> V
    V -->|浏览器网页授权与回调| O[飞书官方 OAuth 授权页]
    V -->|同源 /api| C[Spring Boot Controller]
    C --> S[Service 接口与实现]
    S --> M[MyBatis-Plus Mapper]
    M --> D[(MySQL)]
    S --> L[飞书鉴权 API]
```

数据库访问当前仅接入 `user_info`；政策三表的 Mapper、Service 和 Controller 尚未实现。

## 实际技术栈

| 层 | 当前配置 |
| --- | --- |
| 前端 | Vue 3、Vite 6、Composition API、Element Plus、业务布局 CSS；依赖以 lockfile 为准 |
| 后端 | Java 17、Spring Boot 3.5.7、Lombok |
| 数据访问 | MyBatis-Plus 3.5.12、MySQL Connector；H2 仅测试 |
| API 文档 | Knife4j 4.5.0、springdoc 2.8.14 |
| 检查 | ESLint / Prettier / Vitest，JUnit / Spotless |

Element Plus 组件及样式通过 unplugin-vue-components 按需导入，中文语言包由 ElConfigProvider 配置；申请工作台在登录后延迟加载。未引入 Tailwind；不按示例模板降级 Spring Boot。Knife4j 增强插件因兼容性关闭，`/doc.html` 和 `/v3/api-docs` 保留。

## 前端组件与页面现状

2026-11-11 已完成现有页面的 Element Plus 替换，依赖版本以 `frontend/package-lock.json` 为准。

| 位置 | 已使用组件与行为 |
| --- | --- |
| `src/App.vue` | ConfigProvider 配置中文；Button 显示登录加载状态；申请工作台登录后延迟加载 |
| `views/apply/components/PolicyApplicationForm.vue` | Form、Select、CheckTag、Radio、DatePicker、Tabs、Tag、Input、Upload、Button、Alert；MessageBox 确认渠道切换、取消平台及清空表单 |
| `views/apply/components/PolicyDetailsTable.vue` | Table 使用稳定行 key、横向滚动和右侧固定操作列；Input、Select 编辑明细，Empty 显示无明细提示 |
| `views/apply/ApplicantWorkspace.vue` | 保留业务侧栏和横幅；Empty 与 Button 展示我的提交占位入口；菜单切换保留已填表单 |
| `vite.config.js`、`src/test-setup.js` | 按需导入组件及样式；测试处理组件 CSS，并为 jsdom 提供尺寸监听替身 |

自定义 CSS 保留侧栏、横幅、业务网格、响应式布局和少量控件尺寸调整，不重复绘制通用控件。`assets/main.css` 管理基础主题，`assets/workspace.css` 管理工作台布局。桌面基础信息在适当宽度分两行排列；手机上日期范围上下显示完整时间，日期弹层限制宽度并可纵向滚动，确认操作保持可访问；明细表格在容器内横向查看，空状态使用正常行高。

日期仍保存精确到秒的北京时间墙上时间字符串，不转换为 UTC；金额仍按十进制字符串录入。确认框以异步结果决定是否清除，取消时不修改原渠道和已填明细。Upload 关闭自动上传，仅暂存当前页面文件，可移除；独立附件接口及 `useAttachments` 已有实现，但当前申请页没有接入该上传流程，详见 [附件接口](../api/attachment-api.md)。组件替换不代表政策持久化、标准机型库或申请页附件上传已接通。当前型号、配置和颜色仍是手工输入占位，不能视为已完成标准目录和颜色范围选择。

## 模块职责

```text
frontend/src/
  api/                         公共请求与接口调用
  assets/                      样式
  components/                  通用组件
  constants/                   固定选项
  composables/                 状态与交互
  utils/                       纯函数与 SDK 封装
  views/apply/                 申请人工作台
    components/                表单与明细表格

backend/src/main/java/com/company/policy/
  controller/                  请求、校验与接口说明
  dto/                         请求对象
  vo/                          响应对象
  service/                     服务接口
  service/impl/                业务、组件及事务注解
  entity/                      数据库实体
  mapper/                      持久化
  common/api/                  公共错误响应
  common/config/               配置与组件注册
  common/interceptor/          登录态和来源校验
  common/exception/            异常与统一处理
```

`router/` 已接入 Vue Router Hash 路由，`views/admin/` 为管理员展示页面；`stores/`、`views/audit/` 仍按需启用。Controller 和服务通过接口注入，不把实现写进接口或配置类。页面不承担服务端权限和最终生效判断。

前端公共请求由 `api/request.js` 处理超时、错误和请求头；组件通过 props、emit 或 v-model 交互，不直接修改 props，列表使用稳定标识。未来金额使用十进制字符串传输，服务端 `BigDecimal` 计算。

## 数据与安全边界

- 九表及字段见 [数据字典](../sql/database-design.md)。政策用户核心四表加标准规格、规格颜色、七字段来源、同步任务、商品操作日志；来源内联映射，不另建品牌/系列/型号和规则表。skuId 仅续拉，按内容全量比对，不承诺外部SKU稳定身份更新；历史快照不改写。
- 主表保存申请人、渠道、平台集合、时间、状态及版本；明细逐平台保存机型、数量和补贴；日志保留各轮提交和审核意见。
- 提交、审核和日志须由 Service 同事务完成，条件更新执行状态和版本保护。
- 后端校验权限。ADMIN 是本地受控角色；当前角色来自登录时快照，未来管理员接口须重新校验有效账号、角色与租户。
- 含删除标记的表查询须过滤；明细和日志通过父申请授权。身份查找须识别停用账号，不能重新注册绕过。
- 密钥仅在被忽略的 `backend/.env.local.properties` 或环境变量；用户 token 不存储、不下发。

## 当前实现与验证边界

2026-11-11 管理员展示工作台：`npm.cmd run check` 完整通过 ESLint、Prettier、12 个测试文件的 48 项测试及生产构建；`git diff --check` 无差异格式错误。新增验证覆盖 ADMIN 分流、申请人路由限制、管理员九个子路由、未知地址回退、演示筛选、日期单边及倒置范围。浏览器已检查 1440、768、390px 的页面，验证搜索/重置/分页、详情/历史/候选影响、窄屏导航与商品任务/七字段证据/规格颜色/操作记录展示，业务操作按钮均禁用。用户亲自完成真实登录后，已观察到 5173 管理员页面与真实账号；其余界面验证使用 5174 的隔离组件预览，不代表商品或政策接口已接入。截图保存在忽略目录 `.local-cache/admin-ui/admin-policies.jpg`。本轮未运行后端检查、未连接数据库、未修改飞书远端配置。

| 能力 | 状态 |
| --- | --- |
| 票据缓存、页面签名、config/ready | 已实现 |
| requestAccess、身份核验、用户落库 | 已实现；用户已确认真实登录成功 |
| 普通浏览器网页授权、回调交换 | 已实现；本轮用户完成真实授权后观察到管理员页面，取消/超时等人工联调仍待完成 |
| 会话恢复、退出、来源保护 | 已实现；恢复及退出重登未逐项人工验证 |
| 渠道显隐、平台独立明细、补贴切换 | 已实现前端交互 |
| 草稿保存、正式提交、我的提交 | 未接业务持久化，不显示假成功 |
| 机型读取、共享补贴批量操作、上传 | 待接入 |
| 管理员工作台、筛选及详情 | 展示版已实现，仅筛选本地演示数据，业务接口未接入 |
| 审核、覆盖计算和生效 | 待实现，覆盖口径待确认；展示版不执行决定 |

当前用户目标库未建表，以 2026-11-11 最新说明为准。曾有独立 user_info 回滚测试历史，但不能证明当前目标库存在该表。项目未启用 Flyway 或自动建表，九表 DDL 与业务代码接入分开验收。

此前 2026-11-10 代码检查记录：前端 27 项测试及 lint、格式、构建通过；后端 40 项常规测试及打包、Spotless 通过，1 项真实 MySQL 专项当轮跳过。更早真实 MySQL 回滚及独立 HTTP 检查通过。这些是既往记录，本次文档整理不代表重新运行。命令及验证范围见 [项目 README](../../README.md)。

2026-11-11 Element Plus 替换：本轮前端 lint、格式、41 项测试及生产构建通过。覆盖组件切换、确认框、日期及附件暂存交互。用户授权后，已检查 1440px、768px、390px 浏览器布局，修复窄屏时间与弹层及空状态显示，验证日期选择、明细输入、渠道提示和菜单返回。使用本地临时组件预览，未进行真实飞书授权或业务提交联调；本次未运行后端检查。

本地前端 `http://127.0.0.1:5173`，后端 `http://127.0.0.1:8080`，Vite 代理 `/api`；默认只监听本机。正式 HTTPS 部署须同源代理并设置 Secure Cookie，多实例共享会话和缓存。当前单进程会话随重启清空。
