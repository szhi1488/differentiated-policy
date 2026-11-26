// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import AdminPoliciesPage from '../../src/views/admin/policies/AdminPoliciesPage.vue'
import AdminPolicyHistory from '../../src/views/admin/policies/AdminPolicyHistory.vue'
import { auditLogs, currentDetail, historySnapshot } from '../fixtures/admin-policy-history'
import { reviewRound } from '../fixtures/policy-review-round'

let wrapper
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})
const button = (root, label) => root.findAll('button').find((node) => node.text() === label)

it('兼岗采购从列表打开自己的已结办轮次，可同时持有其他岗位的更新轮次', async () => {
  const detail = currentDetail()
  Object.assign(detail.policy, { status: 'REJECTED', recordVersion: null, reviewable: false })
  detail.audit = { ...detail.audit, auditStatus: 'REJECTED' }
  const round = {
    ...reviewRound(),
    currentSubmissionVersion: 3,
    recordVersion: null,
    status: 'REJECTED',
    reviewStage: null,
    rejectedAtStage: 'PURCHASE_REVIEW',
    allowedActions: [],
  }
  vi.stubGlobal('fetch', async (path) => {
    if (path === '/api/admin/policies/5') {
      return Response.json(detail)
    }
    if (path === '/api/policy-reviews/5?submissionVersion=2') {
      return Response.json(round)
    }
    return Response.json({ items: [{ ...detail.policy, platforms: ['JD'] }], total: 1, counts: {} })
  })
  wrapper = mount(AdminPoliciesPage, {
    global: { stubs: { teleport: true, ElSelect: true } },
  })
  await flushPromises()
  await wrapper.get('[aria-label="查看政策 ZC-000005"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[aria-label="本轮审核进度"]').text()).toContain('已驳回')
  expect(button(wrapper, '通过')).toBeUndefined()
})

