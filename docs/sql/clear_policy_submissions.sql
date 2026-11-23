-- 重新验证政策提交前，清空所选库内所有申请人的政策数据。
-- 包含草稿、延期单、待审核、已通过、已驳回申请，以及历轮快照、补贴决定和商品明细。
-- 适用于已完成政策延期升级的 MySQL 项目库。请先停止项目后端写入、备份并选中目标库。
-- 在 Navicat 同一个「新建查询」窗口中按段运行，核对结果后再手动提交。

-- 1. 核对库名及即将清理的记录数。
SELECT DATABASE() AS target_database,
       (SELECT COUNT(*) FROM policy_apply) AS applications_before,
       (SELECT COUNT(*) FROM policy_audit_log) AS audit_rounds_before,
       (SELECT COUNT(*) FROM policy_goods_detail) AS goods_details_before;

-- 2. 先删每轮商品明细，再删审核记录；解除延期单对原单的引用后，删除申请。
START TRANSACTION;
DELETE FROM policy_goods_detail;
DELETE FROM policy_audit_log;
UPDATE policy_apply SET extension_source_id = NULL WHERE extension_source_id IS NOT NULL;
DELETE FROM policy_apply;

SELECT (SELECT COUNT(*) FROM policy_apply) AS applications_left,
       (SELECT COUNT(*) FROM policy_audit_log) AS audit_rounds_left,
       (SELECT COUNT(*) FROM policy_goods_detail) AS goods_details_left;

-- 3. 上面各语句成功且三个余量均为 0 时，在同一窗口单独运行下一行的 COMMIT 命令。
-- COMMIT;
-- 如有报错或需要取消，在同一窗口单独运行 ROLLBACK;


COMMIT;