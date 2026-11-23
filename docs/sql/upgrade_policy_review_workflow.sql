-- 已有十五表项目库增量升级，MySQL 8.0.16+。在 Navicat 停写、备份、选库，同一连接执行。
-- 空库使用 schema.sql 或 V1__create_policy_tables.sql。先读 database-design.md 的「已有库升级政策审核工作流」。
-- 遇错停止。此前成功的 DDL 已提交；重新连接时恢复核对基准的方法见文档。
SET NAMES utf8mb4;
SET time_zone = '+08:00';
SELECT DATABASE() AS target_database, VERSION() AS mysql_version;

-- 1. 建立本连接的逐行核对底稿。首次执行前应看到零个工作流表、零个 workflow_version 列。
SELECT table_name FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name LIKE 'policy!_review!_%' ESCAPE '!';
SELECT column_type, column_default FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='policy_audit_log' AND column_name='workflow_version';
-- 临时表只保存标识和摘要。升级前另外导出备份，关闭查询连接会丢失临时表。
CREATE TEMPORARY TABLE review_verify_apply (id BIGINT PRIMARY KEY, digest CHAR(64) NOT NULL);
INSERT INTO review_verify_apply (id,digest)
SELECT id, SHA2(CAST(JSON_ARRAY(policy_no,extension_source_id,tenant_key,applicant_id,applicant_name,
  channel_type,platforms,write_off_type,scene_types,is_estimated,start_time,end_time,remark,
  attachment_urls,goods_form_json,status,current_version,record_version,is_deleted,created_at,updated_at) AS CHAR),256) AS digest
FROM policy_apply;
CREATE TEMPORARY TABLE review_verify_audit (id BIGINT PRIMARY KEY, digest CHAR(64) NOT NULL);
INSERT INTO review_verify_audit (id,digest)
SELECT id, SHA2(CAST(JSON_ARRAY(policy_id,tenant_key,version,snapshot_data,audit_status,auditor_id,
  auditor_name,audit_comment,audit_time,created_at) AS CHAR),256) AS digest FROM policy_audit_log;
CREATE TEMPORARY TABLE review_verify_detail (id BIGINT PRIMARY KEY, digest CHAR(64) NOT NULL);
INSERT INTO review_verify_detail (id,digest)
SELECT id, SHA2(CAST(JSON_ARRAY(policy_id,version,platform_code,spu_id,ram,rom,goods_id,created_at) AS CHAR),256) AS digest
FROM policy_goods_detail;
SELECT (SELECT COUNT(*) FROM review_verify_apply) AS applications_before,
       (SELECT COUNT(*) FROM review_verify_audit) AS audits_before,
       (SELECT COUNT(*) FROM review_verify_detail) AS details_before;

-- 2. 已有轮次通过加列时的常量默认值一次标为旧路径，不修改已有快照与审核结果。
-- MySQL 保留当时各行的 0。下方所有步骤均不使用 UPDATE 重标业务记录。
ALTER TABLE policy_audit_log
  ADD COLUMN workflow_version TINYINT NOT NULL DEFAULT 0 COMMENT '0 旧采购路径，1 按轮次节点办理' AFTER snapshot_data,
  ADD CONSTRAINT ck_policy_audit_workflow CHECK (workflow_version IN (0,1));

