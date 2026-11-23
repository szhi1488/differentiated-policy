# 前端开发入口

本目录采用 Vue 3、Vite、JavaScript、Composition API、Vue Router 和 Element Plus。具体版本以 package-lock.json 为准。

## 阅读顺序

1. [根目录 AGENTS.md](../AGENTS.md)：唯一项目级 Agent 指令入口。
2. [文档总览](../docs/README.md)、[AI 辅助全栈开发规范](../docs/architecture/ai-development.md)、[整体架构与实现进度](../docs/architecture/system-overview.md)。
3. [前端架构与开发规范](../docs/architecture/frontend-development.md)：目录职责、组件数据流、接口适配、请求/路由/会话、样式、整改清单和验收。
4. 当前模块的业务与 API 文档，例如[动态表单](../docs/business/perspective-rules.md)、[管理员工作台](../docs/architecture/admin-workspace.md)、[飞书鉴权](../docs/architecture/feishu-auth-flow.md)。
5. [前端工程检查与验收](../docs/architecture/frontend-harness.md)。

此文件仅提供阅读与运行入口，不复制维护另一套 Agent 规则。工作台布局、申请表单区块和统一会话已接入。管理员在「商品 → 源商品库」按品牌导入并查询已落库原文。申请表单已接入在售目录、草稿保存、回填及提交。「我的提交」可查本人列表、当前申请详情和驳回原因，不展示提交轮次或历轮审核，并打开草稿或已驳回申请继续编辑。本人未提交草稿可确认后物理删除。附件已接入上传、失败重试、草稿绑定回填和提交快照，按申请及提交轮次下载。

## 常用命令

申请地址变更在工作台路由守卫中确认未保存内容，Hash 变化不关闭确认框。普通卡配置移除按完整卡片处理已有商品，抽屉确认后才更新表单；相关回归见 `tests/integration/ApplicantLayout.test.js` 和 `tests/unit/usePolicyGoodsPicker.test.js`。

在本目录运行，Node.js 要求以 package.json 为准：

```powershell
npm.cmd run dev
npm.cmd run check
# 仅在需要时修复格式，并检查差异。
npm.cmd run format
```

check 依次执行 ESLint、Prettier、Vitest 和生产构建。布局变更另按规范检查桌面、平板和窄屏；测试通过不代替真实授权或业务联调。环境配置、依赖安装和项目根目录启动脚本见[项目 README](../README.md)。

检查生产样式时，先运行 `npm.cmd run build`，再用 `npm.cmd run preview:qa -- APPLICANT`、`ADMIN` 或 `GUEST` 启动对应的本地预览。端口与验收项见[前端工程检查与验收](../docs/architecture/frontend-harness.md#生产构建的本地浏览器验收)。

管理员进入「机型补贴查询」会先读取商品目录，再按首个机型配置查询北京时间本月 1 日至月末的已通过政策，重置时按当时的月份重新查询。零售与百亿按原单展示。两图均按平台显示整组和具体商品，只在单品有差异的时段拆行，恢复一致后合并。点击单号或色块可核对当秒的审核结果。百亿明细同时保留原单申报商品和整组配置的选单或叠加名单。「当前时间」重新查询截至今天的记录，实际采用记录截至服务端 `asOf`。右侧「后续采用安排」列出该范围的后续单号，未来色块与明细按已通过决定区分计划采用及计划未采用。目录或历史读取失败时显示服务端错误并支持重试。[接口契约](../docs/api/page-api-plan.md#机型补贴查询)

申请人从「政策提交 → 机型补贴查询」打开相同时间轴。平台按当前分工列出，例如「零售 · 京东」，历史及后续补贴也按该范围返回。[申请人查询接口](../docs/api/page-api-plan.md#申请人机型补贴查询)

## 测试目录

- `tests/unit/` 存放 API、工具函数及独立状态逻辑的单元测试。
- `tests/components/` 存放独立 Vue 组件的渲染和交互测试。
- `tests/integration/` 存放应用登录、申请工作台、路由和查询状态的联动测试。

Vitest 只收集 `tests/**/*.test.js`，共用初始化位于 [tests/setup.js](tests/setup.js)。申请表单路由入口位于 [PolicyApplicationPage.vue](src/views/apply/PolicyApplicationPage.vue)，表单区块放在 `src/views/apply/components/`。

历次检查、浏览器预览及验证范围见 [前端规范第 9 节](../docs/architecture/frontend-development.md#9-验证记录与维护)。
