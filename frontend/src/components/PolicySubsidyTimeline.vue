<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { graphic, init, use } from 'echarts/core'
import { CustomChart } from 'echarts/charts'
import { DataZoomComponent, GridComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import AppIcon from './AppIcon.vue'
import { retailPolicySeries } from '../utils/retail-policy-timeline'
import { PLATFORM_LABELS, PRICE_BASE_LABELS } from '../constants/policy'
import {
  timelineContainsAt,
  timelineDecimal,
  timelinePlanCaption,
  timelinePlanDetail,
  timelineAxisTime,
  timelineLastSecond,
  timelineEndText,
  timelineTimeText,
  timelineAxisValue,
} from '../utils/subsidy-timeline'

// 仅注册时间轴实际使用的模块；组件随管理员查询路由加载。
use([CustomChart, GridComponent, TooltipComponent, DataZoomComponent, SVGRenderer])
const props = defineProps({
  segments: { type: Array, required: true },
  retailRows: { type: Array, default: null },
  plotLeft: { type: Number, default: 108 },
  range: { type: Array, required: true },
  selectedId: { type: String, default: '' },
  policyKey: { type: String, default: '' },
  label: { type: String, required: true },
  currentAt: { type: String, required: true },
  cursorAt: { type: String, default: '' },
  zoom: { type: Object, default: () => ({ start: 0, end: 100 }) },
})
const emit = defineEmits(['select', 'select-policy', 'cursor-change', 'zoom-change'])
const container = ref(null)
const chartWidth = ref(660)
const leftMargin = computed(() => Math.min(props.plotLeft, chartWidth.value < 520 ? 148 : 204))
const rowKey = (segment) => segment.trackId
const rows = computed(() => {
  if (props.retailRows) {
    return props.retailRows
  }
  const result = []
  for (const segment of props.segments) {
    const key = rowKey(segment)
    if (!result.some((row) => row.key === key)) {
      result.push({
        key,
        platformCode: segment.platformCode,
        label: segment.label,
        independent: segment.scope === 'SPECIFIED',
      })
    }
  }
  return result.sort((a, b) => {
    const platforms = [...new Set(props.segments.map((segment) => segment.platformCode))]
    return (
      platforms.indexOf(a.platformCode) - platforms.indexOf(b.platformCode) ||
      Number(a.independent) - Number(b.independent)
    )
  })
})
const height = computed(() => {
  if (props.retailRows) {
    return rows.value.length * 88 + 100
  }
  return rows.value.length * 56 + 90
})
function pixelAt(value) {
  const start = timelineAxisValue(props.range[0])
  const span = Math.max(1000, timelineAxisValue(props.range[1]) - start)
  const from = start + (span * props.zoom.start) / 100
  const to = start + (span * props.zoom.end) / 100
  const at = timelineAxisValue(value)
  return leftMargin.value + ((at - from) / (to - from)) * (chartWidth.value - leftMargin.value - 15)
}
const cursorX = computed(() => {
  if (!props.cursorAt) {
    return null
  }
  const x = pixelAt(props.cursorAt)
  return x < leftMargin.value - 0.01 || x > chartWidth.value - 14.99 ? null : x
})
// 指针跨段时仅更新选中轮廓，保留 ECharts 的缩放和悬停状态。
const selectionBands = computed(() => {
  if (props.retailRows) {
    const index = rows.value.findIndex((row) => row.key === props.policyKey)
    const row = rows.value[index]
    const part = row?.parts.find((item) => timelineContainsAt(item, props.cursorAt))
    if (!part) {
      return []
    }
    const x = Math.max(leftMargin.value, pixelAt(part.startAt))
    const end = Math.min(chartWidth.value - 15, pixelAt(part.endAt))
    return end > x
      ? [
          {
            x,
            y: 52 + (index + 0.5) * 88 - 16,
            width: end - x,
            height: 32,
            color: row.plan.subsidyMode === 'FIXED' ? '#275da7' : '#925416',
          },
        ]
      : []
  }
  const segment = props.segments.find((item) => item.id === props.selectedId)
  if (!segment) {
    return []
  }
  const left = Math.max(leftMargin.value, pixelAt(segment.startAt) + 2)
  const right = Math.min(chartWidth.value - 15, pixelAt(segment.endAt ?? props.range[1]) - 2)
  if (right <= left) {
    return []
  }
  const row = rows.value.findIndex((item) => item.key === segment.trackId)
  const center = 35 + ((row + 0.5) * (height.value - 83)) / rows.value.length
  const bandHeight = 42 / segment.policies.length
  return segment.policies.map((plan, index) => ({
    x: left,
    y: center - 21 + index * bandHeight,
    width: right - left,
    height: bandHeight - (segment.policies.length > 1 ? 1 : 0),
    color: plan.subsidyMode === 'FIXED' ? '#347cff' : '#ed963c',
    dashed: plan.estimated === true,
  }))
})
const pointerLabelX = computed(() =>
  Math.max(
    leftMargin.value,
    Math.min((cursorX.value ?? leftMargin.value) - 46, chartWidth.value - 111),
  ),
)
let draggingPointer = false
let chart
let observer
const escape = (value) =>
  String(value).replace(
    /[&<>"']/g,
    (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char],
  )
const shortDate = (value) => value.slice(5, 10).replace('-', '.')
function selectPlatformRow(row) {
  const intervals = props.segments.filter((segment) => segment.trackId === row.key)
  const current = intervals.find((segment) => timelineContainsAt(segment, props.cursorAt))
  if (current || intervals.length) {
    emit('select', current ?? intervals[0])
  }
}

function renderChart() {
  if (!chart) {
    return
  }
  const segments = props.segments
  const entries = segments.flatMap((segment) =>
    segment.policies.map((plan, band) => ({ segment, plan, band })),
  )
  const range = props.range
  // 尺寸变化会先重绘旧图，标签与区间必须引用同一轮行数据。
  const chartRows = rows.value
  // 更新选中项或商品范围前结束旧悬停，避免高亮样式混入下一轮的普通样式。
  if (chart.getOption()?.series?.length) {
    chart.dispatchAction({ type: 'downplay', seriesIndex: 0 }, { silent: true })
  }
  chart.resize({ height: height.value })
  chart.setOption(
    {
      animation: false,
      useUTC: true,
      textStyle: { fontFamily: 'Microsoft YaHei, sans-serif' },
      grid: { left: leftMargin.value, right: 15, top: props.retailRows ? 52 : 35, bottom: 48 },
      tooltip: {
        confine: true,
        trigger: 'item',
        backgroundColor: '#fff',
        borderColor: '#e0e8f5',
        textStyle: { color: '#253550', fontSize: 12 },
        formatter: ({ data }) => {
          if (data.row) {
            const { row, part } = data
            return [
              '<strong>' +
                escape(row.plan.policyCode) +
                (row.plan.estimated ? ' · 预估' : '') +
                '</strong>',
              escape(row.caption) + ' · ' + (part.used ? '采用' : '未采用'),
              '原政策 ' + escape(row.plan.policyFrom) + ' 至 ' + escape(row.plan.policyThrough),
              '本段开始 ' + escape(timelineTimeText(part.startAt)),
              '本段结束 ' + escape(timelineEndText(part.endAt)),
            ].join('<br>')
          }
          const { segment, plan } = data
          return [
            '<strong>' +
              escape(PLATFORM_LABELS[segment.platformCode]) +
              ' · ' +
              escape(segment.decision) +
              '</strong>',
            escape(segment.label) +
              ' · ' +
              escape(plan.policyCode) +
              (plan.estimated === true ? ' · 预估' : ''),
            escape(timelinePlanCaption(plan)) + ' · ' + escape(timelinePlanDetail(plan)),
            '实际开始 ' + escape(timelineTimeText(plan.usePeriod.startAt)),
            '实际结束 ' + escape(timelineEndText(plan.usePeriod.endAt)),
          ].join('<br>')
        },
      },
      xAxis: {
        type: 'time',
        position: 'top',
        min: timelineAxisValue(range[0]),
        max: Math.max(timelineAxisValue(range[1]), timelineAxisValue(range[0]) + 1000),
        splitNumber: 4,
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { show: true, lineStyle: { color: '#e8edf5', type: 'dashed' } },
        axisLabel: {
          color: '#71819a',
          fontSize: 11,
          hideOverlap: true,
          formatter: (value) => {
            const time = timelineAxisTime(value)
            return range[0].slice(0, 10) === range[1].slice(0, 10)
              ? time.slice(11, 16)
              : time.slice(5, 10).replace('-', '/')
          },
        },
      },
      yAxis: {
        type: 'category',
        inverse: true,
        data: chartRows.map((row) => row.key),
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { show: true, lineStyle: { color: '#eef2f8' } },
        splitArea: { show: true, areaStyle: { color: ['#fafcff', '#fff'] } },
        axisLabel: {
          show: false,
        },
      },
      dataZoom: [
        {
          type: 'slider',
          start: props.zoom.start,
          end: props.zoom.end,
          minSpan: 5,
          xAxisIndex: 0,
          filterMode: 'weakFilter',
          height: 16,
          bottom: 8,
          borderColor: '#e5ebf5',
          showDetail: false,
          brushSelect: false,
          handleSize: '100%',
          dataBackground: { lineStyle: { opacity: 0 }, areaStyle: { opacity: 0 } },
        },
      ],
      series: props.retailRows
        ? [retailPolicySeries(chartRows, graphic.clipRectByRect, props.currentAt)]
        : [
            {
              type: 'custom',
              clip: true,
              encode: { x: [1, 2], y: 0 },
              data: entries.map(({ segment, plan }) => ({
                value: [
                  chartRows.findIndex((row) => row.key === rowKey(segment)),
                  timelineAxisValue(segment.startAt),
                  timelineAxisValue(segment.endAt ?? range[1]),
                ],
                segment,
                plan,
              })),
              renderItem(params, api) {
                const { segment, plan: item, band } = entries[params.dataIndex]
                const stacked = segment.policies.length > 1
                const bandHeight = 42 / segment.policies.length
                const start = api.coord([api.value(1), api.value(0)])
                const end = api.coord([api.value(2), api.value(0)])
                const shape = graphic.clipRectByRect(
                  {
                    x: start[0] + 2,
                    y: start[1] - 21 + band * bandHeight,
                    width: Math.max(end[0] - start[0] - 4, 0),
                    height: bandHeight - (stacked ? 1 : 0),
                  },
                  {
                    x: params.coordSys.x,
                    y: params.coordSys.y,
                    width: params.coordSys.width,
                    height: params.coordSys.height,
                  },
                )
                if (!shape || shape.width < 1) {
                  return
                }
                const fixed = item.subsidyMode === 'FIXED'
                const color = fixed ? '#347cff' : '#ed963c'
                const fill = fixed ? '#e9f1ff' : '#fff0e0'
                const currentX = api.coord([timelineAxisValue(props.currentAt), api.value(0)])[0]
                const current =
                  timelineContainsAt(segment, props.currentAt) &&
                  currentX >= params.coordSys.x - 1 &&
                  currentX <= params.coordSys.x + params.coordSys.width + 1
                const currentBadge = current && (!stacked || band === 0)
                const fullBadge = shape.width > (stacked ? 160 : 64)
                const textWidth = Math.max(
                  0,
                  shape.width - 8 - (currentBadge && fullBadge && stacked ? 54 : 0),
                )
                const narrowRatio = !stacked && !fixed && textWidth < 88 && !current
                const caption =
                  textWidth > 34
                    ? narrowRatio
                      ? `${timelineDecimal(item.ratio)}%\n${PRICE_BASE_LABELS[item.priceBase] ?? '基数未填写'}`
                      : timelinePlanCaption(item)
                    : ''
                const shortCode = '#' + item.policyCode.replace(/^ZC-0*/, '')
                const text =
                  textWidth >= 142
                    ? item.policyCode + ' · ' + caption
                    : stacked && textWidth >= 56
                      ? shortCode + ' · ' + caption
                      : caption
                const period = segment.endAt
                  ? `${shortDate(segment.startAt)}—${shortDate(timelineLastSecond(segment.endAt))}`
                  : `${shortDate(segment.startAt)}起`
                return {
                  type: 'group',
                  children: [
                    {
                      type: 'rect',
                      shape: { ...shape, r: 5 },
                      style: {
                        fill,
                        stroke: fixed ? '#c8dcff' : '#ffdbb3',
                        lineWidth: 1,
                      },
                      // 悬停只强调边框，显式保留蓝色或橙色底色，避免默认高亮改变填充。
                      emphasis: { style: { fill, stroke: color, lineWidth: 2 } },
                    },
                    {
                      type: 'text',
                      silent: true,
                      style: {
                        x: shape.x + 4 + textWidth / 2,
                        y: shape.y + (stacked ? 10 : narrowRatio ? 21 : 13),
                        text,
                        fill: fixed ? '#2e62aa' : '#99611e',
                        font: '11px Microsoft YaHei',
                        lineHeight: 16,
                        align: 'center',
                        verticalAlign: 'middle',
                        width: textWidth,
                        overflow: 'truncate',
                      },
                    },
                    {
                      type: 'text',
                      silent: true,
                      style: {
                        x: shape.x + (current ? 8 : shape.width / 2),
                        y: shape.y + 31,
                        text:
                          !stacked && !narrowRatio && shape.width > (current ? 128 : 66)
                            ? shape.width < 150
                              ? item.policyCode
                              : period
                            : '',
                        fill: '#7c8ba3',
                        font: '10px Microsoft YaHei',
                        align: current ? 'left' : 'center',
                        verticalAlign: 'middle',
                      },
                    },
                    ...(currentBadge && fullBadge
                      ? [
                          {
                            type: 'rect',
                            silent: true,
                            shape: {
                              x: shape.x + shape.width - 54,
                              y: shape.y + (stacked ? 3 : 24),
                              width: 48,
                              height: 14,
                              r: 3,
                            },
                            style: { fill: '#dff2e8' },
                          },
                          {
                            type: 'text',
                            silent: true,
                            style: {
                              x: shape.x + shape.width - 30,
                              y: shape.y + (stacked ? 10 : 31),
                              text: '当前适用',
                              fill: '#257251',
                              font: '9px Microsoft YaHei',
                              align: 'center',
                              verticalAlign: 'middle',
                            },
                          },
                        ]
                      : []),
                    ...(currentBadge && !fullBadge && shape.width >= 12
                      ? [
                          {
                            type: 'circle',
                            silent: true,
                            shape: { cx: shape.x + shape.width - 5, cy: shape.y + 5, r: 2.5 },
                            style: { fill: '#29825d' },
                          },
                        ]
                      : []),
                  ],
                }
              },
            },
          ],
    },
    { notMerge: true },
  )
}

function syncZoom() {
  if (!chart) {
    return
  }
  const current = chart.getOption()?.dataZoom?.[0]
  if (
    current &&
    Math.abs(current.start - props.zoom.start) < 0.00001 &&
    Math.abs(current.end - props.zoom.end) < 0.00001
  ) {
    return
  }
  chart.dispatchAction(
    { type: 'dataZoom', dataZoomIndex: 0, start: props.zoom.start, end: props.zoom.end },
    { silent: true },
  )
}

function movePointer(event) {
  if (!chart) {
    return
  }
  const bounds = container.value.getBoundingClientRect()
  const x = event.clientX - bounds.left
  const y = event.clientY - bounds.top
  if (
    !draggingPointer &&
    (x < leftMargin.value ||
      x > chartWidth.value - 15 ||
      y < (props.retailRows ? 52 : 35) ||
      y > height.value - 48)
  ) {
    return
  }
  const point = chart.convertFromPixel({ gridIndex: 0 }, [
    Math.max(leftMargin.value, Math.min(x, chartWidth.value - 15)),
    Math.max(35, Math.min(y, height.value - 48)),
  ])
  if (Number.isFinite(point?.[0])) {
    emit('cursor-change', timelineAxisTime(point[0]))
  }
}
function startPointerDrag(event) {
  draggingPointer = true
  event.currentTarget.setPointerCapture(event.pointerId)
}
function stopPointerDrag() {
  draggingPointer = false
}

onMounted(() => {
  chart = init(container.value, null, { renderer: 'svg' })
  chart.on('click', (event) => {
    if (event.data?.row) {
      const { row, part } = event.data
      const pixel = event.event?.offsetX
      const value = Number.isFinite(pixel)
        ? chart.convertFromPixel({ gridIndex: 0 }, [pixel, 60])?.[0]
        : null
      const at = Number.isFinite(value) ? timelineAxisTime(value) : props.cursorAt
      emit('select-policy', row, timelineContainsAt(part, at) ? at : part.startAt)
    } else if (event.data?.segment) {
      emit('select', event.data.segment)
    }
  })
  chart.on('datazoom', (event) => {
    const value = event.batch?.[0] ?? event
    if (Number.isFinite(value.start) && Number.isFinite(value.end)) {
      emit('zoom-change', { start: value.start, end: value.end })
    }
  })
  observer = new ResizeObserver(() => {
    chartWidth.value = container.value?.clientWidth || 660
    chart?.resize()
  })
  observer.observe(container.value)
  chartWidth.value = container.value.clientWidth || 660
  renderChart()
})
watch(
  () => [props.segments, props.retailRows, leftMargin.value, props.range, props.currentAt],
  renderChart,
  {
    deep: true,
    flush: 'post',
  },
)
watch(() => props.zoom, syncZoom, { deep: true, flush: 'post' })
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="subsidy-timeline-scroll">
    <div
      class="subsidy-timeline-layout"
      :class="{ 'subsidy-policy-layout': plotLeft > 108 }"
      :style="{ height: `${height}px` }"
      @pointerdown="movePointer"
      @pointermove="movePointer"
      @pointerup="stopPointerDrag"
      @pointercancel="stopPointerDrag"
    >
      <template v-if="retailRows">
        <span class="retail-policy-column-title">政策及原始有效期</span>
        <div class="retail-policy-labels" :style="{ width: leftMargin - 8 + 'px' }">
          <div v-for="row in rows" :key="row.key" class="retail-policy-row">
            <div class="retail-policy-id">
              <button
                type="button"
                :aria-label="'查看 ' + row.plan.policyCode + ' ' + row.segment.label + '的政策明细'"
                @click="
                  emit(
                    'select-policy',
                    row,
                    timelineContainsAt(row, cursorAt) ? cursorAt : row.startAt,
                  )
                "
              >
                {{ row.plan.policyCode }}
              </button>
              <span v-if="row.plan.estimated" class="model-subsidy-estimate">预估政策</span>
            </div>
            <div class="retail-policy-amount" :class="row.plan.subsidyMode.toLowerCase()">
              {{ row.caption }}
            </div>
            <small>{{ PLATFORM_LABELS[row.segment.platformCode] }} · {{ row.segment.label }}</small>
            <time>
              {{ timelineTimeText(row.plan.policyFrom) }} 至
              {{ timelineTimeText(row.plan.policyThrough) }}
            </time>
          </div>
        </div>
      </template>
      <div v-else class="subsidy-timeline-platforms">
        <button
          type="button"
          v-for="row in rows"
          :key="row.key"
          class="subsidy-timeline-platform"
          :aria-label="'查看' + PLATFORM_LABELS[row.platformCode] + row.label + '的补贴明细'"
          @click="selectPlatformRow(row)"
        >
          <AppIcon :name="`platform-${row.platformCode}`" :size="19" />
          <span>
            {{ PLATFORM_LABELS[row.platformCode] }}
            <small>{{ row.label }}</small>
          </span>
        </button>
      </div>
      <div
        ref="container"
        class="subsidy-timeline-chart"
        :style="{ height: `${height}px` }"
        role="img"
        :aria-label="label"
      />
      <div v-if="retailRows" class="retail-policy-states" :style="{ left: leftMargin + 'px' }">
        <div v-for="row in rows" :key="row.key">
          <span
            v-if="timelineContainsAt(row, cursorAt)"
            :class="{
              used: row.plan.usePeriods.some((period) => timelineContainsAt(period, cursorAt)),
            }"
          >
            {{
              row.plan.usePeriods.some((period) => timelineContainsAt(period, cursorAt))
                ? '该时点采用'
                : '该时点未采用'
            }}
          </span>
        </div>
      </div>
      <svg
        v-if="cursorX !== null"
        class="subsidy-timeline-pointer"
        :width="chartWidth"
        :height="height - 48"
        aria-hidden="true"
      >
        <rect
          v-for="(band, index) in selectionBands"
          :key="index"
          class="subsidy-timeline-selection"
          :x="band.x"
          :y="band.y"
          :width="band.width"
          :height="band.height"
          rx="5"
          fill="none"
          :stroke="band.color"
          :stroke-dasharray="band.dashed ? '4 2' : undefined"
          stroke-width="2"
        />
        <line
          :x1="cursorX"
          :x2="cursorX"
          y1="24"
          :y2="height - 48"
          stroke="#657cbd"
          stroke-width="1"
          stroke-dasharray="4 3"
        />
        <g class="subsidy-timeline-pointer-grip" @pointerdown.stop.prevent="startPointerDrag">
          <rect :x="pointerLabelX" y="2" width="96" height="20" rx="4" fill="#526fae" />
          <text :x="pointerLabelX + 48" y="16" text-anchor="middle" fill="#fff" font-size="10">
            {{ cursorAt.slice(5).replace('T', ' ') }}
          </text>
          <path :d="`M${cursorX - 4} 22 L${cursorX + 4} 22 L${cursorX} 27Z`" fill="#526fae" />
        </g>
      </svg>
    </div>
  </div>
</template>

<style scoped>
.retail-policy-column-title {
  position: absolute;
  top: 27px;
  left: 14px;
  font-size: 11px;
  color: #566a83;
}
.retail-policy-labels,
.retail-policy-states {
  position: absolute;
  z-index: 1;
  top: 52px;
  bottom: 48px;
  display: grid;
  grid-auto-rows: minmax(0, 1fr);
  pointer-events: none;
}
.retail-policy-labels {
  left: 8px;
  border-right: 1px solid #e0e8f3;
}
.retail-policy-row {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 2px;
  padding: 2px 6px;
  border-top: 1px solid #e8eef6;
}
.retail-policy-id {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #2a405e;
}
.retail-policy-amount {
  font-size: 12px;
  font-weight: 600;
  color: #275da7;
}
.retail-policy-amount.ratio {
  color: #925416;
}
.retail-policy-id button {
  border: 0;
  background: none;
  padding: 0;
  color: inherit;
  font: inherit;
  font-weight: 650;
  cursor: pointer;
  pointer-events: auto;
}
.retail-policy-id button:hover {
  text-decoration: underline;
}
.retail-policy-row small,
.retail-policy-row time {
  color: #576b85;
  font-size: 10px;
  line-height: 1.3;
  font-variant-numeric: tabular-nums;
}
.retail-policy-states > div {
  display: flex;
  align-items: flex-end;
  padding: 0 6px 7px;
}
.retail-policy-states span {
  font-size: 10px;
  color: #65768c;
}
.retail-policy-states .used {
  color: #276a50;
}
.subsidy-timeline-scroll {
  min-width: 0;
  overflow-x: auto;
}
.subsidy-timeline-layout {
  position: relative;
  min-width: 660px;
  width: 100%;
}
.subsidy-timeline-chart {
  width: 100%;
}
.subsidy-policy-layout {
  min-width: 0;
}
.subsidy-timeline-pointer {
  position: absolute;
  inset: 0 auto auto 0;
  z-index: 2;
  overflow: visible;
  pointer-events: none;
}
.subsidy-timeline-pointer-grip {
  pointer-events: auto;
  cursor: ew-resize;
  touch-action: none;
}
.subsidy-timeline-platforms {
  position: absolute;
  z-index: 1;
  inset: 35px auto 48px 10px;
  width: 93px;
  display: grid;
  grid-auto-rows: minmax(0, 1fr);
  pointer-events: none;
}
.subsidy-timeline-platform {
  border: 0;
  background: none;
  padding: 0;
  text-align: left;
  font: inherit;
  cursor: pointer;
  pointer-events: auto;
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: #344761;
  font-size: 12px;
  line-height: 18px;
}
.subsidy-timeline-platform svg {
  flex: none;
}
.subsidy-timeline-platform small {
  display: block;
  font-size: 10px;
  color: #8a98ae;
}
</style>
