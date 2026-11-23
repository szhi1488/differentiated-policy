# 政策审核后端基础（P06–P11）

2026-11-23，用户确认按 P01 范围开展数据库结构设计。现有提交、重提与采购审核仍使用原 HTTP 接口。管理端新增审核名单设置接口；每个提交轮次单独标记办理方式。目标 MySQL 操作由用户在 Navicat 完成。

| 编号 | 已实现的行为 | 核对入口 |
| --- | --- | --- |
| P06 | 新增七表，保存两组全局设置、本轮阶段与分流依据、指派、节点决定、改派及通知。按本轮、本组唯一记录实际签署人，群事件和个人指派各自去重。 | [字段、外键和锁顺序](../sql/database-design.md#已有库升级政策审核工作流) |
| P07 | 已有十五表增量升级，旧轮标记为 0，逐行核对原有政策、审核快照、附件引用和商品明细。空库使用同内容的两份基线之一。 | [增量升级](../sql/upgrade_policy_review_workflow.sql) · [升级后核对](../sql/verify_policy_review_workflow.sql) |
| P08 | 有效管理员可分页查候选，以本地 ID 保存两份多人名单，按版本防止覆盖他人修改。采购只允许本企业有效管理员。 | [三个管理接口](../api/policy-review-settings.md) |
| P09 | 开启新流程后，首提和重提在事务内锁名单和相关账号，按本轮分流筛选人员，排除自审并为两节点保留不同签署人。将配置版本、姓名、部门、前后名单及路由理由冻结。缺配置或缺合格人选时，本单保持可编辑。 | [提交事务](../../backend/src/main/java/com/company/policy/service/impl/PolicyApplyServiceImpl.java) · [本轮冻结](../../backend/src/main/java/com/company/policy/service/impl/PolicyReviewWorkflowImpl.java) |
| P10 | 一次提交登记一条群通知和首节点每名成员的一条待办。需初审时，群通知内区分产品运营办理与采购知悉。终审成功后，为实际签署人和同组其他人分别登记结办原因。业务回滚时连同登记撤销。 | [通知登记](../../backend/src/main/java/com/company/policy/service/impl/PolicyReviewNotificationsImpl.java) |
| P11 | 同一权限查询用于采购列表、详情及写入复核。旧待审轮沿用原采购权限。新轮需核对阶段、实际成员、当前岗位及双版本。通过与驳回均受保护；同组并发只有一个决定能随整单提交。 | [采购接口](../api/admin-api.md#审核) · [办理条件](../../backend/src/main/java/com/company/policy/mapper/PolicyPurchaseReviewMapper.java) |

## 切换与运行边界

1. 目标库先停写、备份并完成增量升级。核对通过后部署配套后端，所有实例的 `POLICY_REVIEW_WORKFLOW_ENABLED` 使用相同值。默认 `false`，此时可先维护两组人员。
2. 开关只影响接下来创建的提交轮次。开启后每次提交写入 `workflow_version=1`。已写入的轮次始终按自己的标记办理；一条新轮缺少节点或指派时，采购入口拒绝操作。随父单一并批准的延期子单复用父单终审结果。
3. B 阶段具备站内事务登记能力。产品运营办理与退回、待办列表、改派 API 已在 [C 阶段](policy-workflow-phase-c.md)交付。目标环境启用新轮前须部署配套的 [D 阶段工作台](policy-workflow-phase-d.md)。业务群由服务端 `FEISHU_POLICY_CHAT_ID` 指定，实际发送按 E 阶段实施。
4. 已产生版本 1 轮次后，关闭开关仅影响后续提交。在办新轮仍须由识别 `workflow_version` 的后端处理。切换或回退部署时须先下线只支持旧采购规则的实例。

## 隔离验收

固定样例中，两名采购处理普通单，提交后有一条群事件与两条采购个人待办。在已批准原单上新增叠加时，本轮冻结两名产品运营和两名采购，群事件包含办理与知悉对象，只有两名产品运营各有一条建待办动作。重复登记保留首份内容。修改默认名单后，已冻结成员保持原样，驳回后重提读取新版本。

已覆盖缺配置、无人合格、同名、跨企业、旧版本并发保存、提交事务回滚、新轮异常缺节点、越级终审、同组竞办、同组其他人结办、旧轮兼容。[提交与登记测试](../../backend/src/test/java/com/company/policy/service/PolicyReviewSubmissionIntegrationTest.java) · [设置接口测试](../../backend/src/test/java/com/company/policy/controller/PolicyReviewSettingsControllerTest.java) · [采购办理测试](../../backend/src/test/java/com/company/policy/controller/PolicyPurchaseReviewTest.java)

2026-11-23 后端 `mvn verify` 构建及格式检查通过。293 项测试中 291 项通过，2 项外部环境测试跳过。两份空库基线逐字节相同，均定义 22 张表，增量脚本内的七张新表与空库基线定义一致。目标库升级结果及真实飞书投递仍待对应环境验证。
