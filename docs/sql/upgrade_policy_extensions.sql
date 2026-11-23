-- 已有项目库在 Navicat 中停写、备份后执行一次。空库使用最新建表基线。
SELECT DATABASE() AS target_database, VERSION() AS mysql_version;
SET @policies_before = (SELECT COUNT(*) FROM policy_apply);

-- 一张已通过单最多直接承接一张延期单，后续可从已通过的延期单继续延长。
ALTER TABLE policy_apply
  ADD COLUMN extension_source_id BIGINT DEFAULT NULL COMMENT '本次延期承接的已通过政策 ID' AFTER policy_no,
  ADD UNIQUE KEY uk_apply_extension_source (extension_source_id, tenant_key),
  ADD CONSTRAINT fk_apply_extension_source FOREIGN KEY (extension_source_id, tenant_key)
    REFERENCES policy_apply (id, tenant_key);

-- 升级后新旧行数应相等，首次升级已有延期数应为 0。
SELECT @policies_before AS policies_before, COUNT(*) AS policies_after,
       COUNT(extension_source_id) AS extensions_after FROM policy_apply;
SHOW CREATE TABLE policy_apply;

-- 预期为 0。起点按创建延期时已获批的实际截止核对；同秒内有并发审批时容许两种提交顺序。
WITH approved_cuts AS (
  SELECT a.tenant_key, c.policy_id, a.audit_time,
         GREATEST(STR_TO_DATE(LEFT(c.end_time, 19), '%Y-%m-%dT%H:%i:%s'),
                  DATE_SUB(a.audit_time, INTERVAL 1 SECOND)) AS actual_end
  FROM policy_audit_log AS a
  JOIN policy_apply AS p ON p.id = a.policy_id AND p.tenant_key = a.tenant_key AND p.current_version = a.version
  JOIN JSON_TABLE(a.snapshot_data, '$.consent.endAdjustments[*]' COLUMNS (
       policy_id BIGINT PATH '$.target.policyId', end_time VARCHAR(32) PATH '$.endTime')) AS c ON TRUE
  WHERE p.status = 'APPROVED' AND p.is_deleted = 0 AND a.audit_status = 'APPROVED'
),
extensions AS (
  SELECT child.*, source.id AS source_id, source.applicant_id AS source_applicant,
         source.status AS source_status, source.is_deleted AS source_deleted,
         CASE WHEN source.extension_source_id IS NULL THEN TIMESTAMP(DATE(source.end_time), '23:59:59')
              ELSE source.end_time END AS original_end,
         DATE_SUB(child.start_time, INTERVAL 1 SECOND) AS prior_second
  FROM policy_apply AS child
  LEFT JOIN policy_apply AS source ON source.id = child.extension_source_id AND source.tenant_key = child.tenant_key
  WHERE child.extension_source_id IS NOT NULL AND child.is_deleted = 0
)
SELECT COUNT(*) AS invalid_extensions
FROM extensions AS e
WHERE e.source_id IS NULL OR e.applicant_id <> e.source_applicant OR e.source_deleted <> 0
   OR e.source_status <> 'APPROVED' OR e.original_end IS NULL OR e.prior_second IS NULL
   OR e.prior_second > e.original_end
   OR EXISTS (SELECT 1 FROM approved_cuts c WHERE c.tenant_key=e.tenant_key AND c.policy_id=e.source_id
              AND c.audit_time < e.created_at AND c.actual_end < e.prior_second)
   OR (e.prior_second <> e.original_end AND NOT EXISTS (
         SELECT 1 FROM approved_cuts c WHERE c.tenant_key=e.tenant_key AND c.policy_id=e.source_id
           AND c.audit_time <= e.created_at AND c.actual_end=e.prior_second));

-- 可用一条原单样本检查延期查询的索引候选。在不同浏览器同时发起同一截止时刻的延期后，
-- 把下面的样本换成测试原单，核对 direct_extensions 为 1，两个窗口返回相同新单号。
SELECT id, tenant_key, applicant_id INTO @source_id, @source_tenant, @source_applicant
FROM policy_apply WHERE status = 'APPROVED' AND is_deleted = 0 ORDER BY id LIMIT 1;
EXPLAIN SELECT id FROM policy_apply
WHERE extension_source_id = @source_id AND tenant_key = @source_tenant
  AND applicant_id = @source_applicant AND is_deleted = 0;
SELECT COUNT(*) AS direct_extensions FROM policy_apply
WHERE extension_source_id = @source_id AND tenant_key = @source_tenant
  AND applicant_id = @source_applicant AND is_deleted = 0;
