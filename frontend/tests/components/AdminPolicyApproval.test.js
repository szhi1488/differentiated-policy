// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import AdminPoliciesPage from '../../src/views/admin/policies/AdminPoliciesPage.vue'
import { parseReviewTarget } from '../../src/utils/review-link'
import { groupedPolicyDetail } from '../fixtures/admin-policy-history'
import { reviewRound } from '../fixtures/policy-review-round'

let wrapper
afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
const button = (root, label) => root.findAll('button').find((node) => node.text() === label)

async function openPurchaseLink(round) {
  const original = groupedPolicyDetail()
  const posts = []
  const fetch = vi.fn(async (path, options) => {
    if (path === '/api/policy-reviews/5?submissionVersion=2') {
      return Response.json(round)
    }
    if (path === '/api/admin/policies/5') {
      return Response.json({
        ...original,
        policy: { ...original.policy, reviewable: true, reviewStage: 'PURCHASE_REVIEW' },
        audit: { ...original.audit, workflowVersion: 1 },
      })
    }
    if (path === '/api/admin/policies/5/review' && options.method === 'POST') {
      posts.push(JSON.parse(options.body))
      Object.assign(round, { status: 'APPROVED', reviewStage: null, allowedActions: [] })
      return Response.json({ id: '5' })
    }
    return Response.json({ items: [], total: 0, counts: {} })
  })
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(AdminPoliciesPage, {
    attachTo: document.body,
    props: { linkedReview: parseReviewTarget('/admin/policies?policyId=5&round=2') },
    global: { stubs: { teleport: true, ElSelect: true } },
  })
  await flushPromises()
  return { posts, fetch }
}

it('采购待办直接在差异化政策中打开目标轮次，终审后回显已通过', async () => {
  const { posts } = await openPurchaseLink({ ...reviewRound(), reviewStage: 'PURCHASE_REVIEW' })
  expect(wrapper.get('[aria-label="政策详情内容"]').exists()).toBe(true)
  await button(wrapper, '通过').trigger('click')
  await flushPromises()
  expect(posts).toEqual([
    { recordVersion: 4, submissionVersion: 2, decision: 'APPROVE', reviewRemark: null },
  ])
  expect(wrapper.text()).toContain('本轮已处理 · 已通过')
  expect(button(wrapper, '通过')).toBeUndefined()
})

it('同单同轮调整参数顺序时保留驳回意见和正在提交的决定', async () => {
  const { fetch } = await openPurchaseLink({ ...reviewRound(), reviewStage: 'PURCHASE_REVIEW' })
  await button(wrapper, '驳回').trigger('click')
  await flushPromises()
  await wrapper.get('textarea').setValue('请核对补贴')
  const reorderLink = (query) =>
    wrapper.setProps({ linkedReview: parseReviewTarget('/admin/policies?' + query) })
  const reads = fetch.mock.calls.length
  await reorderLink('round=2&policyId=5')
  expect(wrapper.get('textarea').element.value).toBe('请核对补贴')
  expect(fetch.mock.calls).toHaveLength(reads)

  let finish
  fetch.mockImplementationOnce(() => new Promise((resolve) => (finish = resolve)))
  await button(wrapper, '确认驳回').trigger('click')
  const [, options] = fetch.mock.lastCall
  await reorderLink('policyId=5&round=2')
  expect(options.method).toBe('POST')
  expect(options.signal.aborted).toBe(false)
  expect(fetch.mock.calls).toHaveLength(reads + 1)
  finish(Response.json({ id: '5' }))
  await flushPromises()
})

it('关闭上一轮的过渡结束时仍显示刚切换的目标轮次', async () => {
  const { fetch } = await openPurchaseLink({ ...reviewRound(), reviewStage: 'PURCHASE_REVIEW' })
  const detailDialog = () => wrapper.findAllComponents({ name: 'ElDialog' })[0]
  const oldDialog = detailDialog()
  oldDialog.vm.$emit('close')
  fetch.mockResolvedValueOnce(Response.json({ ...reviewRound(), submissionVersion: 1 }))
  await wrapper.setProps({
    linkedReview: parseReviewTarget('/admin/policies?policyId=5&round=1'),
  })
  await flushPromises()
  oldDialog.vm.$emit('update:modelValue', false)
  await flushPromises()
  expect(detailDialog().props('modelValue')).toBe(true)
  expect(wrapper.text()).toContain('ZC-000005 · 第 1 轮')
})

