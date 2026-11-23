<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { graphic, init, use } from 'echarts/core'
import { CustomChart } from 'echarts/charts'
import { DataZoomComponent, GridComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import { policyTimelineSeries } from '../utils/policy-timeline'
import { PLATFORM_LABELS } from '../constants/policy'
import {
  timelineContainsAt,
  timelineAxisTime,
  timelineEndText,
  timelineTimeText,
  timelineAxisValue,
} from '../utils/subsidy-timeline'

// 仅注册时间轴实际使用的模块；组件随管理员查询路由加载。
use([CustomChart, GridComponent, TooltipComponent, DataZoomComponent, SVGRenderer])
const props = defineProps({
  rows: { type: Array, required: true },
  range: { type: Array, required: true },
  policyKey: { type: String, default: '' },
  label: { type: String, required: true },
  currentAt: { type: String, required: true },
  cursorAt: { type: String, default: '' },
  zoom: { type: Object, default: () => ({ start: 0, end: 100 }) },
})
const emit = defineEmits(['select-policy', 'cursor-change', 'zoom-change'])
const container = ref(null)
const chartWidth = ref(660)
const leftMargin = computed(() => (chartWidth.value < 520 ? 148 : 204))
const height = computed(() => props.rows.length * 88 + 100)
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
const selectionBand = computed(() => {
  const index = props.rows.findIndex((row) => row.key === props.policyKey)
  const row = props.rows[index]
  const part = row?.parts.find((item) => timelineContainsAt(item, props.cursorAt))
  if (!part) {
    return null
  }
  const x = Math.max(leftMargin.value, pixelAt(part.startAt))
  const end = Math.min(chartWidth.value - 15, pixelAt(part.endAt))
  return end > x
    ? {
        x,
        y: 52 + (index + 0.5) * 88 - 16,
        width: end - x,
        color: row.plan.subsidyMode === 'FIXED' ? '#275da7' : '#925416',
      }
    : null
})
function selectRow(row) {
  const at = props.cursorAt
  if (
    row.segment.fullConfiguration !== undefined &&
    row.parts.length &&
    !row.parts.some((part) => timelineContainsAt(part, at))
  ) {
    const closest = row.parts.find((part) => part.startAt >= at) ?? row.parts.at(-1)
    emit('select-policy', row, closest.startAt)
    return
  }
  emit('select-policy', row, timelineContainsAt(row, at) ? at : row.startAt)
}
const cursorParts = computed(() =>
  props.rows.map((row) => row.parts.find((part) => timelineContainsAt(part, props.cursorAt))),
)
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
function dateLabel(at, boundary) {
  return at.endsWith(boundary) ? at.slice(0, 10) : timelineTimeText(at)
}
function renderChart() {
  if (!chart) {
    return
  }
  const range = props.range
  const chartRows = props.rows
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
      grid: { left: leftMargin.value, right: 15, top: 52, bottom: 48 },
      tooltip: {
        confine: true,
        trigger: 'item',
        backgroundColor: '#fff',
        borderColor: '#e0e8f5',
        textStyle: { color: '#253550', fontSize: 12 },
        formatter: ({ data }) => {
          const row = chartRows[data[0]]
          const part = row.parts[data[3]]
          return [
            '<strong>' +
              escape(row.plan.policyCode) +
              (row.plan.estimated ? ' · 预估' : '') +
              '</strong>',
            escape(row.caption) + ' · ' + (part.status ?? (part.used ? '采用' : '未采用')),
            '原政策 ' + escape(row.plan.policyFrom) + ' 至 ' + escape(row.plan.policyThrough),
            '本段开始 ' + escape(timelineTimeText(part.startAt)),
            '本段结束 ' + escape(timelineEndText(part.endAt, part.open)),
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
      series: [policyTimelineSeries(chartRows, graphic.clipRectByRect, props.currentAt)],
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
    (x < leftMargin.value || x > chartWidth.value - 15 || y < 52 || y > height.value - 48)
  ) {
    return
  }
  const point = chart.convertFromPixel({ gridIndex: 0 }, [
    Math.max(leftMargin.value, Math.min(x, chartWidth.value - 15)),
    Math.max(52, Math.min(y, height.value - 48)),
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
    if (Array.isArray(event.data)) {
      const row = props.rows[event.data[0]]
      const part = row.parts[event.data[3]]
      const pixel = event.event?.offsetX
      const value = Number.isFinite(pixel)
        ? chart.convertFromPixel({ gridIndex: 0 }, [pixel, 60])?.[0]
        : null
      const at = Number.isFinite(value) ? timelineAxisTime(value) : props.cursorAt
      emit('select-policy', row, timelineContainsAt(part, at) ? at : part.startAt)
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
watch(() => [props.rows, leftMargin.value, props.range, props.currentAt], renderChart, {
  flush: 'post',
})
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
      :style="{ height: `${height}px` }"
      @pointerdown="movePointer"
      @pointermove="movePointer"
      @pointerup="stopPointerDrag"
      @pointercancel="stopPointerDrag"
    >
      <span class="timeline-policy-column-title">政策及原始有效期</span>
      <div class="timeline-policy-labels" :style="{ width: leftMargin - 8 + 'px' }">
        <div v-for="row in rows" :key="row.key" class="timeline-policy-row">
          <div class="timeline-policy-id">
            <button
              type="button"
              :aria-label="'查看 ' + row.plan.policyCode + ' ' + row.segment.label + '的政策明细'"
              @click="selectRow(row)"
            >
              {{ row.plan.policyCode }}
            </button>
            <span v-if="row.plan.estimated" class="model-subsidy-estimate">预估政策</span>
          </div>
          <div class="timeline-policy-amount" :class="row.plan.subsidyMode.toLowerCase()">
            {{ row.caption }}
          </div>
          <small>{{ PLATFORM_LABELS[row.segment.platformCode] }} · {{ row.segment.label }}</small>
          <time>
            {{ dateLabel(row.plan.policyFrom, '00:00:00') }} 至
            {{ dateLabel(row.plan.policyThrough, '23:59:59') }}
          </time>
        </div>
      </div>
      <div
        ref="container"
        class="subsidy-timeline-chart"
        :style="{ height: `${height}px` }"
        role="img"
        :aria-label="label"
      />
      <div class="timeline-policy-states" :style="{ left: leftMargin + 'px' }">
        <div v-for="(part, index) in cursorParts" :key="rows[index].key">
          <span v-if="part" :class="{ used: part.used }">
            {{ part.status ?? (part.used ? '该时点采用' : '该时点未采用') }}
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
          v-if="selectionBand"
          class="subsidy-timeline-selection"
          :x="selectionBand.x"
          :y="selectionBand.y"
          :width="selectionBand.width"
          height="32"
          rx="5"
          fill="none"
          :stroke="selectionBand.color"
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
.timeline-policy-column-title {
  position: absolute;
  top: 27px;
  left: 14px;
  font-size: 11px;
  color: #566a83;
}
.timeline-policy-labels,
.timeline-policy-states {
  position: absolute;
  z-index: 1;
  top: 52px;
  bottom: 48px;
  display: grid;
  grid-auto-rows: minmax(0, 1fr);
  pointer-events: none;
}
.timeline-policy-labels {
  left: 8px;
  border-right: 1px solid #e0e8f3;
}
.timeline-policy-row {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 2px;
  padding: 2px 6px;
  border-top: 1px solid #e8eef6;
}
.timeline-policy-id {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #2a405e;
}
.timeline-policy-amount {
  font-size: 12px;
  font-weight: 600;
  color: #275da7;
}
.timeline-policy-amount.ratio {
  color: #925416;
}
.timeline-policy-id button {
  border: 0;
  background: none;
  padding: 0;
  color: inherit;
  font: inherit;
  font-weight: 650;
  cursor: pointer;
  pointer-events: auto;
}
.timeline-policy-id button:hover {
  text-decoration: underline;
}
.timeline-policy-row small,
.timeline-policy-row time {
  color: #576b85;
  font-size: 10px;
  line-height: 1.3;
  font-variant-numeric: tabular-nums;
}
.timeline-policy-row small {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.timeline-policy-states > div {
  display: flex;
  align-items: flex-end;
  padding: 0 6px 7px;
}
.timeline-policy-states span {
  font-size: 10px;
  color: #65768c;
}
.timeline-policy-states .used {
  color: #276a50;
}
.subsidy-timeline-scroll {
  min-width: 0;
  overflow-x: auto;
}
.subsidy-timeline-layout {
  position: relative;
  min-width: 0;
  width: 100%;
}
.subsidy-timeline-chart {
  width: 100%;
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
</style>
