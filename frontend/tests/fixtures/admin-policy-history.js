export const auditLogs = [
  {
    id: '902',
    submissionVersion: 2,
    auditStatus: 'SUBMITTED',
    auditorName: null,
    auditComment: null,
    auditTime: null,
    submittedAt: '2026-11-16T11:00:00+08:00',
  },
  {
    id: '901',
    submissionVersion: 1,
    auditStatus: 'REJECTED',
    auditorName: '历史审核员',
    auditComment: '请核对第一轮补贴',
    auditTime: '2026-11-15T14:00:00+08:00',
    submittedAt: '2026-11-15T10:00:00+08:00',
  },
]

export function historySnapshot(version) {
  return {
    id: '5',
    policyCode: 'ZC-000005',
    submissionVersion: version,
    applicantName: '测试申请人',
    form: {
      perspective: 'RETAIL',
      selectedPlatforms: ['JD'],
      verifyType: 'SALES',
      sceneType: 'SELF_SUB',
      estimated: false,
      startTime: '2026-11-16T00:00:00+08:00',
      endTime: '2026-12-16T00:00:00+08:00',
      specialRemark: version === 1 ? '第一轮原始备注' : '第二轮最新备注',
      attachmentInfo: [
        { id: `file-${version}`, type: 'FILE', originalName: `第${version}轮附件.pdf` },
      ],
      goodsForm: {
        cards: [
          {
            key: 'card',
            cardType: 'SPECIFIED',
            targets: [{ goodsId: '100', spuId: '10', ram: '12GB', rom: '256GB' }],
            subsidyPlans: [
              { key: 'plan', subsidyMode: 'FIXED', fixedAmount: version === 1 ? '0.10' : '20.00' },
            ],
            platformAssignments: [{ platformCode: 'JD', planKey: 'plan', quantity: 0 }],
          },
        ],
      },
    },
    goods: [
      {
        goodsId: '100',
        modelName: version === 1 ? '历史机型名称' : '最新机型名称',
        colorName: '初雪白',
      },
    ],
  }
}

export function currentDetail() {
  return {
    policy: {
      id: '5',
      policyCode: 'ZC-000005',
      status: 'PENDING',
      submissionVersion: 2,
      recordVersion: 4,
      reviewable: true,
    },
    audit: auditLogs[0],
    snapshot: historySnapshot(2),
    coverage: {
      at: '2026-11-16T12:00:00+08:00',
      effectiveFrom: '2026-11-16T12:00:00+08:00',
      items: [],
    },
  }
}
