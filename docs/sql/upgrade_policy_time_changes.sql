-- 原单改期增量。前提：MySQL 8.0.16+，现有二十二表工作流库已停写。请在 Navicat 核对所选库后执行。
-- 既有测试单和审核快照保持原样。每段 DDL 独立提交，已完成某段时从下一段继续。
SELECT DATABASE() AS target_database;
SHOW CREATE TABLE policy_apply;

-- 1. 已通过政策可以提前至生效前一秒。草稿和待审单仍校验起止顺序。
ALTER TABLE policy_apply DROP CHECK ck_apply_time,
  ADD CONSTRAINT ck_apply_time CHECK (start_time IS NULL OR end_time IS NULL OR start_time < end_time
    OR (status='APPROVED' AND TIMESTAMPDIFF(SECOND, end_time, start_time) <= 1));

-- 2. 保存今后每次生效的调整。
CREATE TABLE `policy_time_change` (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本次已生效调整的唯一编号',
  policy_id BIGINT NOT NULL COMMENT '调整的原政策',
  tenant_key VARCHAR(64) NOT NULL COMMENT '所属企业',
  policy_version INT UNSIGNED NOT NULL COMMENT '原单获批轮次',
  audit_log_id BIGINT NOT NULL COMMENT '原单获批审核记录',
  after_record_version INT UNSIGNED NOT NULL COMMENT '本次生效后的主表并发版本',
  direction VARCHAR(8) NOT NULL COMMENT 'EXTEND 延期，SHORTEN 提前结束',
  previous_end DATETIME NOT NULL COMMENT '调整前实际截止',
  requested_end DATETIME NOT NULL COMMENT '本次申请截止',
  actual_end DATETIME NOT NULL COMMENT '本次生效后实际截止',
  effective_at DATETIME NOT NULL COMMENT '实际改变适用范围的起点，北京时间',
  changed_at DATETIME NOT NULL COMMENT '本次办理生效时间，北京时间',
  reason VARCHAR(512) NOT NULL COMMENT '调整原因',
  actor_id BIGINT NOT NULL COMMENT '实际办理人',
  actor_name VARCHAR(64) NOT NULL COMMENT '办理时姓名',
  attachment_json JSON NOT NULL COMMENT '本次调整证明及链接',
  consent_json JSON NOT NULL COMMENT '新增时段的已确认采用决定',
  request_key VARCHAR(36) DEFAULT NULL COMMENT '独立批次幂等标识',
  request_hash VARCHAR(64) DEFAULT NULL COMMENT '独立调整的请求摘要',
  source_policy_id BIGINT DEFAULT NULL COMMENT '随单调整的发起政策',
  source_version INT UNSIGNED DEFAULT NULL COMMENT '随单调整的发起轮次',
  PRIMARY KEY (id),
  UNIQUE KEY uk_time_change_version (policy_id,after_record_version),
  UNIQUE KEY uk_time_change_request (tenant_key,actor_id,request_key,policy_id),
  UNIQUE KEY uk_time_change_source (source_policy_id,source_version,policy_id),
  KEY idx_time_change_period (tenant_key,changed_at,id),
  KEY idx_time_change_policy (policy_id,changed_at,id),
  CONSTRAINT fk_time_change_policy FOREIGN KEY (policy_id,tenant_key) REFERENCES policy_apply(id,tenant_key),
  CONSTRAINT fk_time_change_round FOREIGN KEY (policy_id,policy_version) REFERENCES policy_audit_log(policy_id,version),
  CONSTRAINT fk_time_change_audit FOREIGN KEY (audit_log_id) REFERENCES policy_audit_log(id),
  CONSTRAINT fk_time_change_source FOREIGN KEY (source_policy_id,source_version) REFERENCES policy_audit_log(policy_id,version),
  CONSTRAINT fk_time_change_source_tenant FOREIGN KEY (source_policy_id,tenant_key) REFERENCES policy_apply(id,tenant_key),
  CONSTRAINT fk_time_change_actor FOREIGN KEY (actor_id,tenant_key) REFERENCES user_info(id,tenant_key),
  CONSTRAINT ck_time_change_direction CHECK ((direction='EXTEND' AND requested_end>previous_end AND actual_end=requested_end)
    OR (direction='SHORTEN' AND requested_end<previous_end AND actual_end BETWEEN requested_end AND previous_end)),
  CONSTRAINT ck_time_change_source CHECK ((request_key IS NOT NULL AND request_hash IS NOT NULL AND source_policy_id IS NULL AND source_version IS NULL)
    OR (request_key IS NULL AND request_hash IS NULL AND source_policy_id IS NOT NULL AND source_version IS NOT NULL)),
  CONSTRAINT ck_time_change_reason CHECK (CHAR_LENGTH(TRIM(reason))>0),
  CONSTRAINT ck_time_change_json CHECK (JSON_TYPE(attachment_json)='ARRAY' AND JSON_TYPE(consent_json)='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='原政策的时间调整记录及新增时段采用决定';

-- 3. 新表为空，结构应有原单版本、直接请求及随单轮次三个唯一约束。
SHOW CREATE TABLE policy_time_change;
SELECT COUNT(*) AS adjustments_after_upgrade FROM policy_time_change;
