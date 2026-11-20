// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { ElSelect } from 'element-plus'
import PolicySubmissionCoverage from '../../src/views/apply/components/PolicySubmissionCoverage.vue'
import { coverageDetail, groupedCoverageDetail } from '../fixtures/policy-coverage'

let wrapper
afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
const button = (label) => wrapper.findAll('button').find((node) => node.text() === label)

it('汇总按商品平台范围计数，结果/平台/关键词组合筛选并重置分页，零结果有空态', async () => {
  wrapper = mount(PolicySubmissionCoverage, { props: { detail: coverageDetail() } })
  await flushPromises()
  expect(wrapper.get('.coverage-summary').text()).toContain('全部 12 项')
  expect(wrapper.get('.coverage-summary').text()).toContain('更新已有政策 10 项')
  await wrapper.get('.btn-next').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('测试商品12')
  await button('更新已有政策 10 项').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('测试商品1')
  expect(wrapper.text()).not.toContain('测试商品12')
  wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'PDD')
  await flushPromises()
  expect(wrapper.text()).toContain('没有符合条件的对照结果')
  await button('重置').trigger('click')
  await wrapper.get('input[aria-label="覆盖商品关键词"]').setValue('测试商品12')
  await flushPromises()
  expect(wrapper.text()).toContain('展示 1 组，涉及 1 项商品平台范围 / 全部 12 项')
  expect(wrapper.get('.coverage-summary').text()).toContain('更新已有政策 10 项')
  expect(wrapper.text()).toContain('ZC-000004')
})

it('配置合并行展示已选颜色和商品明细，颜色搜索不带入同组其他商品', async () => {
  wrapper = mount(PolicySubmissionCoverage, { props: { detail: groupedCoverageDetail() } })
  await flushPromises()
  expect(wrapper.text()).toContain('展示 4 组，涉及 8 项商品平台范围 / 全部 8 项')
  expect(wrapper.find('.coverage-product-details').exists()).toBe(false)
  await wrapper.find('.el-table__expand-icon').trigger('click')
  await flushPromises()
  const details = wrapper.get('.coverage-product-details')
  expect(details.text()).toContain('可可棕')
  expect(details.text()).toContain('初雪白')
  expect(details.text()).toContain('奇遇喵')
  expect(details.findAll('.el-table__row').map((node) => node.text())).toEqual([
    expect.stringContaining('100'),
    expect.stringContaining('101'),
    expect.stringContaining('102'),
  ])
  await wrapper.get('input[aria-label="覆盖商品关键词"]').setValue('初雪白')
  await flushPromises()
  expect(wrapper.text()).toContain('展示 2 组，涉及 2 项商品平台范围 / 全部 8 项')
  expect(wrapper.text()).not.toContain('可可棕')
  expect(wrapper.text()).not.toContain('奇遇喵')
})

it('同配置中某件商品命中旧政策，展开后仅对照该件商品的新旧补贴', async () => {
  const detail = groupedCoverageDetail()
  Object.assign(detail.coverage.items[0], {
    outcome: 'REPLACES',
    winner: { id: '4', policyCode: 'ZC-000004', auditLogId: '800' },
    existingSubsidy: { subsidyMode: 'FIXED', fixedAmount: '40.00' },
  })
  wrapper = mount(PolicySubmissionCoverage, { props: { detail } })
  await flushPromises()
  expect(wrapper.text()).toContain('对照结果不同')
  await wrapper.find('.el-table__expand-icon').trigger('click')
  await flushPromises()
  const rows = wrapper.get('.coverage-product-details').findAll('.el-table__row')
  expect(rows[0].text()).toContain('ZC-000004')
  expect(rows[0].text()).toContain('固定补贴 · 40.00 元')
  expect(rows[0].text()).toContain('对比补贴')
  expect(rows[1].text()).toContain('本人暂无可对照政策')
})
