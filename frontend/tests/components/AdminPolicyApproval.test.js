// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import AdminPoliciesPage from '../../src/views/admin/policies/AdminPoliciesPage.vue'
import { groupedCoverageDetail, referenceWinner } from '../fixtures/policy-coverage'

let wrapper
afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
const button = (root, label) => root.findAll('button').find((node) => node.text() === label)
const dialog = () => wrapper.get('.admin-policy-approval-dialog')

async function start({ next = groupedCoverageDetail(), failRead = false, submit } = {}) {
  const initial = groupedCoverageDetail()
  let reads = 0
  const state = { next, failRead }
  const fetch = vi.fn(async (path, options) => {
    if (options.method === 'POST') {
      return submit ? submit() : Response.json({ message: '状态已变化' }, { status: 409 })
    }
    if (path === '/api/admin/policies/5') {
      reads++
      if (reads > 1 && state.failRead) {
        return Response.json({ message: '覆盖核对暂时失败' }, { status: 503 })
      }
      return Response.json(reads === 1 ? initial : state.next)
    }
    return Response.json({
      items: [{ ...initial.policy, perspective: 'RETAIL', platforms: ['JD'], applicant: '测试人' }],
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
  return { fetch, state }
}
const writes = (fetch) => fetch.mock.calls.filter(([, options]) => options.method === 'POST')

it('通过先重新核对并确认整单，取消不写入且保留原筛选和驳回原因', async () => {
  const next = groupedCoverageDetail()
  next.coverage.at = next.coverage.effectiveFrom = '2026-11-16T12:30:00+08:00'
  next.coverage.items.reverse()
  const { fetch } = await start({ next })
  await button(wrapper, '驳回').trigger('click')
  await flushPromises()
  await wrapper.get('textarea').setValue('原审核原因')
  await button(wrapper, '取消').trigger('click')
  await wrapper.get('input[aria-label="覆盖商品关键词"]').setValue('初雪白')
  await button(wrapper, '通过').trigger('click')
  await flushPromises()
  expect(fetch.mock.calls.filter(([path]) => path === '/api/admin/policies/5')).toHaveLength(2)
  expect(writes(fetch)).toHaveLength(0)
  expect(dialog().text()).toContain('4 件商品 · 8 项商品平台范围')
  expect(dialog().text()).toContain('覆盖结果与当前审核窗口一致')
  expect(dialog().find('textarea').exists()).toBe(false)
  expect(button(dialog(), '确认通过').attributes('disabled')).toBeUndefined()
  await button(dialog(), '取消').trigger('click')
  await flushPromises()
  expect(writes(fetch)).toHaveLength(0)
  expect(wrapper.get('input[aria-label="覆盖商品关键词"]').element.value).toBe('初雪白')
  await button(wrapper, '驳回').trigger('click')
  await flushPromises()
  expect(wrapper.get('textarea').element.value).toBe('原审核原因')
})

it('覆盖变化展开最新结果，勾选核对后才能确认，重复点击仅写入一次且通过不带意见', async () => {
  const next = groupedCoverageDetail()
  Object.assign(next.coverage.items[0], {
    outcome: 'REPLACES',
    winner: referenceWinner,
    existingSubsidy: { subsidyMode: 'FIXED', fixedAmount: '40.00' },
  })
  let finish
  const { fetch } = await start({
    next,
    submit: () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  })
  await button(wrapper, '通过').trigger('click')
  await flushPromises()
  expect(dialog().text()).toContain('覆盖结果已变化')
  expect(dialog().text()).toContain('生效时覆盖 1 项')
  expect(dialog().find('.admin-policy-coverage').exists()).toBe(true)
  expect(button(dialog(), '确认通过').attributes('disabled')).toBeDefined()
  await dialog().get('input[type="checkbox"]').setValue(true)
  await button(dialog(), '确认通过').trigger('click')
  await button(dialog(), '确认通过').trigger('click')
  expect(writes(fetch)).toHaveLength(1)
  expect(JSON.parse(writes(fetch)[0][1].body)).toEqual({
    recordVersion: 4,
    submissionVersion: 2,
    decision: 'APPROVE',
    reviewRemark: null,
  })
  expect(button(dialog(), '取消').attributes('disabled')).toBeDefined()
  next.policy.status = 'APPROVED'
  next.policy.reviewable = false
  next.policy.recordVersion++
  finish(Response.json({ id: '5', status: 'APPROVED' }))
  await flushPromises()
  expect(wrapper.text()).toContain('政策详情')
  expect(button(wrapper, '通过')).toBeUndefined()
})

it('重新核对失败时禁止通过，可重试读取；写入冲突必须重新读取后再审核', async () => {
  const { fetch, state } = await start({ failRead: true })
  await button(wrapper, '通过').trigger('click')
  await flushPromises()
  expect(dialog().text()).toContain('覆盖核对暂时失败')
  expect(button(dialog(), '确认通过').attributes('disabled')).toBeDefined()
  expect(writes(fetch)).toHaveLength(0)
  state.failRead = false
  await button(dialog(), '重新核对覆盖结果').trigger('click')
  await flushPromises()
  await button(dialog(), '确认通过').trigger('click')
  await flushPromises()
  expect(dialog().text()).toContain('状态已变化')
  expect(button(dialog(), '确认通过').attributes('disabled')).toBeDefined()
  expect(button(dialog(), '重新读取政策')).toBeDefined()
})

it.each(['version', 'status', 'permission'])(
  '复核发现申请的 %s 变化时不直接批准新版本',
  async (change) => {
    const next = groupedCoverageDetail()
    if (change === 'version') {
      next.policy.submissionVersion++
    }
    if (change === 'status') {
      next.policy.status = 'APPROVED'
    }
    if (change === 'permission') {
      next.policy.reviewable = false
    }
    const { fetch } = await start({ next })
    await button(wrapper, '通过').trigger('click')
    await flushPromises()
    expect(dialog().text()).toContain('申请状态或内容已变化')
    expect(button(dialog(), '确认通过').attributes('disabled')).toBeDefined()
    expect(button(dialog(), '重新读取政策')).toBeDefined()
    expect(writes(fetch)).toHaveLength(0)
  },
)

it('复核中取消会中止读取，迟到的旧结果不影响下一次确认', async () => {
  const { fetch } = await start()
  let finish
  fetch.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  await button(wrapper, '通过').trigger('click')
  await flushPromises()
  const signal = fetch.mock.calls.at(-1)[1].signal
  expect(dialog().text()).toContain('正在重新核对覆盖结果')
  expect(button(dialog(), '确认通过').attributes('disabled')).toBeDefined()
  await button(dialog(), '取消').trigger('click')
  await flushPromises()
  expect(signal.aborted).toBe(true)
  await button(wrapper, '通过').trigger('click')
  await flushPromises()
  const old = groupedCoverageDetail()
  old.policy.recordVersion++
  finish(Response.json(old))
  await flushPromises()
  expect(dialog().text()).toContain('覆盖结果与当前审核窗口一致')
  expect(dialog().text()).not.toContain('申请状态或内容已变化')
  expect(writes(fetch)).toHaveLength(0)
})
