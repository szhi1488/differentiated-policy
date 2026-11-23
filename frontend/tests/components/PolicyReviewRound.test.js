// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { routerKey } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import AttachmentDownload from '../../src/components/AttachmentDownload.vue'
import PolicyReviewRoundPage from '../../src/views/reviews/PolicyReviewRoundPage.vue'
import PolicyReviewInboxPage from '../../src/views/reviews/PolicyReviewInboxPage.vue'
import { reviewRound } from '../fixtures/policy-review-round'

let wrapper
const push = vi.fn()
const global = { provide: { [routerKey]: { push } } }
afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
  push.mockClear()
})
const button = (label) => wrapper.findAll('button').find((node) => node.text() === label)
const details = '/api/policy-reviews/5?submissionVersion=2'

async function open(fetch, node = 'PRODUCT_REVIEW') {
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(PolicyReviewRoundPage, {
    global,
    props: { policyId: '5', submissionVersion: 2, node },
  })
  await flushPromises()
}
it('逐段呈现原拟名单、商品、核销和补贴，并将表单与调整证明固定到所查轮次', async () => {
  await open(() => Response.json(reviewRound()))
  const changes = wrapper.get('[aria-label="逐段执行依据"]').text()
  for (const word of [
    '原执行名单',
    '拟执行名单',
    '加入 · ZC-000005',
    '保留 · ZC-000004',
    '第 3 轮',
    '初雪白',
    '指定数量 0',
    '12.50%',
    '到手价',
    '销售维度',
    '2026-12-16 00:00:00',
  ]) {
    expect(changes).toContain(word)
  }
  expect(changes).not.toContain('申请人已选')
  expect(wrapper.findAllComponents(AttachmentDownload).map((file) => file.props('url'))).toEqual([
    '/api/policy-reviews/5/attachments/file-2/content?submissionVersion=2',
    '/api/policy-reviews/5/attachments/proof-2/content?submissionVersion=2',
  ])
})

it('多候选沿用原政策时显示未选政策的商品与补贴，换单时复用原执行明细', async () => {
  const round = reviewRound()
  const change = round.changes[0]
  const [original, alternative] = change.after
  change.candidates = [original, alternative]
  Object.assign(change, { kind: 'UNCHANGED', after: [original], added: [] })
  round.changes.push({
    ...change,
    from: change.through,
    kind: 'REPLACEMENT',
    after: [alternative],
    retained: [],
    removed: [original],
    added: [alternative],
  })
  await open(() => Response.json(round))
  const [retained, replaced] = wrapper.findAll('.review-changes article')
  const [before, selected, omitted] = retained.findAll('.review-change-sides section')
  expect(before.text()).toContain(original.policyCode)
  expect(selected.text()).toContain('拟执行名单（申请人已选）')
  expect(selected.text()).toContain(original.policyCode)
  expect(selected.text()).not.toContain(alternative.policyCode)
  for (const text of [
    '申请人未选的候选政策',
    alternative.policyCode,
    '12.50%',
    '到手价',
    '初雪白',
  ]) {
    expect(omitted.text()).toContain(text)
  }
  expect(omitted.text()).not.toContain(original.policyCode)
  const replacedOmitted = replaced.findAll('.review-change-sides section')[2]
  expect(replacedOmitted.text()).toContain(original.policyCode)
  expect(replacedOmitted.text()).toContain('补贴与商品见原执行名单')
  expect(replacedOmitted.find('.change-selection').exists()).toBe(false)
})

it('初审退回强制填写原因并校验字数；竞办冲突重读原轮、保留意见及唯一实际签署人', async () => {
  const round = reviewRound()
  const writes = []
  const fetch = vi.fn((path, options) => {
    if (path === details) {
      return Response.json(round)
    }
    expect(path).toBe('/api/policy-reviews/5/product-review')
    writes.push(JSON.parse(options.body))
    round.reviewStage = 'PURCHASE_REVIEW'
    round.recordVersion = 5
    round.allowedActions = []
    Object.assign(round.productReviewers[0], {
      decision: 'APPROVE',
      decidedAt: '2026-11-23T12:34:50+08:00',
      memberStatus: 'HANDLED',
    })
    round.productReviewers[1].memberStatus = 'PEER_HANDLED'
    return Response.json({ message: '其他成员已办理' }, { status: 409 })
  })
  await open(fetch)
  for (const value of ['　 ', '字'.repeat(513)]) {
    await wrapper.get('textarea').setValue(value)
    await button('退回修改').trigger('click')
    expect(writes).toEqual([])
  }
  await wrapper.get('textarea').setValue(' 核对零售补贴 ')
  await button('退回修改').trigger('click')
  await flushPromises()
  expect(writes).toEqual([
    { decision: 'REJECT', submissionVersion: 2, recordVersion: 4, reviewRemark: '核对零售补贴' },
  ])
  expect(fetch.mock.calls.filter(([path]) => path === details)).toHaveLength(2)
  expect(wrapper.get('textarea').element.value).toBe(' 核对零售补贴 ')
  expect(wrapper.text()).toContain('2026-11-23 12:34:50')
  const members = wrapper.get('[aria-label="本轮审核成员"]').findAll('li')
  expect(members[0].text()).toMatch(/周晨.*已同意转采购/)
  expect(members[1].text()).toMatch(/王晴.*同组他人已处理/)
  expect(button('同意，转采购')).toBeUndefined()
})

