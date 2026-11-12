# AI 辅助全栈开发规范

适用于差异化政策管理系统的功能迭代、缺陷修复和代码审查。项目级指令入口是 [AGENTS.md](../../AGENTS.md)；本文件说明如何落地。前端专项细则见[前端架构与开发规范](frontend-development.md)，业务含义以对应规则、数据字典和已确认的接口契约为准。下文代码为写法示例，不代表示例功能已经实现。

## 1. 从需求到可交付变更

1. **建立基线**：先看 `git status --short`、[文档总览](../README.md)和相关源码、测试。定位现有实现和待实现部分；保留用户及其他任务的工作区改动，不用全量格式化或目录迁移夹带无关差异。
2. **写出可验证结果**：用“谁在什么条件下做什么，成功和失败各看到什么，数据库留下什么”描述需求。说明受影响的页面、API、字段/状态、权限和检查范围。缺陷优先复现并定位出错层；改动仅涉及文案时可从简。
3. **先对齐契约再写代码**：跨层改动先确认字段映射、枚举、空值、精度、权限、写入状态和冲突行为；涉及旧客户端或旧数据时列出兼容办法。覆盖优先级、舍入、数量为 `0` 等待确认规则不自行决定。仅暂停依赖该规则的分支，其余工作继续；未就绪的功能不伪造结果。
4. **按最小可验收功能实施**：复用现有入口；每次改动应能独立说明行为与验证。需要持久化时，按数据约束、服务事务与接口、前端适配、端到端结果推进。跨层变更不能仅通过隐藏按钮、增加默认值或返回模拟成功交付。
5. **交付前回看差异**：核对改动范围和配置、旧入口、失败分支；同步相关文档、示例和实现状态。记录实际运行的命令、结果及未验证项。环境受限时写明受影响的场景，不弱化检查来换取通过。

## 2. 项目文件放置

以下是新增或修改文件时的归属表。路径以项目根目录为起点；优先修改已有文件，示例中的新文件只在对应功能实际接入时创建。单个业务使用的组件、样式和规则先留在所属模块，有明确的跨模块复用需求再提取。

### 前端：`frontend/`

| 路径（相对 `frontend/`） | 应放内容 | 现有示例 |
| --- | --- | --- |
| `index.html`、`src/main.js`、`src/App.vue` | HTML 入口、应用装配、全局基础样式及插件、应用根出口 | `src/main.js`；页面业务不放入口 |
| `src/router/` | 路由配置、角色入口和导航守卫 | `index.js` |
| `src/layouts/`、`src/layouts/components/` | 工作台导航、页面出口、跨子页状态和布局专用组件 | `ApplicantLayout.vue`、`components/PolicyBanner.vue` |
| `src/views/apply/`、`src/views/admin/` | 申请人、管理员的路由页面，源商品库放在 `views/admin/catalog/` | `PolicyApplicationPage.vue`、`catalog/CatalogSourcesPage.vue` |
| `src/views/<业务>/components/`、`src/components/` | 前者放本业务组件，后者放跨业务通用组件 | `views/apply/components/PolicyDetailsTable.vue`、`components/AppIcon.vue` |
| `src/api/` | 业务 API 调用，通过 `request.js` 统一处理通信 | `feishu.js`、`attachments.js` |
| `src/composables/` | 表单动作、列表分页、会话等响应式状态和交互 | `usePolicyForm.js`、`useAuthSession.js` |
| `src/constants/`、`src/utils/` | 前者放固定枚举与显示映射；后者放纯规则、DTO 转换及独立命名的 SDK 适配 | `constants/policy.js`、`utils/policy-adapter.js` |
| `src/assets/styles/`、页面和布局目录 | 全局基础和品牌样式放前者；页面/布局样式就近维护，单组件样式可写在自己的 `<style scoped>` | `base.css`、`views/apply/policy-form.css` |
| `src/mocks/`、`src/mocks/fixtures/` | 显式演示入口及样例；仅测试使用的样例留在 `tests/` | `mocks/admin-data.js`、`mocks/fixtures/admin.js` |
| `tests/unit/`、`tests/components/`、`tests/integration/` | 依次放工具/API/状态单测、组件交互、路由或页面联动测试；文件名为 `*.test.js` | `tests/setup.js` 保存共用测试初始化 |
| 工程根目录、`scripts/` | `package.json` 和锁文件、Vite/ESLint/Prettier 配置在工程根目录；前端专用验收脚本放 `scripts/` | `vite.config.js`、`scripts/preview-qa.js` |

