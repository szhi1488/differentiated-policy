// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { routerKey } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import AttachmentDownload from '../../src/components/AttachmentDownload.vue'
import PolicyReviewRoundPage from '../../src/views/reviews/PolicyReviewRoundPage.vue'
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

async function open(fetch) {
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(PolicyReviewRoundPage, {
    global,
    props: { policyId: '5', submissionVersion: 2 },
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

it('运营只在卡片上确认，查看本轮依据及返回详情页均不提交决定', async () => {
  const round = reviewRound()
  const fetch = vi.fn(() => Response.json(round))
  await open(fetch)
  expect(wrapper.text()).toContain('请回到飞书私聊审核卡确认本轮申请')
  expect(wrapper.find('textarea').exists()).toBe(false)
  for (const label of ['同意，转采购', '退回修改', '驳回', '终审通过']) {
    expect(button(label)).toBeUndefined()
  }
  round.reviewStage = 'PURCHASE_REVIEW'
  round.allowedActions = ['APPROVE', 'REJECT']
  window.dispatchEvent(new Event('focus'))
  await flushPromises()
  expect(wrapper.text()).toContain('当前环节为采购终审')
  expect(button('终审通过')).toBeUndefined()
  expect(fetch.mock.calls.every(([path, options]) => path === details && !options.method)).toBe(
    true,
  )
})

it('仍能按轮读取全部冻结的截止调整、历史退回节点及签署意见', async () => {
  const round = reviewRound()
  round.changes = []
  round.status = 'REJECTED'
  round.reviewStage = null
  round.rejectedAtStage = 'PURCHASE_REVIEW'
  round.productReviewers = []
  const submitted = round.snapshot.consent.endAdjustments[0]
  round.adjustments = [
    submitted,
    {
      target: { policyId: '16', previousEnd: submitted.target.previousEnd },
      endTime: submitted.endTime,
      extension: false,
    },
  ]
  Object.assign(round.purchaseReviewers[0], {
    memberStatus: 'HANDLED',
    decision: 'REJECT',
    reason: '请修改补贴',
    decidedAt: '2026-11-23T12:34:50+08:00',
  })
  await open(() => Response.json(round))
  const changes = wrapper.get('.policy-end-changes')
  expect(changes.findAll('article')).toHaveLength(2)
  expect(changes.text()).toContain('ZC-000016')
  expect(changes.text()).toContain('活动提前结束')
  expect(wrapper.text()).toContain('采购终审退回给原申请人测试申请人')
  expect(wrapper.get('.member-reason').text()).toBe('请修改补贴')
  expect(button('驳回')).toBeUndefined()
})

it('历史轮只读，节点变化重新核对，转发给不可见用户时显示服务端错误', async () => {
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
  round.reviewStage = 'PURCHASE_REVIEW'
  window.dispatchEvent(new Event('focus'))
  await flushPromises()
  expect(wrapper.text()).toContain('此链接仅供查看')
  expect(button('同意，转采购')).toBeUndefined()
  visible = false
  await wrapper.setProps({ submissionVersion: 1 })
  await flushPromises()
  expect(wrapper.text()).toContain('未找到可查看的提交轮次')
  expect(wrapper.text()).not.toContain('ZC-000005')
})
