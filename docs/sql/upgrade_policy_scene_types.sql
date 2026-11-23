-- MySQL 8.0.16+。停止应用写入并备份，在 Navicat 选中项目库后同一连接整份执行。
-- 支持旧结构、中断后的双列结构及新版基线。脚本不修改任何一轮提交快照。
SELECT DATABASE() AS target_database, VERSION() AS mysql_version;
SET @rows_before = (SELECT COUNT(*) FROM policy_apply);
SET @logs_before = (SELECT COUNT(*) FROM policy_audit_log);
SET @has_old = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'policy_apply' AND column_name = 'scene'
    AND column_type = 'varchar(20)' AND is_nullable = 'YES');
SET @has_new = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'policy_apply' AND column_name = 'scene_types'
    AND data_type = 'json' AND is_nullable = 'YES');
SET @column_count = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'policy_apply' AND column_name IN ('scene','scene_types'));
SET @has_retail_check = (SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE table_schema = DATABASE() AND table_name = 'policy_apply'
    AND constraint_name = IF(@has_old, 'ck_apply_retail_fields', 'ck_apply_retail_basics') AND enforced = 'YES');
SET @has_scene_check = (SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE table_schema = DATABASE() AND table_name = 'policy_apply'
    AND constraint_name = 'ck_apply_scene_types' AND enforced = 'YES');
SET @supported = @column_count > 0 AND @column_count = @has_old + @has_new
  AND @has_retail_check = 1 AND @has_scene_check = @has_new;

-- ① 原子添加新列和校验。未识别的结构不会进入写入步骤。
SET @scene_sql = IF(@supported AND @has_old AND NOT @has_new,
  'ALTER TABLE policy_apply ADD COLUMN `scene_types` JSON DEFAULT NULL COMMENT ''零售实际选中的国补、地补、自补编码数组，未填及百亿为 SQL NULL'' AFTER write_off_type,
  ADD CONSTRAINT `ck_apply_scene_types` CHECK (
    `scene_types` IS NULL OR (
      `channel_type` = ''RETAIL'' AND JSON_TYPE(`scene_types`) = ''ARRAY''
      AND JSON_DEPTH(`scene_types`) = 2 AND JSON_LENGTH(`scene_types`) BETWEEN 1 AND 3
      AND JSON_LENGTH(`scene_types`) = JSON_CONTAINS(`scene_types`, ''"NATIONAL_SUB"'')
        + JSON_CONTAINS(`scene_types`, ''"LOCAL_SUB"'') + JSON_CONTAINS(`scene_types`, ''"SELF_SUB"'')
    )
  )', 'SELECT 1 AS skipped');
PREPARE scene_step FROM @scene_sql;
EXECUTE scene_step;
DEALLOCATE PREPARE scene_step;

-- ② 逐行将旧标量转为实际单元素数组，保留 SQL NULL 和原更新时间。
SET @scene_sql = IF(@supported AND @has_old,
  'UPDATE policy_apply SET scene_types = JSON_ARRAY(scene), updated_at = updated_at
   WHERE scene IS NOT NULL AND scene_types IS NULL', 'SELECT 1 AS skipped');
PREPARE scene_step FROM @scene_sql;
EXECUTE scene_step;
DEALLOCATE PREPARE scene_step;

-- ③ 保留旧列作逐行对照。每一行（含逻辑删除行）都一致后，才以一次原子 DDL 移除旧列。
SET @scene_differences = NULL;
SET @scene_sql = IF(@supported AND @has_old,
  'SELECT COUNT(*) INTO @scene_differences FROM policy_apply
   WHERE (scene IS NULL AND scene_types IS NOT NULL)
      OR (scene IS NOT NULL AND NOT (scene_types <=> JSON_ARRAY(scene)))',
  'SELECT 1 AS skipped');
PREPARE scene_step FROM @scene_sql;
EXECUTE scene_step;
DEALLOCATE PREPARE scene_step;

SET @scene_sql = IF(@supported AND @has_old AND @scene_differences = 0,
  'ALTER TABLE policy_apply DROP CHECK ck_apply_retail_fields, DROP COLUMN scene,
  ADD CONSTRAINT `ck_apply_retail_basics` CHECK (
    (`write_off_type` IS NULL OR `write_off_type` IN (''SALES'', ''ACTIVATION''))
    AND (`is_estimated` IS NULL OR `is_estimated` IN (0, 1))
    AND (`channel_type` = ''RETAIL'' OR (`write_off_type` IS NULL AND `is_estimated` IS NULL))
  )', 'SELECT 1 AS skipped');
PREPARE scene_step FROM @scene_sql;
EXECUTE scene_step;
DEALLOCATE PREPARE scene_step;

-- ④ 核对表结构、渠道空值、行数和升级结果。已升级库重跑时无需逐行转换，差异显示 NULL。
SHOW CREATE TABLE policy_apply;
SELECT channel_type, CAST(scene_types AS CHAR) AS selected_scenes, COUNT(*) AS policies
FROM policy_apply GROUP BY channel_type, selected_scenes;
SET @billion_errors = (SELECT COUNT(*) FROM policy_apply WHERE channel_type = 'BILLION' AND scene_types IS NOT NULL);
SELECT CASE WHEN @supported AND NOT EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_schema = DATABASE() AND table_name = 'policy_apply' AND column_name = 'scene')
            AND (@has_old = 0 OR @scene_differences = 0) AND @billion_errors = 0
            AND @rows_before = (SELECT COUNT(*) FROM policy_apply)
            AND @logs_before = (SELECT COUNT(*) FROM policy_audit_log)
            THEN '完成' ELSE '请核对结构和差异，暂缓启动新版后端' END AS upgrade_result,
       @scene_differences AS scene_differences, @billion_errors AS billion_scene_errors,
       @rows_before AS policies_before, (SELECT COUNT(*) FROM policy_apply) AS policies_after,
       @logs_before AS snapshots_before, (SELECT COUNT(*) FROM policy_audit_log) AS snapshots_after;

-- ⑤ 抽查新、旧快照的路径与筛选计划。可将场景和租户替换成需要核对的值。
SELECT policy_id, version, JSON_EXTRACT(snapshot_data, '$.form.sceneType') AS legacy_scene,
       JSON_EXTRACT(snapshot_data, '$.form.sceneTypes') AS selected_scenes
FROM policy_audit_log ORDER BY id DESC LIMIT 20;
SET @scene_tenant = (SELECT tenant_key FROM policy_apply ORDER BY id LIMIT 1);
EXPLAIN SELECT p.id FROM policy_apply p JOIN policy_audit_log a
  ON a.policy_id = p.id AND a.version = p.current_version AND a.tenant_key = p.tenant_key
WHERE p.tenant_key = @scene_tenant AND p.is_deleted = 0 AND p.status IN ('PENDING','APPROVED','REJECTED')
  AND JSON_CONTAINS(COALESCE(JSON_EXTRACT(a.snapshot_data, '$.form.sceneTypes'),
                             JSON_EXTRACT(a.snapshot_data, '$.form.sceneType')), JSON_QUOTE('LOCAL_SUB'));