### 后端：`backend/`

Java 生产代码根包为 `backend/src/main/java/com/company/policy/`。包名与目录一致；测试在 `backend/src/test/java/com/company/policy/` 下按被测职责归类。

| 路径（相对 Java 根包，另注明除外） | 应放内容 | 现有示例 |
| --- | --- | --- |
| 根包、`controller/` | 根包放应用启动类；HTTP 路径、参数绑定、响应及 Knife4j 注解在 Controller | `PolicyApplication.java`、`controller/AuthController.java` |
| `dto/`、`vo/` | `dto/` 放请求；`vo/` 放返回给前端的视图模型 | `dto/FeishuLoginRequest.java`、`vo/CurrentUserResponse.java` |
| `service/`、`service/impl/` | 接口放 `service/`，权限、业务规则、事务和外部调用等实现放 `service/impl/` | `UserInfoService.java`、`impl/UserInfoServiceImpl.java` |
| `entity/`、`mapper/` | 表映射对象放 `entity/`；读写 SQL 及持久化接口放 `mapper/`，沿用现有注解 Mapper | `entity/UserInfo.java`、`mapper/UserInfoMapper.java` |
| `common/config/`、`common/interceptor/` | 配置属性、组件装配，以及通用登录和来源校验；单业务规则留在该业务服务 | `FeishuProperties.java`、`LoginInterceptor.java` |
| `common/api/`、`common/exception/` | 公共错误响应及统一异常处理 | `ApiError.java`、`ApiException.java`、`GlobalExceptionHandler.java` |
| `backend/src/main/resources/` | 应用配置和运行时资源；真实凭证使用环境变量或被忽略的 `backend/.env.local.properties` | `application.yml`，配置样例在 `backend/.env.example` |
| `backend/src/test/java/`、`backend/src/test/resources/` | 接口、服务与数据库测试；隔离的测试配置、测试建表 SQL 和样例 | `AuthControllerTest.java`、`user-info-schema.sql` |
| `backend/pom.xml` | 后端依赖、编译、测试、打包和代码格式配置 | Maven 与 Spotless 配置 |

### 根目录、文档与 SQL

- `docs/architecture/` 放架构及工程规范，`docs/business/` 放业务规则，`docs/api/` 放接口契约，`docs/sql/` 放字段字典、建表及迁移交付 SQL。目标库 SQL 按项目约定交给用户执行，不放进应用资源目录作为自动建表入口；H2 等测试专用 SQL 放在后端测试资源。
- 根目录 `scripts/` 放项目级启动脚本，例如 `start-backend.ps1`；根目录的 `README.md`、`AGENTS.md` 和 `.editorconfig` 分别管理启动入口、项目协作和编辑格式。
- `frontend/dist/`、`frontend/node_modules/`、`backend/target/`、`.local-cache/` 是生成产物、依赖或缓存。修改从源码和工程配置入手，不直接维护这些目录里的内容。

以接入“保存已有草稿”为例：在 `frontend/src/views/apply/PolicyApplicationPage.vue` 绑定保存交互，保存状态放 `frontend/src/composables/usePolicyForm.js`，表单到 DTO 的转换扩展 `frontend/src/utils/policy-adapter.js`，政策 API 接入时可新增 `frontend/src/api/policies.js`。后端在 `controller/PolicyController.java`、`dto/SavePolicyDraftRequest.java`、`vo/PolicyDraftResponse.java`、`service/PolicyService.java`、`service/impl/PolicyServiceImpl.java` 和 `mapper/PolicyMasterMapper.java` 各自实现对应职责；用到的表实体放 `entity/`。测试放相应的前端 `tests/` 与后端 `src/test/`，契约变更更新 [政策接口文档](../api/policy-api.md)。如果沿用现有表结构，则无需修改建表 SQL。

## 3. 代码书写约定

