// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import PolicySubsidyTimeline from '../../src/components/PolicySubsidyTimeline.vue'
import AdminModelSubsidiesPage from '../../src/views/admin/policies/AdminModelSubsidiesPage.vue'
import { useSubsidyTimelineNavigation } from '../../src/composables/useSubsidyTimelineNavigation'
import { createSubsidyPreviewSegments } from '../../src/mocks/fixtures/model-subsidies'

const SUBSIDY_PREVIEW_AT = '2026-11-16T12:00:00'

const { charts } = vi.hoisted(() => ({ charts: [] }))
vi.mock('echarts/charts', () => ({ CustomChart: {} }))
vi.mock('echarts/components', () => ({
  DataZoomComponent: {},
  GridComponent: {},
  TooltipComponent: {},
}))
vi.mock('echarts/renderers', () => ({ SVGRenderer: {} }))
vi.mock('echarts/core', () => ({
  use: vi.fn(),
  graphic: { clipRectByRect: (shape) => shape },
  init: vi.fn(() => {
    let option
    const handlers = {}
    const chart = {
      on: (event, handler) => {
        handlers[event] = handler
      },
      resize: vi.fn(),
      dispose: vi.fn(),
      setOption: vi.fn((value) => {
        option = value
      }),
      getOption: () => option,
      convertFromPixel: () => [Date.parse('2026-11-05T12:00:00Z'), 0],
      dispatchAction: vi.fn((action, config) => {
        if (action.type === 'dataZoom') {
          Object.assign(option.dataZoom[0], { start: action.start, end: action.end })
          if (!config?.silent) {
            handlers.datazoom?.(action)
          }
        }
      }),
      userZoom(start, end) {
        Object.assign(option.dataZoom[0], { start, end })
        handlers.datazoom({ start, end })
      },
      clickPolicy(entry) {
        handlers.click({ data: entry })
      },
    }
    charts.push(chart)
    return chart
  }),
}))

let wrapper
beforeEach(() => {
  charts.length = 0
  vi.spyOn(Date, 'now').mockReturnValue(new Date(`${SUBSIDY_PREVIEW_AT}+08:00`).getTime())
})
afterEach(() => {
  wrapper?.unmount()
  vi.restoreAllMocks()
})
function render() {
  const all = createSubsidyPreviewSegments('find-x8', '16-512', '2026-11-16')
  const retail = [
    all.findLast(
      (item) => item.platformCode === 'PDD' && item.channel === 'RETAIL' && item.scope === 'NORMAL',
    ),
  ]
  const billion = [
    all.findLast((item) => item.platformCode === 'PDD' && item.channel === 'BILLION'),
  ]
  wrapper = mount(
    defineComponent({
      components: { PolicySubsidyTimeline },
      setup() {
        const range = ref(['2026-08-01T00:00:00', SUBSIDY_PREVIEW_AT])
        return {
          range,
          ...useSubsidyTimelineNavigation(range),
          retail,
          billion,
          currentAt: SUBSIDY_PREVIEW_AT,
        }
      },
      template: `<div><PolicySubsidyTimeline v-for="(items,index) in [retail,billion]" :key="index" :segments="items" :range="range" :current-at="currentAt" :cursor-at="cursorAt" :zoom="zoom" label="预览" @zoom-change="setZoom" @cursor-change="setCursor" /></div>`,
    }),
  )
}

it('任一图缩放后另一张同步，静默回写不会造成事件循环', async () => {
  render()
  charts[0].userZoom(20, 70)
  await nextTick()
  expect(charts[1].dispatchAction).toHaveBeenCalledExactlyOnceWith(
    { type: 'dataZoom', dataZoomIndex: 0, start: 20, end: 70 },
    { silent: true },
  )
  expect(charts[0].dispatchAction).not.toHaveBeenCalled()
  charts[1].userZoom(40, 90)
  await nextTick()
  expect(charts[0].dispatchAction).toHaveBeenCalledExactlyOnceWith(
    { type: 'dataZoom', dataZoomIndex: 0, start: 40, end: 90 },
    { silent: true },
  )
  expect(
    wrapper.findAllComponents(PolicySubsidyTimeline).map((item) => item.props('zoom')),
  ).toEqual([
    { start: 40, end: 90 },
    { start: 40, end: 90 },
  ])
})

