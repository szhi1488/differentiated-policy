-- 在 Navicat 选中目标库执行。本脚本只读，适用于首次建表及政策接口联调后。

-- 预期三张政策表。轮次、商品和同租户账号均有外键，删除规则为 RESTRICT 或 NO ACTION。
SELECT TABLE_NAME, ENGINE
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('policy_apply', 'policy_audit_log', 'policy_goods_detail')
ORDER BY TABLE_NAME;

SELECT k.TABLE_NAME, k.CONSTRAINT_NAME, k.COLUMN_NAME, k.REFERENCED_TABLE_NAME,
       k.REFERENCED_COLUMN_NAME, r.UPDATE_RULE, r.DELETE_RULE
FROM information_schema.KEY_COLUMN_USAGE k
JOIN information_schema.REFERENTIAL_CONSTRAINTS r
  ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME
WHERE k.CONSTRAINT_SCHEMA = DATABASE()
  AND k.TABLE_NAME IN ('policy_apply', 'policy_audit_log', 'policy_goods_detail')
ORDER BY k.TABLE_NAME, k.CONSTRAINT_NAME, k.ORDINAL_POSITION;

-- 核对每单每轮唯一，以及每轮每平台每商品唯一。
SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND INDEX_NAME IN ('uk_policy_audit_round', 'uk_policy_goods_round_platform')
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;

-- 下列异常检查预期均无结果。初始草稿应无提交轮次，其他状态须与最近轮次一致。
SELECT p.id, p.status, p.current_version, r.audit_status, rounds.latest_version
FROM policy_apply p
LEFT JOIN policy_audit_log r ON r.policy_id = p.id AND r.version = p.current_version
LEFT JOIN (
  SELECT policy_id, MAX(version) AS latest_version
  FROM policy_audit_log
  GROUP BY policy_id
) rounds ON rounds.policy_id = p.id
WHERE p.current_version <> COALESCE(rounds.latest_version, 0)
   OR (p.current_version > 0
       AND NOT (r.audit_status <=> CASE WHEN p.status = 'PENDING' THEN 'SUBMITTED' ELSE p.status END));

SELECT r.policy_id, r.version
FROM policy_audit_log r
LEFT JOIN policy_goods_detail d ON d.policy_id = r.policy_id AND d.version = r.version
WHERE d.id IS NULL;

SELECT d.id, d.policy_id, d.version, d.goods_id
FROM policy_goods_detail d
LEFT JOIN policy_audit_log r ON r.policy_id = d.policy_id AND r.version = d.version
LEFT JOIN goods g ON g.id = d.goods_id AND g.spu_id = d.spu_id AND g.ram = d.ram AND g.rom = d.rom
WHERE r.id IS NULL OR g.id IS NULL;

SELECT policy_id, version, platform_code, goods_id, COUNT(*) AS copies
FROM policy_goods_detail
GROUP BY policy_id, version, platform_code, goods_id
HAVING COUNT(*) > 1;

-- 有联调数据时将示例值替换为实际商品和平台，检查引用和候选范围查询的访问路径。
EXPLAIN SELECT 1 FROM policy_goods_detail WHERE goods_id = 2001;

EXPLAIN SELECT d.policy_id, d.version
FROM policy_goods_detail d
JOIN policy_apply p ON p.id = d.policy_id AND p.current_version = d.version
WHERE d.goods_id = 2001 AND d.platform_code = 'PDD'
  AND p.status = 'APPROVED' AND p.is_deleted = 0;
