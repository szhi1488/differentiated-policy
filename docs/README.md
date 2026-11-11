# 差异化政策管理文档库

更新日期：2026-11-11。项目指令见 [AGENTS.md](../AGENTS.md)，启动和配置见 [项目 README](../README.md)。

## 当前实现

- 管理员登录后进入 `#/admin/catalog/sources`，侧栏只有「商品 → 源商品库」。页面显示两个品牌的静态样例，导入、筛选、品牌切换和分页控件暂时禁用。[管理员工作台](architecture/admin-workspace.md)
- 后端 `JackyunGoodsService` 已接入吉客云单页只读查询。已实测按 `brandName` 查询一加手机、OPPO手机，并用各自的 `lastMaxId` 续拉。同步任务、来源入库、清洗和标准目录仍待接入。[商品查询与同步方案](architecture/catalog-admin-design.md#单页查询联调)
- 飞书客户端自动免登，失败可手动重试。浏览器可扫码、刷新二维码或跳转网页授权。已在本机看到飞书官方二维码，扫码回调和会话恢复已通过模拟验证。真实手机确认后的登录待联调。[飞书登录验证记录](architecture/feishu-auth-flow.md#验证记录)

## 已确认设计与数据库状态

按用户最新说明，目标数据库已有库名，尚未建表。九表空库 SQL 已交付，由用户在 Navicat 执行。九表设计包含商品同步、标准规格、独立规格颜色及政策颜色范围。

品牌仅一加手机和 OPPO手机，来源只采集七业务字段；原始字段先入库，再自动清洗系列、型号、配置、颜色，明确规则唯一结果自动入可用标准库，缺失/歧义/冲突才人工审核。颜色不要求逐个手工建。九表不增表，来源内联候选/证据/规则版本/异常/审核及当前映射，任务分开导入和清洗阶段，日志区分 SYSTEM/USER。skuId仅续拉，品牌各自最新进度，确认结构不等于功能已实现。

## 阅读入口

| 文档 | 内容 | 状态 |
| --- | --- | --- |
| [AI 辅助全栈开发规范](architecture/ai-development.md) | [前后端文件放置](architecture/ai-development.md#2-项目文件放置)、Vue/Java 代码写法、API 兼容、SQL 事务和验收模板 | 工程规范；示例不代表功能已实现 |
| [整体架构](architecture/system-overview.md) | 技术栈、源码进度、九表设计边界 | 实现与设计分别标注 |
| [前端架构与开发规范](architecture/frontend-development.md) | 目录职责、数据流、接口适配、会话路由、样式、AI 开发验收与整改清单 | 前三阶段适用整改已实施并验证；业务接入待实施 |
| [前端工程检查与验收](architecture/frontend-harness.md) | CSS 归属、静态检查、构建产物与本地多角色预览验收 | 本地检查和预览入口已接入 |
| [前端开发入口](../frontend/README.md) | 阅读顺序、常用命令及实现边界 | 入口导航，不另建 Agent 指令 |
| [管理员展示工作台](architecture/admin-workspace.md) | 角色分流、商品导航和源商品库 | 仅保留源商品库展示页 |
| [前端组件与页面现状](architecture/system-overview.md#前端组件与页面现状) | Element Plus 替换范围、样式边界、响应式适配和验证记录 | 已完成组件替换；业务接入边界单独说明 |
| [商品后台确认方案](architecture/catalog-admin-design.md) | 原始导入、自动清洗入库、规格颜色、异常审核 | 展示页及后端单页查询已实现，同步任务和清洗待接入 |
| [自动清洗细则](business/catalog-cleaning.md) | 唯一规则门槛、颜色提取、状态、重试、人工结果保护与系统审计 | 新增确认流程细化；待实现 |
| [飞书鉴权](architecture/feishu-auth-flow.md) | 客户端 JS-SDK 免登、网页扫码与 OAuth 授权、服务端会话 | 已实现；真实手机扫码完成后的登录待联调 |
| [动态表单](business/perspective-rules.md) | 渠道、平台、补贴、颜色范围 | 同步确认版规则；业务持久化待接入 |
| [状态机](business/status-machine.md) | 草稿、提交、审核、重提、历史 | 业务接口待实现 |
| [覆盖检测](business/override-engine.md) | 稳定规格与颜色交集、未定优先级 | 候选方案，非已实现生效引擎 |
| [完整建表 SQL](sql/schema.sql) | MySQL 8.0.16+ 九表 DDL | 空库首次执行，用户自行建表 |
| [V1 同内容版本](sql/V1__create_policy_tables.sql) | 与 schema.sql 完全一致 | 二选一，不执行两遍 |
| [数据库字典](sql/database-design.md) | 九表关联、全部字段、SQL/SVC 约束边界和 Navicat 步骤 | 与 SQL 对应 |
| [商品管理接口](api/catalog-api.md) | 来源、导入清洗、规格颜色、异常审核和重试 | 应用 HTTP 接口待实现，后端只读查询服务已接入 |
| [政策接口](api/policy-api.md) | 申请保存、提交、查询及颜色范围 | 未实现契约 |
| [审核接口](api/admin-api.md) | 审核、候选影响、权限 | 未实现契约 |
| [附件接口](api/attachment-api.md) | 附件接口边界 | 实际接入以源码及对应文档为准 |

文档目录统一 architecture/、business/、sql/、api/，结构入口只用 docs/sql/schema.sql；不新建重复 Agent.md。旧十四表建议已被九表确认版替代。

## 仍需确认或联调

1. 吉客云已实测两品牌的 brandName 筛选、result.data 数组和 lastMaxId 续拉。末页结束条件、最大页大小、限流额度和包含停用商品的场景仍需联调。首期不存外部商品身份，因此采用全量内容比对，不承诺可靠 SKU 增量或完整历史批次回放。
2. 数量为 0 的业务含义、固定补贴上限范围、比例精度/范围、金额舍入与计算基数来源。
3. 时间端点、覆盖优先级、不同政策不限颜色与指定颜色交集的替换、旧政策恢复、局部生效及后续新增颜色对既有政策的适用规则。颜色范围本身已明确，不再把空颜色是否通配列为未定：必须用 color_scope 表达。
4. 管理员名单、跨人可见范围、自审及拒绝原因要求；默认不扩大租户范围。
5. 附件、备注及链接必填要求和权限、容量；实际部署域名及多实例会话。
6. 用真实手机扫码确认，核对当前角色入口及会话刷新。回归飞书内免登和网页授权，并在部署地址确定后核对重定向配置。[飞书真实联调步骤](architecture/feishu-auth-flow.md#浏览器真实联调步骤)

## 维护与验证

schema.sql/V1 同步更新；已部署旧版库另做增量迁移，不重跑完整建表。数据库 DDL 不代表业务鉴权、提交完整性、日志不可变和覆盖引擎已实现。区分用户已确认事实、工程存储约定、待确认算法和已运行验证；数据库核对见数据字典，Element Plus 组件替换及浏览器检查见整体架构。仅更新文档时不重复运行或冒认既往业务检查。
