-- 本脚本仅适用于已单独确认重建的旧 23 表结构。29 表空库建表请直接执行 schema.sql。
-- 以下操作会永久删除当前库的 23 张项目表及全部表数据，保留数据库本身。
-- 按外键依赖先删子表，再删父表。已删除的表会自动跳过。

DROP TABLE IF EXISTS `policy_review_delivery`;
DROP TABLE IF EXISTS `policy_review_decision`;
DROP TABLE IF EXISTS `policy_review_reassignment`;
DROP TABLE IF EXISTS `policy_review_member`;
DROP TABLE IF EXISTS `policy_review_round`;
DROP TABLE IF EXISTS `policy_review_setting_member`;
DROP TABLE IF EXISTS `policy_review_settings`;
DROP TABLE IF EXISTS `policy_goods_detail`;
DROP TABLE IF EXISTS `policy_time_change`;
DROP TABLE IF EXISTS `policy_audit_log`;
DROP TABLE IF EXISTS `policy_apply`;
DROP TABLE IF EXISTS `catalog_operation_log`;
DROP TABLE IF EXISTS `catalog_clean_job_item`;
DROP TABLE IF EXISTS `source_goods_item`;
DROP TABLE IF EXISTS `catalog_clean_job`;
DROP TABLE IF EXISTS `goods_sync_job`;
DROP TABLE IF EXISTS `goods`;
DROP TABLE IF EXISTS `color_dict`;
DROP TABLE IF EXISTS `spu`;
DROP TABLE IF EXISTS `series`;
DROP TABLE IF EXISTS `brand`;
DROP TABLE IF EXISTS `user_platform_assignment`;
DROP TABLE IF EXISTS `user_info`;

-- 新建 29 表的步骤及结构见 database-design.md。
