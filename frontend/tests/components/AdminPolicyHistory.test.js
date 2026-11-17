// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import AdminPoliciesPage from '../../src/views/admin/policies/AdminPoliciesPage.vue'
import AdminPolicyHistory from '../../src/views/admin/policies/AdminPolicyHistory.vue'
import { auditLogs, currentDetail, historySnapshot } from '../fixtures/admin-policy-history'

let wrapper
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})
const button = (root, label) => root.findAll('button').find((node) => node.text() === label)

it('审核窗口可查看原轮次快照及附件，历史隐藏写入与覆盖入口，返回当前申请继续审核', async () => {
  const detail = currentDetail()
  let latestAudit = auditLogs[0]
  const fetch = vi.fn(async (path) => {
    if (path === '/api/admin/policies/5/audit-logs/901') {
      return Response.json({ log: auditLogs[1], snapshot: historySnapshot(1) })
    }
    if (path === '/api/admin/policies/5/audit-logs/902') {
      return Response.json({ log: latestAudit, snapshot: historySnapshot(2) })
    }
    if (path.startsWith('/api/admin/policies/5/audit-logs?')) {
      return Response.json({ items: auditLogs, total: 2 })
    }
    if (path === '/api/admin/policies/5') {
      return Response.json(detail)
    }
    return Response.json({
      items: [
        { ...detail.policy, perspective: 'RETAIL', platforms: ['JD'], applicant: '测试申请人' },
      ],
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
  expect(wrapper.text()).toContain('第二轮最新备注')
  expect(fetch.mock.calls.some(([path]) => path.includes('audit-logs'))).toBe(false)
  await wrapper.get('#tab-history').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('请核对第一轮补贴')
  expect(wrapper.get('.admin-policy-history').text()).toContain('待审核')
  expect(wrapper.get('.admin-policy-history').text()).not.toContain('SUBMITTED')
  expect(wrapper.get('.admin-policy-history').text()).not.toContain('未填写审核意见')
  expect(button(wrapper, '通过')).toBeUndefined()
  await wrapper.get('[aria-label="查看第 1 轮内容"]').trigger('click')
  await flushPromises()
  const history = wrapper.get('.admin-policy-history')
  expect(history.text()).toContain('第一轮原始备注')
  expect(history.text()).toContain('历史机型名称')
  expect(history.text()).toContain('0.10 元')
  expect(history.text()).toContain('历史审核员')
  expect(history.text()).toContain('2026-11-15 14:00:00')
  expect(history.text()).not.toContain('第二轮最新备注')
  expect(history.find('.admin-policy-coverage').exists()).toBe(false)
  expect(history.findComponent({ name: 'AttachmentDownload' }).props('url')).toBe(
    '/api/admin/policies/5/audit-logs/901/attachments/file-1/content',
  )
  await button(wrapper, '返回审核记录').trigger('click')
  await flushPromises()
  expect(wrapper.find('[aria-label="查看第 2 轮内容"]').exists()).toBe(true)
  await wrapper.get('[aria-label="查看第 2 轮内容"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('.admin-policy-history-summary').text()).toContain('审核结果：待审核')
  expect(wrapper.find('.admin-policy-audit').exists()).toBe(false)
  latestAudit = {
    ...auditLogs[0],
    auditStatus: 'APPROVED',
    auditorName: '通过审核员',
    auditTime: '2026-11-16T12:00:00+08:00',
  }
  await button(wrapper, '返回审核记录').trigger('click')
  await wrapper.get('[aria-label="查看第 2 轮内容"]').trigger('click')
  await flushPromises()
  const approved = wrapper.get('.admin-policy-audit')
  expect(approved.text()).toContain('通过审核员')
  expect(approved.text()).toContain('2026-11-16 12:00:00')
  expect(approved.text()).not.toContain('审核意见')
  await wrapper.get('#tab-current').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('第二轮最新备注')
  expect(button(wrapper, '通过')).toBeDefined()
  expect(fetch.mock.calls.every(([, options]) => !options.method || options.method === 'GET')).toBe(
    true,
  )
})

it('记录和快照读取失败分别显示实际错误并可重试，空历史不生成演示记录', async () => {
  let failList = true,
    failSnapshot = true
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path) => {
      if (path.endsWith('/901')) {
        if (failSnapshot) {
          return Response.json({ message: '本轮快照暂不可用' }, { status: 503 })
        }
        return Response.json({
          log: { ...auditLogs[1], auditComment: null },
          snapshot: historySnapshot(1),
        })
      }
      if (failList) {
        return Response.json({ message: '历史记录暂不可用' }, { status: 503 })
      }
      return Response.json({ items: auditLogs, total: 2 })
    }),
  )
  wrapper = mount(AdminPolicyHistory, { props: { policyId: '5' } })
  await flushPromises()
  expect(wrapper.text()).toContain('历史记录暂不可用')
  failList = false
  await button(wrapper, '重试读取审核记录').trigger('click')
  await flushPromises()
  await wrapper.get('[aria-label="查看第 1 轮内容"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('本轮快照暂不可用')
  expect(wrapper.findComponent({ name: 'AdminPolicyDetail' }).exists()).toBe(false)
  failSnapshot = false
  await button(wrapper, '重试读取本轮内容').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('未填写审核意见')
  await button(wrapper, '返回审核记录').trigger('click')
  vi.mocked(fetch).mockResolvedValue(Response.json({ items: [], total: 0 }))
  await wrapper.setProps({ policyId: '6' })
  await flushPromises()
  expect(wrapper.text()).toContain('暂无审核记录')
  expect(wrapper.find('[aria-label="查看第 1 轮内容"]').exists()).toBe(false)
})
