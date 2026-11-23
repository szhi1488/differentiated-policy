// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import AdminPoliciesPage from '../../src/views/admin/policies/AdminPoliciesPage.vue'
import { groupedCoverageDetail } from '../fixtures/policy-coverage'

let wrapper
afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
const button = (root, label) => root.findAll('button').find((node) => node.text() === label)
async function openPolicy() {
  const detail = groupedCoverageDetail()
  const fetch = vi.fn(async (path, options) => {
    if (options.method === 'POST') {
      return Response.json({ message: '状态已变化' }, { status: 409 })
    }
    if (path === '/api/admin/policies/5?includeCoverage=false') {
      return Response.json(detail)
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
  const original = groupedCoverageDetail()
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
    if (path.startsWith('/api/admin/policies/5?')) {
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
  const members = () => wrapper.get('[aria-label="本轮审核成员"]').text()
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
    const original = groupedCoverageDetail()
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
        if (path.startsWith('/api/admin/policies/5?')) {
          if (pause) {
            await new Promise((resolve) => pending.push(resolve))
          }
          return Response.json({
            ...original,
            policy: summary(),
            audit: { ...original.audit, workflowVersion: 1, submissionVersion: current },
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
