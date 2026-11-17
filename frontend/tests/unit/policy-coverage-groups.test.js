import { expect, it } from 'vitest'
import { coverageProducts, groupCoverageProducts } from '../../src/utils/policy-coverage-groups'
import { groupedCoverageDetail, referenceWinner } from '../fixtures/policy-coverage'

const groups = (detail) => groupCoverageProducts(coverageProducts(detail))
const fixed = (fixedAmount = '10.00', maxSubsidyAmount = null) => ({
  key: 'old',
  subsidyMode: 'FIXED',
  fixedAmount,
  maxSubsidyAmount,
})
function replacingDetail() {
  const detail = groupedCoverageDetail()
  detail.coverage.items.forEach((row) =>
    Object.assign(row, { outcome: 'REPLACES', winner: referenceWinner, existingSubsidy: fixed() }),
  )
  return detail
}

it('8 项商品平台范围合并为 4 组，只合并普通卡并完整保留三个实际勾选商品', () => {
  const detail = groupedCoverageDetail(),
    original = JSON.stringify(detail)
  const rows = groups(detail)
  expect(rows).toHaveLength(4)
  expect(rows[0]).toMatchObject({
    name: 'Reno17 Pro · 12GB+256GB',
    colorCount: 3,
    platformCode: 'ZHAOSHANG',
  })
  expect(rows[0].members.map((row) => row.goodsId)).toEqual(['100', '101', '102'])
  expect(rows.map((row) => row.members.length)).toEqual([3, 3, 1, 1])
  expect(JSON.stringify(detail)).toBe(original)
})

it.each([
  [
    '原补贴金额',
    (row) => {
      row.existingSubsidy = fixed('20.00')
    },
  ],
  [
    '原上限 0 与未填写',
    (row) => {
      row.existingSubsidy = fixed('10.00', '0.00')
    },
  ],
  [
    '原补贴方式',
    (row) => {
      row.existingSubsidy = { subsidyMode: 'RATIO', ratio: '10.00', priceBase: 'RETAIL_PRICE' }
    },
  ],
  [
    '对照政策',
    (row) => {
      row.winner = { ...referenceWinner, id: '7' }
    },
  ],
  [
    '对照轮次',
    (row) => {
      row.winner = { ...referenceWinner, auditLogId: '801', submissionVersion: 2 }
    },
  ],
  [
    '覆盖结果',
    (row) => {
      row.outcome = 'RETAINED'
    },
  ],
])('%s 不同仍汇总同一配置，差异保留在商品明细且禁用整组对照', (_, change) => {
  const detail = replacingDetail()
  change(detail.coverage.items[0])
  const rows = groups(detail)
  expect(rows).toHaveLength(4)
  expect(rows[0].members.map((row) => row.goodsId)).toEqual(['100', '101', '102'])
  expect(rows[0].members[0]).toMatchObject(detail.coverage.items[0])
  expect(rows[0].comparisonUniform).toBe(false)
  expect(rows[0].winner).toBeNull()
})

it('本次补贴不同仍汇总，机型版本、配置和指定卡继续隔离', () => {
  const detail = groupedCoverageDetail()
  const card = detail.snapshot.form.goodsForm.cards[0]
  const single = { ...card, key: 'single', cardType: 'SPECIFIED', targets: [card.targets[0]] }
  card.targets = card.targets.slice(1)
  detail.snapshot.form.goodsForm.cards.push(single)
  expect(groups(detail)).toHaveLength(6)
  single.cardType = 'COMMON'
  single.subsidyPlans = card.subsidyPlans.map((plan) => ({
    ...plan,
    ratio: '30.00',
    fixedAmount: '60.00',
  }))
  expect(groups(detail)).toHaveLength(4)
  expect(groups(detail)[0]).toMatchObject({ subsidyVaries: true, proposedSubsidy: null })
  single.subsidyPlans = card.subsidyPlans
  detail.snapshot.goods[0].edition = '特别版'
  expect(groups(detail)).toHaveLength(6)
  detail.snapshot.goods[0].edition = null
  single.targets = [{ ...single.targets[0], rom: '1024GB' }]
  detail.snapshot.goods[0].rom = '1024GB'
  expect(groups(detail)).toHaveLength(6)
  expect(groups(detail).some((row) => row.name.includes('1T'))).toBe(true)
})

it('同色不同 SKU 保留各自 ID，缺少旧补贴仍汇总但不共用对照，规格缺失显示单品', () => {
  const detail = replacingDetail()
  detail.snapshot.goods[1].colorName = '可可棕'
  expect(groups(detail)[0]).toMatchObject({
    colorCount: 2,
    members: expect.arrayContaining([
      expect.objectContaining({ goodsId: '100' }),
      expect.objectContaining({ goodsId: '101' }),
    ]),
  })
  detail.coverage.items.forEach((row) => {
    delete row.existingSubsidy
  })
  expect(groups(detail)).toHaveLength(4)
  expect(groups(detail)[0].comparisonUniform).toBe(false)
  const unknown = groupedCoverageDetail()
  unknown.snapshot.goods = []
  expect(groups(unknown)).toHaveLength(8)
})

it.each([
  ['PENDING', 'REPLACES', 'NEW'],
  ['PENDING', 'RETAINED', 'NEW'],
  ['APPROVED', 'SUPERSEDED', 'EFFECTIVE'],
])('%s 混合结果显示部分覆盖，其他平台结果及原始成员不变', (status, first, rest) => {
  const detail = groupedCoverageDetail()
  detail.policy.status = status
  detail.coverage.items.forEach((row) => {
    row.outcome = rest
  })
  detail.coverage.items[0].outcome = first
  const rows = groups(detail)
  expect(rows[0]).toMatchObject({
    outcome: 'PARTIAL',
    outcomeCounts: { [first]: 1, [rest]: 2 },
  })
  expect(rows[0].members.map((row) => row.outcome)).toEqual([first, rest, rest])
  expect(rows[1]).toMatchObject({ outcome: rest, outcomeCounts: { [rest]: 3 } })
})

it('方案 key 与金额尾零不影响合并，筛选单个颜色后只展示该颜色', () => {
  const detail = replacingDetail()
  detail.coverage.items[0].existingSubsidy = { ...fixed('10'), key: 'other-key' }
  expect(groups(detail)).toHaveLength(4)
  const filtered = coverageProducts(detail).filter((row) => row.color === '初雪白')
  expect(
    groupCoverageProducts(filtered).map((row) => row.members.map((member) => member.goodsId)),
  ).toEqual([['101'], ['101']])
})