it('移动任一图的指针后两图同一时点，不重新创建时间轴或全量重绘', async () => {
  render()
  const before = charts.map((chart) => chart.setOption.mock.calls.length)
  await wrapper
    .findAll('.subsidy-timeline-layout')[1]
    .trigger('pointermove', { clientX: 300, clientY: 80 })
  expect(
    wrapper.findAllComponents(PolicySubsidyTimeline).map((item) => item.props('cursorAt')),
  ).toEqual(['2026-11-05T12:00:00', '2026-11-05T12:00:00'])
  const lines = wrapper.findAll('.subsidy-timeline-pointer line')
  expect(lines[0].attributes('x1')).toBe(lines[1].attributes('x1'))
  expect(charts.map((chart) => chart.setOption.mock.calls.length)).toEqual(before)
})

it('真实页面移动指针和更新时点补贴时保持图表数据稳定，避免悬停中重建图表', async () => {
  wrapper = mount(AdminModelSubsidiesPage)
  await nextTick()
  const before = charts.map((chart) => chart.setOption.mock.calls.length)
  const data = wrapper
    .findAllComponents(PolicySubsidyTimeline)
    .map((item) => item.props('segments'))
  await wrapper
    .find('.subsidy-timeline-layout')
    .trigger('pointermove', { clientX: 300, clientY: 80 })
  wrapper
    .findAllComponents(PolicySubsidyTimeline)[1]
    .vm.$emit('cursor-change', '2026-11-06T12:00:00')
  await nextTick()
  expect(wrapper.get('.model-subsidy-detail-at time').text()).toBe('2026-11-06 12:00:00')
  expect(charts.map((chart) => chart.setOption.mock.calls.length)).toEqual(before)
  wrapper.findAllComponents(PolicySubsidyTimeline).forEach((item, index) => {
    expect(item.props('segments')).toBe(data[index])
  })
})

it('当前时间按钮将历史和未来查询带到北京时间此刻，两图同步定位并恢复完整缩放', async () => {
  vi.spyOn(Date, 'now').mockReturnValue(new Date('2026-11-16T15:26:42+08:00').getTime())
  wrapper = mount(AdminModelSubsidiesPage)
  await nextTick()
  const picker = wrapper.findComponent({ name: 'ElDatePicker' })
  for (const [dates, expectedStart] of [
    [['2026-09-01', '2026-09-30'], '2026-09-01'],
    [['2026-12-01', '2026-12-31'], '2026-11-16'],
  ]) {
    picker.vm.$emit('update:modelValue', dates)
    await wrapper.find('form').trigger('submit')
    if (charts[0].getOption()) {
      charts[0].userZoom(20, 70)
      await nextTick()
    }
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '当前时间')
      .trigger('click')
    await nextTick()
    const timelines = wrapper.findAllComponents(PolicySubsidyTimeline)
    expect(timelines).toHaveLength(2)
    for (const timeline of timelines) {
      expect(timeline.props('cursorAt')).toBe('2026-11-16T15:26:42')
      expect(timeline.props('currentAt')).toBe('2026-11-16T15:26:42')
      expect(timeline.props('range')).toEqual([
        (expectedStart < '2026-10-27' ? expectedStart : '2026-10-27') + 'T00:00:00',
        '2026-11-21T23:59:59',
      ])
      expect(timeline.props('zoom')).toEqual({ start: 0, end: 100 })
    }
    expect(picker.props('modelValue')).toEqual([expectedStart, '2026-11-16'])
    expect(wrapper.get('.model-subsidy-detail-at time').text()).toBe('2026-11-16 15:26:42')
  }
})

