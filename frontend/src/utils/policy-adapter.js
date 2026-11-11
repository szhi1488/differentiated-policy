import {
  CHANNEL_LABELS,
  PLATFORM_LABELS,
  VERIFY_LABELS,
  SCENE_LABELS,
  PRICE_BASE_LABELS,
} from '../constants/policy'
import { queryDate } from './query'

/** 展示模型只白名单读取；保留原始编码，文案不作为筛选或业务身份。 */
export const displayValue = (value, empty = '未填写') =>
  value === null || value === undefined || value === ''
    ? empty
    : typeof value === 'boolean'
      ? value
        ? '是'
        : '否'
      : String(value)
export function stringId(value) {
  if (value === null || value === undefined || value === '') {
    return null
  }
  if (typeof value !== 'string') {
    throw new TypeError('ID 必须是字符串')
  }
  return value
}
export function decimalText(value) {
  if (value === null || value === undefined || value === '') {
    return null
  }
  if (typeof value !== 'string' || !/^\d+(?:\.\d+)?$/.test(value)) {
    throw new TypeError('金额及比例必须是十进制字符串')
  }
  return value
}
/** 北京时间墙上时间，不经 Date 或 UTC。认证 Instant 不经过此适配。 */
export function policyTime(value) {
  if (value === null || value === undefined || value === '') {
    return ''
  }
  if (
    typeof value !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\+08:00)?$/.test(value)
  ) {
    throw new TypeError('政策时间必须精确到秒并使用北京时间')
  }
  const normalized = value.replace('+08:00', '').replace(' ', 'T')
  if (
    !queryDate(normalized.slice(0, 10)) ||
    normalized.slice(11, 13) > '23' ||
    normalized.slice(14, 16) > '59' ||
    normalized.slice(17, 19) > '59'
  ) {
    throw new TypeError('政策时间无效')
  }
  return normalized
}
export const policyApiTime = (value) => (policyTime(value) ? `${policyTime(value)}+08:00` : null)
export function policyDetailDisplay(detail) {
  const fixedAmount = detail.mode === 'FIXED' ? decimalText(detail.fixedAmount) : null
  const maxAmount = detail.mode === 'FIXED' ? decimalText(detail.maxAmount) : null
  const ratio = detail.mode === 'RATIO' ? decimalText(detail.ratio) : null
  return {
    id: stringId(detail.id),
    platform: detail.platform,
    platformLabel: PLATFORM_LABELS[detail.platform] ?? '未知平台',
    model: detail.model,
    spec: detail.spec,
    colorScope: detail.colorScope ?? null,
    color: detail.colorScope === 'ALL_COLORS' ? null : (detail.color ?? null),
    colorLabel:
      detail.colorScope === 'ALL_COLORS'
        ? '不限颜色'
        : detail.colorScope === 'SPECIFIC_COLOR'
          ? displayValue(detail.color, '指定颜色待选')
          : '未选择颜色范围',
    quantity: detail.quantity ?? null,
    quantityLabel: displayValue(detail.quantity),
    mode: detail.mode,
    fixedAmount,
    maxAmount,
    ratio,
    priceBase: detail.mode === 'RATIO' ? (detail.priceBase ?? null) : null,
    subsidy:
      detail.mode === 'FIXED'
        ? `固定金额 ${displayValue(fixedAmount)} 元；上限 ${displayValue(maxAmount)} 元`
        : detail.mode === 'RATIO'
          ? `比例 ${displayValue(ratio)}%；基数：${PRICE_BASE_LABELS[detail.priceBase] ?? '未选择'}`
          : '未选择补贴方式',
  }
}
/** 当前用于显式展示入口；未来真实 VO 另按接口字段适配，禁止直接发送本模型。 */
export function policyDisplay(policy) {
  return {
    id: stringId(policy.id),
    code: policy.code,
    title: policy.title,
    channel: policy.channel,
    channelLabel: CHANNEL_LABELS[policy.channel] ?? '未选择',
    platforms: [...(policy.platforms ?? [])],
    platformLabels: (policy.platforms ?? []).map((code) => PLATFORM_LABELS[code] ?? '未知平台'),
    verifyType: policy.verifyType ?? null,
    verifyLabel: VERIFY_LABELS[policy.verifyType] ?? '不适用',
    scene: policy.scene ?? null,
    sceneLabel: SCENE_LABELS[policy.scene] ?? '不适用',
    estimated: policy.estimated ?? null,
    estimatedLabel: displayValue(policy.estimated, '不适用'),
    applicant: policy.applicant,
    submittedAt: policyTime(policy.submittedAt).replace('T', ' '),
    start: policyTime(policy.start).replace('T', ' '),
    end: policyTime(policy.end).replace('T', ' '),
    status: policy.status,
    round: policy.round,
    remark: policy.remark ?? null,
    reviewRemark: policy.reviewRemark ?? null,
    details: (policy.details ?? []).map(policyDetailDisplay),
    history: (policy.history ?? []).map((item) => ({
      id: item.id,
      time: item.time,
      action: item.action,
      actor: item.actor,
      remark: item.remark,
    })),
  }
}
