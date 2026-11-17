// 仅供机型补贴查询静态页面预览，不是接口失败时的回退数据，也不参与真实政策计算。
export const SUBSIDY_PREVIEW_AT = '2026-11-16T12:00:00'
export const SUBSIDY_PREVIEW_DATES = ['2026-08-01', '2026-11-16']
export const SUBSIDY_PREVIEW_MODELS = [
  { id: 'find-x8', brand: 'OPPO', series: 'Find 系列', name: 'Find X8', edition: '标准版' },
  { id: 'reno14', brand: 'OPPO', series: 'Reno 系列', name: 'Reno14', edition: '标准版' },
  { id: 'oneplus-13', brand: '一加', series: '数字系列', name: '一加 13', edition: '标准版' },
]
export const SUBSIDY_PREVIEW_CONFIGS = [
  { id: '12-256', label: '12GB + 256GB' },
  { id: '16-512', label: '16GB + 512GB' },
  { id: '16-1024', label: '16GB + 1T' },
]

// 每个平台有独立切换节点；这里直接提供展示区间，不在前端重建覆盖算法。
const tracks = [
  ['RETAIL', 'JD', ['08-01', '08-20', '09-14', '10-12'], ['FIXED', 'RATIO', 'FIXED', 'FIXED']],
  ['RETAIL', 'TMALL', ['08-04', '08-30', '09-22', '10-19'], ['RATIO', 'FIXED', 'RATIO', 'FIXED']],
  ['RETAIL', 'PDD', ['08-01', '08-25', '09-18', '10-16'], ['FIXED', 'FIXED', 'RATIO', 'FIXED']],
  ['RETAIL', 'DOUYIN', ['08-08', '09-02', '09-28', '10-23'], ['FIXED', 'RATIO', 'FIXED', 'RATIO']],
  [
    'RETAIL',
    'KUAISHOU',
    ['08-01', '08-28', '09-24', '10-21'],
    ['RATIO', 'FIXED', 'RATIO', 'FIXED'],
  ],
  ['BILLION', 'PDD', ['08-05', '09-01', '09-26', '10-25'], ['RATIO', 'FIXED', 'RATIO', 'FIXED']],
  ['BILLION', 'TAOBAO', ['08-12', '09-08', '10-04', '10-29'], ['FIXED', 'RATIO', 'FIXED', 'RATIO']],
  ['BILLION', 'UNICOM', ['08-18', '09-15', '10-10'], ['FIXED', 'FIXED', 'RATIO']],
]
const amounts = ['50.00', '60.00', '80.00', '100.00']
const ratios = ['3.00', '4.00', '5.00', '2.50']

export function createSubsidyPreviewSegments(modelId, configId) {
  const normal = tracks.flatMap(([channel, platformCode, dates, modes], row) =>
    dates.map((date, index) => {
      const mode = modes[index]
      const number = row * 4 + index + 1
      return {
        id: `${modelId}:${configId}:${channel}:${platformCode}:${index}`,
        channel,
        platformCode,
        scope: 'NORMAL',
        startAt: `2026-${date}T09:00:00`,
        endAt: dates[index + 1] ? `2026-${dates[index + 1]}T09:00:00` : null,
        policyCode: `ZC-${String(number).padStart(6, '0')}`,
        auditStatus: 'APPROVED',
        goods: [
          { id: `${modelId}:${configId}:black`, color: '黑色' },
          { id: `${modelId}:${configId}:white`, color: '白色' },
          { id: `${modelId}:${configId}:blue`, color: '蓝色' },
        ],
        subsidyMode: mode,
        fixedAmount: mode === 'FIXED' ? amounts[(row + index) % amounts.length] : null,
        maxSubsidyAmount: mode === 'FIXED' ? '5000.00' : null,
        ratio: mode === 'RATIO' ? ratios[(row + index) % ratios.length] : null,
        priceBase: mode === 'RATIO' ? (row % 2 === 0 ? 'RETAIL_PRICE' : 'POLICY_PRICE') : null,
      }
    }),
  )
  return [
    ...normal,
    {
      ...normal.find((segment) => segment.channel === 'RETAIL' && segment.platformCode === 'PDD'),
      id: `${modelId}:${configId}:independent`,
      scope: 'SPECIFIED',
      startAt: '2026-10-05T09:00:00',
      endAt: null,
      policyCode: 'ZC-000042',
      goods: [{ id: `${modelId}:${configId}:special-black`, color: '黑色 · 独立商品' }],
      fixedAmount: '120.00',
      maxSubsidyAmount: '6000.00',
      quantity: '0',
    },
  ]
}
