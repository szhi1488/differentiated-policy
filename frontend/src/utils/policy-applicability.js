import { policyTime } from './policy-adapter'
import { catalogConfigLabel } from './catalog-dictionary'
import { subsidySignature } from './policy-coverage'

function incomplete() {
  throw new Error('适用范围预览不完整，请重新预览。')
}

function originalId(id) {
  return /^extension:\d+:(\d+)$/.exec(id)?.[1] ?? id
}

function groupSignatures(groups) {
  return groups
    .map((group) =>
      JSON.stringify([[...group.sceneTypes].sort(), group.verifyType, [...group.policyIds].sort()]),
    )
    .sort()
}

function scopeView({ scope, policies, segments }, effectiveFrom) {
  if (!scope?.platformCode || !policies?.length || !segments?.length) {
    incomplete()
  }
  const proposed =
    policies.find((policy) => policy.proposed && originalId(policy.policyId) === policy.policyId) ??
    policies.find((policy) => policy.proposed)
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
  const policiesByWireId = new Map(policies.map((policy) => [policy.policyId, policy]))
  for (const policy of policies) {
    const id = originalId(policy.policyId)
    if (id !== policy.policyId) {
      const source = policiesByWireId.get(id)
      policiesByWireId.set(policy.policyId, {
        ...policy,
        policyId: id,
        policyCode: source?.policyCode ?? policy.policyCode?.replace(/（延期）$/, ''),
        auditLogId: source?.auditLogId ?? policy.auditLogId,
        submissionVersion: source?.submissionVersion ?? policy.submissionVersion,
        proposed: false,
      })
    }
  }
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
  const ranges = scopeRanges(segments, { scope, key, policiesByWireId, product, effectiveFrom })
  return { key, scope, title, target, commonConfiguration, product, ranges }
}

function scopeRanges(segments, { scope, key, policiesByWireId, product, effectiveFrom }) {
  const ranges = []
  for (const segment of segments) {
    const { action, through } = segment
    if (
      action !== (scope.goodsId ? 'SELECT_RETAIL_POLICIES' : 'STACK_OR_CHOOSE') ||
      !segment.eligiblePolicyIds?.length ||
      !Array.isArray(segment.groups)
    ) {
      incomplete()
    }
    const wireIds = new Map(segment.eligiblePolicyIds.map((id) => [originalId(id), id]))
    if (wireIds.size !== segment.eligiblePolicyIds.length) {
      incomplete()
    }
    const eligiblePolicyIds = [...wireIds.keys()]
    const groups = segment.groups.map((group) => {
      if (!group.sceneTypes?.length || !group.verifyType || !group.policyIds?.length) {
        incomplete()
      }
      if (group.policyIds.some((id) => wireIds.get(originalId(id)) !== id)) {
        incomplete()
      }
      return { ...group, policyIds: group.policyIds.map(originalId) }
    })
    if (action === 'SELECT_RETAIL_POLICIES') {
      const grouped = groups.flatMap((group) => group.policyIds)
      if (grouped.length !== wireIds.size || new Set(grouped).size !== wireIds.size) {
        incomplete()
      }
    }
    const participants = segment.eligiblePolicyIds.map((id) => policiesByWireId.get(id))
    if (participants.some((p) => !p?.policyCode || !p.selections?.length)) {
      incomplete()
    }
    const candidateBasis = JSON.stringify([
      action,
      groupSignatures(groups),
      participants
        .map((p) => JSON.stringify([p.policyId, p.auditLogId, p.submissionVersion, p.selections]))
        .sort(),
    ])
    const fromText = policyTime(segment.from).replace('T', ' ')
    const throughText = policyTime(through).replace('T', ' ')
    if (!fromText || !throughText || fromText > throughText) {
      incomplete()
    }
    const rawSegment = { through, wireIds }
    const rangeKey = JSON.stringify([key, through])
    if (segment.continuesPrevious === true) {
      const previous = ranges.at(-1)
      if (
        !previous ||
        previous.candidateBasis !== candidateBasis ||
        Date.parse(policyTime(segment.from) + '+08:00') -
          Date.parse(policyTime(previous.through) + '+08:00') !==
          1000
      ) {
        incomplete()
      }
      // 只有服务端确认原执行名单也连续时，才能跨旧截止沿用同一次选择。
      previous.through = through
      previous.throughText = throughText
      previous.key = rangeKey
      previous.rawSegments.push(rawSegment)
      continue
    }
    ranges.push({
      ...segment,
      groups,
      eligiblePolicyIds,
      resultLabel:
        groups.length === 1 ? '同组选一' : groups.length > 1 ? '跨组可叠加' : '叠加或选一',
      key: rangeKey,
      scope,
      candidateBasis,
      // 正在生效的首段随核对时钟向前移动，沿用服务端对首段起点的比较口径。
      startKey: JSON.stringify([key, segment.from === effectiveFrom ? 'START' : segment.from]),
      fromText,
      throughText,
      participants,
      byId: new Map(participants.map((p) => [p.policyId, p])),
      product,
      eligible: participants,
      rawSegments: [rawSegment],
    })
  }
  return ranges
}

