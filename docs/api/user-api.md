# 用户、岗位与平台分工接口

2026-11-26 的空库实现使用 `sys_role`、`sys_role_permission`、`sys_user_role`、`platform_team` 和 `user_platform_assignment`。所有设置只作用于当前已核验的飞书企业。账号 ID、角色 ID 和成员 ID 均使用十进制字符串传输。写请求沿用工作台的可信 Origin、会话 Cookie 和 `X-Policy-Request: 1`。

## 登录与首位系统管理员

首次通过飞书身份核验时，服务在同一事务内建立本企业的四个内置角色和 13 个渠道平台，为新账号分配运营。重复登录只刷新资料并读取现有授权。管理员撤销全部岗位后，该账号下次登录仍为空岗位。停用账号不能登录。

首位管理员应先完成一次飞书登录。数据库维护人在 Navicat 核对企业键和该人的应用内 `open_id`，在以下事务中填写两处值。首位管理员使用工作台后可继续分配其余用户，无需再次执行此步骤。

```sql
START TRANSACTION;
SET @company_key = '已核对的企业键';
SET @verified_open_id = '已核对的应用内 open_id';

SELECT id, name, open_id, is_deleted
FROM user_info
WHERE tenant_key = @company_key AND open_id = @verified_open_id
FOR UPDATE;

INSERT INTO sys_user_role (tenant_key, user_id, role_id)
SELECT u.tenant_key, u.id, r.id
FROM user_info u
JOIN sys_role r ON r.tenant_key = u.tenant_key AND r.role_code = 'ADMIN'
WHERE u.tenant_key = @company_key AND u.open_id = @verified_open_id
  AND u.is_deleted = 0 AND r.is_enabled = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_user_role ur
    WHERE ur.tenant_key = u.tenant_key AND ur.user_id = u.id AND ur.role_id = r.id
  );

UPDATE user_info
SET auth_version = auth_version + 1
WHERE ROW_COUNT() = 1 AND tenant_key = @company_key AND open_id = @verified_open_id;
COMMIT;
```

`GET /api/auth/session` 返回 `user.roleCodes` 和 `user.permissions`。权限取所有启用岗位的并集。系统管理员自动获得后端登记的全部权限。账号启停、角色权限及成员分工从数据库核验，旧会话不能保留已撤销的操作权限。

## 账号与角色分配

| 接口 | 请求与返回 |
| --- | --- |
| `GET /api/admin/users` | 要求 `users.manage`。可按 `keyword,roleCode,status,page,pageSize` 筛选。关键词按姓名或部门子串匹配，最多 128 字符。默认每页 10 人，最多 100 人。返回 `{items,total}`，每项含 `id,name,avatarUrl,department,roleCodes,authVersion,status,createdAt`，按加入时间、ID 倒序 |
| `GET /api/admin/users/stats` | 要求 `users.manage`。返回本企业 `total,enabledUsers,enabledAdmins,disabledUsers`，启用人数包含启用的管理员 |
| `GET /api/admin/users/{id}` | 要求 `users.manage`。比列表额外返回应用内 `openId`。本企业可查看已停用账号，其他企业或不存在的账号统一返回 404 |
| `PATCH /api/admin/users/{id}/roles` | 要求 `users.manage`。例如 `{"roleCodes":["PURCHASE","PRODUCT_OPS"],"expectedVersion":1}`。整组替换角色，空数组解除所有角色 |
| `PATCH /api/admin/users/{id}/status` | 要求 `users.manage`。例如 `{"status":"DISABLED","expectedVersion":2}`。状态取 `ENABLED` 或 `DISABLED` |

两种账号更新均返回最新详情。实际变更递增同一个 `authVersion`，旧版本返回 409。保护本人岗位、本人启用状态以及最后一名启用的系统管理员。授权管理岗只能调整其有权授予的角色。

## 岗位定义

| 接口 | 说明 |
| --- | --- |
| `GET /api/admin/roles` | `roles.manage` 或 `users.manage` 可读。返回 `{roles,permissions}`。角色含 `id,roleCode,roleName,builtin,enabled,version,permissions`，权限目录每项为 `{code,name}` |
| `POST /api/admin/roles` | `roles.manage` 可创建角色，传 `roleCode,roleName,enabled,permissions` |
| `PUT /api/admin/roles/{id}` | `roles.manage` 可修改名称、启用状态和权限。传上述字段及 `expectedVersion`。版本冲突返回 409 |

角色编码使用大写英文字母开头，可含数字和下划线，最多 64 字符，创建后固定。权限必须属于后端目录，系统管理员的全权角色受保护。完整预置权限与数据访问边界见[岗位设计](../sql/role-platform-review-design.md#四个内置岗位)。

## 负责平台

| 接口 | 说明 |
| --- | --- |
| `GET /api/admin/users/platform-assignments` | `platforms.manage` 可读所有平台。返回数组，每项为 `{perspective,platformCode,version,members:[{userId,name,memberKind,canSubmit}]}` |
| `GET /api/admin/users/platform-candidates` | `platforms.manage` 可按 `keyword,page,pageSize` 查询具备政策提交、平台政策查询或平台机型补贴查询权限的启用人员，返回 `{items:[{id,name,department,canSubmit}],total}` |
| `PUT /api/admin/users/platform-assignments/{perspective}/{platformCode}` | `platforms.manage` 可保存整组分工。成功返回 204，旧团队版本返回 409 |

```json
{
  "expectedVersion": 0,
  "members": [
    {"userId":"101","memberKind":"SUBMITTER"},
    {"userId":"102","memberKind":"MEMBER"}
  ]
}
```

同一渠道平台的非空配置须指定一位具备 `policy.apply` 的启用提交人。其余成员可有多人。空数组清空整组分工。零售与百亿的同名平台分别配置。管理员停用账号、撤下岗位或调整岗位权限使提交人失去资格时，原平台进入待指定状态。仍有平台政策或补贴查阅资格的成员保留席位。原单归属、历轮签署和飞书回执保留。

运营提交资格、平台已提交政策可见范围和终审群公告的点名依据见[平台共同负责设计](../sql/role-platform-review-design.md#3-同平台多人只有一人能提交)。
