# 覆盖提示与政策范围交集

更新日期：2026-11-13。标准规格及颜色范围已确认；优先级、时间端点、正式替换和最新生效算法仍待确认，尚未实现。原按名称快照和 NULL 颜色精确分组的示例已撤下，避免与确认版范围表达冲突。

## 1. 已确认的身份与颜色交集

政策按 tenant_key、渠道、平台、spu_id、ram、rom 定位范围，名称用于历史快照。不同型号或不同配置使用不同的机型配置键，不因颜色同名而重叠。

同规格的颜色交集如下：

| 新旧范围 | 是否有颜色交集 |
| --- | --- |
| ALL_COLORS 与 ALL_COLORS | 有，当前规格不限颜色 |
| ALL_COLORS 与 SPECIFIC_COLOR | 有，指定的那个颜色 |
| SPECIFIC_COLOR 与同一个 goods_id | 有，该颜色 |
| SPECIFIC_COLOR 与不同 goods_id | 无 |
| 任意范围未选择或指定颜色未补齐 | 草稿不完整，不能给确定结论 |

ALL_COLORS 不使用颜色 NULL 推断，也不按当前颜色列表展开。政策提交后新增颜色是否自动适用既有不限颜色政策仍需确认；结构能表达不限颜色范围，但不能把目录新增颜色当作已确认的政策扩展或覆盖行为。

指定颜色引用的商品必须属于当前配置，后端另校验目录状态为 ACTIVE 并生成快照。停用商品后，已提交政策仍按快照回读。同平台同申请的重复及不限/指定交集应在提交前提示整理；不同申请之间出现交集须在审核候选中展示。

## 2. 候选查询草案

以下仅展示待审明细与已通过政策的候选交集，采用尚未确认的半开时间区间 [start, end) 作演示。参数由后端绑定，不是能直接粘贴 Navicat 执行的脚本，也不能据此确定谁生效。


```sql
SELECT m.id AS policy_id, m.policy_code, m.status,
       m.start_time, m.end_time, m.review_time,
       d.id AS detail_id, d.platform_code, d.spu_id, d.ram, d.rom,
       d.color_scope, d.goods_id, d.color,
       d.model_full_name, d.single_model, d.spec,
       d.subsidy_mode, d.fixed_amount, d.max_subsidy_amount,
       d.subsidy_ratio, d.base_price_type
FROM policy_master m
JOIN policy_detail d
  ON d.policy_id = m.id
WHERE m.tenant_key = :tenantKey
  AND m.is_deleted = 0
  AND m.id <> :newPolicyId
  AND m.status = 'APPROVED'
  AND m.perspective = :perspective
  AND (m.verify_type <=> :verifyType)
  AND (m.scene_type <=> :sceneType)
  AND (m.is_estimated <=> :estimated)
  AND d.platform_code = :platformCode
  AND d.spu_id = :spuId
  AND d.ram = :ram
  AND d.rom = :rom
  AND d.color_scope IS NOT NULL
  AND (d.color_scope = 'ALL_COLORS' OR d.goods_id IS NOT NULL)
  AND (:newColorScope = 'ALL_COLORS'
       OR d.color_scope = 'ALL_COLORS'
       OR (d.color_scope = 'SPECIFIC_COLOR'
           AND d.goods_id = :newGoodsId))
  AND m.start_time < :newEndTime
  AND :newStartTime < m.end_time;
```

示例暂假设核销、场景、预估参与匹配，须在规则确认后定稿。查询前验证待审规格、颜色范围、时间和版本；查询不能把缺失参数当作无交集。主表租户直接过滤，不再借申请人当前状态推导政策租户。

## 3. 审核与生效边界

候选返回新旧政策、平台、机型 ID、RAM 和 ROM、颜色范围/具体颜色、快照、补贴和时间交集。区分当前时间内与未来重叠，标注 CANDIDATE 或 UNCONFIRMED；没有最终规则时不显示“已确定无覆盖”。

不限颜色与某颜色相交不意味着整条不限颜色政策失效，更不意味着同政策其他平台或规格失效。不能默认具体颜色优先，不能按审核时间或创建时间擅自宣布胜出，也不能删除旧记录实现覆盖。

正式审核需重新校验状态、提交/记录版本和交集；同一政策乐观锁不足以防两个不同政策同时审批，需要最终匹配范围的串行化方案。规则尚未确认时，不开放声称最终生效的通过替换闭环；拒绝和历史展示独立实现。

待确认：时间端点、最新/优先级、核销场景预估是否参与、相同优先级、局部颜色及时间替换、新政策结束后旧政策是否恢复、正式生效时点。确认后再实现最新生效查询与并发测试。

关联：[状态机](status-machine.md)、[审核接口](../api/admin-api.md)、[数据库字典](../sql/database-design.md)。
