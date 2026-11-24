import { appendTimelinePart, timelineGoodsLabel } from './subsidy-timeline'
import { subsidySignature } from './policy-coverage'

// 百亿按配置审核。每一段只按原单补贴项实际填报的商品拆行，不重算采用名单。
function selectionGroups(plans, signatureOf) {
  const members = new Map()
  const unspecified = []
  for (const plan of plans) {
    if (!plan.goods.length) {
      unspecified.push(plan)
    }
    for (const product of plan.goods) {
      if (!members.has(product.id)) {
        members.set(product.id, { product, plans: new Set() })
      }
      members.get(product.id).plans.add(plan)
    }
  }
  const groups = new Map()
  for (const { product, plans: selections } of members.values()) {
    const signature = JSON.stringify([...selections].map(signatureOf).sort())
    if (!groups.has(signature)) {
      groups.set(signature, { goods: [], plans: new Map() })
    }
    const group = groups.get(signature)
    group.goods.push(product)
    for (const plan of selections) {
      // 同轮不同普通卡可共用同一补贴。图行关联其中第一张原卡，明细保留全部原卡。
      const key = signatureOf(plan)
      const first = group.plans.get(key)
      if (!first || plan.selectionIndex < first.selectionIndex) {
        group.plans.set(key, plan)
      }
    }
  }
  const result = [...groups.values()].map(({ goods, plans }) => ({
    goods,
    plans: [...plans.values()],
  }))
  if (unspecified.length) {
    result.push({ goods: [], plans: unspecified })
  }
  return result
}

export function billionDisplayHistory(source, configurationGoodsCount) {
  const segments = []
  const rows = new Map()
  const scopes = new Map()
  const originalRows = new Map(source.rows.map((row) => [row.key, row]))
  const signatures = new Map()
  function signatureOf(plan) {
    if (!signatures.has(plan)) {
      const subsidy = plan.cardType === 'COMMON' ? subsidySignature(plan) : null
      signatures.set(plan, subsidy ? JSON.stringify([plan.auditLogId, subsidy]) : plan.key)
    }
    return signatures.get(plan)
  }

  function scopeFor({ goods, plans }) {
    const fullConfiguration =
      goods.length === configurationGoodsCount && plans.some((plan) => plan.cardType === 'COMMON')
    const trackId = JSON.stringify([
      source.scope.trackId,
      goods.map((g) => g.id).sort(),
      fullConfiguration,
    ])
    if (!scopes.has(trackId)) {
      scopes.set(trackId, {
        scope: {
          ...source.scope,
          trackId,
          fullConfiguration,
          goods,
          label: fullConfiguration ? '整组配置' : timelineGoodsLabel(goods) || '原单未注明商品',
        },
        plans: new Map(),
      })
    }
    return scopes.get(trackId)
  }

  function scopedPlan(display, plan) {
    if (!display.plans.has(plan)) {
      display.plans.set(plan, {
        ...plan,
        key: JSON.stringify([display.scope.trackId, plan.auditLogId, plan.selectionIndex]),
      })
    }
    return display.plans.get(plan)
  }

  function displayRow(display, original) {
    const plan = scopedPlan(display, original.plan)
    if (!rows.has(plan.key)) {
      const { scope } = display
      rows.set(plan.key, {
        ...original,
        key: plan.key,
        plan,
        parts: [],
        segment:
          plan.cardType === 'SPECIFIED' ? { ...scope, label: original.segment.label } : scope,
      })
    }
    return rows.get(plan.key)
  }

  for (const segment of source.segments) {
    const adopted = new Set(segment.policies)
    for (const group of selectionGroups([...adopted, ...segment.excluded], signatureOf)) {
      const display = scopeFor(group)
      segments.push({
        ...segment,
        ...display.scope,
        policies: segment.policies.map((plan) => scopedPlan(display, plan)),
        excluded: segment.excluded.map((plan) => scopedPlan(display, plan)),
      })
      for (const plan of group.plans) {
        const original = originalRows.get(plan.key)
        if (original) {
          const row = displayRow(display, original)
          appendTimelinePart(
            row.parts,
            segment.startAt,
            segment.endAt,
            adopted.has(plan),
            segment.open,
            segment.planned,
          )
        }
      }
    }
  }
  // 有原单日期、尚无已知采用时段时，保留可核对的原单行，不补画色块。
  const undated = new Map()
  for (const row of source.rows) {
    if (!row.parts.length) {
      if (!undated.has(row.plan.auditLogId)) {
        undated.set(row.plan.auditLogId, [])
      }
      undated.get(row.plan.auditLogId).push(row.plan)
    }
  }
  for (const plans of undated.values()) {
    for (const group of selectionGroups(plans, signatureOf)) {
      const display = scopeFor(group)
      for (const plan of group.plans) {
        displayRow(display, originalRows.get(plan.key))
      }
    }
  }
  return { rows: [...rows.values()], segments }
}
