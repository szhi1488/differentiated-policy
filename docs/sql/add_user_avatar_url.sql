-- 用途：已有十四表数据库新增飞书头像字段，保留全部用户和业务数据。
-- 前置条件：MySQL 8.0.16+；备份后暂停应用，在 Navicat 选中目标库执行。
-- 先执行第一段检查：user_info 应存在，avatar_url 应不存在。
-- 若 avatar_url 已存在，停止执行 ALTER，核对类型及可空属性；不要重复加列。
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_info'
  AND COLUMN_NAME IN ('id', 'name', 'avatar_url');

-- 执行顺序：核对以上结果 → 执行 ALTER → 执行下方结构核对 → 启动新版后端并重新登录。
-- MySQL DDL 隐式提交；不清空用户，不修改角色、部门、停用状态及用户主键。
ALTER TABLE `user_info`
  ADD COLUMN `avatar_url` VARCHAR(2048) DEFAULT NULL
  COMMENT '飞书头像 HTTPS 地址，登录时同步' AFTER `name`;

-- 预期：一行 varchar(2048)、YES、NULL；已有用户头像为 NULL，重新登录后按飞书返回同步。
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_info'
  AND COLUMN_NAME = 'avatar_url';

-- 手工联调：用同一用户再次登录，核对 user_info 行数及 id 不变，角色、部门、停用状态不变。
-- /api/auth/session 的 user.avatarUrl 应与有效用户保存值一致；两端显示头像。
-- 头像为空或图片加载失败时显示姓名首字。恢复旧会话不会重新请求飞书头像，需重新登录。
-- 新空库改为执行 schema.sql 或 V1__create_policy_tables.sql 之一，不再执行本增量脚本。
