import { policyTime } from './policy-adapter'
import { catalogConfigLabel } from './catalog-dictionary'
import { subsidySignature } from './policy-coverage'

function incomplete() {
  throw new Error('适用范围预览不完整，请重新预览。')
}

function scopeView({ scope, policies, segments }, effectiveFrom) {
  if (!scope?.platformCode || !policies?.length || !segments?.length) {
    incomplete()
  }
  const proposed = policies.find((policy) => policy.proposed)
  const product = proposed?.selections?.[0]?.products?.[0]
  if (!product) {
    incomplete()
  }
  const key = JSON.stringify([scope.platformCode, scope.goodsId, scope.spuId, scope.ram, scope.rom])
  const config =
    product.ram && product.rom
      ? catalogConfigLabel([product.ram, product.rom].map((v) => v.replace(/GB$/, '')).join('+'))
      : ''
  const title = [
    product.modelName || product.goodsName || '机型 ' + product.spuId,
    product.edition,
    config,
  ]
    .filter(Boolean)
    .join(' · ')
  const target = scope.goodsId
    ? [product.colorName, '商品 ID ' + scope.goodsId].filter(Boolean).join(' · ')
    : '按同机型、同配置匹配各颜色'
  const byId = new Map(policies.map((policy) => [policy.policyId, policy]))
  const commonConfiguration =
    scope.goodsId &&
    proposed.selections.length === 1 &&
    proposed.selections[0].cardType === 'COMMON' &&
    product.goodsId === scope.goodsId &&
    product.modelName &&
    product.spuId &&
    config
      ? JSON.stringify([scope.platformCode, product.spuId, title, product.ram, product.rom])
      : null
  const ranges = segments.map((segment) => {
    const { groups, action, eligiblePolicyIds, through } = segment
    if (
      action !== (scope.goodsId ? 'SELECT_RETAIL_POLICIES' : 'STACK_OR_CHOOSE') ||
      !eligiblePolicyIds?.length ||
      !Array.isArray(groups)
    ) {
      incomplete()
    }
    const ids = new Set(eligiblePolicyIds)
    if (action === 'SELECT_RETAIL_POLICIES') {
      const grouped = groups.flatMap((group) => {
        if (!group.sceneTypes?.length || !group.verifyType || !group.policyIds?.length) {
          incomplete()
        }
        return group.policyIds
      })
      if (
        grouped.length !== ids.size ||
        new Set(grouped).size !== ids.size ||
        grouped.some((id) => !ids.has(id))
      ) {
        incomplete()
      }
    }
    const participants = Array.from(ids, (id) => byId.get(id))
    if (participants.some((p) => !p?.policyCode || !p.selections?.length)) {
      incomplete()
    }
    const fromText = policyTime(segment.from).replace('T', ' ')
    const throughText = policyTime(through).replace('T', ' ')
    if (!fromText || !throughText) {
      incomplete()
    }
    return {
      ...segment,
      resultLabel:
        groups.length === 1 ? '同组选一' : groups.length > 1 ? '跨组可叠加' : '叠加或选一',
      key: JSON.stringify([key, through]),
      scope,
      // 正在生效的首段随核对时钟向前移动，沿用服务端对首段起点的比较口径。
      confirmationBasis: JSON.stringify([
        segment.from === effectiveFrom ? 'START' : segment.from,
        through,
        action,
        groups,
        participants.map((p) => [p.policyId, p.auditLogId, p.submissionVersion, p.selections]),
      ]),
      fromText,
      throughText,
      participants,
      byId,
      product,
      eligible: eligiblePolicyIds.map((id) => byId.get(id)),
    }
  })
  return { key, scope, title, target, commonConfiguration, product, ranges }
}

// 只有时段、核销分组、候选轮次及新旧补贴一致，才可用一次操作确认多个零售商品。
function decisionGroupKey(range) {
  const policies = []
  for (const policy of range.participants) {
    const [selection] = policy.selections
    const plan = subsidySignature(selection.subsidy)
    if (policy.selections.length !== 1 || selection.cardType !== 'COMMON' || !plan) {
      return range.key
    }
    policies.push(
      JSON.stringify([policy.policyId, policy.auditLogId, policy.submissionVersion, plan]),
    )
  }
  const groups = range.groups.map((group) =>
    JSON.stringify([group.sceneTypes, group.verifyType, [...group.policyIds].sort()]),
  )
  return JSON.stringify([
    range.from,
    range.through,
    range.action,
    [...range.eligiblePolicyIds].sort(),
    groups.sort(),
    policies.sort(),
  ])
}

/** 按配置汇总命中商品，每个显示时段保留对应的原始决定。 */
export function groupSubmissionScopes(scopes) {
  const cards = new Map()
  for (const item of scopes) {
    const key = item.commonConfiguration ?? item.key
    if (!cards.has(key)) {
      cards.set(key, {
        key,
        scope: item.scope,
        title: item.title,
        target: item.target,
        commonConfiguration: item.commonConfiguration,
        products: [],
        ranges: new Map(),
      })
    }
    const card = cards.get(key)
    card.products.push(item.product)
    for (const range of item.ranges) {
      const groupKey = item.commonConfiguration ? decisionGroupKey(range) : range.key
      if (!card.ranges.has(groupKey)) {
        card.ranges.set(groupKey, { ...range, key: groupKey, members: [] })
      }
      card.ranges.get(groupKey).members.push(range)
    }
  }
  return Array.from(cards.values(), (card) => ({
    ...card,
    ranges: [...card.ranges.values()].sort(
      (a, b) => a.fromText.localeCompare(b.fromText) || a.throughText.localeCompare(b.throughText),
    ),
  }))
}

/** 界面装配服务端给出的交叉段及分组候选，范围及截止时间原样回传。 */
export function readSubmissionPreview(preview) {
  const result = preview?.applicability
  if (!result || !Array.isArray(result.scopes)) {
    incomplete()
  }
  if (result.status === 'NO_EFFECTIVE_PERIOD') {
    throw new Error('已无可生效时段，请调整政策日期后再提交。')
  }
  if (result.status === 'DIRECT' && result.canDirectSubmit === true && !result.scopes.length) {
    return null
  }
  if (
    result.status !== 'NEEDS_CONFIRMATION' ||
    result.canDirectSubmit !== false ||
    !result.scopes.length ||
    !preview.confirmationToken
  ) {
    incomplete()
  }
  const scopes = result.scopes.map((scope) => scopeView(scope, result.effectiveFrom))
  const ranges = scopes.flatMap((s) => s.ranges)
  if (new Set(ranges.map((r) => r.key)).size !== ranges.length) {
    incomplete()
  }
  return { scopes, ranges }
}