| 项目 | 约定 | 示例 |
| --- | --- | --- |
| 格式 | UTF-8、LF、文件末尾换行，遵循 `.editorconfig`；Java 由 Spotless（AOSP）排版，前端由 Prettier 和 ESLint 检查 | 只格式化本次改动涉及的代码 |
| 命名 | 类/组件用 PascalCase，变量/方法用 camelCase，常量用 UPPER_SNAKE_CASE，SQL 用 snake_case | `PolicyApplicationPage.vue`、`saveDraft`、`recordVersion`、`record_version` |
| 语义 | 写操作用明确动词；布尔值表达判断；读方法名应说明结果及重要范围 | `findOwnedPolicy`、`hasSelectedPlatforms`；避免 `doHandle`、`data2` |
| 函数 | 每个方法完成一个能命名的动作；校验失败可提前返回/抛出；先算出校验或映射结果再写状态 | 将“校验提交条件”“构建快照”“写入审计”拆成清楚的步骤 |
| 注释 | 用中文说明原因、不变量、边界和兼容性；公共契约说明输入、空值、输出、副作用及失败条件中容易误解的部分 | “仅在重提成功后清空主表最近审核意见，历史日志保留” |
| 复用 | 同一条业务规则在同一层只有一个主要入口；跨前后端重复的校验用契约和测试对齐，后端负责最终裁决 | 前端提示颜色无效，后端重新校验目录状态和归属 |

- 优先写可直接读懂的条件、循环和映射；发现第二个稳定使用点再提取通用组件或工具。职责、依赖或测试边界变化才拆文件，不按行数机械拆分，不预建空接口或空目录。
- 靠近业务使用点定义枚举与字段映射；不要复制一套状态字符串或把展示中文作为判断条件。只在确认字段确有默认语义时给默认值，分别处理 `null`、`''`、`0` 和 `false`。
- 用明确的错误路径替代空 `catch`、返回空对象、隐式兜底。注释中的 `TODO` 若阻塞交付，写明未满足的条件和当前功能状态；在对应业务文档跟踪未定规则。
- 新依赖先说明现有工具为何不足、使用范围和维护/构建影响；前端同步更新锁文件，后端沿用 Maven 依赖管理。没有任务依据时不迁移框架、升级整套依赖或引入第二套架构。

## 4. Vue 与 JavaScript

- 页面负责组织区块和绑定状态；`composables` 处理异步、用户动作和状态；`utils` 存纯规则和适配；`api` 声明路径、方法及请求参数，经 `api/request.js` 发请求。公共层不依赖页面或布局。
- SFC 使用 `<script setup>`；用 `computed` 表达由已有状态推导的值；用 `watch` 响应路由或输入变化造成的副作用，避免两个 watcher 互相同步状态。异步查询应清理取消逻辑，或显式拒绝旧结果。
- 子组件通过 props 读取数据，emit 携带稳定行标识与修改值。禁止用数组下标标识可删除、重排的明细。切换规格时由状态所有者集中处理旧颜色；清除内容先等待确认，取消保留原值。
- JSDoc 用在跨层模型、复杂转换和空值语义上；表单模型到请求 DTO 使用白名单映射。ID 保持字符串、金额/比例保持十进制字符串、时间遵守政策契约；草稿空值和明确业务选项分别表示。
- 列表显示加载中、加载失败、空数据和结果；写操作区分提交中、已成功、失败及结果未知。409 时保留编辑内容并提示核对，不用旧数据重发覆盖。UI 校验和禁用按钮不能代替服务端验证。
- 使用 Element Plus 和现有主题/断点；覆盖样式限于业务根类，路由直达即可加载所需样式。仍须按[前端验收](frontend-harness.md)检查受影响角色、视口和导航路径。

组件边界示例：子组件只发送意图，父级或 composable 决定是否更新并清理依赖字段。`rowKey` 在行创建时确定，不随排序变化。

```vue
<script setup>
const props = defineProps({ rowKey: { type: String, required: true } })
const emit = defineEmits(['change-variant'])

function changeVariant(variantId) {
  emit('change-variant', { rowKey: props.rowKey, variantId })
}
</script>
```

## 5. Java、Spring Boot 与 API

| 层 | 写法 | 审查要点 |
| --- | --- | --- |
| `controller` | 读取 HTTP 路径/参数/DTO 与会话上下文，使用 `@Valid` 处理请求形状；调用 Service，返回响应 VO 或正确状态码 | 不直接调用 Mapper，不在这里组装业务事务 |
| `dto` / `vo` | 按动作定义请求必填项和响应字段；简单不可变模型可沿用现有 `record` 写法 | 不暴露 Entity；避免把 DTO 整体拷进 Entity；身份、状态、快照、版本变更均由服务端控制 |
| `service` / `service.impl` | 通过接口和构造器注入；实现类用 `@Service`，业务写入入口在实现类标 `@Transactional` | 权限、业务校验、状态迁移、事务、历史写入放在同一操作流程；不通过同类内部调用依赖事务代理生效 |
| `mapper` / `entity` | Mapper 执行明确的查询或条件更新，Entity 表达持久化结构 | 绑定参数，不拼接用户输入；显式限制数据范围，排序字段从白名单映射；写操作核对影响行数 |
| `common.config` / `common.interceptor` | 集中配置与通用登录、来源校验 | 拦截器不替代 Service 对记录归属、租户及当前管理员权限的校验 |

