// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import PolicySubsidyTimeline from '../../src/components/PolicySubsidyTimeline.vue'
import AdminModelSubsidiesPage from '../../src/views/admin/policies/AdminModelSubsidiesPage.vue'
import { useSubsidyTimelineNavigation } from '../../src/composables/useSubsidyTimelineNavigation'
import {
  createSubsidyPreviewSegments,
  SUBSIDY_PREVIEW_AT,
} from '../../src/mocks/fixtures/model-subsidies'

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
      convertFromPixel: () => [new Date('2026-09-15T12:00:00+08:00').getTime(), 0],
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
      userSelect(segment) {
        handlers.click({ data: { segment } })
      },
    }
    charts.push(chart)
    return chart
  }),
}))

let wrapper
beforeEach(() => {
  charts.length = 0
})
afterEach(() => {
  wrapper?.unmount()
  vi.restoreAllMocks()
})
function render() {
  const all = createSubsidyPreviewSegments('find-x8', '16-512')
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
  ).toEqual(['2026-09-15T12:00:00', '2026-09-15T12:00:00'])
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
    .vm.$emit('cursor-change', '2026-09-16T12:00:00')
  await nextTick()
  expect(wrapper.find('.model-subsidy-point-time').text()).toBe('2026-09-16 12:00:00')
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
      expect(timeline.props('range')).toEqual([`${expectedStart}T00:00:00`, '2026-11-16T15:26:42'])
      expect(timeline.props('zoom')).toEqual({ start: 0, end: 100 })
    }
    expect(picker.props('modelValue')).toEqual([expectedStart, '2026-11-16'])
    expect(wrapper.find('.model-subsidy-point-time').text()).toBe('2026-11-16 15:26:42')
  }
})

it('固定与比例补贴悬停只强调边框，点击后选中边框和底色保持正确', async () => {
  wrapper = mount(AdminModelSubsidiesPage)
  await nextTick()
  const start = new Date('2026-08-01T00:00:00+08:00').getTime()
  const end = new Date(`${SUBSIDY_PREVIEW_AT}+08:00`).getTime()
  function rectangle(mode) {
    const series = charts[0].getOption().series[0]
    const index = series.data.findIndex((item) => item.segment.subsidyMode === mode)
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
      segment: series.data[index].segment,
    }
  }
  for (const [mode, fill] of [
    ['FIXED', '#e9f1ff'],
    ['RATIO', '#fff0e0'],
  ]) {
    let rendered = rectangle(mode)
    expect(rendered.shape.style.fill).toBe(fill)
    expect(rendered.shape.emphasis.style.fill).toBe(fill)
    expect(rendered.shape.emphasis.style.lineWidth).toBe(2)
    charts[0].userSelect(rendered.segment)
    await nextTick()
    expect(charts[0].dispatchAction).toHaveBeenLastCalledWith(
      { type: 'downplay', seriesIndex: 0 },
      { silent: true },
    )
    rendered = rectangle(mode)
    expect(rendered.shape.style.fill).toBe(fill)
    expect(rendered.shape.style.lineWidth).toBe(2)
  }
})
