# 覆盖提示与最新有效政策查询草案

更新日期：2026-11-10。本文提供“按机型、平台取最新有效政策”的查询设计及 SQL 示例，尚未接入后端，也未在真实 MySQL 执行。本次不修改旧政策、不实施自动替换。

## 已明确的要求

管理员审核时，若新政策将覆盖已生效补贴，必须提示具体机型。提示至少定位旧政策、机型、平台，并展示新旧补贴方式、参数和有关时间范围。不同平台不能仅因手机名称相同而被整单覆盖；保留原政策与审核历史。

“审核通过”“处于有效时间内”“在该平台机型范围最终被选用”是三个判断，不能只查 `APPROVED` 就宣布生效。正式审批通过时须重新检测，不能只依赖打开审核页时的结果。

## SQL 示例采用的暂定口径

为让查询可评审，下面显式采用以下示例假设，均不据此新增已确认的业务规则：

- 时间区间暂用 `[start_time, end_time)`，在查询时点开始已到且结束未到。
- “最新”暂按审核通过时间 `review_time` 降序，而不是创建/提交时间；相同审核时间用主表 ID、明细 ID 确保排序稳定。技术排序不能代替业务同优先级规则。
- 暂按渠道视角、平台、型号全称/单型号/配置快照、颜色精确匹配，零售核销类型、场景、预估标记也分组；`NULL` 颜色只与 `NULL` 相同，不作通配。
- 查询必须绑定服务端核验的租户；当前主表没有租户列，借申请人的 `user_info.tenant_key` 限定。申请人停用不等于其历史政策自动失效，不能据此自动撤销历史政策。
- 查询仅“读时选择”，不更新旧记录、不推断部分覆盖后的时间切片。若新政策结束后旧政策仍在期限内，本示例可能重新选回旧政策；是否允许这种恢复必须另行确认。

机型稳定主键尚未核对，因此只能展示基于现有快照字段的 SQL 原型。名称一致未必是同一主数据，名称变化也可能是同一机型；正式引擎必须改为已确认的稳定标识与匹配维度，不得把此原型当生产唯一键算法。

## 最新有效政策查询 SQL

面向 MySQL 8.0.16+，使用窗口函数。`:tenantKey`、`:platformCode`、`:atTime` 为文档参数占位符，由实现层安全绑定；不是可直接粘贴 Navicat 的完整脚本，也不是 MyBatis 的最终语法。`atTime` 按北京时间秒级传入，不能由客户端任意指定租户。

```sql
WITH ranked AS (
    SELECT
        m.id AS policy_id,
        m.policy_code,
        m.perspective,
        m.verify_type,
        m.scene_type,
        m.is_estimated,
        m.start_time,
        m.end_time,
        m.review_time,
        m.submission_version,
        d.id AS detail_id,
        d.platform_code,
        d.model_full_name,
        d.single_model,
        d.spec,
        d.color,
        d.quantity,
        d.subsidy_mode,
        d.fixed_amount,
        d.max_subsidy_amount,
        d.subsidy_ratio,
        d.base_price_type,
        ROW_NUMBER() OVER (
            PARTITION BY
                m.perspective,
                d.platform_code,
                CAST(d.model_full_name AS BINARY),
                CAST(d.single_model AS BINARY),
                CAST(d.spec AS BINARY),
                CAST(d.color AS BINARY),
                m.verify_type,
                m.scene_type,
                m.is_estimated
            ORDER BY m.review_time DESC, m.id DESC, d.id DESC
        ) AS selection_rank
    FROM policy_master m
    JOIN policy_detail d ON d.policy_id = m.id
    JOIN user_info applicant ON applicant.id = m.applicant_id
    WHERE m.is_deleted = 0
      AND applicant.tenant_key = :tenantKey
      AND m.status = 'APPROVED'
      AND m.review_time IS NOT NULL
      AND m.review_time <= :atTime
      AND m.start_time <= :atTime
      AND :atTime < m.end_time
      AND d.platform_code = :platformCode
      AND d.model_full_name IS NOT NULL
      AND d.single_model IS NOT NULL
      AND d.spec IS NOT NULL
      AND JSON_CONTAINS(
          m.selected_platforms, JSON_QUOTE(d.platform_code), '$'
      ) = 1
)
SELECT *
FROM ranked
WHERE selection_rank = 1;
```

按字节分组避免默认数据库排序规则把不同快照文本折叠，但仍不能解决主数据身份问题。当前 SQL 没有完备的正式提交校验；上线前须保证已通过记录的字段、平台与互斥参数有效，遇到脏数据应报告异常。重复同平台机型明细应在提交时拒绝，不能依赖上述 `d.id` 排序静默消解。

该查询只给出单一时点的候选选用结果，不能直接返回“新政策未来整段会覆盖哪些历史政策”，也不能重建任意历史时点的数据库状态；历史查询应结合不可变提交快照和审核日志设计。

## 审核覆盖预览

1. 校验管理员、申请租户、`recordVersion` 和 `submissionVersion`，读取当前待审快照。
2. 按具体平台逐明细找候选旧政策；渠道、机型和颜色等使用最终确认的维度。
3. 示例半开区间的时间重叠条件为 `old.start_time < new.end_time AND new.start_time < old.end_time`。若选择其他端点规则，需同步修改。
4. 区分当前有效和未来重叠候选，不能把未来政策标成“目前已生效”。候选全部展示，不用 `ROW_NUMBER = 1` 提前隐藏潜在受影响旧政策。
5. 输出旧/新政策 ID、具体明细、平台、型号、时间、补贴方式、固定金额/上限或比例/基数及候选相交区间。

补贴数值比较使用精确十进制，`NULL` 不等于 0；同平台同机型但参数相同是否算覆盖也待确认。现阶段预览应标记“候选影响，待确认口径”，不能返回确定失效结论。

## 正式审批与待确认项

正式实现需在审核事务中重新读取候选并执行已确认规则。单申请 `record_version` 只防止同一单重复处理，不能防止两张新申请同时审核后相互影响；还须设计按匹配范围串行化或锁定策略，不能声称一个条件更新已保证全局覆盖一致性。

上线前确认：稳定机型 ID、配置/颜色维度、空颜色是否通配、核销/场景/预估是否参与、端点、“最新”时点及平级处理、旧政策恢复、局部替换与生效时点、是否二次确认。确认后补齐服务端实现与时间边界、并发、局部平台不受影响的测试，再提供确定的生效查询接口。

关联文档：[状态机](status-machine.md)、[管理员接口](../api/admin-api.md)、[数据字典](../sql/database-design.md)。
