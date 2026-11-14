# 覆盖提示与政策范围交集

更新日期：2026-11-14。适用商品按实际选中的商品 ID 确定。覆盖优先级、时间端点及最终生效规则待确认。

## 1. 商品范围交集

同一租户、渠道及平台下，按 `goods_id` 判断商品范围。普通卡和指定卡使用同一套匹配规则。同配置黑色与白色为不同商品，分别选中才各自适用。当前轮次同一平台重复选择同一商品时，提交前须提示整理。来自不同申请的同平台同商品进入覆盖候选。

## 2. 候选查询草案

以下仅展示待审明细与已通过政策的候选交集，采用尚未确认的半开时间区间 [start, end) 作演示。参数由后端绑定，不是能直接粘贴 Navicat 执行的脚本，也不能据此确定谁生效。


```sql
SELECT m.id AS policy_id, m.policy_no, m.status,
       m.start_time, m.end_time, a.audit_time,
       a.id AS audit_id, a.version,
       d.id AS detail_id, d.platform_code, d.spu_id, d.ram, d.rom,
       d.goods_id
FROM policy_apply m
JOIN policy_audit_log a
  ON a.policy_id = m.id AND a.version = m.current_version
JOIN policy_goods_detail d
  ON d.policy_id = a.policy_id AND d.version = a.version
WHERE m.tenant_key = :tenantKey
  AND m.is_deleted = 0
  AND m.id <> :newPolicyId
  AND m.status = 'APPROVED'
  AND a.audit_status = 'APPROVED'
  AND m.channel_type = :perspective
  AND (m.write_off_type <=> :verifyType)
  AND (m.scene <=> :sceneType)
  AND (m.is_estimated <=> :estimated)
  AND d.platform_code = :platformCode
  AND d.goods_id = :goodsId
  AND m.start_time < :newEndTime
  AND :newStartTime < m.end_time;
```

示例暂假设核销、场景、预估参与匹配，须在规则确认后定稿。查询前验证待审商品、平台、时间和版本；查询不能把缺失参数当作无交集。主表按会话租户过滤。候选关联相同提交轮次，名称、数量和补贴按平台与商品范围从本轮 `snapshot_data` 读取。

## 3. 审核与生效边界

候选返回新旧政策、平台、机型 ID、RAM 和 ROM、商品 ID、提交时的名称和颜色、补贴及时间交集。区分当前时间内与未来重叠，标注 CANDIDATE 或 UNCONFIRMED；没有最终规则时不显示“已确定无覆盖”。

覆盖候选以实际相交的平台、商品和时间为单位。优先级、替换范围及旧政策恢复按正式业务规则判定，保留历史记录。

正式审核需重新校验状态、提交/记录版本和交集；同一政策乐观锁不足以防两个不同政策同时审批，需要最终匹配范围的串行化方案。规则尚未确认时，不开放声称最终生效的通过替换闭环；拒绝和历史展示独立实现。

待确认：时间端点、最新/优先级、核销场景预估是否参与、相同优先级、局部商品及时间替换、新政策结束后旧政策是否恢复、正式生效时点。确认后再实现最新生效查询与并发测试。

关联：[状态机](status-machine.md)、[审核接口](../api/admin-api.md)、[数据库字典](../sql/database-design.md)。
