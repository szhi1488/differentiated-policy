import { PRICE_BASE_LABELS } from '../constants/policy'
import { subsidySignature } from './policy-coverage'
import {
  timelineDecimal,
  timelineGoodsLabel,
  timelineGoodsScope,
  timelinePlanText,
  timelineTimeFromValue,
  timelineTimeValue,
} from './subsidy-timeline'

// 商品审核轨道可能分段合并或拆开。图行始终按原单、平台、补贴分配和指定商品识别。
function declarationKey(scope, plan) {
  const subsidy = subsidySignature(plan) ?? [
    plan.subsidyMode,
    plan.fixedAmount,
    plan.maxSubsidyAmount,
    plan.ratio,
    plan.priceBase,
  ]
  return JSON.stringify([
    scope.channel,
    scope.platformCode,
    plan.auditLogId,
    plan.cardType,
    subsidy,
    ...(plan.cardType === 'SPECIFIED' ? [plan.goods.map((g) => g.id).sort(), plan.quantity] : []),
  ])
}

function partsForScopes(scopes) {
  const boundaries = new Map()
  function boundary(at) {
    if (!boundaries.has(at)) {
      boundaries.set(at, { starts: [], ends: [] })
    }
    return boundaries.get(at)
  }
  for (const entry of scopes) {
    boundary(entry.scope.startAt).starts.push(entry)
    boundary(entry.scope.endAt).ends.push(entry)
  }
  const times = [...boundaries.keys()].sort()
  const active = new Set()
  const parts = []
  for (let index = 0; index < times.length - 1; index++) {
    const startAt = times[index]
    const endAt = times[index + 1]
    const { starts, ends } = boundaries.get(startAt)
    for (const entry of ends) {
      active.delete(entry)
    }
    for (const entry of starts) {
      active.add(entry)
    }
    if (!active.size) {
      continue
    }
    const scopes = [...active]
    const used = scopes.some((item) => item.used)
    const mixed = used && scopes.some((item) => !item.used)
    const planned = scopes[0].scope.planned
    const status = planned ? (mixed ? '计划部分采用' : used ? '计划采用' : '计划未采用') : undefined
    const open = scopes.some(({ scope }) => scope.open && scope.endAt === endAt)
    parts.push({ startAt, endAt, used, mixed, status, open, scopes })
  }
  return parts
}

export function policyTimelineRows(declarations, segments) {
  const groups = new Map()
  for (const { scope, plan, matchesQuery } of declarations) {
    const key = declarationKey(scope, plan)
    plan.rowKey = key
    if (!groups.has(key)) {
      groups.set(key, {
        key,
        scope,
        plan,
        plans: [],
        products: new Map(),
        matchesQuery: false,
        scopes: [],
      })
    }
    const group = groups.get(key)
    group.matchesQuery ||= matchesQuery
    group.plans.push(plan)
    for (const item of plan.goods) {
      if (item.color || !group.products.has(item.id)) {
        group.products.set(item.id, item)
      }
    }
  }
  // 每个商品范围保留原来的逐段名单；只有实际申报了该范围的原单行才关联这个时段。
  for (const scope of segments) {
    const seen = new Set()
    for (const [plans, used] of [
      [scope.policies, true],
      [scope.excluded, false],
    ]) {
      for (const { rowKey } of plans) {
        if (seen.has(rowKey)) {
          continue
        }
        seen.add(rowKey)
        const group = groups.get(rowKey)
        if (
          group.matchesQuery &&
          (scope.channel === 'RETAIL' ||
            (scope.goods.length
              ? scope.goods.some((g) => group.products.has(g.id))
              : !group.products.size))
        ) {
          group.scopes.push({ scope, used })
        }
      }
    }
  }
  const rows = []
  for (const group of groups.values()) {
    const goods = [...group.products.values()]
    for (const plan of group.plans) {
      plan.declaredGoods = goods
    }
    if (!group.matchesQuery) {
      continue
    }
    const { key } = group
    const parts = partsForScopes(group.scopes)
    const plan = { ...group.plan, key, goods, usePeriods: parts.filter((p) => p.used && !p.status) }
    const scope = timelineGoodsScope(goods, [plan])
    if (plan.cardType === 'SPECIFIED') {
      scope.label = '独立商品 · ' + timelineGoodsLabel(goods)
    }
    const caption =
      plan.subsidyMode === 'FIXED'
        ? timelinePlanText(plan)
        : (PRICE_BASE_LABELS[plan.priceBase] ?? '基数未填写') +
          ' × ' +
          timelineDecimal(plan.ratio) +
          '%'
    const effectiveEnd = timelineTimeFromValue(timelineTimeValue(plan.effectiveThrough) + 1000)
    rows.push({
      key,
      plan,
      parts,
      caption,
      segment: { ...group.scope, ...scope, goods, trackId: key },
      startAt: parts[0]?.startAt < plan.policyFrom ? parts[0].startAt : plan.policyFrom,
      endAt: parts.at(-1)?.endAt > effectiveEnd ? parts.at(-1).endAt : effectiveEnd,
    })
  }
  return rows
}
