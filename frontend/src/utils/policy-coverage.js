import { PRICE_BASE_LABELS } from '../constants/policy'

/** 按具体商品与平台读取提交快照中的唯一补贴，数量不参与适用范围判断。 */
export function subsidyForScope(snapshot, goodsId, platformCode) {
  const matches = []
  for (const card of snapshot?.form?.goodsForm?.cards ?? []) {
    if (!card.targets.some((target) => target.goodsId === goodsId)) {
      continue
    }
    for (const assignment of card.platformAssignments) {
      if (assignment.platformCode === platformCode) {
        const plan = card.subsidyPlans.find(({ key }) => key === assignment.planKey)
        if (plan) {
          matches.push(plan)
        }
      }
    }
  }
  return matches.length === 1 ? matches[0] : null
}

export function subsidyText(plan) {
  if (!plan) {
    return '未找到对应补贴方案'
  }
  if (plan.subsidyMode === 'FIXED') {
    return `固定补贴 · ${plan.fixedAmount} 元${typeof plan.maxSubsidyAmount === 'string' ? ` · 上限 ${plan.maxSubsidyAmount} 元` : ''}`
  }
  return `比例补贴 · ${plan.ratio}% · ${PRICE_BASE_LABELS[plan.priceBase] ?? '—'}`
}

const hundredths = (value) => {
  if (typeof value !== 'string' || !/^\d+(?:\.\d{1,2})?$/.test(value)) {
    return null
  }
  const [whole, fraction = ''] = value.split('.')
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))
}

/** 比较补贴的业务参数，不受方案 key 或小数尾零影响；空上限与 0 保持不同。 */
export function subsidySignature(plan) {
  if (!plan) {
    return null
  }
  if (plan.subsidyMode === 'FIXED') {
    const amount = hundredths(plan.fixedAmount)
    const hasCap = plan.maxSubsidyAmount !== null && plan.maxSubsidyAmount !== undefined
    const cap = hasCap ? hundredths(plan.maxSubsidyAmount) : null
    if (amount === null || (hasCap && cap === null)) {
      return null
    }
    return JSON.stringify(['FIXED', amount.toString(), cap?.toString() ?? null])
  }
  const ratio = hundredths(plan.ratio)
  return plan.subsidyMode === 'RATIO' && ratio !== null && plan.priceBase
    ? JSON.stringify(['RATIO', ratio.toString(), plan.priceBase])
    : null
}

export function subsidyForGroup(snapshot, members, platformCode) {
  const plans = members.map(({ goodsId }) => subsidyForScope(snapshot, goodsId, platformCode))
  const signature = subsidySignature(plans[0])
  return signature && plans.every((plan) => subsidySignature(plan) === signature) ? plans[0] : null
}

function difference(before, after, unit) {
  const oldValue = hundredths(before),
    newValue = hundredths(after)
  if (oldValue === null || newValue === null) {
    return '参数不完整，无法计算差值'
  }
  const delta = newValue - oldValue
  if (delta === 0n) {
    return '不变'
  }
  const absolute = delta < 0n ? -delta : delta
  return `${delta > 0n ? '增加' : '减少'} ${absolute / 100n}.${String(absolute % 100n).padStart(2, '0')} ${unit}`
}

/** 十进制字符串转整数百分位计算差值，不经过浮点数，不推算实际结算金额。 */
export function subsidyChanges(before, after) {
  if (!before || !after) {
    return ['未找到完整补贴方案，请核对整单内容']
  }
  if (before.subsidyMode !== after.subsidyMode) {
    return ['补贴方式变更，金额与比例不直接比较']
  }
  if (after.subsidyMode === 'RATIO') {
    if (before.priceBase !== after.priceBase) {
      return ['计算基数变更，比例不直接比较']
    }
    return [`补贴比例：${difference(before.ratio, after.ratio, '个百分点')}`]
  }
  const changes = [`固定金额：${difference(before.fixedAmount, after.fixedAmount, '元')}`]
  const oldCap = before.maxSubsidyAmount,
    newCap = after.maxSubsidyAmount
  if (typeof oldCap === 'string' && typeof newCap === 'string') {
    changes.push(`补贴上限：${difference(oldCap, newCap, '元')}`)
  } else if (typeof oldCap === 'string' || typeof newCap === 'string') {
    changes.push(`补贴上限：${oldCap ?? '未填写'} → ${newCap ?? '未填写'}`)
  }
  return changes
}
