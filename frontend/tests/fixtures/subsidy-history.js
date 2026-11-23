export const directory = {
  series: [
    { seriesId: '11', brandName: 'OPPO手机' },
    { seriesId: '12', brandName: '一加手机' },
  ],
  models: [
    { seriesId: '11', spuId: '9007199254740993', modelName: 'Find X8', edition: '标准版' },
    { seriesId: '12', spuId: '22', modelName: '一加 13', edition: null },
  ],
  modelConfigurations: [
    { spuId: '9007199254740993', ram: '16GB', rom: '1024GB' },
    { spuId: '22', ram: '12GB', rom: '256GB' },
  ],
}
const at = (time) => '2026-11-' + time + '+08:00'
const fixed = { subsidyMode: 'FIXED', fixedAmount: '0.10', maxSubsidyAmount: null }
const ratio = { subsidyMode: 'RATIO', ratio: '2.50', priceBase: 'POLICY_PRICE' }
const common = (subsidy, goodsId = '51') => ({
  cardType: 'COMMON',
  products: [{ goodsId, colorName: '浮光白' }],
  subsidy,
  quantity: null,
})
const specified = (quantity) => ({
  cardType: 'SPECIFIED',
  products: [{ goodsId: '52', colorName: null, goodsName: null }],
  subsidy: ratio,
  quantity,
})
function policy(id, selections, extra = {}) {
  return {
    policyId: id,
    policyCode: 'ZC-' + id.padStart(6, '0'),
    auditLogId: '10' + id,
    submissionVersion: 1,
    policyFrom: at('01T00:00:00'),
    policyThrough: at('30T23:59:59'),
    approvedAt: at('01T00:00:00'),
    verifyType: null,
    sceneTypes: [],
    estimated: null,
    matchesQuery: true,
    selections,
    ...extra,
  }
}
const segment = (start, end, adopted, excluded = []) => ({
  startAt: at(start),
  endAt: end && at(end),
  adoptedAuditLogIds: adopted.map((id) => '10' + id),
  excludedAuditLogIds: excluded.map((id) => '10' + id),
})
export const history = {
  asOf: at('16T12:00:00'),
  tracks: [
    {
      perspective: 'RETAIL',
      platformCode: 'JD',
      goodsIds: ['51'],
      policies: [
        policy('1', [common(fixed)], { verifyType: 'SALES', sceneTypes: ['NATIONAL_SUB'] }),
        policy('2', [common(ratio)], {
          estimated: true,
          verifyType: 'SALES',
          sceneTypes: ['NATIONAL_SUB', 'LOCAL_SUB'],
        }),
        policy('3', [common(fixed)], { matchesQuery: false, policyThrough: at('02T23:59:59') }),
      ],
      segments: [
        segment('01T00:00:00', '03T00:00:00', ['1', '3'], ['2']),
        segment('03T00:00:00', '05T10:29:59', ['1'], ['2']),
        segment('05T10:30:00', '09T00:00:00', ['2'], ['1']),
        segment('09T00:00:00', null, ['1', '2']),
      ],
      plannedSegments: [
        segment('16T12:00:01', '20T00:00:00', ['1'], ['2']),
        segment('20T00:00:00', '25T00:00:00', ['1', '2']),
      ],
    },
    {
      perspective: 'BILLION',
      platformCode: 'PDD',
      goodsIds: [],
      policies: [policy('4', [common(fixed), specified(0)]), policy('5', [specified(null)])],
      segments: [
        segment('01T00:00:00', '08T00:00:00', ['4', '5']),
        segment('08T00:00:00', null, ['4'], ['5']),
      ],
      plannedSegments: [segment('16T12:00:01', '30T00:00:00', ['4'], ['5'])],
    },
  ],
}

// 京东整组原单有三件商品。仅可可棕在中间时段换单，另外两件在接口中共用一条轨道。
export function retailGroupHistory(cardType = 'COMMON') {
  const brown = { goodsId: '61', colorName: '可可棕' }
  const others = [
    { goodsId: '62', colorName: '初雪白' },
    { goodsId: '63', colorName: '奇遇喵' },
  ]
  const groupPolicy = (products) =>
    policy('23', [{ ...common(fixed), products }], { policyThrough: at('20T23:59:59') })
  return {
    asOf: history.asOf,
    configurationGoodsCount: 3,
    tracks: [
      {
        perspective: 'RETAIL',
        platformCode: 'JD',
        goodsIds: ['61'],
        policies: [
          groupPolicy([brown]),
          policy(
            '24',
            [
              {
                ...common(ratio),
                cardType,
                products: [brown],
                quantity: cardType === 'SPECIFIED' ? 0 : null,
              },
            ],
            {
              policyFrom: at('15T00:00:00'),
              policyThrough: at('17T23:59:59'),
            },
          ),
        ],
        segments: [
          segment('01T00:00:00', '15T00:00:00', ['23']),
          segment('15T00:00:00', null, ['24'], ['23']),
        ],
        plannedSegments: [
          segment('16T12:00:01', '18T00:00:00', ['24'], ['23']),
          segment('18T00:00:00', '21T00:00:00', ['23']),
        ],
      },
      {
        perspective: 'RETAIL',
        platformCode: 'JD',
        goodsIds: others.map(({ goodsId }) => goodsId),
        policies: [groupPolicy(others)],
        segments: [segment('01T00:00:00', null, ['23'])],
        plannedSegments: [segment('16T12:00:01', '21T00:00:00', ['23'])],
      },
    ],
  }
}

// 同样的三件商品，百亿接口按配置给出一份名单，先换单再叠加。
export function billionGroupHistory(cardType = 'COMMON') {
  const sample = retailGroupHistory(cardType)
  const [brown, others] = sample.tracks
  const [whole, single] = brown.policies
  whole.selections[0].products.push(...others.policies[0].selections[0].products)
  return {
    ...sample,
    tracks: [
      {
        ...brown,
        perspective: 'BILLION',
        platformCode: 'PDD',
        goodsIds: [],
        policies: [whole, single],
        plannedSegments: [
          segment('16T12:00:01', '17T00:00:00', ['24'], ['23']),
          segment('17T00:00:00', '18T00:00:00', ['23', '24']),
          segment('18T00:00:00', '21T00:00:00', ['23']),
        ],
      },
    ],
  }
}

// 同平台曾有逐商品审核，接口因此分成两条商品轨。切换后的配置审核段在两轨完全相同。
export function mixedBillionHistory(cardType = 'COMMON') {
  const sample = billionGroupHistory(cardType)
  const [modern] = sample.tracks
  modern.policies[0].policyFrom = at('08T00:00:00')
  modern.policies[0].approvedAt = at('08T00:00:00')
  modern.segments[0].startAt = at('08T00:00:00')
  const periods = modern.segments.map((part) => ({ ...part, configurationDecision: true }))
  const planned = modern.plannedSegments.map((part) => ({ ...part, configurationDecision: true }))
  const products = modern.policies[0].selections[0].products
  return {
    ...sample,
    tracks: [[products[0]], products.slice(1)].map((items, index) => {
      const code = index === 0 ? '21' : '22'
      const legacy = policy(code, [{ ...common(fixed), products: items }], {
        policyThrough: at('07T23:59:59'),
      })
      return {
        ...modern,
        goodsIds: items.map((g) => g.goodsId),
        policies: [legacy, ...modern.policies],
        segments: [
          { ...segment('01T00:00:00', '08T00:00:00', [code]), configurationDecision: false },
          ...periods,
        ],
        plannedSegments: planned,
      }
    }),
  }
}
