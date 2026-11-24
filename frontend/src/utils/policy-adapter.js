import { SCENE_LABELS } from '../constants/policy'
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
export function selectedPolicyScenes({ sceneTypes }) {
  return sceneTypes ?? []
}

export function policySceneLabel(policy, empty = '—') {
  return (
    selectedPolicyScenes(policy)
      .map((code) => SCENE_LABELS[code] ?? code)
      .join('、') || empty
  )
}

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
