// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import PolicyRejectionNotice from '../../src/views/apply/components/PolicyRejectionNotice.vue'

let wrapper
const policy = { id: '5', status: 'REJECTED', submissionVersion: 2 }
const audit = {
  id: '3',
  submissionVersion: 2,
  auditStatus: 'REJECTED',
  rejectedAtStage: 'PURCHASE_REVIEW',
  auditComment: '请核对补贴金额。\n补充活动说明后重新提交。',
  auditorName: '采购审核人',
  auditTime: '2026-11-15T16:05:51+08:00',
}
const reply = (body, status = 200) => ({ ok: status < 400, status, json: async () => body })
const history = (log = audit) => reply({ items: [log], total: 2, page: 1, pageSize: 1 })

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.unstubAllGlobals()
})

it('直接展示驳回原因、审核人及北京时间，保留换行并隐藏内部轮次', async () => {
  const fetch = vi.fn(async () => history())
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(PolicyRejectionNotice, { props: { policy } })
  await flushPromises()
  expect(fetch.mock.calls[0][0]).toBe('/api/policies/5/audit-logs?page=1&pageSize=1')
  expect(wrapper.get('.policy-rejection-comment').element.textContent.trim()).toBe(
    audit.auditComment,
  )
  expect(wrapper.text()).not.toContain('第 2 轮')
  expect(wrapper.text()).toContain('采购审核人')
  expect(wrapper.text()).toContain('退回环节：采购终审')
  expect(wrapper.text()).toContain('2026-11-15 16:05:51')
  expect(wrapper.text()).not.toContain('未填写')
})

it.each([null, '', '   '])('审核原因为空值 %s 时明确提示未填写', async (auditComment) => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => history({ ...audit, auditComment })),
  )
  wrapper = mount(PolicyRejectionNotice, { props: { policy } })
  await flushPromises()
  expect(wrapper.text()).toContain('审核人未填写驳回原因。')
})

it.each(['DRAFT', 'PENDING', 'APPROVED'])('状态为 %s 时不显示或请求驳回原因', async (status) => {
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(PolicyRejectionNotice, { props: { policy: { ...policy, status } } })
  await flushPromises()
  expect(wrapper.find('.policy-rejection').exists()).toBe(false)
  expect(fetch).not.toHaveBeenCalled()
})

it('读取失败显示实际错误，重试成功后显示原因，不误报为未填写', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(reply({ message: '暂时无法读取审核记录。' }, 503))
    .mockResolvedValueOnce(history())
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(PolicyRejectionNotice, { props: { policy } })
  await flushPromises()
  expect(wrapper.text()).toContain('暂时无法读取审核记录。')
  expect(wrapper.text()).not.toContain('审核人未填写')
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('请核对补贴金额。')
  expect(fetch).toHaveBeenCalledTimes(2)
})

it('日志与当前轮次不一致时不借用旧驳回原因，并提供重试', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => history({ ...audit, submissionVersion: 1 })),
  )
  wrapper = mount(PolicyRejectionNotice, { props: { policy } })
  await flushPromises()
  expect(wrapper.text()).toContain('暂时无法获取驳回原因')
  expect(wrapper.text()).not.toContain(audit.auditComment)
  expect(wrapper.text()).not.toContain('审核人未填写')
  expect(wrapper.get('button').text()).toBe('重试读取驳回原因')
})

it('切换申请时中止旧读取，迟到的上一份原因不覆盖新申请', async () => {
  let resolve
  const fetch = vi
    .fn()
    .mockImplementationOnce(() => new Promise((done) => (resolve = done)))
    .mockResolvedValueOnce(
      history({
        ...audit,
        submissionVersion: 3,
        rejectedAtStage: 'PRODUCT_REVIEW',
        auditorName: '当前产品运营',
        auditComment: '新申请的原因',
      }),
    )
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(PolicyRejectionNotice, { props: { policy } })
  expect(wrapper.text()).toContain('正在读取驳回原因')
  await wrapper.setProps({ policy: { ...policy, id: '8', submissionVersion: 3 } })
  await flushPromises()
  expect(fetch.mock.calls[0][1].signal.aborted).toBe(true)
  expect(wrapper.text()).toContain('新申请的原因')
  expect(wrapper.text()).toContain('退回环节：产品运营初审')
  expect(wrapper.text()).toContain('当前产品运营')
  resolve(history())
  await flushPromises()
  expect(wrapper.text()).toContain('新申请的原因')
  expect(wrapper.text()).not.toContain('请核对补贴金额')
  await wrapper.setProps({
    policy: { ...policy, id: '8', status: 'PENDING', submissionVersion: 4 },
  })
  await flushPromises()
  expect(wrapper.find('.policy-rejection').exists()).toBe(false)
  expect(fetch).toHaveBeenCalledTimes(2)
})
