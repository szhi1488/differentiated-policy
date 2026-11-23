import { timelineTimeFromValue, timelineTimeValue } from '../../utils/subsidy-timeline'

// 示例日期随打开页面时的北京时间平移。各时段的采用、排除结果均已给定。
export function previewDate(day, offset) {
  return timelineTimeFromValue(timelineTimeValue(day + 'T00:00:00') + offset * 86400000).slice(
    0,
    10,
  )
}

export const RETAIL_EXAMPLE = {
  modelId: 'reno17-pro',
  configId: '12-256',
  from: '2026-11-23',
  through: '2026-11-25',
}

export const SUBSIDY_PREVIEW_MODELS = [
  {
    id: 'find-x8',
    brand: 'OPPO',
    name: 'Find X8',
    edition: '标准版',
    configs: ['16-512', '12-256', '16-1024'],
  },
  {
    id: RETAIL_EXAMPLE.modelId,
    brand: 'OPPO',
    name: 'Reno17 Pro',
    edition: '',
    configs: [RETAIL_EXAMPLE.configId],
  },
  { id: 'reno14', brand: 'OPPO', name: 'Reno14', edition: '标准版', configs: ['12-256'] },
  { id: 'oneplus-13', brand: '一加', name: '一加 13', edition: '标准版', configs: ['16-512'] },
]
export const SUBSIDY_PREVIEW_CONFIGS = [
  { id: '12-256', label: '12GB + 256GB' },
  { id: '16-512', label: '16GB + 512GB' },
  { id: '16-1024', label: '16GB + 1T' },
]

const fixed = (amount, limit = null) => ({
  subsidyMode: 'FIXED',
  fixedAmount: amount,
  maxSubsidyAmount: limit,
})
const ratio = (percent, base) => ({ subsidyMode: 'RATIO', ratio: percent, priceBase: base })
const dayStart = (date) => date + 'T00:00:00'

