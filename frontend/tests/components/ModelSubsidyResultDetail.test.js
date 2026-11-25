// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import AdminModelSubsidiesPage from '../../src/views/admin/policies/AdminModelSubsidiesPage.vue'
import PolicySubsidyTimeline from '../../src/components/PolicySubsidyTimeline.vue'
import { billionGroupHistory, directory, retailGroupHistory } from '../fixtures/subsidy-history'

let wrapper
afterEach(() => {
  wrapper?.unmount()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function open(sample) {
  vi.spyOn(Date, 'now').mockReturnValue(Date.parse(sample.asOf))
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(Response.json(directory))
      .mockResolvedValueOnce(Response.json(sample)),
  )
  wrapper = mount(AdminModelSubsidiesPage, { global: { stubs: { PolicySubsidyTimeline: true } } })
  await flushPromises()
  return wrapper.getComponent(PolicySubsidyTimeline)
}

it.each([false, true])(
  '零售整组原行在一秒交叉逐件核对并只显示一次原单金额，计划=%s',
  async (planned) => {
    const sample = retailGroupHistory('SPECIFIED')
    const [brown, others] = sample.tracks
    const tick = '2026-11-15T00:00:00'
    const next = '2026-11-15T00:00:01'
    const single = brown.policies[1]
    single.effectiveThrough = single.policyThrough = tick + '+08:00'
    const before = brown.segments[0]
    const during = { ...brown.segments[1], endAt: next + '+08:00' }
    const after = { ...before, startAt: next + '+08:00', endAt: '2026-11-20T00:00:00+08:00' }
    const whole = { ...others.segments[0], endAt: after.endAt }
    sample.asOf = planned ? '2026-11-13T00:00:00+08:00' : '2026-11-20T00:00:00+08:00'
    for (const [track, periods] of [
      [brown, [before, during, after]],
      [others, [whole]],
    ]) {
      track.segments = planned ? [] : periods
      track.plannedSegments = planned ? periods : []
    }
    const chart = await open(sample)
    const rows = chart.props('rows')
    const group = rows.find((row) => row.plan.cardType === 'COMMON')
    const individual = rows.find((row) => row.plan.cardType === 'SPECIFIED')
    expect(rows).toHaveLength(2)
    chart.vm.$emit('select-policy', group, tick)
    await nextTick()
    const panel = wrapper.get('[aria-label="所选补贴明细"]')
    const outcomes = panel.get('[aria-label="逐商品审核结果"]').findAll('li')
    expect(outcomes.map((outcome) => outcome.text())).toEqual(
      expect.arrayContaining([
        expect.stringMatching(/可可棕 #61.*采用 · ZC-000024.*未采用 · ZC-000023/),
        expect.stringMatching(/初雪白 #62.*奇遇喵 #63.*采用 · ZC-000023/),
      ]),
    )
    expect(panel.text()).toContain(planned ? '本段计划部分采用' : '该时点部分采用')
    expect(panel.get('.model-subsidy-detail-fields').text()).toContain('2026-11-15 00:00:00')
    expect(panel.findAll('.model-subsidy-source')).toHaveLength(2)
    expect(panel.findAll('.model-subsidy-source.inspected')).toHaveLength(1)
    expect(panel.get('.model-subsidy-source.inspected').text()).toContain('固定 0.1 元')
    expect(panel.text()).toMatch(/该平台数量\s*0/)
    chart.vm.$emit('select-policy', individual, tick)
    await nextTick()
    expect(panel.get('.model-subsidy-source.inspected').text()).toContain('ZC-000024')
    expect(panel.find('[aria-label="逐商品审核结果"]').exists()).toBe(false)
    chart.vm.$emit('select-policy', group, next)
    await nextTick()
    expect(panel.find('[aria-label="逐商品审核结果"]').exists()).toBe(false)
    expect(panel.findAll('.model-subsidy-source')).toHaveLength(1)
    expect(panel.text()).not.toContain('ZC-000024')
  },
)

it('百亿按整组审核并显示被点选原单的申报商品，指定数量只列一次', async () => {
  const sample = billionGroupHistory('SPECIFIED')
  const chart = await open(sample)
  const [group, individual] = chart.props('rows')
  const panel = wrapper.get('[aria-label="所选补贴明细"]')
  for (const row of [group, individual]) {
    chart.vm.$emit('select-policy', row, '2026-11-16T12:00:00')
    await nextTick()
    expect(panel.find('.model-subsidy-detail-summary').exists()).toBe(false)
    expect(panel.get('[aria-label="后续采用安排"]').text()).toContain('审核范围 · 整组配置')
    expect(panel.find('[aria-label="逐商品审核结果"]').exists()).toBe(false)
    expect(panel.findAll('.model-subsidy-source')).toHaveLength(2)
    expect(panel.get('.model-subsidy-source.inspected').text()).toContain(row.plan.policyCode)
    expect(panel.text().match(/该平台数量\s*0/g)).toHaveLength(1)
  }
})
