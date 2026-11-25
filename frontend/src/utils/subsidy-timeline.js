import { PRICE_BASE_LABELS } from '../constants/policy'

/** 时间按北京时间解析；金额与比例只格式化十进制字符串。 */
export const timelineTimeValue = (value) => new Date(`${value}+08:00`).getTime()
// ECharts 的 UTC 时间刻度使用北京墙上时间，让日期线落在北京时间零点。
export const timelineAxisValue = (beijingTime) => Date.parse(beijingTime + 'Z')
export const timelineAxisTime = (value) => new Date(value).toISOString().slice(0, 19)
export const timelineTimeText = (value) => value.replace('T', ' ')
export const timelineTimeFromValue = (value) =>
  new Date(Math.floor(value / 1000) * 1000 + 8 * 3600000).toISOString().slice(0, 19)

export function timelineMonthDates(beijingTime) {
  const month = beijingTime.slice(0, 7)
  const [year, monthNumber] = month.split('-').map(Number)
  const lastDay = new Date(Date.UTC(year, monthNumber, 0)).toISOString().slice(0, 10)
  return [month + '-01', lastDay]
}

// 展示给业务人员的是包含在该段内的最后一秒。
export const timelineLastSecond = (exclusiveEnd) =>
  timelineTimeFromValue(timelineTimeValue(exclusiveEnd) - 1000)
export const timelineDecimal = (value) =>
  String(value)
    .replace(/\.0+$/, '')
    .replace(/(\.\d*?)0+$/, '$1')

export function timelineGoodsLabel(goods) {
  return goods.map((g) => g.label || g.color || '#' + g.id).join('、')
}

export function timelinePartStatus(part) {
  return part.status ?? (part.mixed ? '部分采用' : part.used ? '采用' : '未采用')
}

// 新普通卡以当轮确认的商品总数和申报 ID 为准；早期普通卡按原有目录口径识别。
export function timelineGoodsScope(goods, plans, configurationGoodsCount) {
  const fullConfiguration =
    goods.length > 0 &&
    plans.length > 0 &&
    plans.every((plan) => {
      if (plan.cardType !== 'COMMON') {
        return false
      }
      if (plan.wholeConfigurationGoodsCount === null) {
        return goods.length === configurationGoodsCount
      }
      if (goods.length !== plan.wholeConfigurationGoodsCount) {
        return false
      }
      const declared = new Set(plan.goods.map((g) => g.id))
      return goods.every((g) => declared.has(g.id))
    })
  return {
    fullConfiguration,
    label: fullConfiguration ? '整组配置' : timelineGoodsLabel(goods) || '原单未注明商品',
  }
}

export function appendTimelinePart(parts, startAt, endAt, used, open, planned) {
  const last = parts.at(-1)
  const status = planned ? (used ? '计划采用' : '计划未采用') : undefined
  if (last && last.used === used && last.status === status && last.endAt === startAt) {
    Object.assign(last, { endAt, open })
  } else {
    parts.push({ startAt, endAt, used, open, status })
  }
}

export function timelinePlanText(segment) {
  return segment.subsidyMode === 'FIXED'
    ? `固定 ${timelineDecimal(segment.fixedAmount)} 元`
    : `比例 ${timelineDecimal(segment.ratio)}%`
}

export function timelinePlanDetail(segment) {
  if (segment.subsidyMode === 'FIXED') {
    const cap = segment.maxSubsidyAmount
    const amount = cap === null || cap === undefined ? '未填写' : timelineDecimal(cap) + ' 元'
    return `补贴上限：${amount}`
  }
  return `计算基数：${PRICE_BASE_LABELS[segment.priceBase] ?? '未填写'}`
}

export function timelinePlanCaption(segment) {
  return segment.subsidyMode === 'RATIO'
    ? `${timelineDecimal(segment.ratio)}% · ${PRICE_BASE_LABELS[segment.priceBase] ?? '基数未填写'}`
    : timelinePlanText(segment)
}

/** 时点按左闭右开区间匹配。 */
export function timelineContainsAt(segment, at) {
  return Boolean(at && segment.startAt <= at && (!segment.endAt || at < segment.endAt))
}

export function timelineEndText(endAt, open = false) {
  if (!endAt) {
    return '尚无确定结束时间'
  }
  const end = timelineTimeText(timelineLastSecond(endAt))
  return open ? end + '（截至查询时）' : end
}
