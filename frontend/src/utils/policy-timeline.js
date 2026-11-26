import { retailDisplayTracks } from './retail-timeline-tracks'
import { billionDisplaySegments } from './billion-timeline-history'
import { policyTimelineRows } from './policy-timeline-rows'
import { SCENE_LABELS, VERIFY_LABELS } from '../constants/policy'
import {
  appendTimelinePart,
  timelineGoodsScope,
  timelinePartStatus,
  timelineTimeFromValue,
  timelineAxisValue,
  timelineTimeValue,
} from './subsidy-timeline'

// 接口时间带时区，图表和指针统一使用北京时间，精确到秒。
function beijingTime(value) {
  if (!value || !Number.isFinite(Date.parse(value))) {
    throw new Error('补贴历史的时间格式无效。')
  }
  return timelineTimeFromValue(Date.parse(value))
}
const nextSecond = (at) => timelineTimeFromValue(timelineTimeValue(at) + 1000)

// 同色商品可能出现在不同轨道，同一次查询统一标注商品 ID 供核对。
function duplicateProductColors(tracks) {
  const firstIds = new Map()
  const duplicates = new Set()
  for (const track of tracks) {
    for (const policy of track.policies) {
      for (const selection of policy.selections) {
        for (const { goodsId: id, colorName, goodsName } of selection.products) {
          const color = colorName || goodsName
          if (firstIds.has(color) && firstIds.get(color) !== id) {
            duplicates.add(color)
          } else {
            firstIds.set(color, id)
          }
        }
      }
    }
  }
  return duplicates
}

function trackHistory(track, trackId, asOf, duplicateColors) {
  const rounds = new Map()
  const products = new Map()
  for (const policy of track.policies) {
    const parts = []
    const usePeriods = []
    const plans = policy.selections.map((selection, index) => {
      const goods = selection.products.map(({ goodsId: id, colorName, goodsName }) => {
        const color = colorName || goodsName || ''
        const product = { id, color, label: duplicateColors.has(color) ? color + ' #' + id : color }
        if (!products.has(id) || color) {
          products.set(id, product)
        }
        return product
      })
      return {
        ...selection.subsidy,
        key: JSON.stringify([trackId, policy.auditLogId, index]),
        auditLogId: policy.auditLogId,
        selectionIndex: index,
        policyCode: policy.policyCode,
        policyFrom: beijingTime(policy.policyFrom),
        policyThrough: beijingTime(policy.policyThrough),
        effectiveThrough: beijingTime(policy.effectiveThrough),
        approvedAt: beijingTime(policy.approvedAt),
        estimated: policy.estimated,
        scenes: policy.sceneTypes?.map((code) => SCENE_LABELS[code] ?? code) ?? [],
        verify: VERIFY_LABELS[policy.verifyType] ?? policy.verifyType,
        goods,
        usePeriods,
        cardType: selection.cardType,
        wholeConfigurationGoodsCount: selection.wholeConfigurationGoodsCount ?? null,
        ...(selection.cardType === 'SPECIFIED' ? { quantity: selection.quantity } : {}),
      }
    })
    rounds.set(policy.auditLogId, { matchesQuery: policy.matchesQuery, plans, parts, usePeriods })
  }

  const goods = track.goodsIds.map((id) => products.get(id) ?? { id, color: '' })
  const scope = {
    trackId,
    channel: track.perspective,
    platformCode: track.platformCode,
    goods,
  }
  function readSegment(segment, planned) {
    const startAt = beijingTime(segment.startAt)
    const endAt = segment.endAt ? beijingTime(segment.endAt) : nextSecond(asOf)
    function allocations(ids, used) {
      return ids.flatMap((id) => {
        const round = rounds.get(id)
        if (!round) {
          throw new Error('补贴历史缺少对应的审核轮次。')
        }
        appendTimelinePart(round.parts, startAt, endAt, used, segment.endAt === null, planned)
        return round.plans
      })
    }
    const policies = allocations(segment.adoptedAuditLogIds, true)
    const excluded = allocations(segment.excludedAuditLogIds, false)
    const count = segment.adoptedAuditLogIds.length
    const decision =
      count === 0
        ? '该时点未采用'
        : count === 1
          ? '单独适用'
          : track.perspective === 'BILLION'
            ? '同配置叠加'
            : '跨组叠加'
    return {
      ...scope,
      ...timelineGoodsScope(goods, [...policies, ...excluded]),
      startAt,
      endAt,
      open: segment.endAt === null,
      policies,
      excluded,
      decision,
      planned,
    }
  }
  const segments = [
    ...track.segments.map((segment) => readSegment(segment, false)),
    ...track.plannedSegments.map((segment) => readSegment(segment, true)),
  ]
  const declarations = []
  for (const { matchesQuery, plans, parts, usePeriods } of rounds.values()) {
    usePeriods.push(...parts.filter((part) => part.used && !part.status))
    for (const plan of plans) {
      declarations.push({ scope, plan, matchesQuery })
    }
  }
  return { declarations, segments }
}

