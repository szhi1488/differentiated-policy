import { expect, it } from 'vitest'
import {
  decimalText,
  displayValue,
  policyApiTime,
  policyTime,
  stringId,
} from '../../src/utils/policy-adapter'

it('显示适配保留字符串精度、大 ID、数量空值、0 和 false', () => {
  expect(decimalText('0.10')).toBe('0.10')
  expect(stringId('9223372036854775807')).toBe('9223372036854775807')
  expect(displayValue(false)).toBe('否')
  expect(displayValue(0)).toBe('0')
  expect(displayValue(null)).toBe('未填写')
  expect(() => decimalText(0.1)).toThrow()
  expect(() => stringId(Number.MAX_SAFE_INTEGER)).toThrow()
})
it('页面与接口时间显式转换北京时间，保留秒，不调用 UTC 转换', () => {
  expect(policyTime('2026-11-11T09:10:11+08:00')).toBe('2026-11-11T09:10:11')
  expect(policyApiTime('2026-11-11 09:10:11')).toBe('2026-11-11T09:10:11+08:00')
  expect(policyApiTime('')).toBeNull()
  expect(() => policyTime('2026-11-11T01:10:11Z')).toThrow()
  expect(() => policyTime('2026-02-30T09:10:11')).toThrow()
  expect(() => policyTime('2026-11-11T24:10:11')).toThrow()
})
