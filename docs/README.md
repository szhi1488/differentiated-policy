# 差异化政策管理文档库

更新日期：2026-11-10。顶层协作指令见 [AGENTS.md](../AGENTS.md)，环境配置和启动步骤见 [项目 README](../README.md)。

## 目录与阅读入口

```text
docs/
├── README.md
├── architecture/
│   ├── system-overview.md
│   └── feishu-auth-flow.md
├── business/
│   ├── perspective-rules.md
│   ├── status-machine.md
│   └── override-engine.md
├── sql/
│   ├── schema.sql
│   ├── database-design.md
│   └── V1__create_policy_tables.sql
└── api/
    ├── policy-api.md
    └── admin-api.md
```

统一使用 `docs/sql/`，不混用 `docs/database/`；数据字典文件名为 `database-design.md`，修正模板重复的 `.md.md`。沿用现有四表命名，不引入 `t_user`、`t_policy_master` 等另一套表名。

| 文档 | 内容 | 状态 |
| --- | --- | --- |
| [整体架构](architecture/system-overview.md) | 技术栈、分层、目录、进度 | 对照现有源码整理 |
| [飞书鉴权流程](architecture/feishu-auth-flow.md) | JS-SDK、免登、会话、安全设置 | 已实现，人工验证边界另列 |
| [动态表单规约](business/perspective-rules.md) | 渠道、平台、明细、补贴与空值 | 需求及设计约定；持久化待接入 |
| [状态机](business/status-machine.md) | 草稿、提交、审核、驳回重提及历史 | 待实现设计 |
| [覆盖检测与查询](business/override-engine.md) | 覆盖提示、最新有效政策 SQL | 待确认口径的设计草案 |
| [建表入口](sql/schema.sql) | MySQL 8.0.16+ 完整四表 DDL | 与 V1 内容一致；本次未执行 |
| [数据字典](sql/database-design.md) | 字段、枚举、约束、迁移边界 | 现有设计；实际后三表约束待核对 |
| [申请人接口](api/policy-api.md) | 保存、提交、查询、重提 | 待实现契约草案 |
| [管理员接口](api/admin-api.md) | 筛选、详情、覆盖预览、审核 | 待实现契约草案 |

## 事实、约定与草案

- 已明确需求：来自业务文档及用户明确要求。
- 工程约定：当前设计的命名、类型、分层及数据表达，不等于全部业务计算口径已确认。
- 设计草案：用于下一阶段实现与评审；API 路径、示例、状态迁移和 SQL 不表示线上能力已存在。
- 已实现：须对应源码；历史自动化结果和真实操作分别记录，文档整理不冒充新的实测。

当前已实现飞书 SDK、免登、用户落库、会话和申请人表单交互。政策保存/提交、我的提交查询、审核、覆盖计算、机型主数据和附件上传尚未接入。本次仅维护文档和 SQL 文件，不执行建表或业务写入。

## 相关实现前仍需确认

1. 机型库字段、稳定主键与读取方式；数量空值/零值和空颜色含义。
2. 固定补贴上限单位和范围，比例范围、精度、基数来源与舍入规则。
3. 覆盖匹配维度、时间端点、“最新”的排序口径、同优先级处理、生效和局部替换时点。
4. 管理员名单、跨人/跨租户可见范围、自审规则及拒绝原因是否必填。
5. 备注、附件、链接必填要求，存储、格式、大小、数量和下载权限。
6. 实际部署域名与多实例会话方案。当前已采用客户端 `requestAccess` 免登，无需重新选择登录方式；部署时再同步飞书配置。

## 文档维护

变更表单时同步状态机、API 和数据字典；变更 DDL 同步 `schema.sql` 与 V1，并为已有库另写增量迁移。完整 SQL 仅维护在 SQL 文件，数据字典不再复制整段 DDL。迁移及接口草案不触发自动部署。