it.each([
  ['历史轮次', { currentSubmissionVersion: 3 }, '已有新一轮'],
  ['尚在初审', { reviewStage: 'PRODUCT_REVIEW' }, '当前环节为产品运营初审'],
])('采购链接遇到%s只读目标轮次快照和附件', async (_title, overrides, prompt) => {
  const { fetch, posts } = await openPurchaseLink({
    ...reviewRound(),
    reviewStage: 'PURCHASE_REVIEW',
    ...overrides,
  })
  expect(wrapper.text()).toContain(prompt)
  expect(wrapper.text()).toContain('ZC-000005 · 第 2 轮')
  expect(wrapper.text()).toContain('第2轮附件.pdf')
  expect(fetch.mock.calls.some(([path]) => path === '/api/admin/policies/5')).toBe(false)
  expect(button(wrapper, '通过')).toBeUndefined()
  expect(posts).toEqual([])
})

it('当前管理员缺少本轮采购办理权时只读，即使当前单据标记可审核', async () => {
  await openPurchaseLink({ ...reviewRound(), reviewStage: 'PURCHASE_REVIEW', allowedActions: [] })
  expect(wrapper.get('[aria-label="政策详情内容"]').exists()).toBe(true)
  expect(button(wrapper, '通过')).toBeUndefined()
  expect(button(wrapper, '驳回')).toBeUndefined()
})