-- 3. 按依赖顺序建立七张表。中断后以 SHOW CREATE TABLE 对照已成功的语句，从第一张缺失表继续。
CREATE TABLE policy_review_settings (
  tenant_key VARCHAR(64) NOT NULL COMMENT '当前企业',
  version INT NOT NULL DEFAULT 0 COMMENT '每次成功保存递增，未配置为 0',
  updated_by BIGINT DEFAULT NULL COMMENT '最近一次保存的管理员',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次保存时间',
  PRIMARY KEY (tenant_key),
  CONSTRAINT ck_review_settings_version CHECK (version >= 0),
  CONSTRAINT fk_review_settings_editor FOREIGN KEY (updated_by, tenant_key) REFERENCES user_info (id, tenant_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='企业政策审核设置';

CREATE TABLE policy_review_setting_member (
  tenant_key VARCHAR(64) NOT NULL COMMENT '企业',
  group_code VARCHAR(16) NOT NULL COMMENT 'PRODUCT 产品运营，PURCHASE 采购',
  user_id BIGINT NOT NULL COMMENT '本地人员 ID',
  PRIMARY KEY (tenant_key, group_code, user_id),
  CONSTRAINT ck_review_setting_group CHECK (group_code IN ('PRODUCT','PURCHASE')),
  CONSTRAINT fk_review_setting_parent FOREIGN KEY (tenant_key) REFERENCES policy_review_settings (tenant_key),
  CONSTRAINT fk_review_setting_user FOREIGN KEY (user_id, tenant_key) REFERENCES user_info (id, tenant_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='企业当前两组审核名单';

CREATE TABLE policy_review_round (
  policy_id BIGINT NOT NULL COMMENT '申请 ID',
  version INT UNSIGNED NOT NULL COMMENT '正式提交轮次',
  tenant_key VARCHAR(64) NOT NULL COMMENT '申请所属企业',
  settings_version INT NOT NULL COMMENT '提交时读取的名单配置版本',
  stage VARCHAR(24) NOT NULL COMMENT '产品运营初审、采购终审或已结束',
  route_json JSON NOT NULL COMMENT '提交时冻结的整单路由、原拟执行名单、触发段和摘要',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '冻结时间',
  PRIMARY KEY (policy_id, version),
  UNIQUE KEY uk_review_round_tenant (policy_id, version, tenant_key),
  KEY idx_review_round_stage (tenant_key, stage, created_at),
  CONSTRAINT ck_review_round_settings CHECK (settings_version > 0),
  CONSTRAINT ck_review_round_stage CHECK (stage IN ('PRODUCT_REVIEW','PURCHASE_REVIEW','FINISHED')),
  CONSTRAINT ck_review_round_route CHECK (JSON_TYPE(route_json) = 'OBJECT'),
  CONSTRAINT fk_review_round_audit FOREIGN KEY (policy_id, version) REFERENCES policy_audit_log (policy_id, version),
  CONSTRAINT fk_review_round_tenant FOREIGN KEY (policy_id, tenant_key) REFERENCES policy_apply (id, tenant_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='本轮审核阶段与冻结分流依据';

CREATE TABLE policy_review_member (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本轮的一次个人指派',
  policy_id BIGINT NOT NULL COMMENT '申请 ID',
  version INT UNSIGNED NOT NULL COMMENT '正式提交轮次',
  tenant_key VARCHAR(64) NOT NULL COMMENT '企业',
  group_code VARCHAR(16) NOT NULL COMMENT 'PRODUCT 初审、PURCHASE 终审、REVISION 原申请人修改',
  user_id BIGINT NOT NULL COMMENT '本地人员 ID',
  display_name VARCHAR(64) NOT NULL COMMENT '指派时的姓名',
  department VARCHAR(128) DEFAULT NULL COMMENT '指派时的部门',
  assignment_no INT NOT NULL DEFAULT 1 COMMENT '同人同组再次加入时递增',
  member_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '有效、移除、本人办理或同组他人处理',
  task_guid VARCHAR(128) DEFAULT NULL COMMENT '本次指派的飞书待办 GUID',
  active_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN member_status = 'ACTIVE' THEN user_id ELSE NULL END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_review_member_assignment (policy_id, version, group_code, user_id, assignment_no),
  UNIQUE KEY uk_review_member_active (policy_id, version, group_code, active_user_id),
  UNIQUE KEY uk_review_member_group (id, policy_id, version, tenant_key, group_code),
  UNIQUE KEY uk_review_member_round (id, policy_id, version, tenant_key),
  UNIQUE KEY uk_review_member_task (tenant_key, task_guid),
  KEY idx_review_person_work (tenant_key, user_id, member_status, group_code, policy_id, version),
  CONSTRAINT ck_review_member_group CHECK (group_code IN ('PRODUCT','PURCHASE','REVISION')),
  CONSTRAINT ck_review_member_status CHECK (member_status IN ('ACTIVE','REMOVED','HANDLED','PEER_HANDLED')),
  CONSTRAINT ck_review_member_sequence CHECK (assignment_no > 0),
  CONSTRAINT fk_review_member_round FOREIGN KEY (policy_id, version, tenant_key) REFERENCES policy_review_round (policy_id, version, tenant_key),
  CONSTRAINT fk_review_member_user FOREIGN KEY (user_id, tenant_key) REFERENCES user_info (id, tenant_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='按轮按节点冻结和改派的个人成员';

CREATE TABLE policy_review_decision (
  policy_id BIGINT NOT NULL COMMENT '申请 ID',
  version INT UNSIGNED NOT NULL COMMENT '正式提交轮次',
  group_code VARCHAR(16) NOT NULL COMMENT '已决节点',
  tenant_key VARCHAR(64) NOT NULL COMMENT '企业',
  member_id BIGINT NOT NULL COMMENT '真正作出决定的指派 ID',
  decision VARCHAR(16) NOT NULL COMMENT 'APPROVE 同意，REJECT 退回',
  reason VARCHAR(512) DEFAULT NULL COMMENT '初审退回必填，采购驳回选填',
  decided_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'ERP 决策时间',
  PRIMARY KEY (policy_id, version, group_code),
  CONSTRAINT ck_review_decision_group CHECK (group_code IN ('PRODUCT','PURCHASE')),
  CONSTRAINT ck_review_decision CHECK (decision IN ('APPROVE','REJECT')),
  CONSTRAINT ck_review_rejection_reason CHECK (group_code <> 'PRODUCT' OR decision <> 'REJECT'
    OR (reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0)),
  CONSTRAINT fk_review_decision_member FOREIGN KEY (member_id, policy_id, version, tenant_key, group_code)
    REFERENCES policy_review_member (id, policy_id, version, tenant_key, group_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='每轮每节点首个成功决定';

CREATE TABLE policy_review_reassignment (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '改派操作记录',
  policy_id BIGINT NOT NULL COMMENT '申请 ID',
  version INT UNSIGNED NOT NULL COMMENT '正式提交轮次',
  tenant_key VARCHAR(64) NOT NULL COMMENT '企业',
  group_code VARCHAR(16) NOT NULL COMMENT '改派的节点',
  operator_id BIGINT NOT NULL COMMENT '执行改派的管理员',
  reason VARCHAR(512) NOT NULL COMMENT '改派原因',
  before_members JSON NOT NULL COMMENT '改派前的成员指派及稳定 ID',
  after_members JSON NOT NULL COMMENT '改派后的成员指派及稳定 ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '改派时间',
  PRIMARY KEY (id),
  KEY idx_review_reassign_round (policy_id, version, tenant_key, group_code, id),
  CONSTRAINT fk_review_reassign_round FOREIGN KEY (policy_id, version, tenant_key) REFERENCES policy_review_round (policy_id, version, tenant_key),
  CONSTRAINT fk_review_reassign_operator FOREIGN KEY (operator_id, tenant_key) REFERENCES user_info (id, tenant_key),
  CONSTRAINT ck_review_reassign_group CHECK (group_code IN ('PRODUCT','PURCHASE')),
  CONSTRAINT ck_review_reassign_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0),
  CONSTRAINT ck_review_reassign_members CHECK (JSON_TYPE(before_members) = 'ARRAY' AND JSON_TYPE(after_members) = 'ARRAY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='在途节点改派的前后记录';

CREATE TABLE policy_review_delivery (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '投递动作 ID',
  tenant_key VARCHAR(64) NOT NULL COMMENT '企业',
  policy_id BIGINT NOT NULL COMMENT '申请 ID',
  version INT UNSIGNED NOT NULL COMMENT '正式提交轮次',
  event_key VARCHAR(128) NOT NULL COMMENT '轮次内稳定的业务事件标识',
  action_type VARCHAR(16) NOT NULL COMMENT '群消息、创建个人任务或结办个人任务',
  recipient_key BIGINT NOT NULL COMMENT '群为 0，个人动作取本轮成员指派 ID',
  recipient_member_id BIGINT GENERATED ALWAYS AS (NULLIF(recipient_key, 0)) STORED,
  payload_json JSON NOT NULL COMMENT '本次动作所需的业务字段',
  delivery_status VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT '投递状态',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '业务事务登记时间',
  attempts INT NOT NULL DEFAULT 0 COMMENT '已领取投递次数',
  next_attempt_at DATETIME DEFAULT NULL COMMENT '最早可重试时间',
  lease_owner VARCHAR(128) DEFAULT NULL COMMENT '领取本动作的执行者',
  lease_until DATETIME DEFAULT NULL COMMENT '占用截止时间',
  receipt_id VARCHAR(128) DEFAULT NULL COMMENT '飞书消息或任务回执',
  delivered_at DATETIME DEFAULT NULL COMMENT '确认送达时间',
  last_error VARCHAR(512) DEFAULT NULL COMMENT '最近脱敏错误说明',
  PRIMARY KEY (id),
  UNIQUE KEY uk_review_delivery_event (tenant_key, policy_id, version, event_key, action_type, recipient_key),
  KEY idx_review_delivery_due (delivery_status, next_attempt_at, lease_until, id),
  CONSTRAINT fk_review_delivery_round FOREIGN KEY (policy_id, version, tenant_key) REFERENCES policy_review_round (policy_id, version, tenant_key),
  CONSTRAINT fk_review_delivery_member FOREIGN KEY (recipient_member_id, policy_id, version, tenant_key)
    REFERENCES policy_review_member (id, policy_id, version, tenant_key),
  CONSTRAINT ck_review_delivery_event CHECK (CHAR_LENGTH(TRIM(event_key)) > 0),
  CONSTRAINT ck_review_delivery_recipient CHECK ((action_type = 'GROUP_MESSAGE' AND recipient_key = 0)
    OR (action_type IN ('CREATE_TASK','CLOSE_TASK') AND recipient_key > 0)),
  CONSTRAINT ck_review_delivery_status CHECK (delivery_status IN ('PENDING','SENDING','SENT','RETRY','UNKNOWN','FAILED','CANCELLED')),
  CONSTRAINT ck_review_delivery_attempts CHECK (attempts >= 0),
  CONSTRAINT ck_review_delivery_payload CHECK (JSON_TYPE(payload_json) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='业务事务内登记的逐项群消息及待办动作';

-- 4. 只切换今后省略此列的默认值。新版服务对尚未启用的轮次显式写 0。
ALTER TABLE policy_audit_log ALTER COLUMN workflow_version SET DEFAULT 1;

-- 5. 同一连接中按主键逐行核对。三项差异及三项新增均为 0；旧轮次中非 0 标记数为 0。
SELECT
 (SELECT COUNT(*) FROM review_verify_apply b LEFT JOIN policy_apply p ON p.id=b.id
  WHERE p.id IS NULL OR b.digest<>SHA2(CAST(JSON_ARRAY(p.policy_no,p.extension_source_id,p.tenant_key,p.applicant_id,p.applicant_name,
    p.channel_type,p.platforms,p.write_off_type,p.scene_types,p.is_estimated,p.start_time,p.end_time,p.remark,
    p.attachment_urls,p.goods_form_json,p.status,p.current_version,p.record_version,p.is_deleted,p.created_at,p.updated_at) AS CHAR),256)) AS changed_or_missing_applications,
 (SELECT COUNT(*) FROM review_verify_audit b LEFT JOIN policy_audit_log a ON a.id=b.id
  WHERE a.id IS NULL OR b.digest<>SHA2(CAST(JSON_ARRAY(a.policy_id,a.tenant_key,a.version,a.snapshot_data,a.audit_status,
    a.auditor_id,a.auditor_name,a.audit_comment,a.audit_time,a.created_at) AS CHAR),256)) AS changed_or_missing_audits,
 (SELECT COUNT(*) FROM review_verify_detail b LEFT JOIN policy_goods_detail d ON d.id=b.id
  WHERE d.id IS NULL OR b.digest<>SHA2(CAST(JSON_ARRAY(d.policy_id,d.version,d.platform_code,d.spu_id,d.ram,d.rom,d.goods_id,d.created_at) AS CHAR),256)) AS changed_or_missing_details;
SELECT
 (SELECT COUNT(*) FROM policy_apply p LEFT JOIN review_verify_apply b ON b.id=p.id WHERE b.id IS NULL) AS added_applications,
 (SELECT COUNT(*) FROM policy_audit_log a LEFT JOIN review_verify_audit b ON b.id=a.id WHERE b.id IS NULL) AS added_audits,
 (SELECT COUNT(*) FROM policy_goods_detail d LEFT JOIN review_verify_detail b ON b.id=d.id WHERE b.id IS NULL) AS added_details;
SELECT
 (SELECT COUNT(*) FROM policy_audit_log a JOIN review_verify_audit b ON b.id=a.id WHERE a.workflow_version<>0) AS wrongly_marked_legacy_rounds;
-- 6. 在同一目标库执行 verify_policy_review_workflow.sql，核对结构与完整性，新表应为空。
