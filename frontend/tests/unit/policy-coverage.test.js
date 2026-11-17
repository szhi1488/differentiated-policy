import { expect, it } from 'vitest'
import { subsidyChanges, subsidyForScope } from '../../src/utils/policy-coverage'
import { historySnapshot } from '../fixtures/admin-policy-history'

it('补贴匹配具体商品和平台，不用数量决定范围，也不借用其他颜色或平台', () => {
  const snapshot = historySnapshot(1)
  expect(subsidyForScope(snapshot, '100', 'JD').fixedAmount).toBe('0.10')
  expect(subsidyForScope(snapshot, '100', 'PDD')).toBeNull()
  expect(subsidyForScope(snapshot, '101', 'JD')).toBeNull()
  snapshot.form.goodsForm.cards.push(snapshot.form.goodsForm.cards[0])
  expect(subsidyForScope(snapshot, '100', 'JD')).toBeNull()
})

it('固定金额和上限精确比较，区分 0、空上限及数值相同的不同字符串', () => {
  const fixed = (fixedAmount, maxSubsidyAmount) => ({
    subsidyMode: 'FIXED',
    fixedAmount,
    maxSubsidyAmount,
  })
  expect(subsidyChanges(fixed('0.10', '0.30'), fixed('0.30', '0.10'))).toEqual([
    '固定金额：增加 0.20 元',
    '补贴上限：减少 0.20 元',
  ])
  expect(subsidyChanges(fixed('1'), fixed('1.00', '0.00'))).toEqual([
    '固定金额：不变',
    '补贴上限：未填写 → 0.00',
  ])
  expect(subsidyChanges(fixed('99999999.99'), fixed('0.00'))).toEqual([
    '固定金额：减少 99999999.99 元',
  ])
})

it('同基数比例按百分点比较，换方式或换基数不推算金额', () => {
  const ratio = { subsidyMode: 'RATIO', ratio: '10.10', priceBase: 'RETAIL_PRICE' }
  expect(subsidyChanges(ratio, { ...ratio, ratio: '10.30' })).toEqual([
    '补贴比例：增加 0.20 个百分点',
  ])
  expect(subsidyChanges(ratio, { ...ratio, priceBase: 'POLICY_PRICE' })).toEqual([
    '计算基数变更，比例不直接比较',
  ])
  expect(subsidyChanges(ratio, { subsidyMode: 'FIXED', fixedAmount: '10.00' })).toEqual([
    '补贴方式变更，金额与比例不直接比较',
  ])
  expect(subsidyChanges(null, ratio)).toEqual(['未找到完整补贴方案，请核对整单内容'])
})