export function createSubsidyPreviewSegments(modelId, configId, day) {
  const date = (offset) =>
    previewDate(modelId === RETAIL_EXAMPLE.modelId ? RETAIL_EXAMPLE.from : day, offset)
  const midnight = (offset) => dayStart(date(offset))
  // 截止日含最后一秒。图表用下一段的起点作为排他结束时间。
  function policy(code, from, through, plan, goods, options = {}) {
    return {
      policyCode: 'ZC-' + code,
      approvedAt: date(from - 1) + 'T16:30:00',
      policyFrom: date(from),
      policyThrough: date(through),
      goods,
      estimated: options.scenes ? false : null,
      ...plan,
      ...options,
    }
  }
  function segment(
    track,
    startAt,
    endAt,
    policies,
    decision = '单独适用',
    note = '',
    excluded = [],
  ) {
    return {
      ...track,
      id: [modelId, configId, track.trackId, startAt].join(':'),
      startAt,
      endAt,
      policies,
      decision,
      note,
      excluded,
    }
  }
  if (modelId === RETAIL_EXAMPLE.modelId && configId === RETAIL_EXAMPLE.configId) {
    const goods = [
      { id: '8401', color: '可可棕' },
      { id: '8402', color: '初雪白' },
      { id: '8403', color: '奇遇喵' },
    ]
    const track = {
      channel: 'RETAIL',
      platformCode: 'JD',
      trackId: 'jd-reno17',
      scope: 'NORMAL',
      goods,
      label: goods.map((item) => item.color).join('、'),
    }
    const national = { scenes: ['国补'], verify: '销售维度' }
    const old = policy('000003', 0, 2, ratio('20.00', 'POLICY_PRICE'), goods, national)
    const flat = policy('000006', 0, 1, fixed('20.00', '100.00'), goods, national)
    const estimate = policy('000007', 0, 2, ratio('2.00', 'POLICY_PRICE'), goods, {
      ...national,
      scenes: ['国补', '地补'],
      estimated: true,
    })
    return [
      segment(
        track,
        midnight(0),
        midnight(2),
        [flat, estimate],
        '跨组叠加',
        '国补组选用 006，国补＋地补组选用 007。三个颜色均按这两张政策叠加。',
        [old],
      ),
      segment(
        track,
        midnight(2),
        midnight(3),
        [old],
        '单独适用',
        '本段确认仅采用 003。预估政策 007 仍在原单日期内，本段未选用。',
        [estimate],
      ),
    ]
  }
  if (modelId !== 'find-x8' || configId !== '16-512') {
    const example = {
      'find-x8:12-256': ['000310', '8101', '浮光白', '80.00'],
      'reno14:12-256': ['000311', '8201', '人鱼色', '60.00'],
      'oneplus-13:16-512': ['000312', '8301', '黑曜秘境', '90.00'],
    }[[modelId, configId].join(':')]
    if (!example) {
      return []
    }
    const [code, id, color, amount] = example
    const goods = [{ id, color }]
    const track = {
      trackId: 'jd',
      channel: 'RETAIL',
      platformCode: 'JD',
      scope: 'NORMAL',
      label: color,
      goods,
    }
    const plan = policy(code, -12, 2, fixed(amount), goods, {
      scenes: ['国补'],
      verify: '销售维度',
    })
    return [segment(track, midnight(-12), midnight(3), [plan])]
  }

  const white = { id: '8001', color: '浮光白' }
  const black = { id: '8002', color: '星野黑' }
  const blue = { id: '8003', color: '追风蓝' }
  const special = { id: '8004', color: '星野黑' }
  const pair = [white, black]
  const colors = [...pair, blue]
  const jd = { channel: 'RETAIL', platformCode: 'JD', scope: 'NORMAL' }
  const jdPair = { ...jd, trackId: 'jd-pair', label: '浮光白、星野黑', goods: pair }
  const jdBlue = { ...jd, trackId: 'jd-blue', label: blue.color, goods: [blue] }
  const pdd = {
    ...jd,
    platformCode: 'PDD',
    trackId: 'pdd-common',
    label: '三款颜色',
    goods: colors,
  }
  const independent = {
    ...pdd,
    trackId: 'pdd-specified',
    scope: 'SPECIFIED',
    label: '独立商品',
    goods: [special],
  }
  const billion = {
    channel: 'BILLION',
    platformCode: 'PDD',
    scope: 'NORMAL',
    trackId: 'billion-pdd',
    label: '整组配置',
  }

  const national = { scenes: ['国补'], verify: '销售维度' }
  const base = policy('000110', -20, 5, fixed('100.00', '5000.00'), colors, national)
  const blueOffer = policy('000113', -12, -8, fixed('130.00'), [blue], national)
  const replacement = policy('000111', -7, 2, fixed('150.00', '6000.00'), pair, {
    ...national,
    approvedAt: date(-7) + 'T10:30:00',
  })
  const extra = policy('000112', -3, 1, ratio('2.50', 'POLICY_PRICE'), pair, {
    estimated: true,
    scenes: ['地补', '自补'],
    verify: '销售维度',
  })
  const pddPlan = policy('000130', -16, 4, ratio('3.00', 'RETAIL_PRICE'), colors, national)
  const specified = policy('000042', -6, 3, fixed('120.00'), [special], {
    ...national,
    quantity: 0,
  })
  const plan201 = policy('000201', -18, 5, fixed('100.00'), [black])
  const plan202 = policy('000202', -5, 2, fixed('150.00'), [white], { quantity: 0 })
  const sameGroup = '两单均为国补、销售维度。本段确认采用 ZC-000111。'
  return [
    segment(jdPair, midnight(-20), replacement.approvedAt, [base]),
    segment(jdPair, replacement.approvedAt, midnight(-3), [replacement], '同组替换', sameGroup, [
      base,
    ]),
    segment(
      jdPair,
      midnight(-3),
      midnight(2),
      [replacement, extra],
      '跨组叠加',
      '本段确认采用国补组的 ZC-000111 和地补＋自补组的 ZC-000112。后一张政策的比例补贴只计一次。',
      [base],
    ),
    segment(jdPair, midnight(2), midnight(3), [replacement], '同组替换', sameGroup, [base]),
    segment(jdPair, midnight(3), midnight(6), [base]),
    segment(jdBlue, midnight(-20), midnight(-12), [base]),
    segment(
      jdBlue,
      midnight(-12),
      midnight(-7),
      [blueOffer],
      '同组替换',
      '追风蓝在本段确认采用 ZC-000113。',
      [base],
    ),
    segment(
      jdBlue,
      midnight(-7),
      midnight(6),
      [base],
      '单独适用',
      'ZC-000113 已到期，追风蓝恢复采用仍在有效期内的 ZC-000110。',
    ),
    segment(pdd, midnight(-16), midnight(5), [pddPlan]),
    segment(independent, midnight(-6), midnight(4), [specified]),
    segment(billion, midnight(-18), midnight(-5), [plan201]),
    segment(
      billion,
      midnight(-5),
      midnight(3),
      [plan201],
      '交叉段选一',
      '两单选中不同颜色，但属于同一机型配置。本段确认仅采用 ZC-000201。',
      [plan202],
    ),
    segment(billion, midnight(3), midnight(6), [plan201]),
    segment(
      { ...billion, platformCode: 'TAOBAO', trackId: 'billion-taobao' },
      midnight(-5),
      midnight(3),
      [
        policy('000203', -5, 2, fixed('80.00'), [black]),
        policy('000204', -5, 2, ratio('1.50', 'HAND_PRICE'), [white], { quantity: null }),
      ],
      '同配置叠加',
      '本段确认两单叠加。普通商品和指定商品分别保留各自的补贴方案。',
    ),
  ]
}
