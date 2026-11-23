import { PRICE_BASE_LABELS } from '../constants/policy'
import {
  timelineDecimal,
  timelinePlanText,
  timelineTimeFromValue,
  timelineAxisValue,
  timelineTimeValue,
} from './subsidy-timeline'

// 原单截止包含最后一秒，绘图和命中判断统一用右开区间。
function originalPeriod(plan) {
  const startAt = plan.policyFrom.length === 10 ? plan.policyFrom + 'T00:00:00' : plan.policyFrom
  const through =
    plan.policyThrough.length === 10 ? plan.policyThrough + 'T23:59:59' : plan.policyThrough
  return { startAt, endAt: timelineTimeFromValue(timelineTimeValue(through) + 1000) }
}

function adoptionParts(period, usePeriods) {
  const parts = []
  let next = period.startAt
  for (const use of usePeriods) {
    const from = use.startAt > next ? use.startAt : next
    const to = use.endAt && use.endAt < period.endAt ? use.endAt : period.endAt
    if (from >= to) {
      continue
    }
    if (from > next) {
      parts.push({ startAt: next, endAt: from, used: false })
    }
    parts.push({ startAt: from, endAt: to, used: true })
    next = to
  }
  if (next < period.endAt) {
    parts.push({ startAt: next, endAt: period.endAt, used: false })
  }
  return parts
}

/** 按原单日期筛选，采用区间完全沿用各商品范围已经给出的决定。 */
export function retailPolicyRows(segments, range) {
  const rows = new Map()
  if (range[0] > range[1]) {
    return []
  }
  for (const segment of segments) {
    if (segment.channel !== 'RETAIL') {
      continue
    }
    for (const plan of [...segment.policies, ...segment.excluded]) {
      const key = JSON.stringify([segment.trackId, plan.policyCode, plan.submissionVersion])
      if (rows.has(key)) {
        continue
      }
      const period = originalPeriod(plan)
      if (period.startAt > range[1] || period.endAt <= range[0]) {
        continue
      }
      const caption =
        plan.subsidyMode === 'FIXED'
          ? timelinePlanText(plan)
          : `${PRICE_BASE_LABELS[plan.priceBase] ?? '基数未填写'} × ${timelineDecimal(plan.ratio)}%`
      rows.set(key, {
        ...period,
        key,
        segment,
        plan,
        caption,
        parts: adoptionParts(period, plan.usePeriods),
      })
    }
  }
  return [...rows.values()]
}

export function retailPolicySeries(rows, clipRect, currentAt) {
  const entries = rows.flatMap((row, index) =>
    row.parts.map((part) => ({
      row,
      part,
      plan: row.plan,
      value: [index, timelineAxisValue(part.startAt), timelineAxisValue(part.endAt)],
    })),
  )
  return {
    type: 'custom',
    clip: true,
    encode: { x: [1, 2], y: 0 },
    data: entries,
    renderItem(params, api) {
      const { row, part } = entries[params.dataIndex]
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
      const status = part.used ? '采用' : '未采用'
      const label = part.used && shape.width >= 130 ? row.caption + ' · 采用' : status
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
            style: { fill, stroke, lineWidth: 1 },
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