/** 只沿用同一商品、起点、候选轮次、补贴和规则下的决定。改期跨过其他原有确认段时须重选。 */
export function retainSubmissionChoices(previous, next, choices, previousChanges, nextChanges) {
  const before = new Map(previousChanges.map((change) => [change.target.policyId, change]))
  const after = new Map(nextChanges.map((change) => [change.target.policyId, change]))
  const changedEnds = new Map()
  for (const id of new Set([...before.keys(), ...after.keys()])) {
    const originalEnd = (after.get(id) ?? before.get(id)).target.previousEnd
    const oldEnd = policyTime(before.get(id)?.endTime ?? originalEnd)
    const newEnd = policyTime(after.get(id)?.endTime ?? originalEnd)
    if (oldEnd !== newEnd) {
      changedEnds.set(id, [oldEnd, newEnd])
    }
  }
  const oldRanges = new Map()
  for (const { ranges } of previous.scopes) {
    ranges.forEach((range, i) => {
      oldRanges.set(range.startKey, { range, nextFrom: ranges[i + 1]?.fromText })
    })
  }
  const kept = new Map()
  for (const range of next.ranges) {
    const old = oldRanges.get(range.startKey)
    if (
      !old ||
      old.range.candidateBasis !== range.candidateBasis ||
      old.range.throughText < range.fromText ||
      range.throughText < old.range.fromText ||
      !choices.has(old.range.key)
    ) {
      continue
    }
    if (old.range.through !== range.through) {
      const ends = [old.range.through, range.through].map(policyTime)
      const changedBoundary = range.eligiblePolicyIds.some((id) =>
        changedEnds.get(id)?.some((end) => ends.includes(end)),
      )
      if (!changedBoundary || (old.nextFrom && old.nextFrom <= range.throughText)) {
        continue
      }
    }
    kept.set(range.key, choices.get(old.range.key))
  }
  return kept
}

/** 页面按原单号勾选，服务端按每段自己的候选标识校验并冻结决定。 */
export function submissionDecisions(ranges, choices) {
  return ranges.flatMap((range) => {
    const selected = choices.get(range.key)
    return range.rawSegments.map(({ through, wireIds }) => {
      const decision = { scope: range.scope, through }
      if (Array.isArray(selected)) {
        return {
          ...decision,
          choice: 'SELECT',
          selectedPolicyIds: selected.map((id) => wireIds.get(id)).sort(),
        }
      }
      return selected === 'STACK'
        ? { ...decision, choice: 'STACK' }
        : { ...decision, choice: 'CHOOSE_ONE', policyId: wireIds.get(selected) }
    })
  })
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
  return JSON.stringify([
    range.from,
    range.through,
    range.action,
    [...range.eligiblePolicyIds].sort(),
    groupSignatures(range.groups),
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