- **分层校验**：Bean Validation 检查格式和静态约束；Service 检查渠道/平台、补贴互斥、草稿与提交完整度、规格颜色归属和记录状态；SQL 约束承担适合数据库保证的唯一性、关联和取值边界。草稿可缺字段，已填字段仍要合法。
- **统一错误**：沿用 `ApiException`、`GlobalExceptionHandler` 和 `{ "code": "稳定代码", "message": "中文提示" }`。按实际契约返回 400（参数）、401（无有效会话）、403（无权）、404（找不到）、409（状态/版本冲突）等；不要以 HTTP 200 包装业务失败。新增错误由 code 驱动客户端分支，中文文案可独立调整；异常消息、上游响应和凭证不外显。
- **契约与兼容**：同步对应 `docs/api/` 文档和 Knife4j 注解，列明分页、枚举、空值、条件必填、版本和错误。新增必填项、删除/改名、改字段类型或枚举是兼容性变更；如旧前端与新后端可能并行，交代发布顺序、过渡读写和旧数据处理。只在实现和验证后更新实现状态。
- **数值与时间**：从十进制字符串构建 `BigDecimal`，业务数值比较用 `compareTo`，不经过 `double` 或 `float`，不用数据库截断当校验。政策时间按北京时间精确到秒并遵循对应接口格式；认证 `Instant` 独立处理。涉及当前时刻的业务判断应能注入固定时钟测试。
- **会话与配置**：请求身份来自服务端会话；写请求保留可信来源和自定义请求头约束。真实配置走环境变量或忽略的本地文件；日志只保留排查所需的业务记录 ID、阶段、错误码或计数，避免输出凭证、Cookie、授权参数和完整上游报文。变更飞书登录须回归客户端免登和浏览器网页授权两条入口。

DTO 写法示例：更新请求的版本使用 `Integer`，配合 `@NotNull` 区分未传和合法的初始版本 `0`。下面只展示版本字段；真实保存请求还包含[政策接口](../api/policy-api.md)约定的表单字段。版本与当前记录是否匹配，由 Service 和数据库条件更新判断。

```java
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record DraftVersionExample(@NotNull @PositiveOrZero Integer recordVersion) {}
```

## 6. MySQL、事务与外部调用

