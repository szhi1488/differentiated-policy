import { PRICE_BASE_LABELS } from '../constants/policy'

/** 时间按北京时间解析；金额与比例只格式化十进制字符串。 */
export const timelineTimeValue = (value) => new Date(`${value}+08:00`).getTime()
export const timelineTimeText = (value) => value.replace('T', ' ')
export const timelineTimeFromValue = (value) =>
  new Date(Math.floor(value / 1000) * 1000 + 8 * 3600000).toISOString().slice(0, 19)
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

export function visibleTimelineSegments(segments, range, showIndependent, platformCode) {
  if (range[0] >= range[1]) {
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