it('平台条件随查询或当前时间生效，时间轴、独立商品、时点补贴和明细使用同一范围', async () => {
  vi.spyOn(Date, 'now').mockReturnValue(new Date(`${SUBSIDY_PREVIEW_AT}+08:00`).getTime())
  wrapper = mount(AdminModelSubsidiesPage)
  const platform = wrapper
    .findAllComponents({ name: 'ElSelect' })
    .find((select) => select.props('ariaLabel') === '平台')
  const tracks = () => [
    ...new Set(
      wrapper
        .findAll('.subsidy-timeline-platform > span, .retail-policy-row small')
        .map((row) => row.text().replace(' · ', '')),
    ),
  ]
  await wrapper.get('input[type="checkbox"]').setValue(true)
  platform.vm.$emit('update:modelValue', 'PDD')
  await nextTick()
  expect(tracks().some((label) => label.includes('京东'))).toBe(true)
  await wrapper.get('form').trigger('submit')
  expect(tracks().map((label) => label.replaceAll(' ', ''))).toEqual([
    '拼多多三款颜色',
    '拼多多独立商品',
    '拼多多整组配置',
  ])
  await wrapper.get('[aria-label="查看 ZC-000042 独立商品的政策明细"]').trigger('click')
  expect(wrapper.get('.model-subsidy-detail-card').text()).toContain('ZC-000042')

  platform.vm.$emit('update:modelValue', 'JD')
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '当前时间')
    .trigger('click')
  expect(tracks()).toHaveLength(2)
  expect(tracks()).toEqual(['京东浮光白、星野黑', '京东追风蓝'])
  expect(wrapper.get('[aria-label="百亿补贴时间轴"]').text()).toContain('暂无示例政策')
  expect(wrapper.get('.model-subsidy-detail-summary').text()).toContain('京东')
})

it('平台没有示例时清空两图、时点行和旧明细，重置后恢复全部平台', async () => {
  vi.spyOn(Date, 'now').mockReturnValue(new Date(`${SUBSIDY_PREVIEW_AT}+08:00`).getTime())
  wrapper = mount(AdminModelSubsidiesPage)
  const platform = wrapper
    .findAllComponents({ name: 'ElSelect' })
    .find((select) => select.props('ariaLabel') === '平台')
  platform.vm.$emit('update:modelValue', 'DEWU')
  await wrapper.get('form').trigger('submit')
  expect(wrapper.findAllComponents(PolicySubsidyTimeline)).toHaveLength(0)
  expect(wrapper.findAll('.retail-policy-row')).toHaveLength(0)
  expect(wrapper.get('.model-subsidy-detail-card').text()).toContain('暂无可展示的补贴明细')
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重置')
    .trigger('click')
  expect(platform.props('modelValue')).toBe('')
  expect(wrapper.findAllComponents(PolicySubsidyTimeline)).toHaveLength(2)
  expect(wrapper.find('[aria-label="所选时点适用结果"]').exists()).toBe(false)
  expect(wrapper.get('.model-subsidy-workspace').element.firstElementChild.className).toBe(
    'model-subsidy-charts',
  )
})