- 查询按租户及权限过滤；含 `is_deleted` 的业务表正常查询过滤已删除行，明细/日志通过主表鉴权。用户身份核验需能读到停用记录并拒绝登录。分页使用稳定排序（必要时用主键打破并列），关联数据用批量查询，避免逐行发起查询。
- 状态更新将租户、记录 ID、允许状态、版本与未删除条件放进实际写入；本人编辑加本人归属，审核加提交轮次。不能只在 `SELECT` 检查一次后用主键更新。更新成功后再写关联记录和审计；主表、明细、快照及日志任一步失败都回滚，不能捕获失败后提交部分结果。
- 第一开发阶段允许重新设计项目内所有表。每次重设计前说明拟调整范围、原因、数据和代码影响，并重新征得用户明确确认。依据实际读写路径设计主键、关联、约束和索引，并准备执行计划、数据完整性及并发行为所需的验证 SQL。[AGENTS.md 第 13 条](../../AGENTS.md#3-核心技术规则与边界)
- 查库、变更和验证 SQL 以 `docs/sql/` 下的 `.sql` 文件交付，均由用户在 Navicat 执行。SQL 变更同步空库基线与字段字典。已有表的库按确认方案提供增量迁移或重建 SQL，说明前置数据条件、验证查询、需保留的数据及恢复办法。
- 外部请求设超时并分类处理技术失败和业务异常；网络调用不占用本地写事务。只重试契约允许且不会造成重复副作用的步骤；POST 结果未知时按既有幂等契约或查询核对，不能默认重发。同步任务应在对应原始数据可靠落库后再确认该页进度。
- 吉客云接入只读，原始七字段与本地清洗结果分开；任务的导入和清洗状态分别推进。技术错误可重试，缺失、歧义、冲突按业务规则进入审核；执行租约、版本和审计不得省略。详细规则见[自动清洗细则](../business/catalog-cleaning.md)。

以“本人保存已有草稿”为例，下面仅演示并发保护的 `WHERE` 写法（MyBatis 参数）。实际保存还需更新允许的业务字段，在同一事务内维护明细和日志。权限与版本先在服务层校验；随后条件更新未命中时按[状态机](../business/status-machine.md)处理冲突并回滚。

```sql
UPDATE policy_master
SET record_version = record_version + 1
WHERE id = #{policyId}
  AND tenant_key = #{tenantKey}
  AND applicant_id = #{applicantId}
  AND is_deleted = 0
  AND status IN ('DRAFT', 'REJECTED')
  AND record_version = #{recordVersion};
```

## 7. 测试与交付标准

测试围绕可见行为和数据不变量：改坏关键分支时测试应失败。纯规则用单元测试；组件检查用户操作和结果；接口测试请求/响应与权限；事务、条件更新、MySQL 专属约束用相应集成测试。避免只断言私有方法调用顺序、整页快照或原样复述实现。局部文案和低影响样式修复按影响范围人工核对即可；布局和组件仍按前端专项要求验收。

| 变更类型 | 优先验证的场景 |
| --- | --- |
| 政策表单或 DTO | 草稿允许缺项，正式提交拒绝缺项；固定/比例互斥；不限颜色与未选范围有区别；切规格清颜色；共享补贴不复制数量；取消清理保留原值 |
| 状态机和持久化 | 本人/管理员的合法操作；越权或跨租户拒绝；旧版本冲突；重复提交或审核不产生第二条成功日志；明细或审计失败后主表不变 |
| 查询和工作台 | 筛选、分页、加载/空态/错误；旧响应不覆盖新条件；受影响角色直达和路由往返；模拟会话和真实接口结果分开记录 |
| 登录与来源校验 | 未登录、停用、会话失效；两个登录入口；授权取消、state 过期或重放、刷新恢复；合成联调与真实飞书授权分别记录 |
| 商品与目录 | 原文先存；明确商品自动映射；歧义不误建；技术错误可重试；重复运行不覆盖人工结果、不复活停用规格；系统与人工审计可区分 |
| DDL、金额与时间 | 空库执行及对应约束；已有表时的迁移或重建方案；精度、空值、合法边界和北京时间到秒的往返 |

- 前端代码：在 `frontend/` 执行 `npm.cmd run check`；页面或布局另做[生产构建预览](frontend-harness.md#生产构建的本地浏览器验收)。
- 后端代码：在 `backend/` 执行 `mvn.cmd '-Dmaven.repo.local=.local-cache/maven-repository' verify`。需直接操作 MySQL 的验证，由用户在隔离测试库执行交付的 SQL 文件。后端检查配置见[项目 README](../../README.md)。
- 纯文档：检查链接、术语、实现状态、示例与 diff；不新增业务测试，也不把历史测试写成本次结果。
- 交付说明应包含：改了什么、对应文件/接口、实际运行及结果、未验证或待确认事项。涉及 API、DDL、配置变更时补上兼容要求、所需执行动作和顺序。只提交页面、Mock 或设计时明确该交付范围。

## 8. 给 AI 提交迭代需求的模板

```text
目标：本轮要让哪个角色，在什么场景下完成什么操作？
规则：哪些行为已确认？缺字段、无权限、冲突或失败时应怎样处理？
范围：涉及哪些页面、接口、数据和文档？哪些已有流程必须继续可用？
验收：给出一个成功例和关键失败例；说清页面反馈和最终数据状态。
环境：是否只做本地实现？哪些验证已有授权，哪些要等实际联调？
```

示例：接入“保存已有草稿”。本人保存 `DRAFT` 或 `REJECTED` 时允许表单不完整，成功后刷新记录版本并保留当前编辑结果。对同一记录发出两次携带相同版本的保存，只允许一次写入成功；另一方收到 409 并保留本地编辑，数据库只有一次变更和一条对应成功日志。验收还需覆盖他人或其他租户无法修改、非法已填字段被拒绝、保存失败后主表和明细保持原状。本轮不包含正式提交、审核或覆盖规则实现。
