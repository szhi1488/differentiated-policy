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
