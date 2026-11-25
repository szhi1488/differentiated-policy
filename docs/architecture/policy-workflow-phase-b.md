# 政策审核后端基础（P06–P11）

2026-11-23，用户确认按 P01 范围开展数据库结构设计。提交、重提与采购审核沿用原 HTTP 接口，管理端新增审核名单设置接口。当前每个提交轮次均冻结分流依据、审核节点及指派。目标 MySQL 操作由用户在 Navicat 完成。

| 编号 | 已实现的行为 | 核对入口 |
| --- | --- | --- |
| P06 | 新增七表，保存两组全局设置、本轮阶段与分流依据、指派、节点决定、改派及通知。按本轮、本组唯一记录实际签署人，群事件和个人指派各自去重。 | [字段、外键和锁顺序](../sql/database-design.md#已有库升级政策审核工作流) |
| P07 | 已有十五表结构增量升级，逐行核对原有政策、审核快照、附件引用和商品明细。空库使用同内容的两份基线之一。 | [增量升级](../sql/upgrade_policy_review_workflow.sql) · [升级后核对](../sql/verify_policy_review_workflow.sql) |
| P08 | 有效管理员可分页查候选，以本地 ID 保存两份多人名单，按版本防止覆盖他人修改。采购只允许本企业有效管理员。 | [三个管理接口](../api/policy-review-settings.md) |
| P09 | 首提和重提在事务内锁名单和相关账号，按本轮分流筛选人员，排除自审并为两节点保留不同签署人。将配置版本、姓名、部门、前后名单及路由理由冻结。缺配置或缺合格人选时，本单保持可编辑。 | [提交事务](../../backend/src/main/java/com/company/policy/service/impl/PolicyApplyServiceImpl.java) · [本轮冻结](../../backend/src/main/java/com/company/policy/service/impl/PolicyReviewWorkflowImpl.java) |
| P10 | B 阶段在提交事务内登记一条群事件和首节点每名成员的一条待办，回滚时一并撤销。终审成功后分别记录实际签署人及同组其他人的结办原因。E 阶段沿用逐事件、逐指派去重，调整了初审发卡和群通知的时点。 | [现行通知登记](../../backend/src/main/java/com/company/policy/service/impl/PolicyReviewNotificationsImpl.java) · [各节点收件人与回执](policy-workflow-phase-e.md#各节点的收件人与回执) |
| P11 | 同一权限查询用于采购列表、详情及写入复核。每一轮均核对阶段、实际成员、当前岗位及双版本。通过与驳回均受保护；同组并发只有一个决定能随整单提交。 | [采购接口](../api/admin-api.md#审核) · [办理条件](../../backend/src/main/java/com/company/policy/mapper/PolicyPurchaseReviewMapper.java) |

## 切换与运行边界

1. 目标库在 Navicat 核对工作流结构，部署配套服务，并在「政策审核设置」保存运营和采购名单。
2. 首提和重提均在事务内冻结本轮指派、配置版本和依据。缺节点或有效指派时拒绝办理。随单改期使用本轮采购终审结果，每个目标分别在原单登记一次调整。
3. 目标环境接收提交前须部署 [C 阶段审核 API](policy-workflow-phase-c.md) 和 [D 阶段工作台](policy-workflow-phase-d.md)。E 阶段已接入飞书投递，自动发送由独立开关 `POLICY_REVIEW_DELIVERY_ENABLED` 控制，默认关闭。需初审时，机器人向每名本轮初审人私发一张卡，初审通过或直达采购时逐人私发采购办理卡与终审待办，初审退回或采购驳回时私发原申请人。采购批准后向 `FEISHU_POLICY_CHAT_ID` 指定的群发一条政策公告。[E 阶段实现与隔离验收](policy-workflow-phase-e.md)

## 隔离验收

2026-11-23 的 B 阶段固定样例核对了事务登记：普通单分给两名采购，提交后有一条群事件与两条采购个人待办。新增叠加的样例冻结两名产品运营和两名采购，群事件记录办理与知悉对象，仅为两名产品运营登记建待办动作。重复登记保留首份内容。修改默认名单后，已冻结成员保持原样，驳回后重提读取新版本。

已覆盖缺配置、无人合格、同名、跨企业、旧版本并发保存、提交事务回滚、新轮异常缺节点、越级终审、同组竞办、同组其他人结办。[提交与登记测试](../../backend/src/test/java/com/company/policy/service/PolicyReviewSubmissionIntegrationTest.java) · [设置接口测试](../../backend/src/test/java/com/company/policy/controller/PolicyReviewSettingsControllerTest.java) · [采购办理测试](../../backend/src/test/java/com/company/policy/controller/PolicyPurchaseReviewTest.java)

2026-11-23 后端 `mvn verify` 构建及格式检查通过。293 项测试中 291 项通过，2 项外部环境测试跳过。两份空库基线逐字节相同，均定义 22 张表，增量脚本内的七张新表与空库基线定义一致。目标库升级结果及真实飞书投递仍待对应环境验证。