it('查看政策时改期流水独占中间页签，切换申请重新显示当前申请', async () => {
  const detail = currentDetail()
  const initialEnd = detail.snapshot.form.endTime
  const newEnd = '2027-01-16T00:00:00+08:00'
  detail.policy.status = 'APPROVED'
  detail.audit = { ...detail.audit, auditStatus: 'APPROVED' }
  detail.timeHistory = {
    initialEnd,
    currentEnd: newEnd,
    changes: [
      {
        id: '17',
        direction: 'EXTEND',
        previousEnd: initialEnd,
        requestedEnd: newEnd,
        actualEnd: newEnd,
        reason: '平台活动延续',
        actorName: '胡源',
        changedAt: '2026-11-25T13:35:07+08:00',
        effectiveAt: '2026-12-16T00:00:01+08:00',
        sourcePolicyCode: 'ZC-000030',
        attachmentInfo: [{ id: 'proof', originalName: '档期证明.pdf' }],
      },
    ],
  }
  detail.snapshot.consent = {
    endAdjustments: [
      { target: { policyId: '28', previousEnd: initialEnd }, endTime: newEnd, reason: '随单申请' },
    ],
  }
  const other = currentDetail()
  Object.assign(other.policy, { id: '6', policyCode: 'ZC-000006' })
  Object.assign(other.snapshot, { id: '6', policyCode: 'ZC-000006' })
  const fetch = vi.fn(async (path) => {
    if (path === '/api/admin/policies/5') {
      return Response.json(detail)
    }
    if (path === '/api/admin/policies/6') {
      return Response.json(other)
    }
    if (path.startsWith('/api/policy-reviews/')) {
      const id = path.includes('/6?') ? '6' : '5'
      return Response.json({ ...reviewRound(), id })
    }
    return Response.json({
      items: [detail, other].map(({ policy }) => ({ ...policy, platforms: ['JD'] })),
      total: 2,
      counts: {},
    })
  })
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(AdminPoliciesPage, {
    attachTo: document.body,
    global: { stubs: { teleport: true, ElSelect: true } },
  })
  await flushPromises()
  await wrapper.get('[aria-label="查看政策 ZC-000005"]').trigger('click')
  await flushPromises()
  const tabs = () => wrapper.get('[aria-label="政策详情内容"]').findAll('[role=tab]')
  expect(tabs().map((tab) => tab.text())).toEqual(['当前申请', '时间调整记录', '审核记录'])
  expect(tabs()[0].attributes('aria-selected')).toBe('true')
  expect(wrapper.text()).toContain('当前截止2027-01-16 00:00:00')
  expect(wrapper.text()).toContain('首次获批截止2026-12-16 00:00:00')
  expect(wrapper.text()).toContain('随单申请')
  expect(wrapper.text()).not.toContain('平台活动延续')

  await tabs()[1].trigger('click')
  await flushPromises()
  const pane = wrapper.get('[role=tabpanel][aria-labelledby=tab-timeHistory]')
  expect(tabs()[1].attributes('aria-selected')).toBe('true')
  expect(pane.text()).toContain('延期')
  expect(pane.text()).toContain('2026-12-16 00:00:00 → 2027-01-16 00:00:00')
  expect(pane.text()).toContain('平台活动延续')
  expect(pane.text()).toContain('胡源')
  expect(pane.text()).toContain('随 ZC-000030 获批')
  expect(pane.getComponent({ name: 'AttachmentDownload' }).props('url')).toBe(
    '/api/policy-reviews/5/time-changes/17/attachments/proof/content?submissionVersion=2',
  )
  expect(wrapper.text()).not.toContain('随单申请')
  expect(fetch.mock.calls.some(([path]) => path.includes('audit-logs'))).toBe(false)
  await tabs()[0].trigger('click')
  expect(wrapper.text()).toContain('随单申请')
  expect(wrapper.text()).not.toContain('平台活动延续')
  await tabs()[1].trigger('click')
  await wrapper.get('[role=dialog] .el-dialog__headerbtn').trigger('click')
  await flushPromises()
  await wrapper.get('[aria-label="查看政策 ZC-000006"]').trigger('click')
  await flushPromises()
  expect(tabs()[0].attributes('aria-selected')).toBe('true')
  await tabs()[1].trigger('click')
  expect(wrapper.text()).toContain('暂无时间调整记录')
  expect(wrapper.text()).not.toContain('平台活动延续')
})

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
    if (path === '/api/policy-reviews/5?submissionVersion=2') {
      return Response.json({ ...reviewRound(), reviewStage: 'PURCHASE_REVIEW' })
    }
    if (path === '/api/policy-reviews/5?submissionVersion=1') {
      return Response.json({
        ...reviewRound(),
        submissionVersion: 1,
        status: 'REJECTED',
        reviewStage: null,
        allowedActions: [],
      })
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
  expect(wrapper.text()).toContain('国补、地补')
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
  expect(history.text()).toContain('自补')
  expect(history.text()).not.toContain('国补、地补')
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
  expect(wrapper.text()).toContain('国补、地补')
  expect(button(wrapper, '通过')).toBeDefined()
  expect(fetch.mock.calls.every(([, options]) => !options.method || options.method === 'GET')).toBe(
    true,
  )
})

it('记录和快照读取失败分别显示实际错误并可重试，空历史不生成演示记录', async () => {
  let failList = true,
    failSnapshot = true,
    failRound = true
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path) => {
      if (path === '/api/policy-reviews/5?submissionVersion=1') {
        if (failRound) {
          return Response.json({ message: '第一轮成员暂不可用' }, { status: 503 })
        }
        return Response.json({
          ...reviewRound(),
          submissionVersion: 1,
          status: 'REJECTED',
          reviewStage: null,
          allowedActions: [],
        })
      }
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
  expect(wrapper.text()).toContain('历史机型名称')
  expect(wrapper.text()).toContain('第一轮成员暂不可用')
  expect(wrapper.find('[aria-label="本轮审核进度"]').exists()).toBe(false)
  failRound = false
  await button(wrapper, '重新读取本轮审核信息').trigger('click')
  await flushPromises()
  expect(wrapper.find('[aria-label="本轮审核进度"]').exists()).toBe(true)
  expect(fetch.mock.calls.filter(([path]) => path.endsWith('/901'))).toHaveLength(2)
  await button(wrapper, '返回审核记录').trigger('click')
  vi.mocked(fetch).mockResolvedValue(Response.json({ items: [], total: 0 }))
  await wrapper.setProps({ policyId: '6' })
  await flushPromises()
  expect(wrapper.text()).toContain('暂无审核记录')
  expect(wrapper.find('[aria-label="查看第 1 轮内容"]').exists()).toBe(false)
})