it('无交叉时单列全部冻结的截止调整，成功退回后显示本轮阶段、原申请人及签署意见', async () => {
  const round = reviewRound()
  round.changes = []
  round.reviewStage = 'PURCHASE_REVIEW'
  round.productReviewers = []
  round.changeSummary = { summary: '本单由采购直审。' }
  const submitted = round.snapshot.consent.endAdjustments[0]
  round.adjustments = [
    submitted,
    {
      target: { policyId: '16', previousEnd: submitted.target.previousEnd },
      endTime: submitted.endTime,
      extension: false,
    },
  ]
  await open((path, options) => {
    if (path === details) {
      return Response.json(round)
    }
    const { reviewRemark } = JSON.parse(options.body)
    Object.assign(round, {
      status: 'REJECTED',
      rejectedAtStage: 'PURCHASE_REVIEW',
      reviewStage: null,
      allowedActions: [],
    })
    Object.assign(round.purchaseReviewers[0], {
      memberStatus: 'HANDLED',
      decision: 'REJECT',
      reason: reviewRemark,
      decidedAt: '2026-11-23T12:34:50+08:00',
    })
    return Response.json({ status: 'REJECTED' })
  }, 'PURCHASE_REVIEW')
  const changes = wrapper.get('.policy-end-changes')
  expect(changes.findAll('article')).toHaveLength(2)
  expect(changes.text()).toContain('ZC-000016')
  expect(changes.text()).toContain('活动提前结束')
  await wrapper.get('textarea').setValue('请修改补贴')
  await button('驳回').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('采购终审退回给原申请人测试申请人')
  expect(wrapper.get('.member-reason').text()).toBe('请修改补贴')
  expect(button('驳回')).toBeUndefined()
})

it.each([
  ['PRODUCT_REVIEW', '同意，转采购', '/api/policy-reviews/5/product-review'],
  ['PURCHASE_REVIEW', '终审通过', '/api/admin/policies/5/review'],
])('仅由匹配的当前节点执行 %s，结果未知时只重读同一轮', async (node, label, endpoint) => {
  const round = reviewRound()
  round.reviewStage = node
  const writes = []
  const fetch = vi.fn((path, options) => {
    if (path === details) {
      return Response.json(round)
    }
    writes.push([path, JSON.parse(options.body)])
    round.allowedActions = []
    throw new TypeError('network')
  })
  await open(fetch, node)
  await button(label).trigger('click')
  await flushPromises()
  expect(writes).toEqual([
    [endpoint, { decision: 'APPROVE', submissionVersion: 2, recordVersion: 4, reviewRemark: null }],
  ])
  expect(fetch.mock.calls.filter(([path]) => path === details)).toHaveLength(2)
  expect(button(label)).toBeUndefined()
  expect(wrapper.text()).toContain('核对结果')
})

it('通用链接从后台返回后重新核权，原初审意见不会带入新节点', async () => {
  const round = reviewRound()
  await open(() => Response.json(round), '')
  await wrapper.get('textarea').setValue('初审意见')
  round.reviewStage = 'PURCHASE_REVIEW'
  round.recordVersion++
  window.dispatchEvent(new Event('focus'))
  await flushPromises()
  expect(wrapper.text()).toContain('此链接仅供查看')
  expect(wrapper.get('textarea').element.value).toBe('初审意见')
  expect(button('终审通过')).toBeUndefined()
  expect(button('驳回')).toBeUndefined()
})

it('旧轮或其他节点的链接只读，转发给不可见用户时显示服务端错误', async () => {
  const round = reviewRound()
  round.currentSubmissionVersion = 3
  let visible = true
  await open(() =>
    visible
      ? Response.json(round)
      : Response.json({ message: '未找到可查看的提交轮次。' }, { status: 404 }),
  )
  expect(wrapper.text()).toContain('已有新一轮')
  expect(button('同意，转采购')).toBeUndefined()
  round.currentSubmissionVersion = 2
  await wrapper.setProps({ node: 'PURCHASE_REVIEW' })
  await flushPromises()
  expect(wrapper.text()).toContain('此链接仅供查看')
  expect(button('同意，转采购')).toBeUndefined()
  visible = false
  await wrapper.setProps({ submissionVersion: 1 })
  await flushPromises()
  expect(wrapper.text()).toContain('未找到可查看的提交轮次')
  expect(wrapper.text()).not.toContain('ZC-000005')
})

it('个人待审读取指派分页，办理链接携带精确单号、轮次和节点', async () => {
  const fetch = vi.fn(() =>
    Response.json({
      total: 1,
      items: [
        { ...reviewRound(), summary: '整组配置补贴叠加', submittedAt: '2026-11-23T11:20:30+08:00' },
      ],
    }),
  )
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(PolicyReviewInboxPage, { global })
  await flushPromises()
  expect(fetch.mock.calls[0][0]).toBe('/api/policy-reviews/mine?page=1&pageSize=20')
  expect(wrapper.text()).toContain('整组配置补贴叠加')
  await button('办理').trigger('click')
  expect(push).toHaveBeenCalledWith({
    path: '/reviews/5',
    query: { round: '2', node: 'PRODUCT_REVIEW' },
  })
})
