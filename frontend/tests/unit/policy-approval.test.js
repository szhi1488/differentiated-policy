import { expect, it } from 'vitest'
import { coverageSignature } from '../../src/utils/policy-approval'
import { groupedCoverageDetail, referenceWinner } from '../fixtures/policy-coverage'

it('覆盖核对忽略查询时间、假设通过时间、条目顺序和金额尾零', () => {
  const before = groupedCoverageDetail().coverage
  before.items[0].existingSubsidy = { subsidyMode: 'FIXED', fixedAmount: '40.00' }
  const after = structuredClone(before)
  after.at = after.effectiveFrom = '2026-11-16T15:00:00+08:00'
  after.items[0].existingSubsidy.fixedAmount = '40'
  after.items.reverse()
  expect(coverageSignature(before)).toBe(coverageSignature(after))
})

it.each(['winner', 'subsidy', 'scope', 'outcome'])('汇总项数相同也识别 %s 变化', (change) => {
  const before = groupedCoverageDetail().coverage
  before.items[0].winner = referenceWinner
  before.items[0].existingSubsidy = { subsidyMode: 'FIXED', fixedAmount: '40.00' }
  const after = structuredClone(before)
  if (change === 'winner') {
    after.items[0].winner.auditLogId = '801'
  }
  if (change === 'subsidy') {
    after.items[0].existingSubsidy = {
      subsidyMode: 'RATIO',
      ratio: '25.00',
      priceBase: 'RETAIL_PRICE',
    }
  }
  if (change === 'scope') {
    after.items[0].goodsId = '999'
  }
  if (change === 'outcome') {
    after.items[0].outcome = 'RETAINED'
  }
  expect(coverageSignature(before)).not.toBe(coverageSignature(after))
})
