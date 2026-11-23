-- 在已升级或新建的目标 MySQL 库执行。全为只读查询；预期见 database-design.md。
SELECT DATABASE() AS target_database, VERSION() AS mysql_version;
SELECT table_name FROM information_schema.tables
WHERE table_schema=DATABASE() AND table_name IN ('policy_review_settings','policy_review_setting_member','policy_review_round','policy_review_member',
            'policy_review_decision','policy_review_reassignment','policy_review_delivery') ORDER BY table_name;
SELECT column_type, is_nullable, column_default FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='policy_audit_log' AND column_name='workflow_version';
SELECT table_name, index_name, non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS indexed_columns
FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name IN ('policy_review_settings','policy_review_setting_member','policy_review_round','policy_review_member',
            'policy_review_decision','policy_review_reassignment','policy_review_delivery')
GROUP BY table_name,index_name,non_unique ORDER BY table_name,index_name;
SELECT table_name,constraint_name,constraint_type,enforced FROM information_schema.table_constraints
WHERE constraint_schema=DATABASE() AND (table_name IN ('policy_review_settings','policy_review_setting_member','policy_review_round','policy_review_member',
            'policy_review_decision','policy_review_reassignment','policy_review_delivery') OR constraint_name='ck_policy_audit_workflow')
ORDER BY table_name,constraint_type,constraint_name;

-- 刚升级的库应只含旧轮（0）；空库可为零行。上线后的版本与阶段按实际运行核对。
SELECT workflow_version,audit_status,COUNT(*) AS rounds FROM policy_audit_log
GROUP BY workflow_version,audit_status ORDER BY workflow_version,audit_status;
SELECT (SELECT COUNT(*) FROM policy_review_settings) AS settings_count,
       (SELECT COUNT(*) FROM policy_review_setting_member) AS configured_members,
       (SELECT COUNT(*) FROM policy_review_round) AS workflow_rounds,
       (SELECT COUNT(*) FROM policy_review_member) AS assignments,
       (SELECT COUNT(*) FROM policy_review_decision) AS decisions,
       (SELECT COUNT(*) FROM policy_review_reassignment) AS reassignments,
       (SELECT COUNT(*) FROM policy_review_delivery) AS deliveries;
-- 以下五项应为零，核对实际归属、节点决定、个人投递及原申请人的修改指派。
SELECT (SELECT COUNT(*) FROM policy_audit_log a LEFT JOIN policy_review_round r
          ON r.policy_id=a.policy_id AND r.version=a.version AND r.tenant_key=a.tenant_key
        WHERE (a.workflow_version=1 AND r.policy_id IS NULL) OR (a.workflow_version=0 AND r.policy_id IS NOT NULL)) AS marker_mismatches,
       (SELECT COUNT(*) FROM policy_review_decision d LEFT JOIN policy_review_member m
          ON m.id=d.member_id AND m.policy_id=d.policy_id AND m.version=d.version AND m.tenant_key=d.tenant_key AND m.group_code=d.group_code
        WHERE m.id IS NULL OR m.member_status<>'HANDLED') AS wrong_decision_members,
       (SELECT COUNT(*) FROM policy_review_delivery d LEFT JOIN policy_review_member m
          ON m.id=d.recipient_key AND m.policy_id=d.policy_id AND m.version=d.version AND m.tenant_key=d.tenant_key
        WHERE (d.action_type='GROUP_MESSAGE' AND d.recipient_key<>0) OR (d.action_type<>'GROUP_MESSAGE' AND m.id IS NULL)) AS wrong_delivery_recipients,
       (SELECT COUNT(*) FROM policy_review_round r JOIN policy_audit_log a
          ON a.policy_id=r.policy_id AND a.version=r.version
        WHERE (r.stage='FINISHED')<>(a.audit_status<>'SUBMITTED')) AS stage_mismatches,
       (SELECT COUNT(*) FROM policy_review_member m JOIN policy_apply p ON p.id=m.policy_id AND p.tenant_key=m.tenant_key
        WHERE m.group_code='REVISION' AND m.user_id<>p.applicant_id) AS wrong_revision_owners;

-- 拿一个实际企业和已指派用户的样本核对索引候选及访问量；空表时执行计划没有生产代表性。
SET @review_tenant=(SELECT tenant_key FROM policy_review_member ORDER BY id LIMIT 1);
SET @review_user=(SELECT user_id FROM policy_review_member WHERE tenant_key=@review_tenant ORDER BY id LIMIT 1);
EXPLAIN SELECT policy_id,version,group_code FROM policy_review_member
WHERE tenant_key=@review_tenant AND user_id=@review_user AND member_status='ACTIVE';
EXPLAIN SELECT id FROM policy_review_delivery
WHERE delivery_status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=CURRENT_TIMESTAMP)
ORDER BY next_attempt_at,lease_until,id LIMIT 50;
