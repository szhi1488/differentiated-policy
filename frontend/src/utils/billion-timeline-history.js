import { timelineGoodsScope } from './subsidy-timeline'
import { subsidySignature } from './policy-coverage'

// 百亿各商品共用该配置的审核决定，明细仍可分别核对每一组商品的原单分配。
export function billionDisplaySegments(source) {
  const result = []
  for (const segment of source.segments) {
    const products = new Map()
    const unspecified = []
    for (const plan of [...segment.policies, ...segment.excluded]) {
      if (!plan.goods.length) {
        unspecified.push(plan)
      }
      // 同轮、同方案的普通卡可合并核对，指定商品保留独立身份。
      const subsidy = plan.cardType === 'COMMON' ? subsidySignature(plan) : null
      const key = subsidy ? JSON.stringify([plan.auditLogId, subsidy]) : plan.key
      for (const item of plan.goods) {
        if (!products.has(item.id)) {
          products.set(item.id, { item, plans: new Map() })
        }
        products.get(item.id).plans.set(key, plan)
      }
    }
    const groups = new Map()
    for (const { item, plans } of products.values()) {
      const key = JSON.stringify([...plans.keys()].sort())
      if (!groups.has(key)) {
        groups.set(key, { goods: [], plans: [...plans.values()] })
      }
      groups.get(key).goods.push(item)
    }
    if (unspecified.length) {
      groups.set('', { goods: [], plans: unspecified })
    }
    for (const { goods, plans } of groups.values()) {
      const scope = timelineGoodsScope(goods, plans)
      result.push({
        ...segment,
        ...scope,
        goods,
        trackId: JSON.stringify([segment.trackId, goods.map((g) => g.id).sort()]),
      })
    }
  }
  return result
}
