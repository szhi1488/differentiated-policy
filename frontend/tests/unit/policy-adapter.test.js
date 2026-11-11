import { expect, it } from 'vitest'
import {
  decimalText,
  displayValue,
  policyApiTime,
  policyDetailDisplay,
  policyDisplay,
  policyTime,
  stringId,
} from '../../src/utils/policy-adapter'

it('显示适配保留字符串精度、大 ID、数量空值、0 和 false，并过滤未知字段', () => {
  const detail = policyDetailDisplay({
    id: '9223372036854775807',
    platform: 'JD',
    model: '占位型号',
    quantity: '',
    mode: 'FIXED',
    fixedAmount: '0.10',
    maxAmount: '0.00',
    ratio: '99.99',
    priceBase: 'HAND_PRICE',
    secret: '不可透传',
  })
  expect(detail).toMatchObject({
    id: '9223372036854775807',
    fixedAmount: '0.10',
    maxAmount: '0.00',
    ratio: null,
    priceBase: null,
    quantity: '',
    quantityLabel: '未填写',
    colorScope: null,
    colorLabel: '未选择颜色范围',
  })
  expect(detail).not.toHaveProperty('secret')
  expect(displayValue(false)).toBe('否')
  expect(displayValue(0)).toBe('0')
  expect(displayValue(null)).toBe('未填写')
  expect(() => decimalText(0.1)).toThrow()
  expect(() => stringId(Number.MAX_SAFE_INTEGER)).toThrow()
})
it('三种颜色范围保持独立，固定和比例参数互斥，不补数量或生成审核历史', () => {
  const all = policyDetailDisplay({
    mode: 'RATIO',
    ratio: '0.10',
    fixedAmount: '100.00',
    colorScope: 'ALL_COLORS',
    color: '旧颜色',
    quantity: null,
  })
  expect(all).toMatchObject({
    color: null,
    colorLabel: '不限颜色',
    quantity: null,
    fixedAmount: null,
    ratio: '0.10',
  })
  expect(policyDetailDisplay({ colorScope: 'SPECIFIC_COLOR' }).colorLabel).toBe('指定颜色待选')
  expect(policyDisplay({ id: '1', status: 'APPROVED', estimated: false })).toMatchObject({
    history: [],
    estimated: false,
    estimatedLabel: '否',
  })
})
it('页面与接口时间显式转换北京时间，保留秒，不调用 UTC 转换', () => {
  expect(policyTime('2026-11-11T09:10:11+08:00')).toBe('2026-11-11T09:10:11')
  expect(policyApiTime('2026-11-11 09:10:11')).toBe('2026-11-11T09:10:11+08:00')
  expect(policyApiTime('')).toBeNull()
  expect(() => policyTime('2026-11-11T01:10:11Z')).toThrow()
  expect(() => policyTime('2026-02-30T09:10:11')).toThrow()
  expect(() => policyTime('2026-11-11T24:10:11')).toThrow()
})
