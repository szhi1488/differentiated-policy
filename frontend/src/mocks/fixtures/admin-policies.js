const fixed = { subsidyMode: 'FIXED', fixedAmount: '500.00' }
const ratio = { subsidyMode: 'RATIO', ratio: '15.00', basePriceType: 'HAND_PRICE' }

const find = { id: 'find-x8-black', model: 'OPPO Find X8', config: '16GB + 512GB', color: '星野黑' }
const findWhite = { ...find, id: 'find-x8-white', color: '浮光白' }
const reno = { id: 'reno14', model: 'OPPO Reno14', config: '12GB + 256GB', color: '人鱼姬' }
const ace = { id: 'ace-5', model: '一加 Ace 5', config: '16GB + 512GB', color: '全速黑' }
const oneplus = { id: 'oneplus-13', model: '一加 13', config: '16GB + 512GB', color: '白露晨曦' }

// 普通行预先按型号、配置、适用平台和补贴方案归组，保留本轮选中的商品。
function common(products, subsidy, platformCodes = null) {
  const [{ id, model, config }] = products
  return {
    id: [id, ...(platformCodes ?? [])].join('-'),
    model,
    config,
    cardType: 'COMMON',
    selectedProducts: products,
    platformCodes,
    subsidy,
  }
}

function specified(product, platformAssignments) {
  return { ...product, cardType: 'SPECIFIED', platformAssignments }
}

// 单号、提交时间、渠道、整单平台、核销类型、场景、提交人、状态、商品及补贴。
const submissions = [
  [
    'POL-20250907-001',
    '2025-09-07 14:30:00',
    'RETAIL',
    ['DOUYIN', 'JD'],
    'SALES',
    'NATIONAL_SUB',
    '张三',
    'PENDING',
    [
      common([find, findWhite], fixed, ['DOUYIN']),
      specified(oneplus, [
        { platformCode: 'DOUYIN', quantity: 500, subsidy: ratio },
        { platformCode: 'JD', quantity: 200, subsidy: fixed },
      ]),
    ],
  ],
  [
    'POL-20250906-002',
    '2025-09-06 16:20:00',
    'BILLION',
    ['PDD'],
    null,
    null,
    '李四',
    'APPROVED',
    [common([ace], fixed)],
  ],
  [
    'POL-20250906-003',
    '2025-09-06 11:12:00',
    'RETAIL',
    ['JD'],
    'SALES',
    'LOCAL_SUB',
    '王五',
    'REJECTED',
    [common([reno], ratio)],
  ],
  [
    'POL-20250905-004',
    '2025-09-05 10:45:00',
    'BILLION',
    ['TAOBAO'],
    null,
    null,
    '赵六',
    'APPROVED',
    [common([find], fixed)],
  ],
  [
    'POL-20250904-005',
    '2025-09-04 09:30:00',
    'RETAIL',
    ['UNICOM'],
    'SALES',
    'SELF_SUB',
    '钱七',
    'PENDING',
    [specified(ace, [{ platformCode: 'UNICOM', quantity: 240, subsidy: ratio }])],
  ],
  [
    'POL-20250903-006',
    '2025-09-03 16:15:00',
    'RETAIL',
    ['JD', 'TMALL'],
    'ACTIVATION',
    'LOCAL_SUB',
    '王五',
    'APPROVED',
    [common([oneplus], fixed)],
  ],
  [
    'POL-20250903-007',
    '2025-09-03 09:20:00',
    'RETAIL',
    ['KUAISHOU'],
    'SALES',
    'SELF_SUB',
    '赵六',
    'REJECTED',
    [common([find], fixed)],
  ],
  [
    'POL-20250902-008',
    '2025-09-02 14:00:00',
    'BILLION',
    ['PDD'],
    null,
    null,
    '李四',
    'APPROVED',
    [common([reno], fixed)],
  ],
  [
    'POL-20250902-009',
    '2025-09-02 10:50:00',
    'RETAIL',
    ['DOUYIN'],
    'ACTIVATION',
    'NATIONAL_SUB',
    '张三',
    'PENDING',
    [common([ace], fixed)],
  ],
  [
    'POL-20250901-010',
    '2025-09-01 16:40:00',
    'RETAIL',
    ['JD'],
    'SALES',
    'LOCAL_SUB',
    '钱七',
    'APPROVED',
    [common([reno], ratio)],
  ],
  [
    'POL-20250901-011',
    '2025-09-01 13:30:00',
    'RETAIL',
    ['PDD'],
    'SALES',
    'SELF_SUB',
    '李四',
    'APPROVED',
    [specified(oneplus, [{ platformCode: 'PDD', quantity: null, subsidy: ratio }])],
  ],
  [
    'POL-20250831-012',
    '2025-08-31 09:00:00',
    'BILLION',
    ['TAOBAO', 'UNICOM'],
    null,
    null,
    '赵六',
    'APPROVED',
    [common([find], fixed, ['TAOBAO']), common([find], ratio, ['UNICOM'])],
  ],
]

export const ADMIN_POLICY_SAMPLES = submissions.map(
  ([
    policyCode,
    submittedAt,
    perspective,
    platforms,
    verifyType,
    sceneType,
    applicant,
    status,
    goods,
  ]) => ({
    policyCode,
    submittedAt,
    perspective,
    platforms,
    verifyType,
    sceneType,
    applicant,
    status,
    estimated: perspective === 'RETAIL' ? false : null,
    goods,
    specialRemark: policyCode === 'POL-20250903-006' ? '两平台共用本轮补贴方案。' : null,
    attachmentName: policyCode === 'POL-20250907-001' ? '政策文件.pdf' : null,
  }),
)
