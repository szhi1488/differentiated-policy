# 前端开发入口

本目录采用 Vue 3、Vite、JavaScript、Composition API、Vue Router 和 Element Plus。具体版本以 package-lock.json 为准。

## 阅读顺序

1. [根目录 AGENTS.md](../AGENTS.md)：唯一项目级 Agent 指令入口。
2. [文档总览](../docs/README.md)、[AI 辅助全栈开发规范](../docs/architecture/ai-development.md)、[整体架构与实现进度](../docs/architecture/system-overview.md)。
3. [前端架构与开发规范](../docs/architecture/frontend-development.md)：目录职责、组件数据流、接口适配、请求/路由/会话、样式、整改清单和验收。
4. 当前模块的业务与 API 文档，例如[动态表单](../docs/business/perspective-rules.md)、[管理员工作台](../docs/architecture/admin-workspace.md)、[飞书鉴权](../docs/architecture/feishu-auth-flow.md)。
5. [前端工程检查与验收](../docs/architecture/frontend-harness.md)。

此文件仅提供阅读与运行入口，不复制维护另一套 Agent 规则。工作台布局、申请表单区块和统一会话已接入。管理员在「商品 → 源商品库」按品牌导入并查询已落库原文。申请表单已接入在售目录、草稿保存、回填及提交。「我的提交」可查本人列表、详情和历轮审核，并打开草稿或已驳回申请继续编辑。附件文件仅暂存。

## 常用命令

在本目录运行，Node.js 要求以 package.json 为准：

```powershell
npm.cmd run dev
npm.cmd run check
# 仅在需要时修复格式，并检查差异。
npm.cmd run format
```

check 依次执行 ESLint、Prettier、Vitest 和生产构建。布局变更另按规范检查桌面、平板和窄屏；测试通过不代替真实授权或业务联调。环境配置、依赖安装和项目根目录启动脚本见[项目 README](../README.md)。

检查生产样式时，先运行 `npm.cmd run build`，再用 `npm.cmd run preview:qa -- APPLICANT`、`ADMIN` 或 `GUEST` 启动对应的本地预览。端口与验收项见[前端工程检查与验收](../docs/architecture/frontend-harness.md#生产构建的本地浏览器验收)。

## 测试目录

- `tests/unit/` 存放 API、工具函数及独立状态逻辑的单元测试。
- `tests/components/` 存放独立 Vue 组件的渲染和交互测试。
- `tests/integration/` 存放应用登录、申请工作台、路由和查询状态的联动测试。

Vitest 只收集 `tests/**/*.test.js`，共用初始化位于 [tests/setup.js](tests/setup.js)。申请表单路由入口位于 [PolicyApplicationPage.vue](src/views/apply/PolicyApplicationPage.vue)，表单区块放在 `src/views/apply/components/`。

历次检查、浏览器预览及验证范围见 [前端规范第 9 节](../docs/architecture/frontend-development.md#9-验证记录与维护)。
