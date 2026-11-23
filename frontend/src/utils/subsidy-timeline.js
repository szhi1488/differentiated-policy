import { PRICE_BASE_LABELS } from '../constants/policy'

/** 时间按北京时间解析；金额与比例只格式化十进制字符串。 */
export const timelineTimeValue = (value) => new Date(`${value}+08:00`).getTime()
// ECharts 的 UTC 时间刻度使用北京墙上时间，让日期线落在北京时间零点。
export const timelineAxisValue = (beijingTime) => Date.parse(beijingTime + 'Z')
export const timelineAxisTime = (value) => new Date(value).toISOString().slice(0, 19)
export const timelineTimeText = (value) => value.replace('T', ' ')
export const timelineTimeFromValue = (value) =>
  new Date(Math.floor(value / 1000) * 1000 + 8 * 3600000).toISOString().slice(0, 19)
// 展示给业务人员的是包含在该段内的最后一秒。
export const timelineLastSecond = (exclusiveEnd) =>
  timelineTimeFromValue(timelineTimeValue(exclusiveEnd) - 1000)
export const timelineDecimal = (value) =>
  String(value)
    .replace(/\.0+$/, '')
    .replace(/(\.\d*?)0+$/, '$1')

export function timelinePlanText(segment) {
  return segment.subsidyMode === 'FIXED'
    ? `固定 ${timelineDecimal(segment.fixedAmount)} 元`
    : `比例 ${timelineDecimal(segment.ratio)}%`
}

export function timelinePlanDetail(segment) {
  return segment.subsidyMode === 'FIXED'
    ? `补贴上限：${segment.maxSubsidyAmount === null ? '未填写' : timelineDecimal(segment.maxSubsidyAmount) + ' 元'}`
    : `计算基数：${PRICE_BASE_LABELS[segment.priceBase] ?? '未填写'}`
}

export function timelinePlanCaption(segment) {
  return segment.subsidyMode === 'RATIO'
    ? `${timelineDecimal(segment.ratio)}% · ${PRICE_BASE_LABELS[segment.priceBase] ?? '基数未填写'}`
    : timelinePlanText(segment)
}

/** 只匹配示例已提供的适用区间，不在前端重建审核或覆盖算法。 */
export function timelineContainsAt(segment, at) {
  return Boolean(at && segment.startAt <= at && (!segment.endAt || at < segment.endAt))
}

/** 整理已给定的采用结果。同范围同一轮政策连续采用时共用起止，停用后恢复则另起一段。 */
export function withPolicyUsePeriods(segments) {
  const history = new Map()
  function periodsFor(segment, plan) {
    const key = JSON.stringify([
      segment.channel,
      segment.platformCode,
      segment.trackId,
      plan.policyCode,
      plan.submissionVersion,
    ])
    if (!history.has(key)) {
      history.set(key, [])
    }
    return history.get(key)
  }
  const result = segments.map((segment) => ({ ...segment }))
  for (const segment of [...result].sort((a, b) => a.startAt.localeCompare(b.startAt))) {
    segment.excluded = segment.excluded.map((plan) => ({
      ...plan,
      usePeriods: periodsFor(segment, plan),
    }))
    segment.policies = segment.policies.map((plan) => {
      const usePeriods = periodsFor(segment, plan)
      let period = usePeriods.at(-1)
      if (period && period.endAt === segment.startAt) {
        period.endAt = segment.endAt
      } else {
        period = { startAt: segment.startAt, endAt: segment.endAt }
        usePeriods.push(period)
      }
      return { ...plan, usePeriod: period, usePeriods }
    })
  }
  return result
}

export function timelineEndText(endAt) {
  return endAt ? timelineTimeText(timelineLastSecond(endAt)) : '尚无确定结束时间'
}

export function visibleTimelineSegments(segments, range, showIndependent, platformCode) {
  if (range[0] > range[1]) {
    return []
  }
  return segments.filter(
    (segment) =>
      (!platformCode || segment.platformCode === platformCode) &&
      (showIndependent || segment.scope === 'NORMAL') &&
      segment.startAt <= range[1] &&
      (!segment.endAt || segment.endAt > range[0]),
  )
}
