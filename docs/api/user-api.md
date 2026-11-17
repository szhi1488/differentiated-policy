# 用户管理接口

更新日期：2026-11-17。

后端已实现用户列表、全量统计、详情、角色调整及账号启停。管理员页面 `#/admin/users` 已接入全部五个接口。

## 实施状态

| 能力 | 后端状态 | 当前前端 |
| --- | --- | --- |
| `GET /api/admin/users` 用户列表 | 已实现组合筛选与分页 | 服务端筛选及分页 |
| `GET /api/admin/users/stats` 顶部统计 | 已实现独立全量统计 | 独立加载全公司统计 |
| `GET /api/admin/users/{id}` 用户详情 | 已实现本公司用户详情 | 按本地 ID 加载详情 |
| `PATCH /api/admin/users/{id}/role` 角色调整 | 已实现旧值校验、权限与并发保护 | 确认后提交目标角色及旧值 |
| `PATCH /api/admin/users/{id}/status` 启用／停用 | 已实现状态更新及并发保护，保留原角色和业务历史 | 确认后提交目标状态及旧值 |

五个接口复用现有 `user_info` 字段。角色、状态和加入时间分别来自 `role_type`、`is_deleted`、`created_at`。姓名、头像、部门和飞书身份取自已有资料，部门可为空。

## 页面调用与刷新

进入用户管理页时，列表和统计分别请求。关键词、角色、状态及分页参数只随列表请求发送。打开详情抽屉按本地用户 ID 查询。每次打开角色或启停弹窗时再读一次该用户的详情，以返回的 `roleType` 和 `status` 填入 PATCH 请求的 `expectedRoleType` 和 `expectedStatus`。

确认写入成功后，并行刷新当前筛选下的列表、全公司统计及已打开的详情。例如，从「已启用」列表停用某人后，该用户移出筛选结果，停用人数更新，原详情抽屉显示「已停用」。若最后一页因此消失，页面回到新的最后一页。

