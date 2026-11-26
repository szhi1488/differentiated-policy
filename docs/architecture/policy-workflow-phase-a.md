# 政策审核分流依据（P01–P05）

> 本文保留当时的阶段设计与验收记录。现行流程在首提或退回重提时按品牌、系列预设冻结负责人，投递状态与飞书回执只保存在后台。运行与联调请参阅[现行审核设置及后台投递记录](../api/policy-review-settings.md)。


2026-11-23，A 阶段交付零售、百亿逐段执行名单的比较与中文摘要，当时的提交入口沿用采购审核。下表记录该阶段结束时的实现与接入计划，文中单号、金额和时间来自隔离样例。现行流程汇总各段实际参与叠加的政策，所有参与单整单所选的具体商品完全相同才进入运营初审。[现行分流规则](policy-workflow-feishu-technical-design.md#02-逐段识别叠加与替换)

## P01 入口与后续接入位置

| 业务入口 | 当前源码和能力 | 后续接入位置 |
| --- | --- | --- |
| 首提、驳回重提 | `POST /api/policies/{id}/submit` 校验归属、版本、商品和平台，在同一事务确认选单，写主表、本轮快照及商品明细。[提交服务](../../backend/src/main/java/com/company/policy/service/impl/PolicyApplyServiceImpl.java) | P12 在形成已确认快照后、事务提交前取整单分流结果，冻结本轮名单和触发段，同时登记群事件与当前节点每人的待办。 |
| 采购终审 | `POST /api/admin/policies/{id}/review` 校验本企业有效管理员、自审限制及双版本。通过时锁定范围、核对剩余时段，条件更新主表和当轮审核日志，并处理整批延期。[审核服务](../../backend/src/main/java/com/company/policy/service/impl/PolicyReviewServiceImpl.java) | P11 同时约束详情、列表的 `reviewable` 和审核写入口。新轮按节点、成员和当前资格核验。P13–P15 在事务内落实同节点第一个有效决定，保存实际签署人，结办其余人的任务。 |
| 逐段选单与实际执行 | `submission-preview` 构造候选范围；正式提交校验选择。已批准的时点和时间轴按快照回放。[选单校验](../../backend/src/main/java/com/company/policy/support/policy/PolicyConfirmation.java) · [执行规则](../../backend/src/main/java/com/company/policy/support/policy/PolicyApplicabilityEvaluator.java) | A 阶段共用执行规则和截止调整，以同一时点比较原、拟名单。后续审核页与消息取同一份本轮证据。 |
| 后台候选、统一审核名单 | `GET /api/admin/users` 支持本企业姓名／部门、角色、状态筛选，详情具备本地用户 ID 和 openId。[用户服务](../../backend/src/main/java/com/company/policy/service/impl/UserManagementServiceImpl.java) | P08 按稳定 ID 保存两份设置及版本，采购限有效 ADMIN。P09 首提或重提再核验并冻结，产品运营可以有独立的办理权限。 |
| 申请人平台分工 | 管理员通过 `/api/admin/users/platform-assignments` 和 `/{id}/platforms` 查询或变更，申请人从 `GET /api/policies/platforms` 读取。授权维度为本企业、渠道、平台。[分工 Mapper](../../backend/src/main/java/com/company/policy/mapper/UserPlatformMapper.java) | 分工继续决定申报和时间调整资格。新审核设置独立维护。移交通知在 P29 处理。 |
| 飞书登录及办理链接 | 客户端走 `/api/auth/feishu/login`，浏览器走 `/api/auth/feishu/browser/authorize` 和 `/api/auth/feishu/browser/login`，成功后建立 ERP 会话。[认证控制器](../../backend/src/main/java/com/company/policy/controller/AuthController.java) | P22 登录后按同源目标恢复单号、轮次与节点，重新鉴权。P23–P26 再接群回执及每名成员的飞书待办。 |

### P06 请求确认的逻辑范围

| 拟持久化内容 | 原因及对已有数据、代码的影响 |
| --- | --- |
| 本企业两组默认审核人及配置版本 | 现有账号和平台分工可提供真实身份，缺少按角色组保存多人审核设置的依据。配置修改影响后续提交轮次。 |
| 按申请和轮次冻结的分流依据、阶段、成员及改派记录 | 需要还原提交时的配置、前后名单、触发时段，以及每次人员增减。新提交和原单重提写入新轮。保留原快照、单号、附件引用、选单、原审核结果及延期关联。 |
| 每个节点唯一生效的决定、实际签署人及其余成员的结办状态 | 现有采购审核日志记录整轮结果。新增初审与终审并发约束，避免两人同时同意或退回时产生两次迁移。办理时须核对本轮的有效节点和签名。 |
| 群通知、逐人待办的登记、回执与重试依据 | 一条群事件与多条个人任务分别核对，避免事务回滚后发消息或重试时重复建单。需支持改派、同组其他人结办和投递结果未知后的恢复。旧历史不自动补发。 |

2026-11-23，用户明确同意按上述 P01 范围改库并继续 B 阶段。P06–P07 据此交付审核工作流七表及当时的按轮切换标记。现行空库基线已包含工作流结构，由用户在 Navicat 建表和核对结果。[B 阶段交付记录](policy-workflow-phase-b.md) · [空库建表步骤](../sql/database-design.md#1-在-navicat-建表)

## P02–P05 当时的计算与验收

服务入口是 [PolicyCoverageService.workflowRoute](../../backend/src/main/java/com/company/policy/service/PolicyCoverageService.java)。输入为已鉴权的申请、由服务端核验过选择的本轮快照、北京时间提交时点。输出包含整单是否需要初审、触发段数、不同变化的段数、逐段前后与加入／退出／保留名单、独立列出的截止变更及中文摘要。独立缩短不会产生补贴段。结果适合在 P12 的提交锁内按轮冻结。

| 验收项 | 预期行为 |
| --- | --- |
| P02 名单分类 | 以政策 ID 和提交轮次作集合差。无→B 为普通新增，A→A 为沿用，A→A＋B 为叠加，A→B 为替换，A＋B→A 为取消叠加，A＋C→B＋C 单列保留的 C。 |
| P03 零售 | 平台和具体商品独立回放，本轮选择遵守完整场景集合、核销类型的同组互斥。在历史决定起始秒及截止的下一秒重新比较。只变一个颜色就只在该颜色出触发段。未采用的候选不进入退出名单。 |
| P04 百亿 | 按平台、机型版本、RAM、ROM 回放配置决定，保留各单实际申报商品和数量。同配置不同颜色可触发。此前同段二选一已排除的政策仍受当段原决议约束。 |
| P05 整单及时间调整 | 任一范围触发，整单进入初审，摘要只突出触发段。独立延期和随单延期均从原实际截止次秒计算，按渠道一次纳入同批全部拟执行段。本单尚未开始时，两张延期也能相互构成叠加。纯截止变动另列目标原单及新旧截止。 |

### 一张申请只有京东发生变化

下例保留 A 阶段原始摘要，供核对当时的验收记录。本例只有两张政策参与京东段的叠加，两单的整单范围都是同一件浮光白商品，因此按现行规则也进入初审。淘宝段只采用旧单，选择结果仍可在详情中核对。[现行审核规则](../business/status-machine.md#状态迁移)

隔离样例在京东、淘宝申报同一浮光白商品。两平台均有旧单 ZC-000096，按销售核销，国补固定 20 元/台，上限 30 元。本单 ZC-000128 申报地补，按到手价的 5%，指定数量为 0。申请人勾选京东两单叠加，淘宝只选旧单。

> 有 1 段执行安排变化，需产品运营初审。
>
> 京东零售，Reno17 Pro 12GB+256GB 浮光白，2026-12-01 00:00:00 至 2026-12-05 23:59:59，保留ZC-000096（国补，销售维度，20 元/台，上限 30 元），新增叠加ZC-000128（地补，销售维度，按到手价的 5%，数量 0 台）。

完整明细另保留淘宝沿用原单的依据。样例及异常快照验收见[整单服务测试](../../backend/src/test/java/com/company/policy/service/impl/PolicyWorkflowRouteTest.java)，同批延期和独立时间调整见[隔离集成测试](../../backend/src/test/java/com/company/policy/controller/GoodsSyncIntegrationTest.java)。

### 本轮实际验证

- 在 `backend/` 执行 Maven `verify`，共 269 项，267 项通过，0 失败，0 错误。两项外部验证按配置跳过，分别为真实 MySQL 登录和吉客云双品牌实查。Spring Boot 打包和 Spotless 检查通过。
- H2 用例从本轮已确认快照计算整单结果，覆盖独立缩短、跨渠道随单缩短、从实际截止次秒起的延期、同批两份延期先于主单相交，以及延期来源损坏后要求重新核对。
- 上面的京东摘要由后端服务针对固定样例生成，并与本文逐行核对。目标 MySQL 与真实飞书群、个人待办的联调按后续阶段执行。