/** 名单和区间取自后端，用政策生效范围确定绘图边界。 */
export function readTimelineHistory(response) {
  const asOf = beijingTime(response.asOf)
  const declarations = []
  const segments = []
  if (
    !Array.isArray(response.tracks) ||
    response.tracks.some(
      (track) => !Array.isArray(track.segments) || !Array.isArray(track.plannedSegments),
    )
  ) {
    throw new Error('补贴历史缺少审核时段或后续安排。')
  }
  const duplicateColors = duplicateProductColors(response.tracks)
  for (const [index, track] of retailDisplayTracks(response).entries()) {
    if (track.perspective === 'BILLION' && track.goodsIds.length) {
      throw new Error('百亿补贴的审核范围必须是整组配置。')
    }
    const source = trackHistory(track, index, asOf, duplicateColors)
    declarations.push(...source.declarations)
    const scopeSegments =
      track.perspective === 'BILLION' ? billionDisplaySegments(source) : source.segments
    segments.push(...scopeSegments)
  }
  const rows = policyTimelineRows(declarations, segments)
  return { asOf, rows, segments }
}

export function policyTimelineSeries(rows, clipRect, currentAt) {
  const entries = rows.flatMap((row, index) =>
    row.parts.map((part, partIndex) => [
      index,
      timelineAxisValue(part.startAt),
      timelineAxisValue(part.endAt),
      partIndex,
    ]),
  )
  return {
    type: 'custom',
    clip: true,
    encode: { x: [1, 2], y: 0 },
    data: entries,
    renderItem(params, api) {
      const row = rows[api.value(0)]
      const part = row.parts[api.value(3)]
      const [left, center] = api.coord([api.value(1), api.value(0)])
      const [right] = api.coord([api.value(2), api.value(0)])
      const shape = clipRect(
        { x: left, y: center - 16, width: Math.max(1, right - left), height: 32 },
        params.coordSys,
      )
      if (!shape || shape.width < 1) {
        return
      }
      const fixed = row.plan.subsidyMode === 'FIXED'
      const ink = fixed ? '#275da7' : '#925416'
      const solid = fixed ? '#346dc0' : '#a55d19'
      const light = fixed ? '#eef4fd' : '#fff6ec'
      // 部分商品采用时保留同一条色块，底部深色条区分于整组采用或整组未采用。
      const fill = part.mixed
        ? {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: light },
              { offset: 0.82, color: light },
              { offset: 0.82, color: solid },
              { offset: 1, color: solid },
            ],
          }
        : part.used
          ? solid
          : light
      const stroke = part.used ? solid : fixed ? '#91b3e2' : '#dfa86c'
      const status = timelinePartStatus(part)
      const label = part.used && shape.width >= 130 ? row.caption + ' · ' + status : status
      const estimated = row.plan.estimated === true
      const now = api.coord([timelineAxisValue(currentAt), api.value(0)])[0]
      const current =
        part.used &&
        currentAt >= part.startAt &&
        currentAt < part.endAt &&
        now >= shape.x &&
        now <= shape.x + shape.width
      return {
        type: 'group',
        children: [
          {
            type: 'rect',
            shape: { ...shape, r: 3 },
            style: { fill, stroke, lineWidth: 1, lineDash: part.status ? [4, 3] : undefined },
            emphasis: { style: { fill, stroke: ink, lineWidth: 2 } },
          },
          {
            type: 'text',
            style: {
              x: shape.x + shape.width / 2,
              y: center,
              text: shape.width >= (part.used ? 25 : 36) ? label : '',
              fill: part.used && !part.mixed ? '#fff' : ink,
              font: '12px Microsoft YaHei',
              align: 'center',
              verticalAlign: 'middle',
              width: Math.max(0, shape.width - 4),
              overflow: 'truncate',
            },
          },
          ...(api.value(2) - api.value(1) === 1000 && right - left < 8
            ? [
                {
                  // 单秒交界缩小后仍能点中，点击时按该段的真实起止定位。
                  type: 'circle',
                  z2: 8,
                  shape: { cx: shape.x, cy: center - 16, r: 5 },
                  style: {
                    fill: part.used && !part.mixed ? solid : light,
                    stroke: ink,
                    lineWidth: 2,
                  },
                },
              ]
            : []),
          ...(estimated
            ? [
                {
                  type: 'line',
                  shape: {
                    x1: shape.x,
                    x2: shape.x + shape.width,
                    y1: center - 19,
                    y2: center - 19,
                  },
                  style: { stroke: '#7850a5', lineWidth: 2, lineDash: [4, 2] },
                },
                ...(shape.width >= 28
                  ? [
                      {
                        type: 'text',
                        style: {
                          text: shape.width >= 62 ? '预估政策' : '预估',
                          x: shape.x + 1,
                          y: center - 20,
                          verticalAlign: 'bottom',
                          font: 'bold 10px Microsoft YaHei',
                          fill: '#fff',
                          backgroundColor: '#715097',
                          borderRadius: 3,
                          padding: [2, 3],
                        },
                      },
                    ]
                  : []),
              ]
            : []),
          ...(current
            ? [
                {
                  type: 'text',
                  silent: true,
                  style: {
                    text: part.mixed ? '当前部分适用' : '当前适用',
                    x: Math.max(
                      params.coordSys.x + 22,
                      Math.min(now, params.coordSys.x + params.coordSys.width - 22),
                    ),
                    y: estimated ? center + 27 : center - 24,
                    align: 'center',
                    verticalAlign: 'middle',
                    fill: '#277052',
                    font: '10px Microsoft YaHei',
                  },
                },
              ]
            : []),
        ],
      }
    },
  }
}
