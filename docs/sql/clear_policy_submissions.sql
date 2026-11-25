-- 清空所有申请人的政策及本轮工作流记录，保留企业审核名单、用户、分工、商品和磁盘附件。
-- 适用于完成延期升级的十五表库或采用审核工作流的二十二、二十三表库。先停写、备份并在 Navicat 选库。
-- 若正在投递飞书动作，应先停用并核对尚未结办的外部待办。脚本不会撤回外部消息。
SET @review_table_count=(SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema=DATABASE() AND table_name IN ('policy_review_settings','policy_review_setting_member','policy_review_round','policy_review_member',
            'policy_review_decision','policy_review_reassignment','policy_review_delivery'));
SET @workflow_column=(SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='policy_audit_log' AND column_name='workflow_version');
SET @workflow_ready=(@review_table_count=7 AND @workflow_column=1);
SET @clear_ready=(@workflow_ready OR (@review_table_count=0 AND @workflow_column=0));
SELECT DATABASE() AS target_database, @clear_ready AS ready_to_clear,
       (SELECT COUNT(*) FROM policy_apply) AS applications_before,
       (SELECT COUNT(*) FROM policy_audit_log) AS audit_rounds_before,
       (SELECT COUNT(*) FROM policy_goods_detail) AS goods_details_before;
-- ready_to_clear 应为 1；部分升级时先查明原因。所有清理语句共同检查此前置条件。
SET @review_counts=IF(@workflow_ready,
 'SELECT (SELECT COUNT(*) FROM policy_review_delivery) AS deliveries, (SELECT COUNT(*) FROM policy_review_decision) AS decisions, (SELECT COUNT(*) FROM policy_review_reassignment) AS reassignments, (SELECT COUNT(*) FROM policy_review_member) AS assignments, (SELECT COUNT(*) FROM policy_review_round) AS workflow_rounds',
 'SELECT 0 AS workflow_counts_available');
PREPARE review_counts FROM @review_counts;
EXECUTE review_counts;
-- 确认以上库及数量后，在本窗口执行以下事务段。
START TRANSACTION;
SET @review_clear=IF(@workflow_ready,'DELETE FROM policy_review_delivery','SELECT 0 AS workflow_rows_to_clear');
PREPARE review_delete FROM @review_clear;
EXECUTE review_delete;
DEALLOCATE PREPARE review_delete;
SET @review_clear=IF(@workflow_ready,'DELETE FROM policy_review_decision','SELECT 0 AS workflow_rows_to_clear');
PREPARE review_delete FROM @review_clear;
EXECUTE review_delete;
DEALLOCATE PREPARE review_delete;
SET @review_clear=IF(@workflow_ready,'DELETE FROM policy_review_reassignment','SELECT 0 AS workflow_rows_to_clear');
PREPARE review_delete FROM @review_clear;
EXECUTE review_delete;
DEALLOCATE PREPARE review_delete;
SET @review_clear=IF(@workflow_ready,'DELETE FROM policy_review_member','SELECT 0 AS workflow_rows_to_clear');
PREPARE review_delete FROM @review_clear;
EXECUTE review_delete;
DEALLOCATE PREPARE review_delete;
SET @review_clear=IF(@workflow_ready,'DELETE FROM policy_review_round','SELECT 0 AS workflow_rows_to_clear');
PREPARE review_delete FROM @review_clear;
EXECUTE review_delete;
DEALLOCATE PREPARE review_delete;
SET @has_time_changes=(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='policy_time_change');
SET @change_clear=IF(@has_time_changes=1,'DELETE FROM policy_time_change WHERE @clear_ready','SELECT 0 AS change_rows_to_clear');
PREPARE change_delete FROM @change_clear;
EXECUTE change_delete;
DEALLOCATE PREPARE change_delete;
DELETE FROM policy_goods_detail WHERE @clear_ready;
DELETE FROM policy_audit_log WHERE @clear_ready;
UPDATE policy_apply SET extension_source_id=NULL WHERE @clear_ready AND extension_source_id IS NOT NULL;
DELETE FROM policy_apply WHERE @clear_ready;
EXECUTE review_counts;
DEALLOCATE PREPARE review_counts;
SELECT (SELECT COUNT(*) FROM policy_apply) AS applications_left,
       (SELECT COUNT(*) FROM policy_audit_log) AS audit_rounds_left,
       (SELECT COUNT(*) FROM policy_goods_detail) AS goods_details_left;
-- 所有适用余量均为 0 后，在同一窗口单独运行 COMMIT；任何语句失败请单独运行 ROLLBACK。
-- COMMIT;
-- ROLLBACK;
