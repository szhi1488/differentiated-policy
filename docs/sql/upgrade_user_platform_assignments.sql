-- 平台分工统一升级入口，适用于 MySQL 8.0.16+ 的项目库。
-- 停止后端写入并备份，在 Navicat 选中已有 user_info 的项目库，整份运行。
-- 首次创建空分工表；已有分工表按当前索引升级，保留所有分工记录。可重复执行。
CREATE TABLE IF NOT EXISTS `user_platform_assignment` (
  `tenant_key` VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '飞书公司标识',
  `perspective` VARCHAR(16) COLLATE utf8mb4_bin NOT NULL COMMENT '零售或百亿渠道',
  `platform_code` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '平台编码',
  `user_id` BIGINT NOT NULL COMMENT '负责申请人',
  PRIMARY KEY (`tenant_key`, `perspective`, `platform_code`),
  KEY `idx_assignment_user_tenant` (`user_id`, `tenant_key`),
  CONSTRAINT `fk_assignment_user` FOREIGN KEY (`user_id`, `tenant_key`)
    REFERENCES `user_info` (`id`, `tenant_key`),
  CONSTRAINT `ck_assignment_platform` CHECK (
    (`perspective` = 'RETAIL' AND `platform_code` IN
      ('KUAISHOU','PDD','JD','DOUYIN','TMALL','TAOBAO','ZHAOSHANG','DEWU','UNICOM','GOV_ENTERPRISE'))
    OR (`perspective` = 'BILLION' AND `platform_code` IN ('PDD','TAOBAO','UNICOM')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='申请人渠道平台分工';

SET @assignment_count_before = (SELECT COUNT(*) FROM user_platform_assignment);

-- 只移除上一版已知的唯一索引。先核对组合主键，确保同一渠道平台仍限一位负责人。
SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) INTO @assignment_primary_columns
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'user_platform_assignment' AND index_name = 'PRIMARY';

SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index), MAX(non_unique)
INTO @assignment_old_columns, @assignment_old_non_unique
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'user_platform_assignment' AND index_name = 'uk_owner_platform';

SET @assignment_upgrade_sql = CASE
  WHEN COALESCE(@assignment_primary_columns, '') <> 'tenant_key,perspective,platform_code'
    THEN 'SELECT ''主键与项目基线不符，请核对表结构'' AS upgrade_note'
  WHEN @assignment_old_non_unique IS NULL
    THEN 'SELECT ''分工表已支持按渠道独立分配'' AS upgrade_note'
  WHEN @assignment_old_non_unique = 0 AND @assignment_old_columns = 'user_id,platform_code'
    THEN 'ALTER TABLE user_platform_assignment DROP INDEX uk_owner_platform'
  ELSE 'SELECT ''旧索引定义与预期不符，请核对表结构'' AS upgrade_note'
END;
PREPARE assignment_upgrade FROM @assignment_upgrade_sql;
EXECUTE assignment_upgrade;
DEALLOCATE PREPARE assignment_upgrade;

SHOW CREATE TABLE user_platform_assignment;

-- 分配后可独立执行下列只读验收。异常计数预期为 0。
SELECT COUNT(*) AS invalid_owners
FROM user_platform_assignment a
LEFT JOIN user_info u ON u.id = a.user_id AND u.tenant_key = a.tenant_key
WHERE u.id IS NULL OR u.role_type <> 'APPLICANT' OR u.is_deleted <> 0;

-- 选择一个已分配用户，核对申请表单按账号读取的查询计划。
SET @platform_owner_id = (SELECT MIN(user_id) FROM user_platform_assignment);
SET @platform_tenant_key = (SELECT tenant_key FROM user_info WHERE id = @platform_owner_id);
EXPLAIN SELECT perspective, platform_code FROM user_platform_assignment
WHERE tenant_key = @platform_tenant_key AND user_id = @platform_owner_id
ORDER BY perspective, platform_code;

-- 最后一行预期为“完成”，升级前后分工数相同；首次建表两者均为 0。
SELECT CASE WHEN @assignment_primary_columns = 'tenant_key,perspective,platform_code'
             AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                             WHERE table_schema = DATABASE() AND table_name = 'user_platform_assignment'
                               AND index_name = 'uk_owner_platform')
             AND COUNT(*) = @assignment_count_before
            THEN '完成' ELSE '请核对表结构及分工数' END AS upgrade_result,
       @assignment_count_before AS assignments_before, COUNT(*) AS assignments_after
FROM user_platform_assignment;
