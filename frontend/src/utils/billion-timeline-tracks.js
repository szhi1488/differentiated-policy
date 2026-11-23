import { groupPlatform } from './retail-timeline-tracks'

const segmentIds = (segment) => [...segment.adoptedAuditLogIds, ...segment.excludedAuditLogIds]

// 混合历史中，配置级决定在每条商品轨重复出现。相同边界来自同一平台的同一次配置审核。
function mixedPlatform(tracks, asOf) {
  const actual = new Map()
  const planned = new Map()
  const byAudit = new Map()
  const seen = new Set()
  const productTracks = []
  for (const track of tracks) {
    for (const policy of track.policies) {
      if (!byAudit.has(policy.auditLogId) || policy.matchesQuery) {
        byAudit.set(policy.auditLogId, policy)
      }
    }
    const productRounds = new Set()
    function split(intervals, configuration) {
      return intervals.filter((interval) => {
        const ids = segmentIds(interval)
        ids.forEach((id) => seen.add(id))
        if (interval.configurationDecision) {
          configuration.set(interval.startAt, interval)
          return false
        }
        ids.forEach((id) => productRounds.add(id))
        return true
      })
    }
    const segments = split(track.segments, actual)
    const plannedSegments = split(track.plannedSegments ?? [], planned)
    if (productRounds.size) {
      productTracks.push({
        ...track,
        segments,
        plannedSegments,
        policies: track.policies.filter((policy) => productRounds.has(policy.auditLogId)),
      })
    }
  }
  const result = groupPlatform(productTracks, asOf).map((track) => ({
    ...track,
    configurationDecision: false,
  }))
  const inOrder = (intervals) =>
    [...intervals.values()].sort((a, b) => Date.parse(a.startAt) - Date.parse(b.startAt))
  const segments = inOrder(actual)
  const plannedSegments = inOrder(planned)
  const configurationRounds = new Set([...segments, ...plannedSegments].flatMap(segmentIds))
  if (configurationRounds.size) {
    result.push({
      ...tracks[0],
      goodsIds: [],
      segments,
      plannedSegments,
      policies: [...byAudit.values()].filter((policy) =>
        configurationRounds.has(policy.auditLogId),
      ),
    })
  }
  // 只列原单、没有色块的轮次只保留一份；根据快照的商品和来源轨道展示范围。
  for (const policy of byAudit.values()) {
    if (!policy.matchesQuery || seen.has(policy.auditLogId)) {
      continue
    }
    const goodsIds = new Set(
      tracks
        .filter((track) =>
          track.policies.some((p) => p.auditLogId === policy.auditLogId && p.matchesQuery),
        )
        .flatMap((track) => track.goodsIds),
    )
    result.push({
      ...tracks[0],
      goodsIds: [...goodsIds],
      configurationDecision: false,
      policies: [policy],
      segments: [],
      plannedSegments: [],
    })
  }
  return result
}

export function billionDisplayTracks(tracks, asOf) {
  const platforms = new Map()
  for (const track of tracks) {
    if (track.perspective === 'BILLION' && track.goodsIds.length) {
      if (!platforms.has(track.platformCode)) {
        platforms.set(track.platformCode, [])
      }
      platforms.get(track.platformCode).push(track)
    }
  }
  const replacements = new Map()
  for (const members of platforms.values()) {
    const intervals = members.flatMap((track) => [
      ...track.segments,
      ...(track.plannedSegments ?? []),
    ])
    // 旧接口未标记逐段规则时，只能沿用其明确返回的商品范围。
    if (intervals.length && intervals.every((s) => typeof s.configurationDecision === 'boolean')) {
      replacements.set(members[0], mixedPlatform(members, asOf))
      for (const track of members.slice(1)) {
        replacements.set(track, [])
      }
    }
  }
  return tracks.flatMap((track) => replacements.get(track) ?? [track])
}