it('固定与比例补贴悬停只强调边框，点击后选中边框和底色保持正确', async () => {
  wrapper = mount(AdminModelSubsidiesPage)
  await nextTick()
  const start = new Date('2026-08-01T00:00:00+08:00').getTime()
  const end = new Date(`${SUBSIDY_PREVIEW_AT}+08:00`).getTime()
  function rectangle(mode) {
    const series = charts[0].getOption().series[0]
    const index = series.data.findIndex((item) => item.plan.subsidyMode === mode)
    const values = series.data[index].value
    const group = series.renderItem(
      { dataIndex: index, coordSys: { x: 88, y: 35, width: 557, height: 280 } },
      {
        value: (dimension) => values[dimension],
        coord: ([time, row]) => [88 + ((time - start) / (end - start)) * 557, 35 + row * 56 + 28],
      },
    )
    return {
      shape: group.children.find((child) => child.type === 'rect' && !child.silent),
      entry: series.data[index],
    }
  }
  for (const [mode, fill] of [
    ['FIXED', '#346dc0'],
    ['RATIO', '#a55d19'],
  ]) {
    let rendered = rectangle(mode)
    expect(rendered.shape.style.fill).toBe(fill)
    expect(rendered.shape.emphasis.style.fill).toBe(fill)
    expect(rendered.shape.emphasis.style.lineWidth).toBe(2)
    charts[0].clickPolicy(rendered.entry)
    await nextTick()
    const outline = wrapper.find(
      '.subsidy-timeline-selection[stroke="' + rendered.shape.emphasis.style.stroke + '"]',
    )
    expect(outline.attributes('stroke-width')).toBe('2')
    rendered = rectangle(mode)
    expect(rendered.shape.style.fill).toBe(fill)
    expect(rendered.shape.style.lineWidth).toBe(1)
  }
})
it('同一配置按颜色列出零售胜出政策，多场景的同一轮补贴只展示一次', async () => {
  wrapper = mount(AdminModelSubsidiesPage)
  await wrapper.get('[aria-label="查看 ZC-000112 浮光白、星野黑的政策明细"]').trigger('click')
  const used = wrapper.findAll('.model-subsidy-source:not(.muted)')
  expect(used).toHaveLength(2)
  expect(used[0].text()).toContain('固定 150 元')
  expect(used[1].text()).toContain('比例 2.5%')
  expect(wrapper.get('.model-subsidy-detail-summary').text()).toContain('跨组叠加')
  expect(wrapper.get('.model-subsidy-detail-card').text()).toContain('地补、自补')
  expect(wrapper.get('.model-subsidy-source.muted').text()).toContain('ZC-000110')
})

it('百亿选一时只列赢家，叠加时列出两单，并区分指定商品数量零与未填', async () => {
  wrapper = mount(AdminModelSubsidiesPage)
  await wrapper.get('[aria-label="查看拼多多整组配置的补贴明细"]').trigger('click')
  expect(wrapper.get('.model-subsidy-detail-summary').text()).toContain('交叉段选一')
  expect(wrapper.get('.model-subsidy-source:not(.muted)').text()).toContain('ZC-000201')
  expect(wrapper.get('.model-subsidy-source.muted').text()).toContain('ZC-000202')
  const excluded = wrapper.get('.model-subsidy-source.muted')
  expect(excluded.text()).toContain('ZC-000202')
  expect(excluded.text()).toMatch(/该平台数量\s*0/)
  await wrapper.get('[aria-label="查看淘宝整组配置的补贴明细"]').trigger('click')
  expect(wrapper.findAll('.model-subsidy-source')).toHaveLength(2)
  expect(wrapper.get('.model-subsidy-detail-card').text()).toMatch(/该平台数量\s*未填写/)
})

it('晚审核从审核通过该秒切换，同组被替换的政策保持原截止日', async () => {
  wrapper = mount(AdminModelSubsidiesPage)
  const timeline = wrapper.findComponent(PolicySubsidyTimeline)
  for (const [time, code] of [
    ['10:29:59', 'ZC-000110'],
    ['10:30:00', 'ZC-000111'],
  ]) {
    timeline.vm.$emit('cursor-change', '2026-11-09T' + time)
    await nextTick()
    expect(wrapper.get('.model-subsidy-source:not(.muted)').text()).toContain(code)
  }
  expect(wrapper.get('.model-subsidy-detail-fields').text()).toContain('2026-11-12 23:59:59')
  expect(wrapper.get('.model-subsidy-source.muted').text()).toContain('2026-11-21')
})

it('截止日最后一秒仍用短期补贴，次日只为原商品恢复仍有效的旧单', async () => {
  wrapper = mount(AdminModelSubsidiesPage)
  const timeline = wrapper.findComponent(PolicySubsidyTimeline)
  await wrapper.get('[aria-label="查看 ZC-000110 追风蓝的政策明细"]').trigger('click')
  const blue = () => wrapper.get('.model-subsidy-source:not(.muted)')
  timeline.vm.$emit('cursor-change', '2026-11-08T23:59:59')
  await nextTick()
  expect(blue().text()).toContain('ZC-000113')
  timeline.vm.$emit('cursor-change', '2026-11-09T00:00:00')
  await nextTick()
  expect(blue().text()).toContain('ZC-000110')
  expect(blue().text()).not.toContain('ZC-000113')
  expect(wrapper.get('.model-subsidy-decision-note').text()).toContain('ZC-000113 已到期')
})