it.each(['缺失审核轮次', '已改期的获批原单'])('%s不会仅凭列表标记开放终审', async (scenario) => {
  const detail = groupedPolicyDetail()
  const extension = scenario === '已改期的获批原单'
  if (extension) {
    detail.policy.status = 'APPROVED'
    detail.audit = { ...detail.audit, auditStatus: 'APPROVED' }
    detail.timeHistory = {
      initialEnd: '2026-12-10T00:00:00+08:00',
      currentEnd: '2026-12-12T00:00:00+08:00',
      changes: [],
    }
  }
  const calls = []
  vi.stubGlobal('fetch', async (path, options) => {
    calls.push({ path, method: options.method || 'GET' })
    if (path === '/api/admin/policies/5') {
      return Response.json(detail)
    }
    if (path === '/api/policy-reviews/5?submissionVersion=2') {
      return Response.json({ message: '本轮缺少审核阶段' }, { status: 409 })
    }
    return Response.json({
      items: [{ ...detail.policy, platforms: ['JD'], reviewable: true }],
      total: 1,
      counts: {},
    })
  })
  wrapper = mount(AdminPoliciesPage, {
    attachTo: document.body,
    global: { stubs: { teleport: true, ElSelect: true } },
  })
  await flushPromises()
  await wrapper.get('[aria-label="审核政策 ZC-000005"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain(extension ? '机型及补贴信息' : '本轮缺少审核阶段')
  if (extension) {
    expect(wrapper.text()).toContain('当前截止2026-12-12 00:00:00')
  }
  expect(calls.some(({ path }) => path.startsWith('/api/policy-reviews/'))).toBe(true)
  expect(button(wrapper, '通过')).toBeUndefined()
  expect(button(wrapper, '驳回')).toBeUndefined()
  expect(calls.every(({ method }) => method === 'GET')).toBe(true)
})

async function openPolicy() {
  const detail = groupedPolicyDetail()
  const fetch = vi.fn(async (path, options) => {
    if (options.method === 'POST') {
      return Response.json({ message: '状态已变化' }, { status: 409 })
    }
    if (path === '/api/admin/policies/5') {
      return Response.json(detail)
    }
    if (path === '/api/policy-reviews/5?submissionVersion=2') {
      return Response.json({ ...reviewRound(), reviewStage: 'PURCHASE_REVIEW' })
    }
    return Response.json({
      items: [{ ...detail.policy, perspective: 'RETAIL', platforms: ['JD'], applicant: '申请人' }],
      total: 1,
      counts: { PENDING: 1 },
    })
  })
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(AdminPoliciesPage, {
    attachTo: document.body,
    global: { stubs: { teleport: true, ElSelect: true } },
  })
  await flushPromises()
  await wrapper.get('[aria-label="审核政策 ZC-000005"]').trigger('click')
  await flushPromises()
  return fetch
}

it.each(['查看', '审核'])('%s先显示申请商品与附件，轮次失败后可以单独重读资格', async (mode) => {
  const detail = groupedPolicyDetail()
  let finishRound
  let roundRequests = 0
  const fetch = vi.fn(async (path) => {
    if (path === '/api/admin/policies/5') {
      return Response.json({
        ...detail,
        round: { ...reviewRound(), reviewStage: 'PURCHASE_REVIEW' },
      })
    }
    if (path === '/api/policy-reviews/5?submissionVersion=2') {
      if (++roundRequests === 1) {
        return new Promise((resolve) => {
          finishRound = resolve
        })
      }
      return Response.json({ ...reviewRound(), reviewStage: 'PURCHASE_REVIEW' })
    }
    return Response.json({ items: [{ ...detail.policy, platforms: ['JD'] }], total: 1, counts: {} })
  })
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(AdminPoliciesPage, {
    attachTo: document.body,
    global: { stubs: { teleport: true, ElSelect: true } },
  })
  await flushPromises()
  await wrapper.get(`[aria-label="${mode}政策 ZC-000005"]`).trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('Reno17 Pro')
  expect(wrapper.text()).toContain('第2轮附件.pdf')
  expect(wrapper.text()).toContain('正在读取本轮审核信息')
  expect(button(wrapper, '通过')).toBeUndefined()
  finishRound(Response.json({ message: '服务暂时不可用，请稍后重试' }, { status: 500 }))
  await flushPromises()
  expect(wrapper.text()).toContain('Reno17 Pro')
  expect(wrapper.text()).toContain('服务暂时不可用，请稍后重试')
  expect(button(wrapper, '通过')).toBeUndefined()
  expect(wrapper.find('[aria-label="本轮审核进度"]').exists()).toBe(false)
  await button(wrapper, '重新读取本轮审核信息').trigger('click')
  await flushPromises()
  expect(wrapper.find('[aria-label="本轮审核进度"]').exists()).toBe(true)
  expect(Boolean(button(wrapper, '通过'))).toBe(mode === '审核')
  expect(fetch.mock.calls.filter(([path]) => path === '/api/admin/policies/5')).toHaveLength(1)
})

it.each(['通过', '驳回'])('管理员根据本轮申请内容直接%s，冲突后重新读取', async (action) => {
  const fetch = await openPolicy()
  expect(wrapper.text()).toContain('机型及补贴信息')
  expect(wrapper.text()).not.toMatch(/覆盖预览|生效范围|覆盖结果/)
  await button(wrapper, action).trigger('click')
  if (action === '驳回') {
    await flushPromises()
    await wrapper.get('textarea').setValue('请修改补贴')
    await button(wrapper, '确认驳回').trigger('click')
  }
  await flushPromises()
  const writes = fetch.mock.calls.filter(([, options]) => options.method === 'POST')
  expect(writes).toHaveLength(1)
  expect(JSON.parse(writes[0][1].body)).toEqual({
    recordVersion: 4,
    submissionVersion: 2,
    decision: action === '通过' ? 'APPROVE' : 'REJECT',
    reviewRemark: action === '通过' ? null : '请修改补贴',
  })
  expect(wrapper.text()).toContain('重新读取政策')
  expect(button(wrapper, action).attributes('disabled')).toBeDefined()
})

it('采购从当前轮查看初审签署人和同组结办，迟到的终审自动读回实际决定', async () => {
  const original = groupedPolicyDetail()
  let handled = false
  const fetch = vi.fn(async (path, options) => {
    const policy = {
      ...original.policy,
      reviewStage: handled ? null : 'PURCHASE_REVIEW',
      status: handled ? 'APPROVED' : 'PENDING',
      recordVersion: handled ? 5 : 4,
      reviewable: !handled,
      productReviewerName: '初审签署人',
      productReviewedAt: '2026-11-23T12:10:00+08:00',
      platforms: ['JD'],
    }
    if (options.method === 'POST') {
      handled = true
      return Response.json({ message: '同组采购已完成本轮。' }, { status: 409 })
    }
    if (path === '/api/admin/policies/5') {
      return Response.json({
        ...original,
        policy,
        audit: { ...original.audit, workflowVersion: 1 },
      })
    }
    if (path === '/api/policy-reviews/5?submissionVersion=2') {
      return Response.json({
        ...policy,
        currentSubmissionVersion: 2,
        allowedActions: handled ? [] : ['APPROVE', 'REJECT'],
        requiresProductReview: true,
        productReviewers: [
          {
            assignmentId: '1',
            name: '初审签署人',
            decision: 'APPROVE',
            decidedAt: policy.productReviewedAt,
          },
          { assignmentId: '2', name: '同组产品', memberStatus: 'PEER_HANDLED' },
        ],
        purchaseReviewers: [
          { assignmentId: '3', name: '采购同事', decision: handled ? 'APPROVE' : null },
        ],
      })
    }
    return Response.json({ items: [policy], total: 1, counts: { [policy.status]: 1 } })
  })
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(AdminPoliciesPage, {
    attachTo: document.body,
    global: { stubs: { teleport: true, ElSelect: true } },
  })
  await flushPromises()
  await wrapper.get('[aria-label="审核政策 ZC-000005"]').trigger('click')
  await flushPromises()
  const members = () => wrapper.get('[aria-label="本轮审核进度"]').text()
  expect(members()).toContain('初审签署人')
  expect(members()).toContain('2026-11-23 12:10:00')
  expect(members()).toContain('同组他人已处理')
  await button(wrapper, '通过').trigger('click')
  await flushPromises()
  expect(members()).toContain('采购同事')
  expect(members()).toContain('已批准')
  expect(button(wrapper, '通过')).toBeUndefined()
  expect(wrapper.find('[aria-label="审核政策 ZC-000005"]').exists()).toBe(false)
  expect(fetch.mock.calls.filter(([, options]) => options.method === 'POST')).toHaveLength(1)
})

it.each(['列表过期', '连续聚焦'])(
  '采购在%s后仍只能办理所选轮次，新轮须重新点击审核',
  async (scenario) => {
    const original = groupedPolicyDetail()
    let current = 2
    let pause = false
    const pending = []
    const posts = []
    const summary = () => ({
      ...original.policy,
      submissionVersion: current,
      recordVersion: current + 2,
      reviewStage: 'PURCHASE_REVIEW',
      platforms: ['JD'],
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async (path, options) => {
        if (options.method === 'POST') {
          posts.push(JSON.parse(options.body))
          return Response.json({ id: '5' })
        }
        if (path === '/api/admin/policies/5') {
          if (pause) {
            await new Promise((resolve) => pending.push(resolve))
          }
          return Response.json({
            ...original,
            policy: summary(),
            audit: { ...original.audit, workflowVersion: 1, submissionVersion: current },
            snapshot: { ...original.snapshot, submissionVersion: current },
          })
        }
        if (path.startsWith('/api/policy-reviews/5?')) {
          return Response.json({
            ...summary(),
            currentSubmissionVersion: current,
            allowedActions: ['APPROVE', 'REJECT'],
            productReviewers: [],
            purchaseReviewers: [],
          })
        }
        return Response.json({ items: [summary()], total: 1, counts: { PENDING: 1 } })
      }),
    )
    wrapper = mount(AdminPoliciesPage, {
      attachTo: document.body,
      global: { stubs: { teleport: true, ElSelect: true } },
    })
    await flushPromises()
    const openReview = () => wrapper.get('[aria-label="审核政策 ZC-000005"]').trigger('click')
    if (scenario === '列表过期') {
      current = 3
      await openReview()
    } else {
      await openReview()
      await flushPromises()
      await button(wrapper, '驳回').trigger('click')
      await flushPromises()
      await wrapper.get('textarea').setValue('第二轮意见')
      current = 3
      pause = true
      window.dispatchEvent(new Event('focus'))
      window.dispatchEvent(new Event('focus'))
      await flushPromises()
      expect(pending).toHaveLength(2)
      expect(button(wrapper, '确认驳回').element.disabled).toBe(true)
      await button(wrapper, '确认驳回').trigger('click')
      pause = false
      pending[1]()
      await flushPromises()
      pending[0]()
    }
    await flushPromises()
    expect(wrapper.text()).toContain('所选提交轮次已变化')
    expect(wrapper.text()).not.toContain('机型及补贴信息')
    expect(button(wrapper, '通过')).toBeUndefined()
    if (scenario === '连续聚焦') {
      expect(wrapper.get('.admin-policy-note').text()).toContain('第二轮意见')
    }
    expect(posts).toEqual([])
    await button(wrapper, '返回列表并刷新').trigger('click')
    await flushPromises()
    await openReview()
    await flushPromises()
    await button(wrapper, '通过').trigger('click')
    await flushPromises()
    expect(posts).toEqual([
      { recordVersion: 5, submissionVersion: 3, decision: 'APPROVE', reviewRemark: null },
    ])
  },
)