遇到 409 或写入结果未知时，页面显示错误，保留管理员选择的目标并重新读取账号。若查到的当前值已符合目标，确认按钮禁用。若仍需修改，由管理员核对后再次确认。读取失败的区域可单独重试。[页面规则](../architecture/admin-workspace.md#用户管理)

## 权限与数据范围

- 沿用当前 HttpSession，未登录或会话到期返回 401。服务端根据会话的本地用户 ID、飞书企业及用户标识重新读取账号，校验本公司有效 `ADMIN`，不能仅凭登录时角色快照授权。已降级、已停用或身份不符的账号返回 403。
- 用户范围固定为服务端核验的本公司 `tenant_key`。接口不接受前端指定查询公司；查询参数不能提升权限或切换公司。
- 为支持用户管理查看停用账号，本接口专门允许返回 `is_deleted=0/1` 的记录。状态不传时包含启用和停用用户。此例外不影响登录停用检查和其他业务查询的有效账号过滤。
- 三个查询接口均只读，成功和失败响应均禁止缓存。不调用飞书通讯录，不写用户表。
- 两个写接口均校验可信 Origin 与 `X-Policy-Request: 1`，事务内按本地 ID 升序锁定操作人和目标用户，重新核验锁内的操作人身份及目标旧值。

## 用户列表

`GET /api/admin/users`

| 参数 | 类型 | 默认值 | 规则 |
| --- | --- | --- | --- |
| page | 整数 | 1 | 从 1 开始，最大为 Java int 上限；超出结果范围返回空 items，total 保留匹配总数 |
| pageSize | 整数 | 10 | 1 至 100，页面可使用 10／20／50 |
| keyword | 字符串 | 不限 | 姓名或部门任一字段包含该关键词；去除首尾空白，空白视为不筛选，最多 128 个 Unicode 字符 |
| roleType | 字符串 | 不限 | `APPLICANT` 或 `ADMIN`；去除首尾空白，空值不限，非法编码返回 400 |
| status | 字符串 | 不限 | `ENABLED` 或 `DISABLED`；去除首尾空白，空值不限，非法编码返回 400 |

筛选条件组合取交集。关键词按整段子串匹配，英文字母不区分大小写；`%`、`_`、`!` 和反斜杠按普通字符匹配，不允许调用者传 SQL 通配符。查询通过 MyBatis 绑定参数执行。

按 `created_at DESC, id DESC` 排序，同秒加入的用户仍有稳定顺序。数据库执行计数与分页，返回的 total 对应当前全部筛选条件。计数与列表在同一只读事务内查询。

示例：

```http
GET /api/admin/users?page=1&pageSize=10&keyword=采购&roleType=ADMIN&status=ENABLED
```

成功响应：

```json
{
  "items": [
    {
      "id": "12",
      "name": "示例用户",
      "avatarUrl": null,
      "department": "采购部",
      "roleType": "ADMIN",
      "status": "ENABLED",
      "createdAt": "2026-11-17 09:00:00"
    }
  ],
  "total": 1
}
```

| 返回字段 | 说明 |
| --- | --- |
| items | 当前页列表，无匹配或超出页码时为空数组 |
| total | 当前筛选条件匹配的用户总数，包含当前页之外的记录 |
| items[].id | 本地用户 ID，始终为字符串，避免 BIGINT 精度丢失 |
| items[].name | 已保存的用户姓名 |
| items[].avatarUrl | 已保存的可空飞书头像地址；为空或加载失败时由前端回退姓名首字 |
| items[].department | 可空的本地部门资料；当前不自动同步飞书部门，不根据姓名推断 |
| items[].roleType | `APPLICANT` 申请人、`ADMIN` 管理员 |
| items[].status | 现有 `is_deleted=0` 映射 `ENABLED`，`1` 映射 `DISABLED`；不直接暴露数据库删除标记 |
| items[].createdAt | 取现有加入系统时间，格式 `yyyy-MM-dd HH:mm:ss`，北京时间；不代表最近登录时间 |

列表不返回 `tenantKey`、`openId`、`unionId` 或任何访问令牌。分页响应复用现有 `PageResponse` 的 items／total，不额外返回统计卡数据。

## 用户详情

`GET /api/admin/users/{id}`

路径参数 id 为本地用户 ID，须为正整数，范围 `1` 至 `9223372036854775807`。接口独立按用户 ID 读取，不受列表关键词、角色、状态、分页或前端指定公司影响。

调用人须为本公司有效管理员；允许查看本公司启用及停用用户，也允许查看本人资料。查询同时限定公司和用户 ID，不先查询其他公司资料。目标不存在或不属于本公司时，统一返回相同的 404 `USER_NOT_FOUND`，不泄露该账号是否实际存在。`/stats` 继续作为固定路由，不会按用户 ID 处理。

成功响应示例：

```json
{
  "id": "12",
  "name": "示例用户",
  "avatarUrl": null,
  "department": null,
  "openId": "ou_example",
  "roleType": "APPLICANT",
  "status": "DISABLED",
  "createdAt": "2026-11-17 09:00:00"
}
```

详情为扁平对象，包含列表的七个字段以及应用内飞书标识 openId。id 始终使用字符串，姓名、头像、部门、角色及状态均从现有用户表读取，不能用登录快照代替目标用户。头像和部门缺失时保留 null；加入时间取 `created_at`，北京时间精确到秒，不是最近登录时间。停用目标的状态为 `DISABLED`，读取不会重新启用账号。

openId 对应当前飞书应用内的 `open_id`，仅在经过授权的本公司用户详情中返回。接口不返回 tenantKey、unionId、数据库删除标记或任何访问令牌，也不触发资料同步。

## 顶部统计

`GET /api/admin/users/stats`

无需查询参数。独立统计当前服务端核验的本公司全量用户，不引用用户列表筛选；即使请求附带 `keyword`、`roleType`、`status`、`page`、`pageSize` 或 `tenantKey`，也会忽略这些参数。调用人仍须为本公司有效管理员；过期角色或停用账号不能使用旧会话访问。

响应示例：

```json
{
  "total": 5,
  "enabledApplicants": 1,
  "enabledAdmins": 2,
  "disabledUsers": 2
}
```

| 字段 | 统计范围 |
| --- | --- |
| total | 本公司用户总数，包含两种角色的启用及停用账号，含当前管理员 |
| enabledApplicants | `role_type=APPLICANT` 且 `is_deleted=0` |
| enabledAdmins | `role_type=ADMIN` 且 `is_deleted=0` |
| disabledUsers | `is_deleted=1`，包含申请人和管理员 |

四项均为非空整数，使用 long 计数。合法角色和启停值下，`total = enabledApplicants + enabledAdmins + disabledUsers`。使用单次 SQL 聚合保证四项来自同一次查询；空范围聚合返回四个 0，不返回 null。正常调用至少包含当前有效管理员。统计返回实际数据，不缓存列表结果或演示值，不提供用户个人资料。

统计接口沿用下列 401、403、503 错误和禁止缓存策略，不对附带的列表参数执行校验，也不接入任何角色或启停写入操作。

## 调整用户角色

`PATCH /api/admin/users/{id}/role`

id 为本地用户正整数 ID，范围与详情接口一致。须有当前应用可信的 Origin、`X-Policy-Request: 1` 及 `Content-Type: application/json`。

请求示例：

```json
{
  "roleType": "ADMIN",
  "expectedRoleType": "APPLICANT",
  "expectedStatus": "ENABLED"
}
```

| 字段 | 必填 | 规则 |
| --- | --- | --- |
| roleType | 是 | 目标角色，`APPLICANT` 或 `ADMIN` |
| expectedRoleType | 是 | 最近读取的目标角色，从详情 roleType 取得 |
| expectedStatus | 是 | 最近读取的目标状态，从详情 status 取得，`ENABLED` 或 `DISABLED` |

成功返回 HTTP 200 和更新后的完整用户详情，字段与 `GET /api/admin/users/{id}` 相同。目标角色已与当前角色一致且预期旧值相符时，返回当前详情，不修改更新时间。目标不存在或跨公司时仍为统一的 404。

- 仅本公司当前有效管理员可操作。禁止调整自己的角色；降级最后一位有效管理员也被拒绝。停用的管理员不计入有效管理员人数。
- 允许调整其他停用用户的角色，原启停状态保持不变。姓名、头像、部门、本地 ID、openId、unionId、加入时间、原申请及审核历史保持不变。
- 角色和状态须与 expectedRoleType／expectedStatus 一致，否则返回 409。重复发送已经成功写入前的旧请求也会冲突，结果未知时应重新读取详情核对，不能盲目重发。
- 写入采用 READ_COMMITTED 事务，先锁定 ID 较小的账号，再锁定另一个账号，锁定后重新核验操作人的有效管理员身份。两位管理员互相降级时，后获得锁的一方会因角色已变化被拒绝，不能把管理员清空。同一目标的过期变更返回冲突。SQL 同时限定公司、目标 ID、旧角色和旧启停标记，并检查影响行数。
- 只有实际角色变化才写入 `role_type` 和北京时间到秒的 `updated_at`；读取更新后详情也在同一事务内，失败时回滚。锁等待或死锁冲突返回 409，由调用方重新读取后确认。
- 旧值校验使用现有字段，不是独立的记录版本号；不提供完整修改历史或对“修改后又恢复原值”的历史检测。本轮不新增操作历史表。

角色变更后，商品、政策及用户管理业务接口在后续请求中按数据库当前角色授权；已降级用户不能凭旧管理员会话继续使用管理接口。`GET /api/auth/session` 仍是登录时角色快照，工作台入口需重新登录后刷新。本轮不改会话机制或前端角色分流。

## 启用或停用账号

`PATCH /api/admin/users/{id}/status` 按本地用户正整数 ID 设置账号状态。使用管理员会话、可信 Origin、`X-Policy-Request: 1` 和 `Content-Type: application/json`。

```json
{
  "status": "DISABLED",
  "expectedRoleType": "APPLICANT",
  "expectedStatus": "ENABLED"
}
```

三个字段均必填。`status` 为目标状态（`ENABLED` 启用、`DISABLED` 停用）。`expectedRoleType` 和 `expectedStatus` 取自最近一次详情中的 `roleType` 和 `status`。例如，重新启用上述申请人时传 `ENABLED`、`APPLICANT`、`DISABLED`。

- 成功返回 HTTP 200，响应与用户详情相同。实际变化只更新目标行的 `is_deleted` 和北京时间到秒的 `updated_at`。原账号 ID、飞书身份、角色、申请及审核历史均保留。旧值相符且已是目标状态时直接返回详情。
- 角色和启停操作共用行锁。提交前的角色或状态已变化时返回 409，包括成功后重发旧请求。结果未知时重新读取详情核对。禁止管理员停用本人，且公司须保留至少一位启用管理员。
- 停用期间，飞书身份落库校验返回 403 `USER_DISABLED`，已有会话访问受保护的业务接口也会被拒绝。重新启用后，原账号可按原角色访问。启停沿用现有会话机制，未过期的会话按当前账号状态授权。

## 错误响应

沿用 `{ "code": "稳定错误编码", "message": "中文提示" }`。

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | USER_LIST_INVALID_REQUEST | 分页不是整数、页码小于 1、页容量不在 1 至 100、角色／状态编码无效、关键词过长 |
| 400 | USER_DETAIL_INVALID_REQUEST | 详情用户 ID 非整数、不为正数或超出 BIGINT 有符号上限 |
| 400 | USER_ROLE_INVALID_REQUEST | 角色接口的用户 ID 或服务端角色参数校验无效 |
| 400 | USER_STATUS_INVALID_REQUEST | 启停接口的用户 ID 或服务端启停参数无效 |
| 400 | INVALID_REQUEST | 写请求体缺失、JSON 无效、字段缺失或角色／状态编码无效 |
| 401 | LOGIN_REQUIRED | 未登录或会话失效 |
| 403 | USER_ADMIN_REQUIRED | 账号不存在、本地 ID 不符、当前非管理员或已停用 |
| 403 | USER_SCOPE_DENIED | 已核验账号不属于配置的公司 |
| 403 | REQUEST_ORIGIN_DENIED | 写请求缺少可信 Origin 或 X-Policy-Request 请求头 |
| 404 | USER_NOT_FOUND | 目标不存在或不在当前公司可见范围，统一响应 |
| 409 | USER_ROLE_CONFLICT | 角色／状态旧值不匹配、条件更新未命中，或写入锁冲突 |
| 409 | USER_ROLE_SELF_FORBIDDEN | 试图调整自己的角色 |
| 409 | USER_STATUS_CONFLICT | 启停操作的角色／状态旧值不匹配、条件更新未命中或锁冲突 |
| 409 | USER_STATUS_SELF_FORBIDDEN | 试图停用本人账号 |
| 409 | USER_LAST_ADMIN_REQUIRED | 试图降级或停用最后一位有效管理员 |
| 503 | USER_SCOPE_MISSING | 未配置有效公司飞书租户 |
| 503 | USER_STORAGE_UNAVAILABLE | 用户数据读取或事务不可用，响应不包含 SQL、连接信息或原始异常 |

## 实现与验证

实现沿用 `Controller → Service → Mapper`。`UserManagementController` 接收参数并映射异常。`UserManagementServiceImpl` 通过 `CatalogAccess.requireUserAdmin` 核验管理员，查询使用只读事务，角色和启停各自在可写事务内完成。`UserManagementMapper` 执行筛选、行锁及条件更新。

用户列表初次接入的 H2 + MockMvc 回归为 20 项，覆盖默认与组合筛选、空值、启停映射、稳定分页和越界、默认页容量、通配符及 SQL 文本按字面查询、长 ID、Unicode 长度、未登录／申请人／停用／其他公司／身份不符／过期角色、缺少公司配置，以及存储／事务异常脱敏。H2 检查不代表目标 MySQL 已联调。

2026-11-17 实际运行后端 `mvn.cmd '-Dmaven.repo.local=.local-cache/maven-repository' verify`，共 128 项测试：126 项通过、2 项真实外部环境测试跳过，无失败或错误；打包和 Spotless 检查通过。新增用例包含上述 20 项，不启动或连接目标 MySQL，不运行吉客云实测。

同日补充统计接口后，用户管理回归扩展至 24 项：新增验证四项计数及合计关系、其他公司排除、当前管理员计入、列表筛选与分页完全不影响统计、实际数据变化后重查、空范围全零及仅当前管理员启用。原有权限、缺少配置和存储异常测试同时覆盖列表及统计路径。最终完整 `verify` 通过 132 项中的 130 项，2 项真实外部环境测试跳过，打包及 Spotless 通过。前端未修改，数据库结构未调整。

同日补充详情接口后，用户管理回归共 33 项全部通过，新增 9 项覆盖实际资料和 openId、本人详情、停用目标及缺失资料、跨公司与不存在目标的相同 404、查询参数不能切换公司、长 ID 精度和无效 ID。原有身份权限、缺少配置、存储和事务异常用例扩展至三个读取接口，统计固定路由继续通过。完整后端 `verify` 共 141 项：139 项通过、2 项真实外部环境测试跳过，无失败或错误，打包及 Spotless 通过。验证使用隔离 H2 和 MockMvc，未连接目标 MySQL 或真实飞书，未修改前端和数据库结构。

同日补充角色接口后，新增 `UserRoleControllerTest` 25 项全部通过，覆盖双向切换、资料及身份保持、无变化不写更新时间、停用目标保持停用、自身角色保护、最后有效管理员保护、旧值冲突与旧请求重放、公司隔离、来源请求头、实时角色校验、缺项／无效参数、长 ID、失败回滚、同一目标并发，以及两位管理员互相降级。实际运行完整后端 `verify`：共 166 项，164 项通过、2 项外部环境测试跳过，无失败或错误，打包及 Spotless 通过。两个用户管理测试类共 58 项通过，锁行为使用隔离 H2 验证，不代表目标 MySQL 已联调。

同日补充启停接口后，H2 与 MockMvc 新增 19 项回归。17 项启停测试覆盖状态往返、账号和申请及审核记录保留、停用期间身份核验与旧会话访问、统计及筛选、本人和最后一位管理员保护、旧值冲突、参数和公司范围校验、回滚及锁冲突。另有 2 项并发测试覆盖同一目标的升权与停用，以及两位管理员互相停用与降级。后端完整 `mvn.cmd '-Dmaven.repo.local=.local-cache/maven-repository' verify` 共 185 项，183 项通过，2 项外部环境测试跳过。打包及 Spotless 检查通过。该轮后端验证使用隔离测试数据。

2026-11-17 页面接入后，前端 37 个测试文件共 202 项通过。生产构建在桌面、平板、手机三种尺寸下连接实际 Spring Boot 接口和隔离 H2，使用模拟飞书身份建立会话。验收期间五个用户接口合计返回 52 次 HTTP 200。[前端检查与浏览器验证](../architecture/frontend-development.md#2026-11-17-用户管理页面接入)

## 目标环境联调

- 目标 MySQL 待验证公司隔离、事务回滚及角色与启停并发。查库和 SQL 由用户在 Navicat 执行。
- 真实飞书会话待验证停用期间的登录及旧会话访问限制、重新启用后的登录，以及按当前角色进入工作台。