it('京东 11 月 17 日 05:26 同步标出三张采用单及各自起止，缩短查询窗口保留真实边界', async () => {
  vi.spyOn(Date, 'now').mockReturnValue(new Date('2026-11-20T12:00:00+08:00').getTime())
  wrapper = mount(AdminModelSubsidiesPage)
  wrapper
    .findAllComponents({ name: 'ElSelect' })
    .find((item) => item.props('ariaLabel') === '平台')
    .vm.$emit('update:modelValue', 'JD')
  wrapper
    .findComponent({ name: 'ElDatePicker' })
    .vm.$emit('update:modelValue', ['2026-11-17', '2026-11-17'])
  await wrapper.get('form').trigger('submit')
  const chart = wrapper.findComponent(PolicySubsidyTimeline)
  chart.vm.$emit('cursor-change', '2026-11-17T05:26:43')
  await nextTick()
  expect(wrapper.findAll('.retail-policy-states .used')).toHaveLength(3)
  expect(wrapper.get('.model-subsidy-detail-at').text()).toContain('2026-11-17 05:26:43')
  const option = charts[0].getOption()
  const at = chart.props('cursorAt')
  const rows = option.series[0].data.filter(
    ({ part }) => part.used && part.startAt <= at && at < part.endAt,
  )
  expect(rows).toHaveLength(3)
  for (const [code, from, to] of [
    ['000111', '2026-11-13 10:30:00', '2026-11-22 23:59:59'],
    ['000112', '2026-11-17 00:00:00', '2026-11-21 23:59:59'],
    ['000110', '2026-11-13 00:00:00', '2026-11-25 23:59:59'],
  ]) {
    const row = rows.find((item) => item.row.plan.policyCode === 'ZC-' + code)
    const tip = option.tooltip.formatter({ data: row })
    expect(tip).toContain(from)
    expect(tip).toContain(to)
  }
  const source = wrapper.get('.model-subsidy-source')
  expect(source.text()).toContain('2026-11-13 10:30:00')
  expect(source.text()).toContain('2026-11-22 23:59:59')
  const entry = option.series[0].data.find(
    (item) => item.row.plan.policyCode === 'ZC-000111' && item.part.used,
  )
  expect(option.tooltip.formatter({ data: entry })).toContain('本段开始 2026-11-13 10:30:00')
})

