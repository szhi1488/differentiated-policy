import { historySnapshot } from './admin-policy-history.js'

export function reviewRound() {
  const snapshot = historySnapshot(2)
  const from = snapshot.form.startTime
  const through = snapshot.form.endTime
  const product = { ...snapshot.goods[0], spuId: '10', ram: '12GB', rom: '256GB' }
  const selection = {
    cardType: 'SPECIFIED',
    quantity: 0,
    products: [product],
    subsidy: snapshot.form.goodsForm.cards[0].subsidyPlans[0],
  }
  const old = {
    policyId: '4',
    policyCode: 'ZC-000004',
    submissionVersion: 3,
    verifyType: 'SALES',
    sceneTypes: ['SELF_SUB'],
    selections: [selection],
  }
  const added = {
    ...old,
    policyId: '5',
    policyCode: 'ZC-000005',
    submissionVersion: 2,
    selections: [
      { ...selection, subsidy: { subsidyMode: 'RATIO', ratio: '12.50', priceBase: 'HAND_PRICE' } },
    ],
  }
  snapshot.consent = {
    endAdjustments: [
      {
        target: { policyId: '4', previousEnd: through },
        endTime: from,
        reason: '活动提前结束',
        attachmentInfo: [{ id: 'proof-2', originalName: '调整证明.pdf', type: 'FILE' }],
      },
    ],
  }
  return {
    id: '5',
    policyCode: 'ZC-000005',
    status: 'PENDING',
    submissionVersion: 2,
    currentSubmissionVersion: 2,
    recordVersion: 4,
    settingsVersion: 1,
    reviewStage: 'PRODUCT_REVIEW',
    allowedActions: ['APPROVE', 'REJECT'],
    productReviewers: [
      {
        assignmentId: '21',
        userId: '3',
        name: '周晨',
        department: '运营一部',
        memberStatus: 'ACTIVE',
      },
      {
        assignmentId: '22',
        userId: '4',
        name: '王晴',
        department: '运营二部',
        memberStatus: 'ACTIVE',
      },
    ],
    purchaseReviewers: [
      {
        assignmentId: '23',
        userId: '6',
        name: '陈杉',
        department: '采购部',
        memberStatus: 'ACTIVE',
      },
    ],
    changeSummary: { summary: '京东零售有一段新增叠加，需产品运营初审。' },
    adjustments: [],
    changes: [
      {
        perspective: 'RETAIL',
        scope: { platformCode: 'JD', goodsId: '100' },
        kind: 'STACK',
        from,
        through,
        before: [old],
        after: [old, added],
        added: [added],
        retained: [old],
        removed: [],
        candidates: [],
      },
    ],
    snapshot,
  }
}
