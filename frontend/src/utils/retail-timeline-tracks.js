import { subsidySignature } from './policy-coverage'

// 服务端按商品给出真实采用/未采用名单。展示层只合并同一平台、同一时段结果相同的范围。
function policySignature(policy) {
  return [
    policy.auditLogId,
    policy.selections.map(({ cardType, subsidy, quantity, products }) => [
      cardType,
      subsidySignature(subsidy) ?? subsidy,
      quantity,
      cardType === 'SPECIFIED' ? products.map((product) => product.goodsId) : null,
    ]),
  ]
}

function combinePolicy(members, auditId) {
  const policies = members.map((source) => source.policies.get(auditId))
  const first = policies[0]
  if (members.length === 1) {
    return first
  }
  return {
    ...first,
    matchesQuery: policies.some((policy) => policy.matchesQuery),
    selections: first.selections.map((selection, index) => {
      const products = new Map()
      for (const policy of policies) {
        for (const product of policy.selections[index].products) {
          products.set(product.goodsId, product)
        }
      }
      return { ...selection, products: [...products.values()] }
    }),
  }
}

function appendInterval(intervals, state, startAt, endAt) {
  const last = intervals.at(-1)
  if (last && last.endAt === startAt && last.signature === state.signature) {
    last.endAt = endAt
  } else {
    intervals.push({ ...state.segment, startAt, endAt, signature: state.signature })
  }
}

export function groupPlatform(tracks, asOf) {
  if (tracks.length === 1) {
    return tracks
  }
  const sources = tracks.map((track, index) => ({
    index,
    track,
    policies: new Map(track.policies.map((p) => [p.auditLogId, p])),
    signatures: new Map(track.policies.map((p) => [p.auditLogId, policySignature(p)])),
    displayed: new Set(),
  }))
  const boundaries = new Map()
  function boundary(at) {
    if (!boundaries.has(at)) {
      boundaries.set(at, { starts: [], ends: [] })
    }
    return boundaries.get(at)
  }
  const afterAsOf = Date.parse(asOf) + 1000
  for (const source of sources) {
    for (const planned of [false, true]) {
      const intervals = planned ? source.track.plannedSegments : source.track.segments
      for (const segment of intervals) {
        const from = Date.parse(segment.startAt)
        const through = segment.endAt === null ? afterAsOf : Date.parse(segment.endAt)
        const signatures = (ids) =>
          [...ids].sort().map((id) => {
            if (!source.signatures.has(id)) {
              throw new Error('补贴历史缺少对应的审核轮次。')
            }
            return source.signatures.get(id)
          })
        const signature = JSON.stringify([
          planned,
          signatures(segment.adoptedAuditLogIds),
          signatures(segment.excludedAuditLogIds),
        ])
        boundary(from).starts.push({ source, segment, signature, planned })
        boundary(through).ends.push(source)
      }
    }
  }
  const times = [...boundaries.keys()].sort((a, b) => a - b)
  const active = new Map()
  const scopes = new Map()
  for (let i = 0; i < times.length - 1; i++) {
    const at = times[i],
      next = times[i + 1]
    const { starts, ends } = boundaries.get(at)
    for (const source of ends) {
      active.delete(source)
    }
    for (const state of starts) {
      active.set(state.source, state)
    }
    const groups = new Map()
    for (const state of active.values()) {
      if (!groups.has(state.signature)) {
        groups.set(state.signature, [])
      }
      groups.get(state.signature).push(state)
    }
    for (const states of groups.values()) {
      const members = states.map((state) => state.source).sort((a, b) => a.index - b.index)
      const key = members.map((source) => source.index).join(',')
      if (!scopes.has(key)) {
        scopes.set(key, {
          perspective: tracks[0].perspective,
          platformCode: tracks[0].platformCode,
          goodsIds: members.flatMap((source) => source.track.goodsIds),
          policies: new Map(),
          segments: [],
          plannedSegments: [],
        })
      }
      const scope = scopes.get(key)
      const state = states[0]
      for (const id of [
        ...state.segment.adoptedAuditLogIds,
        ...state.segment.excludedAuditLogIds,
      ]) {
        if (!scope.policies.has(id)) {
          scope.policies.set(id, combinePolicy(members, id))
          members.forEach((source) => source.displayed.add(id))
        }
      }
      // null 表示实际历史仅确知到查询当秒，不能把后续计划并回实际。
      const open = next === afterAsOf && states.some(({ segment }) => segment.endAt === null)
      appendInterval(
        state.planned ? scope.plannedSegments : scope.segments,
        state,
        new Date(at).toISOString(),
        open ? null : new Date(next).toISOString(),
      )
    }
  }
  const result = [...scopes.values()].map((scope) => ({
    ...scope,
    policies: [...scope.policies.values()],
  }))
  for (const source of sources) {
    // 原单可能只有日期，没有可绘制的色块；仍保留单号供核对。
    const policies = source.track.policies.filter(
      (p) => p.matchesQuery && !source.displayed.has(p.auditLogId),
    )
    if (policies.length) {
      result.push({ ...source.track, policies, segments: [], plannedSegments: [] })
    }
  }
  return result
}

export function retailDisplayTracks(response) {
  const platforms = new Map()
  for (const track of response.tracks) {
    if (track.perspective === 'RETAIL') {
      if (!platforms.has(track.platformCode)) {
        platforms.set(track.platformCode, [])
      }
      platforms.get(track.platformCode).push(track)
    }
  }
  const retail = [...platforms.values()].flatMap((tracks) =>
    groupPlatform(tracks, response.asOf).map((track) => ({
      ...track,
      fullConfiguration:
        track.goodsIds.length === response.configurationGoodsCount &&
        track.policies.some((p) => p.selections.some((s) => s.cardType === 'COMMON')),
    })),
  )
  return [...retail, ...response.tracks.filter((track) => track.perspective !== 'RETAIL')]
}
