import { retailDisplayTracks } from './retail-timeline-tracks'
import { billionDisplayHistory } from './billion-timeline-history'
import { billionDisplayTracks } from './billion-timeline-tracks'
import { PRICE_BASE_LABELS, SCENE_LABELS, VERIFY_LABELS } from '../constants/policy'
import {
  appendTimelinePart,
  timelineGoodsLabel,
  timelineDecimal,
  timelinePlanText,
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

function trackHistory(track, trackId, asOf) {
  const rows = []
  const rounds = new Map()
  const products = new Map()
  for (const policy of track.policies) {
    const parts = []
    const usePeriods = []
    const plans = policy.selections.map((selection, index) => {
      const goods = selection.products.map(({ goodsId: id, colorName, goodsName }) => {
        const product = { id, color: colorName || goodsName || '' }
        if (!products.has(id) || product.color) {
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
        approvedAt: beijingTime(policy.approvedAt),
        estimated: policy.estimated,
        scenes: policy.sceneTypes?.map((code) => SCENE_LABELS[code] ?? code) ?? [],
        verify: VERIFY_LABELS[policy.verifyType] ?? policy.verifyType,
        goods,
        usePeriods,
        cardType: selection.cardType,
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
    fullConfiguration: track.fullConfiguration,
    label: goods.length && !track.fullConfiguration ? timelineGoodsLabel(goods) : '整组配置',
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
    ...(track.plannedSegments ?? []).map((segment) => readSegment(segment, true)),
  ]
  for (const { matchesQuery, plans, parts, usePeriods } of rounds.values()) {
    usePeriods.push(...parts.filter((part) => part.used && !part.status))
    if (!matchesQuery) {
      continue
    }
    for (const plan of plans) {
      const specified = plan.cardType === 'SPECIFIED'
      const label = specified ? '独立商品 · ' + timelineGoodsLabel(plan.goods) : scope.label
      const caption =
        plan.subsidyMode === 'FIXED'
          ? timelinePlanText(plan)
          : (PRICE_BASE_LABELS[plan.priceBase] ?? '基数未填写') +
            ' × ' +
            timelineDecimal(plan.ratio) +
            '%'
      const originalEnd = nextSecond(plan.policyThrough)
      rows.push({
        key: plan.key,
        plan,
        parts,
        caption,
        segment: { ...scope, label },
        startAt: parts[0]?.startAt < plan.policyFrom ? parts[0].startAt : plan.policyFrom,
        endAt: parts.at(-1)?.endAt > originalEnd ? parts.at(-1).endAt : originalEnd,
      })
    }
  }
  return { rows, segments, scope }
}

/** 名单、范围和区间均取自后端。原单日期只用于绘图范围，不填补未知时段。 */
export function readTimelineHistory(response) {
  const asOf = beijingTime(response.asOf)
  const rows = []
  const segments = []
  const retailTracks = retailDisplayTracks(response)
  const tracks =
    response.configurationGoodsCount > 0
      ? billionDisplayTracks(retailTracks, response.asOf)
      : retailTracks
  for (const [index, track] of tracks.entries()) {
    const source = trackHistory(track, index, asOf)
    const history =
      track.perspective === 'BILLION' &&
      (!track.goodsIds.length || track.configurationDecision === false) &&
      response.configurationGoodsCount > 0
        ? billionDisplayHistory(source, response.configurationGoodsCount)
        : source
    rows.push(...history.rows)
    segments.push(...history.segments)
  }
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
      const fill = part.used ? (fixed ? '#346dc0' : '#a55d19') : fixed ? '#eef4fd' : '#fff6ec'
      const stroke = part.used ? fill : fixed ? '#91b3e2' : '#dfa86c'
      const status = part.status ?? (part.used ? '采用' : '未采用')
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
              fill: part.used ? '#fff' : ink,
              font: '12px Microsoft YaHei',
              align: 'center',
              verticalAlign: 'middle',
              width: Math.max(0, shape.width - 4),
              overflow: 'truncate',
            },
          },
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
                    text: '当前适用',
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