it('京东固定示例跨过零点后只用旧单，007 的预估标记、未采用提示和实际起止一致', async () => {
  wrapper = mount(AdminModelSubsidiesPage)
  await wrapper.get('.model-subsidy-example').trigger('click')
  await nextTick()
  const chart = wrapper.getComponent(PolicySubsidyTimeline)
  expect(chart.props('range')).toEqual(['2026-11-23T00:00:00', '2026-11-25T23:59:59'])
  expect(wrapper.findAllComponents(PolicySubsidyTimeline)).toHaveLength(1)
  chart.vm.$emit('cursor-change', '2026-11-24T23:59:59')
  await nextTick()
  expect(wrapper.findAll('.retail-policy-states .used')).toHaveLength(2)
  expect(
    wrapper.findAll('.model-subsidy-source:not(.muted) strong').map((el) => el.text().trim()),
  ).toEqual(['ZC-000006', 'ZC-000007 预估'])
  expect(wrapper.get('.retail-policy-row:nth-child(2) .model-subsidy-estimate').text()).toBe(
    '预估政策',
  )
  expect(wrapper.find('.model-subsidy-chart-card table').exists()).toBe(false)
  chart.vm.$emit('cursor-change', '2026-11-25T00:00:00')
  await nextTick()
  expect(wrapper.get('.model-subsidy-source:not(.muted) strong').text()).toBe('ZC-000003')
  const excluded = wrapper.get('.model-subsidy-source.muted')
  expect(excluded.get('.model-subsidy-estimate').text()).toBe('预估')
  expect(excluded.text()).toContain('2026-11-23 至 2026-11-25')
  expect(excluded.get('.model-subsidy-source-periods').text()).toContain('2026-11-24 23:59:59')
  expect(
    chart.props('retailRows').map((row) => [row.plan.policyCode, row.parts.map((p) => p.used)]),
  ).toEqual([
    ['ZC-000006', [true]],
    ['ZC-000007', [true, false]],
    ['ZC-000003', [false, true]],
  ])
  expect(wrapper.get('.retail-policy-row:nth-child(2) time').text()).toBe(
    '2026-11-23 至 2026-11-25',
  )
  expect(wrapper.findAll('.retail-policy-states .used')).toHaveLength(1)
  const firstPart = charts[0]
    .getOption()
    .series[0].data.find((item) => item.row.plan.policyCode === 'ZC-000007' && item.part.used)
  charts[0].clickPolicy(firstPart)
  await nextTick()
  expect(wrapper.findAll('.retail-policy-states .used')).toHaveLength(2)
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '当前时间')
    .trigger('click')
  expect(wrapper.get('.model-subsidy-detail-at time').text()).toContain('2026-11-16 12:00:00')
  expect(wrapper.find('.model-subsidy-source').exists()).toBe(false)
})

it('晚审单从原单开始绘制，点击审核前的浅色段不改变采用名单，恢复的旧单分为两段', async () => {
  wrapper = mount(AdminModelSubsidiesPage)
  const chart = wrapper.getComponent(PolicySubsidyTimeline)
  const rows = chart.props('retailRows')
  const late = rows.find((row) => row.plan.policyCode === 'ZC-000111')
  expect(late.parts[0]).toEqual({
    used: false,
    startAt: '2026-11-09T00:00:00',
    endAt: '2026-11-09T10:30:00',
  })
  const legacy = rows.find(
    (row) => row.plan.policyCode === 'ZC-000110' && row.segment.trackId === late.segment.trackId,
  )
  expect(legacy.parts.map((part) => part.used)).toEqual([true, false, true])
  chart.vm.$emit('select-policy', late, '2026-11-09T10:29:59')
  await nextTick()
  expect(wrapper.get('.model-subsidy-source.inspected.muted').text()).toContain('ZC-000111')
  expect(wrapper.get('.model-subsidy-source:not(.muted)').text()).toContain('ZC-000110')
  chart.vm.$emit('cursor-change', '2026-11-09T10:30:00')
  await nextTick()
  expect(wrapper.get('.model-subsidy-source.inspected:not(.muted)').text()).toContain('ZC-000111')
})

it('查看某范围后移动指针，明细和采用时段同步切换，无有效单时清空旧明细', async () => {
  wrapper = mount(AdminModelSubsidiesPage)
  const chart = wrapper.findComponent(PolicySubsidyTimeline)
  await wrapper.get('button[aria-label="查看 ZC-000110 追风蓝的政策明细"]').trigger('click')
  chart.vm.$emit('cursor-change', '2026-11-08T23:59:59')
  await nextTick()
  expect(wrapper.get('.model-subsidy-source').text()).toContain('ZC-000113')
  chart.vm.$emit('cursor-change', '2026-11-09T00:00:00')
  await nextTick()
  expect(wrapper.get('.model-subsidy-source').text()).toContain('ZC-000110')
  await wrapper.get('[aria-label="查看拼多多整组配置的补贴明细"]').trigger('click')
  chart.vm.$emit('cursor-change', '2026-10-27T00:00:00')
  await nextTick()
  expect(wrapper.find('.model-subsidy-source').exists()).toBe(false)
  expect(wrapper.get('.model-subsidy-detail-card').text()).toContain(
    '拼多多 · 整组配置在该时点暂无适用政策',
  )
})
