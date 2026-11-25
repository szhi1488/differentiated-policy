// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import { createMemoryHistory } from 'vue-router'
import AdminLayout from '../../src/layouts/AdminLayout.vue'
import { createWorkspaceRouter } from '../../src/router'
import PolicySubsidyTimeline from '../../src/components/PolicySubsidyTimeline.vue'
import AdminModelSubsidiesPage from '../../src/views/admin/policies/AdminModelSubsidiesPage.vue'
import { useSubsidyTimelineNavigation } from '../../src/composables/useSubsidyTimelineNavigation'
import { readTimelineHistory } from '../../src/utils/policy-timeline'
import {
  billionGroupHistory,
  directory,
  history,
  midnightPolicyHistory,
  retailGroupHistory,
} from '../fixtures/subsidy-history'

const AS_OF = '2026-11-16T12:00:00'

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
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path) => {
      const url = new URL(path, 'http://localhost')
      if (
        url.pathname === '/api/admin/catalog/goods/summary' &&
        url.searchParams.get('includeModelConfigurations') === 'true'
      ) {
        return Response.json(directory)
      }
      if (url.pathname !== '/api/admin/policies/subsidy-timeline') {
        throw new Error('接口路径错误')
      }
      const platform = url.searchParams.get('platformCode')
      return Response.json({
        ...history,
        tracks: history.tracks.filter(
          (t) =>
            (!platform || t.platformCode === platform) && url.searchParams.get('spuId') !== '22',
        ),
      })
    }),
  )
  vi.spyOn(Date, 'now').mockReturnValue(new Date(`${AS_OF}+08:00`).getTime())
})
afterEach(() => {
  wrapper?.unmount()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
function render() {
  const range = ref(['2026-08-01T00:00:00', AS_OF])
  const all = readTimelineHistory(history).rows
  wrapper = mount(
    defineComponent({
      components: { PolicySubsidyTimeline },
      setup() {
        return {
          range,
          ...useSubsidyTimelineNavigation(range),
          retail: all.filter((row) => row.segment.channel === 'RETAIL'),
          billion: all.filter((row) => row.segment.channel === 'BILLION'),
          currentAt: AS_OF,
        }
      },
      template: `<div><PolicySubsidyTimeline v-for="(items,index) in [retail,billion]" :key="index" :rows="items" :range="range" :current-at="currentAt" :cursor-at="cursorAt" :zoom="zoom" label="预览" @zoom-change="setZoom" @cursor-change="setCursor" /></div>`,
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
  await openPage()
  const before = charts.map((chart) => chart.setOption.mock.calls.length)
  const data = wrapper.findAllComponents(PolicySubsidyTimeline).map((item) => item.props('rows'))
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
    expect(item.props('rows')).toBe(data[index])
  })
})

it('固定与比例补贴悬停只强调边框，点击后选中边框和底色保持正确', async () => {
  await openPage()
  const start = new Date('2026-08-01T00:00:00+08:00').getTime()
  const end = new Date(`${AS_OF}+08:00`).getTime()
  function rectangle(mode) {
    const series = charts[0].getOption().series[0]
    const rows = wrapper.findComponent(PolicySubsidyTimeline).props('rows')
    const index = series.data.findIndex(
      ([row, , , part]) => rows[row].plan.subsidyMode === mode && rows[row].parts[part].used,
    )
    const values = series.data[index]
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

async function openPage() {
  wrapper = mount(AdminModelSubsidiesPage)
  await flushPromises()
}

it('不足一个像素的单秒交界可点击，定位到该秒且悬停显示真实截止', async () => {
  const at = '2026-12-08T00:00:00'
  const after = '2026-12-08T00:00:01'
  const base = readTimelineHistory(retailGroupHistory()).rows[0]
  const row = {
    ...base,
    parts: [{ startAt: at, endAt: after, used: true, status: '计划采用' }],
  }
  wrapper = mount(PolicySubsidyTimeline, {
    props: {
      rows: [row],
      range: [at, '2026-12-09T00:00:00'],
      currentAt: AS_OF,
      cursorAt: after,
      label: '零售',
    },
  })
  const option = charts[0].getOption()
  const series = option.series[0]
  const [entry] = series.data
  const group = series.renderItem(
    { coordSys: { x: 100, y: 0, width: 600, height: 150 } },
    {
      value: (i) => entry[i],
      coord: ([time]) => [200 + (time - entry[1]) / 120000, 80],
    },
  )
  expect(group.children.find((child) => child.type === 'circle')?.shape).toMatchObject({
    cx: 200,
    cy: 64,
    r: 5,
  })
  expect(option.tooltip.formatter({ data: entry })).toContain('本段计划结束 2026-12-08 00:00:00')
  charts[0].clickPolicy(entry)
  expect(wrapper.emitted('select-policy')[0][1]).toBe(at)
})
const select = (label) =>
  wrapper.findAllComponents({ name: 'ElSelect' }).find((s) => s.props('ariaLabel') === label)
const button = (label) => wrapper.findAll('button').find((b) => b.text() === label)
const timelines = () => wrapper.findAllComponents(PolicySubsidyTimeline)
const panel = () => wrapper.get('.model-subsidy-detail-card')
const adoptedPolicyCodes = () =>
  panel()
    .findAll('.model-subsidy-source:not(.muted) header strong')
    .map((item) => item.text())
    .sort()
const lastParams = () => new URL(fetch.mock.calls.at(-1)[0], 'http://localhost').searchParams
async function move(at, chart = timelines()[0]) {
  chart.vm.$emit('cursor-change', at)
  await nextTick()
}

it('目录联动使用真实机型 ID 和容量，编辑条件后点查询才替换已应用结果', async () => {
  await openPage()
  expect(fetch).toHaveBeenCalledTimes(2)
  expect(Object.fromEntries(lastParams())).toEqual({
    spuId: '9007199254740993',
    ram: '16GB',
    rom: '1024GB',
    dateFrom: '2026-11-01',
    dateTo: '2026-11-30',
  })
  expect(timelines()[0].props('label')).toContain('16GB + 1T')
  select('品牌').vm.$emit('update:modelValue', '一加手机')
  await nextTick()
  expect(select('机型及版本').props('modelValue')).toBe('22')
  expect(select('配置').props('modelValue')).toBe('12GB+256GB')
  expect(timelines()[0].props('label')).toContain('Find X8')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(lastParams().get('spuId')).toBe('22')
  expect(timelines()).toHaveLength(0)
  expect(wrapper.text()).toContain('暂无已通过政策')
  expect(panel().find('.model-subsidy-source').exists()).toBe(false)
  await button('重置').trigger('click')
  await flushPromises()
  expect(timelines()).toHaveLength(2)
})

it('切回机型补贴查询时按上次已应用的条件重新取数，重置后记住默认条件', async () => {
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addEventListener() {},
    removeEventListener() {},
  }))
  const catalog = structuredClone(directory)
  catalog.models.push({ seriesId: '12', spuId: '23', modelName: '一加 15' })
  catalog.modelConfigurations.push(
    { spuId: '23', ram: '12GB', rom: '256GB' },
    { spuId: '23', ram: '16GB', rom: '1024GB' },
  )
  const requests = []
  let tracks = []
  fetch.mockImplementation(async (path) => {
    const url = new URL(path, 'http://localhost')
    if (url.pathname === '/api/admin/catalog/goods/summary') {
      return Response.json(catalog)
    }
    if (url.pathname === '/api/admin/policies') {
      return Response.json({ items: [], total: 0 })
    }
    requests.push(Object.fromEntries(url.searchParams))
    return Response.json({ ...history, tracks })
  })
  const user = { id: '1', roleType: 'ADMIN' }
  const router = createWorkspaceRouter(createMemoryHistory(), { user: ref(user) })
  async function navigate(name) {
    await router.push({ name })
    await flushPromises()
  }
  await navigate('admin-model-subsidies')
  wrapper = mount(AdminLayout, { props: { user }, global: { plugins: [router] } })
  await flushPromises()
  const defaults = requests[0]
  select('品牌').vm.$emit('update:modelValue', '一加手机')
  select('机型及版本').vm.$emit('update:modelValue', '23')
  select('配置').vm.$emit('update:modelValue', '16GB+1024GB')
  select('平台').vm.$emit('update:modelValue', 'PDD')
  const dates = ['2026-11-04', '2026-11-20']
  wrapper.getComponent({ name: 'ElDatePicker' }).vm.$emit('update:modelValue', dates)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(timelines()).toHaveLength(0)
  select('平台').vm.$emit('update:modelValue', 'JD')
  await navigate('admin-policies')
  tracks = history.tracks.filter((track) => track.platformCode === 'PDD')
  await navigate('admin-model-subsidies')
  expect(requests).toHaveLength(3)
  expect(requests[2]).toEqual({
    spuId: '23',
    ram: '16GB',
    rom: '1024GB',
    platformCode: 'PDD',
    dateFrom: dates[0],
    dateTo: dates[1],
  })
  expect(select('品牌').props('modelValue')).toBe('一加手机')
  expect(select('机型及版本').props('modelValue')).toBe('23')
  expect(select('配置').props('modelValue')).toBe('16GB+1024GB')
  expect(select('平台').props('modelValue')).toBe('PDD')
  expect(wrapper.getComponent({ name: 'ElDatePicker' }).props('modelValue')).toEqual(dates)
  expect(
    timelines()[0]
      .props('rows')
      .some((row) => row.plan.cardType === 'SPECIFIED'),
  ).toBe(true)
  expect(timelines()[0].props('label')).toContain('一加 15')

  await navigate('admin-policies')
  catalog.modelConfigurations.pop()
  await navigate('admin-model-subsidies')
  expect(requests.at(-1)).toEqual({ ...requests[2], ram: '12GB', rom: '256GB' })
  await button('重置').trigger('click')
  await flushPromises()
  await navigate('admin-policies')
  await navigate('admin-model-subsidies')
  expect(requests.at(-1)).toEqual(defaults)
})

it('默认两张时间轴展示整月，跨月政策保留原单日期', async () => {
  const sample = structuredClone(history)
  Object.assign(sample.tracks[0].policies[0], {
    policyFrom: '2026-10-15T00:00:00+08:00',
    policyThrough: '2026-12-15T23:59:59+08:00',
    effectiveThrough: '2026-12-15T23:59:59+08:00',
  })
  fetch.mockResolvedValueOnce(Response.json(directory)).mockResolvedValueOnce(Response.json(sample))
  await openPage()
  for (const chart of timelines()) {
    expect(chart.props('range')).toEqual(['2026-11-01T00:00:00', '2026-11-30T23:59:59'])
  }
  expect(timelines()[0].text()).toContain('起 2026-10-15 00:00:00')
  expect(timelines()[0].text()).toContain('止 2026-12-15 23:59:59')
})

it.each([
  ['2026-12-31T16:00:00Z', '2027-01-01', '2027-01-31'],
  ['2027-01-31T16:00:00Z', '2027-02-01', '2027-02-28'],
  ['2028-02-29T15:59:59Z', '2028-02-01', '2028-02-29'],
])('页面跨月后重置按当时的北京时间查询完整月份 %s', async (now, start, end) => {
  await openPage()
  Date.now.mockReturnValue(Date.parse(now))
  await button('重置').trigger('click')
  await flushPromises()
  expect(wrapper.getComponent({ name: 'ElDatePicker' }).props('modelValue')).toEqual([start, end])
  expect(lastParams().get('dateFrom')).toBe(start)
  expect(lastParams().get('dateTo')).toBe(end)
})

it.each([
  [['2026-09-01', '2026-09-30'], '2026-09-01'],
  [['2026-12-01', '2026-12-31'], '2026-11-16'],
])('当前时间应用平台与日期，恢复完整缩放并定位北京时间 %j', async (dates, start) => {
  await openPage()
  charts[0].userZoom(20, 70)
  const picker = wrapper.getComponent({ name: 'ElDatePicker' })
  picker.vm.$emit('update:modelValue', dates)
  select('平台').vm.$emit('update:modelValue', 'PDD')
  await button('当前时间').trigger('click')
  await flushPromises()
  expect(lastParams().get('dateFrom')).toBe(start)
  expect(lastParams().get('dateTo')).toBe('2026-11-16')
  expect(lastParams().get('platformCode')).toBe('PDD')
  expect(timelines()).toHaveLength(1)
  expect(timelines()[0].props('cursorAt')).toBe(AS_OF)
  expect(timelines()[0].props('zoom')).toEqual({ start: 0, end: 100 })
  expect(panel().text()).toContain('百亿渠道')
})

it('同平台整组只占一行，交叉段在原行标出部分采用，并可查看独立商品', async () => {
  fetch
    .mockResolvedValueOnce(Response.json(directory))
    .mockResolvedValueOnce(Response.json(retailGroupHistory('SPECIFIED')))
  await openPage()
  const chart = timelines()[0]
  const rows = chart.props('rows')
  expect(chart.text()).toContain('京东 · 整组配置')
  expect(rows.map((row) => row.segment.label).sort()).toEqual(
    ['整组配置', '独立商品 · 可可棕'].sort(),
  )
  async function clickPart(code, label, time) {
    const at = '2026-11-' + time
    await move(at, chart)
    const entry = charts[0].getOption().series[0].data.find(([index, , , partIndex]) => {
      const row = rows[index]
      const part = row.parts[partIndex]
      return (
        row.plan.policyCode === code &&
        row.segment.label === label &&
        part.startAt <= at &&
        at < part.endAt
      )
    })
    expect(entry).toBeDefined()
    charts[0].clickPolicy(entry)
    await nextTick()
    expect(panel().get('.model-subsidy-source.inspected').text()).toContain(code)
  }
  await clickPart('ZC-000023', '整组配置', '14T23:59:59')
  expect(panel().get('.model-subsidy-source.inspected').text()).toMatch(/可可棕/)
  expect(panel().get('.model-subsidy-source.inspected').text()).toMatch(/初雪白/)
  const upcoming = panel().findAll('.model-subsidy-upcoming li')
  expect(upcoming.map((item) => [item.get('span').text(), item.get('strong').text()])).toEqual(
    expect.arrayContaining([
      ['可可棕', 'ZC-000024'],
      ['初雪白、奇遇喵', 'ZC-000023'],
      ['整组配置', 'ZC-000023'],
    ]),
  )
  await move('2026-11-15T00:00:00', chart)
  expect(chart.get('.timeline-policy-states').text()).toContain('该时点部分采用')
  const mixedEntry = charts[0]
    .getOption()
    .series[0].data.find(([index, , , part]) => rows[index].parts[part].mixed)
  const tooltip = charts[0].getOption().tooltip.formatter({ data: mixedEntry })
  expect(tooltip).toContain('采用商品 初雪白、奇遇喵')
  expect(tooltip).toContain('未采用商品 可可棕')
  await clickPart('ZC-000023', '整组配置', '15T00:00:00')
  await clickPart('ZC-000024', '独立商品 · 可可棕', '15T00:00:00')
  expect(panel().get('.model-subsidy-source.inspected').text()).toContain('可可棕')
  expect(panel().get('.model-subsidy-source.muted').text()).toContain('ZC-000023')
  expect(panel().get('.model-subsidy-source.inspected').text()).not.toContain('初雪白')
  await move('2026-11-18T00:00:00', chart)
  expect(panel().get('.model-subsidy-source:not(.muted)').text()).toContain('ZC-000023')
  expect(panel().get('.model-subsidy-source:not(.muted)').text()).toMatch(/可可棕.*初雪白.*奇遇喵/)
  await clickPart('ZC-000023', '整组配置', '18T00:00:00')
  expect(panel().text()).toContain('本段计划采用')
  expect(panel().find('.model-subsidy-source.muted').exists()).toBe(false)
  expect(fetch).toHaveBeenCalledTimes(2)
})

it('采用名单按轨道及审核轮次，展示对照单、缺口、晚审切换、恢复和截止后的未知状态', async () => {
  await openPage()
  const chart = timelines()[0]
  const [old, estimate] = chart.props('rows')
  expect(chart.props('rows')).toHaveLength(2)
  expect(old.plan.usePeriods.map(({ startAt, endAt }) => [startAt, endAt])).toEqual([
    ['2026-11-01T00:00:00', '2026-11-03T00:00:00'],
    ['2026-11-03T00:00:00', '2026-11-05T10:29:59'],
    ['2026-11-09T00:00:00', '2026-11-16T12:00:01'],
  ])
  expect(estimate.plan.estimated).toBe(true)
  chart.vm.$emit('select-policy', estimate, '2026-11-02T23:59:59')
  await nextTick()
  expect(panel().get('.model-subsidy-source.inspected.muted').text()).toContain('ZC-000002')
  expect(panel().text()).toContain('ZC-000003')
  await move('2026-11-05T10:29:59')
  expect(panel().find('.model-subsidy-source').exists()).toBe(false)
  await move('2026-11-05T10:30:00')
  expect(panel().get('.model-subsidy-source.inspected:not(.muted)').text()).toContain('国补、地补')
  await move('2026-11-08T23:59:59')
  expect(panel().get('.model-subsidy-source.muted').text()).toContain('ZC-000001')
  await move('2026-11-09T00:00:00')
  expect(adoptedPolicyCodes()).toEqual(['ZC-000001', 'ZC-000002'])
  await move(AS_OF)
  expect(panel().text()).toContain('截至查询时')
  const periods = panel().findAll('.model-subsidy-upcoming li strong')
  expect(periods.map((item) => item.text())).toEqual(['ZC-000001', 'ZC-000001、ZC-000002'])
  await move('2026-11-16T12:00:01')
  expect(panel().get('.model-subsidy-source:not(.muted)').text()).toContain('ZC-000001')
  expect(panel().get('.model-subsidy-source.muted').text()).toContain('ZC-000002')
  expect(panel().text()).toContain('本段计划未采用')
  expect(wrapper.get('.timeline-policy-states span').text()).toBe('计划采用')
  await move('2026-11-20T00:00:00')
  expect(adoptedPolicyCodes()).toEqual(['ZC-000001', 'ZC-000002'])
  expect(fetch).toHaveBeenCalledTimes(2)
  await button('当前时间').trigger('click')
  await flushPromises()
  await move('2026-11-16T12:00:01')
  expect(panel().text()).toContain('本段计划采用')
  expect(panel().text()).not.toContain('实际开始')
})

it('当前查询仅命中独立商品时，两渠道首次进入直接显示商品及数量', async () => {
  const sample = structuredClone(history)
  const original = sample.tracks[1].policies[1]
  const only = {
    ...sample.tracks[1],
    policies: [original],
    segments: [
      {
        startAt: original.policyFrom,
        endAt: null,
        adoptedAuditLogIds: [original.auditLogId],
        excludedAuditLogIds: [],
      },
    ],
    plannedSegments: [],
  }
  sample.tracks = [{ ...only, perspective: 'RETAIL', platformCode: 'JD', goodsIds: ['52'] }, only]
  fetch.mockResolvedValueOnce(Response.json(directory)).mockResolvedValueOnce(Response.json(sample))
  await openPage()
  expect(timelines()).toHaveLength(2)
  for (const chart of timelines()) {
    const [row] = chart.props('rows')
    expect(row.segment.label).toBe('独立商品 · #52')
    chart.vm.$emit('select-policy', row, AS_OF)
    await nextTick()
    expect(panel().find('.model-subsidy-detail-summary').exists()).toBe(false)
    expect(panel().text()).not.toMatch(/单独适用|本段(?:申报)?商品|审核范围|整组配置|Find X8/)
    expect(
      panel()
        .findAll('.model-subsidy-detail-fields dt')
        .map((field) => field.text()),
    ).toEqual(['本段实际开始', '本段实际结束'])
    expect(panel().get('.model-subsidy-source.inspected header strong').text()).toBe('ZC-000005')
    expect(panel().get('.model-subsidy-source.inspected').text()).toMatch(/原单指定商品\s*·\s*#52/)
    expect(panel().text()).toMatch(/该平台数量\s*未填写/)
  }
})

it('百亿同一轮的多个分配只计一单，独立商品直接显示并保留采用名单及数量', async () => {
  await openPage()
  const chart = timelines()[1]
  const first = chart.props('rows')[0]
  chart.vm.$emit('select-policy', first, AS_OF)
  await nextTick()
  expect(panel().get('.model-subsidy-source-heading').text()).toMatch(/采用\s*1 单/)
  expect(panel().findAll('.model-subsidy-source:not(.muted)')).toHaveLength(2)
  expect(panel().text()).toMatch(/该平台数量\s*0/)
  expect(panel().get('.model-subsidy-source.muted').text()).toMatch(/该平台数量\s*未填写/)
  expect(chart.props('rows')).toHaveLength(3)
  expect(new Set(chart.props('rows').map((r) => r.key)).size).toBe(3)
  expect(chart.props('rows')[2].parts.map((p) => p.status ?? p.used)).toEqual([
    true,
    false,
    '计划未采用',
  ])
  expect(wrapper.text()).toContain('独立商品 · #52')
  await move('2026-11-07T23:59:59', chart)
  expect(adoptedPolicyCodes()).toEqual(['ZC-000004', 'ZC-000004', 'ZC-000005'])
  expect(fetch).toHaveBeenCalledTimes(2)
})

it('百亿按原单展示整组和独立商品，配置级换单和叠加在相应时段显示', async () => {
  fetch
    .mockResolvedValueOnce(Response.json(directory))
    .mockResolvedValueOnce(Response.json(billionGroupHistory('SPECIFIED')))
  await openPage()
  const chart = timelines()[0]
  const rows = () => chart.props('rows')
  expect(chart.text()).toContain('拼多多 · 整组配置')
  expect(rows().some((row) => row.plan.cardType === 'SPECIFIED')).toBe(true)
  expect(rows()).toHaveLength(2)
  const group = rows().find(
    (row) => row.plan.policyCode === 'ZC-000023' && row.segment.label === '整组配置',
  )
  expect(group).toBeDefined()
  chart.vm.$emit('select-policy', group, AS_OF)
  await nextTick()
  expect(panel().get('.model-subsidy-upcoming p').text()).toMatch(/审核范围\s*·\s*整组配置/)
  expect(
    panel()
      .findAll('.model-subsidy-upcoming li strong')
      .map((item) => item.text()),
  ).toEqual(['ZC-000024', 'ZC-000023、ZC-000024', 'ZC-000023'])
  expect(panel().findAll('.model-subsidy-source')).toHaveLength(2)
  const single = panel().get('.model-subsidy-source:not(.muted)').text()
  expect(single).toContain('ZC-000024')
  expect(single).toContain('可可棕')
  expect(single).toMatch(/该平台数量\s*0/)
  const whole = panel().get('.model-subsidy-source.inspected.muted').text()
  expect(whole).toContain('ZC-000023')
  for (const color of ['可可棕', '初雪白', '奇遇喵']) {
    expect(whole).toContain(color)
  }
  await move('2026-11-17T00:00:00', chart)
  expect(panel().get('.model-subsidy-source-heading').text()).toMatch(/本段计划采用\s*2 单/)
  expect(adoptedPolicyCodes()).toEqual(['ZC-000023', 'ZC-000024'])
  const specified = rows().find((row) => row.plan.cardType === 'SPECIFIED')
  expect(specified.segment.label).toBe('独立商品 · 可可棕')
  chart.vm.$emit('select-policy', specified, '2026-11-17T23:59:59')
  await nextTick()
  expect(panel().get('.model-subsidy-source.inspected').text()).toContain('ZC-000024')
  await move('2026-11-18T00:00:00', chart)
  expect(panel().get('.model-subsidy-source').text()).toMatch(/ZC-000023.*可可棕.*初雪白.*奇遇喵/)
  expect(panel().findAll('.model-subsidy-source')).toHaveLength(1)
  expect(fetch).toHaveBeenCalledTimes(2)
})

it('百亿同单两张不同金额的补贴卡按颜色展示，配置审核保留两项', async () => {
  const sample = billionGroupHistory()
  const [track] = sample.tracks
  const allocations = track.policies[0].selections
  const [first] = allocations
  first.subsidy = { ...first.subsidy, fixedAmount: '50.00' }
  allocations.push({
    ...first,
    products: first.products.splice(1),
    subsidy: { ...first.subsidy, fixedAmount: '80.00' },
  })
  fetch.mockResolvedValueOnce(Response.json(directory)).mockResolvedValueOnce(Response.json(sample))
  await openPage()
  const chart = timelines()[0]
  const white = chart
    .props('rows')
    .find((r) => r.plan.policyCode === 'ZC-000023' && r.segment.label === '初雪白、奇遇喵')
  expect(white).toBeDefined()
  chart.vm.$emit('select-policy', white, '2026-11-14T23:59:59')
  await nextTick()
  expect(
    panel()
      .findAll('.model-subsidy-source:not(.muted)')
      .map((p) => p.get('header span').text())
      .sort(),
  ).toEqual(['固定 50 元', '固定 80 元'])
  await move('2026-11-15T00:00:00', chart)
  const old = panel().findAll('.model-subsidy-source.muted')
  expect(old.map((p) => p.get('header span').text()).sort()).toEqual(['固定 50 元', '固定 80 元'])
  expect(old.every((p) => p.get('strong').text() === 'ZC-000023')).toBe(true)
})

it('保留整段未采用政策及旧单超过原截止的已知采用段，查询一天仍显示完整原单', async () => {
  const sample = structuredClone(history)
  sample.tracks[1].plannedSegments = []
  sample.tracks[1].policies[0].policyThrough = '2026-11-06T23:59:59+08:00'
  sample.tracks[1].policies[0].effectiveThrough = sample.tracks[1].policies[0].policyThrough
  sample.tracks[1].segments[0].adoptedAuditLogIds = ['104']
  sample.tracks[1].segments[0].excludedAuditLogIds = ['105']
  const { rows } = readTimelineHistory(sample)
  const old = rows.find((r) => r.plan.auditLogId === '104')
  expect(old.endAt).toBe('2026-11-16T12:00:01')
  const unused = rows.find((r) => r.plan.auditLogId === '105')
  expect(unused.parts.map(({ startAt, endAt, used }) => [startAt, endAt, used])).toEqual([
    ['2026-11-01T00:00:00', '2026-11-08T00:00:00', false],
    ['2026-11-08T00:00:00', '2026-11-16T12:00:01', false],
  ])
  await openPage()
  wrapper
    .getComponent({ name: 'ElDatePicker' })
    .vm.$emit('update:modelValue', ['2026-11-08', '2026-11-08'])
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(timelines()[0].props('range')).toEqual(['2026-11-01T00:00:00', '2026-11-30T23:59:59'])
  expect(timelines()[0].props('cursorAt')).toBe('2026-11-08T23:59:59')
})
it('十二月的已通过百亿政策定位到计划首日，展示原单金额但不生成实际采用记录', async () => {
  await openPage()
  const plan = structuredClone(history.tracks[1].policies[0])
  Object.assign(plan, {
    policyCode: 'ZC-000008',
    policyFrom: '2026-12-02T00:00:00+08:00',
    policyThrough: '2026-12-05T23:59:59+08:00',
    effectiveThrough: '2026-12-05T23:59:59+08:00',
  })
  plan.selections = [plan.selections[0]]
  Object.assign(plan.selections[0].subsidy, { fixedAmount: '100.00', maxSubsidyAmount: '200.00' })
  fetch.mockResolvedValueOnce(
    Response.json({
      asOf: history.asOf,
      configurationGoodsCount: 1,
      tracks: [
        {
          ...history.tracks[1],
          policies: [plan],
          segments: [],
          plannedSegments: [
            {
              startAt: plan.policyFrom,
              endAt: '2026-12-06T00:00:00+08:00',
              adoptedAuditLogIds: [plan.auditLogId],
              excludedAuditLogIds: [],
            },
          ],
        },
      ],
    }),
  )
  select('平台').vm.$emit('update:modelValue', 'PDD')
  wrapper
    .getComponent({ name: 'ElDatePicker' })
    .vm.$emit('update:modelValue', ['2026-12-01', '2026-12-24'])
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  const [chart] = timelines()
  expect(chart.props('cursorAt')).toBe('2026-12-02T00:00:00')
  const [row] = chart.props('rows')
  expect(row.parts).toMatchObject([
    {
      startAt: '2026-12-02T00:00:00',
      endAt: '2026-12-06T00:00:00',
      status: '计划采用',
      used: true,
      open: false,
      mixed: false,
      scopes: [{ used: true, scope: { planned: true, label: '整组配置' } }],
    },
  ])
  expect(row.plan.usePeriods).toEqual([])
  const header = panel().get('.model-subsidy-source header')
  expect(header.findAll('strong,small,span').map((field) => field.text())).toEqual([
    'ZC-000008',
    '待生效',
    '固定 100 元',
    '补贴上限：200 元',
  ])
  expect(panel().text()).not.toContain('该时点采用')
  expect(panel().text()).not.toContain('未采用')
  await move('2026-12-05T23:59:59', chart)
  expect(panel().text()).toContain('本段计划采用')
  await move('2026-12-06T00:00:00', chart)
  expect(panel().find('.model-subsidy-source').exists()).toBe(false)
})

async function queryDecember(sample) {
  await openPage()
  fetch.mockResolvedValueOnce(Response.json(sample))
  wrapper
    .getComponent({ name: 'ElDatePicker' })
    .vm.$emit('update:modelValue', ['2026-12-01', '2026-12-31'])
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  return timelines()[0]
}

it('切回窗口按已应用条件读取连续改期，保留核对时点、缩放、选中政策和正在编辑的筛选', async () => {
  const sample = midnightPolicyHistory('RETAIL')
  const [track] = sample.tracks
  const [policy] = track.policies
  policy.selections[0].subsidy = { subsidyMode: 'RATIO', ratio: '12.00', priceBase: 'HAND_PRICE' }
  const chart = await queryDecember(sample)
  const at = '2026-12-07T10:00:00'
  chart.vm.$emit('select-policy', chart.props('rows')[0], at)
  chart.vm.$emit('zoom-change', { start: 5, end: 50 })
  await nextTick()
  const applied = Object.fromEntries(lastParams())
  const picker = wrapper.getComponent({ name: 'ElDatePicker' })
  const draftDates = ['2026-12-20', '2026-12-26']
  picker.vm.$emit('update:modelValue', draftDates)
  select('平台').vm.$emit('update:modelValue', 'JD')
  await nextTick()
  for (const [through, exclusive, event] of [
    ['2026-12-18T00:00:00', '2026-12-18T00:00:01', 'focus'],
    ['2026-12-07T12:00:00', '2026-12-07T12:00:01', 'visibilitychange'],
    ['2026-12-12T23:59:59', '2026-12-13T00:00:00', 'storage'],
  ]) {
    policy.effectiveThrough = through + '+08:00'
    track.plannedSegments[0].endAt = exclusive + '+08:00'
    fetch.mockResolvedValueOnce(Response.json(sample))
    const before = fetch.mock.calls.length
    if (event === 'storage') {
      window.dispatchEvent(
        new StorageEvent('storage', { key: 'policy-subsidy-updated', newValue: '1' }),
      )
      window.dispatchEvent(
        new StorageEvent('storage', { key: 'policy-subsidy-updated', newValue: null }),
      )
    } else {
      const target = event === 'focus' ? window : document
      target.dispatchEvent(new Event(event))
    }
    await flushPromises()
    expect(fetch).toHaveBeenCalledTimes(before + 1)
    expect(Object.fromEntries(lastParams())).toEqual(applied)
    expect(chart.props('zoom')).toEqual({ start: 5, end: 50 })
    expect(chart.props('cursorAt')).toBe(at)
    expect(chart.get('.timeline-policy-row').text()).toContain('止 ' + through.replace('T', ' '))
    const selected = panel().get('.model-subsidy-source.inspected header')
    expect(selected.findAll('strong,small,span').map((field) => field.text())).toEqual([
      policy.policyCode,
      '待生效',
      '比例 12%',
      '计算基数：到手价',
    ])
    expect(panel().get('.model-subsidy-detail-fields').text()).toContain(
      '本段计划结束' + through.replace('T', ' '),
    )
    expect(picker.props('modelValue')).toEqual(draftDates)
    expect(select('平台').props('modelValue')).toBe('JD')
  }
})

it.each(['RETAIL', 'BILLION'])(
  '%s 时间轴与悬停显示最新有效期，明细仅展示阶段起止，截止秒仍可核对',
  async (channel) => {
    const chart = await queryDecember(midnightPolicyHistory(channel))
    const from = '2026-12-07 00:00:00'
    const through = '2026-12-08 00:00:00'
    expect(
      chart
        .get('.timeline-policy-row')
        .findAll('time')
        .map((time) => time.text()),
    ).toEqual(['起 ' + from, '止 ' + through])
    const option = charts.at(-1).getOption()
    const tooltip = option.tooltip.formatter({ data: option.series[0].data[0] })
    for (const text of [
      '政策开始 ' + from,
      '当前截止 ' + through,
      '本段计划开始 ' + from,
      '本段计划结束 ' + through,
    ]) {
      expect(tooltip).toContain(text)
    }
    await move('2026-12-06T23:59:59', chart)
    expect(panel().find('.model-subsidy-source').exists()).toBe(false)
    for (const at of ['2026-12-07T00:00:00', '2026-12-07T23:59:59', '2026-12-08T00:00:00']) {
      await move(at, chart)
      const source = panel().get('.model-subsidy-source')
      expect(source.text()).toContain('ZC-000013')
      expect(source.text()).not.toMatch(/原单申报时间|审核通过|获批调整后截止|2026-\d{2}-\d{2}/)
      expect(panel().text()).toContain('本段计划开始' + from)
      expect(panel().text()).toContain('本段计划结束' + through)
    }
    await move('2026-12-08T00:00:01', chart)
    expect(panel().find('.model-subsidy-source').exists()).toBe(false)
  },
)

it.each([
  ['2026-12-07T19:39:07+08:00', '生效中'],
  ['2026-12-07T19:39:08+08:00', '已结束'],
])('历史阶段的政策按查询当秒 %s 和获批调整后的截止展示状态 %s', async (asOf, status) => {
  const sample = midnightPolicyHistory('RETAIL')
  sample.asOf = asOf
  const [track] = sample.tracks
  const [policy] = track.policies
  policy.effectiveThrough = '2026-12-07T19:39:07+08:00'
  track.segments = [{ ...track.plannedSegments[0], endAt: '2026-12-07T19:39:08+08:00' }]
  track.plannedSegments = []
  const chart = await queryDecember(sample)
  await move('2026-12-07T12:00:00', chart)
  expect(panel().get('.model-subsidy-source header small').text()).toBe(status)
  expect(panel().get('.model-subsidy-detail-fields').text()).toContain(
    '本段实际结束2026-12-07 19:39:07',
  )
})

it.each(['RETAIL', 'BILLION'])(
  '%s 原单获批缩短后显示最新截止，实际段与计划段分别核对',
  async (channel) => {
    const sample = midnightPolicyHistory(channel)
    sample.asOf = '2026-12-07T08:00:00+08:00'
    const [track] = sample.tracks
    const [original] = track.policies
    original.policyFrom = '2026-12-07T07:59:59+08:00'
    original.policyThrough = '2026-12-07T12:30:45+08:00'
    original.effectiveThrough = '2026-12-07T08:00:01+08:00'
    const [plan] = track.plannedSegments
    track.segments = [{ ...plan, startAt: original.policyFrom, endAt: null }]
    plan.startAt = original.effectiveThrough
    plan.endAt = '2026-12-07T08:00:02+08:00'
    const chart = await queryDecember(sample)
    expect(chart.props('rows')[0].endAt).toBe('2026-12-07T08:00:02')
    expect(chart.get('.timeline-policy-row').text()).toContain('止 2026-12-07 08:00:01')
    expect(chart.get('.timeline-policy-row').text()).not.toContain('12:30:45')
    const option = charts.at(-1).getOption()
    const tips = option.series[0].data.map((data) => option.tooltip.formatter({ data }))
    expect(tips[0]).toContain('本段实际结束 2026-12-07 08:00:00（截至查询时）')
    expect(tips[1]).toContain('本段计划结束 2026-12-07 08:00:01')
    expect(tips.every((text) => text.includes('当前截止 2026-12-07 08:00:01'))).toBe(true)
    await move('2026-12-07T08:00:00', chart)
    expect(panel().text()).toContain('本段实际结束2026-12-07 08:00:00（截至查询时）')
    await move('2026-12-07T08:00:01', chart)
    expect(panel().text()).toContain('本段计划开始2026-12-07 08:00:01')
    expect(panel().get('.model-subsidy-source').text()).not.toMatch(
      /原单申报时间|审核通过|获批调整后截止|12:30:45/,
    )
    await move('2026-12-07T08:00:02', chart)
    expect(panel().find('.model-subsidy-source').exists()).toBe(false)
  },
)

it('目录为空时只读目录，目录错误可重试，无示例回退', async () => {
  fetch.mockResolvedValueOnce(Response.json({ message: '目录服务不可用' }, { status: 503 }))
  await openPage()
  expect(wrapper.get('[role="alert"]').text()).toContain('目录服务不可用')
  fetch.mockResolvedValueOnce(Response.json({ series: [], models: [], modelConfigurations: [] }))
  await button('重试').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('商品目录暂无可查询的机型配置')
  expect(fetch).toHaveBeenCalledTimes(2)
  expect(timelines()).toHaveLength(0)
})
it('查询错误清除旧明细，重试沿用原请求并显示后端结果', async () => {
  await openPage()
  select('平台').vm.$emit('update:modelValue', 'PDD')
  fetch.mockResolvedValueOnce(Response.json({ message: '请配置实际规则上线时间' }, { status: 409 }))
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('请配置实际规则上线时间')
  expect(wrapper.find('.model-subsidy-detail-card').exists()).toBe(false)
  select('平台').vm.$emit('update:modelValue', 'JD')
  await button('重试').trigger('click')
  await flushPromises()
  expect(lastParams().get('platformCode')).toBe('PDD')
  expect(panel().text()).toContain('百亿渠道')
})
it('同页连续查询取消旧请求，即使旧请求最后返回也不会覆盖新结果', async () => {
  await openPage()
  let finish
  fetch.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  await wrapper.get('form').trigger('submit')
  const { signal } = fetch.mock.calls.at(-1)[1]
  expect(wrapper.get('[role="status"]').text()).toContain('正在查询')
  select('平台').vm.$emit('update:modelValue', 'DEWU')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(signal.aborted).toBe(true)
  finish(Response.json(history))
  await flushPromises()
  expect(wrapper.text()).toContain('暂无已通过政策')
  expect(timelines()).toHaveLength(0)
})
