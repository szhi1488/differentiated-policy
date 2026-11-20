import { currentDetail } from './admin-policy-history.js'

export const referenceWinner = {
  id: '4',
  policyCode: 'ZC-000004',
  auditLogId: '800',
  submissionVersion: 1,
}
export function coverageDetail() {
  const detail = currentDetail()
  detail.coverage.items = Array.from({ length: 12 }, (_, index) => ({
    goodsId: String(100 + index),
    platformCode: index === 11 ? 'PDD' : 'JD',
    outcome: index === 11 ? 'RETAINED' : index === 10 ? 'NEW' : 'REPLACES',
    winner: index === 10 ? null : referenceWinner,
  }))
  detail.snapshot.goods = detail.coverage.items.map(({ goodsId }, index) => ({
    goodsId,
    goodsName: `测试商品${index + 1}`,
    modelName: '测试机型',
    colorName: '初雪白',
  }))
  detail.snapshot.form.goodsForm.cards[0].targets = detail.coverage.items.map(({ goodsId }) => ({
    goodsId,
    spuId: '10',
    ram: '12GB',
    rom: '256GB',
  }))
  return detail
}
// 与管理员示例一致：3 个普通颜色加 1 个其他配置，每个商品适用两个平台。
export function groupedCoverageDetail() {
  const detail = currentDetail()
  const goods = [
    {
      goodsId: '100',
      spuId: '10',
      modelName: 'Reno17 Pro',
      ram: '12GB',
      rom: '256GB',
      colorName: '可可棕',
    },
    {
      goodsId: '101',
      spuId: '10',
      modelName: 'Reno17 Pro',
      ram: '12GB',
      rom: '256GB',
      colorName: '初雪白',
    },
    {
      goodsId: '102',
      spuId: '10',
      modelName: 'Reno17 Pro',
      ram: '12GB',
      rom: '256GB',
      colorName: '奇遇喵',
    },
    {
      goodsId: '103',
      spuId: '11',
      modelName: 'Reno17',
      ram: '12GB',
      rom: '512GB',
      colorName: '可可棕',
    },
  ].map((item) => ({
    ...item,
    edition: null,
    goodsName: `${item.modelName} ${item.ram}+${item.rom} ${item.colorName}`,
  }))
  detail.snapshot.goods = goods
  detail.snapshot.form.selectedPlatforms = ['ZHAOSHANG', 'KUAISHOU']
  detail.snapshot.form.goodsForm.cards = [goods.slice(0, 3), goods.slice(3)].map(
    (products, index) => ({
      key: `common-${index}`,
      cardType: 'COMMON',
      followsOrderPlatforms: true,
      targets: products.map(({ goodsId, spuId, ram, rom }) => ({ goodsId, spuId, ram, rom })),
      subsidyPlans:
        index === 0
          ? [
              { key: 'ratio', subsidyMode: 'RATIO', ratio: '25.00', priceBase: 'RETAIL_PRICE' },
              {
                key: 'fixed',
                subsidyMode: 'FIXED',
                fixedAmount: '50.00',
                maxSubsidyAmount: '250.00',
              },
            ]
          : [
              {
                key: 'fixed',
                subsidyMode: 'FIXED',
                fixedAmount: '10.00',
                maxSubsidyAmount: '30.00',
              },
            ],
      platformAssignments: [
        { platformCode: 'ZHAOSHANG', planKey: index === 0 ? 'ratio' : 'fixed' },
        { platformCode: 'KUAISHOU', planKey: 'fixed' },
      ],
    }),
  )
  detail.coverage.items = goods.flatMap(({ goodsId }) =>
    ['ZHAOSHANG', 'KUAISHOU'].map((platformCode) => ({
      goodsId,
      platformCode,
      outcome: 'NEW',
      winner: null,
      existingSubsidy: null,
    })),
  )
  return detail
}
