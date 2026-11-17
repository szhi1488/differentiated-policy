<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { graphic, init, use } from 'echarts/core'
import { CustomChart } from 'echarts/charts'
import { DataZoomComponent, GridComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import AppIcon from './AppIcon.vue'
import { PLATFORM_LABELS, PRICE_BASE_LABELS } from '../constants/policy'
import {
  timelineContainsAt,
  timelineDecimal,
  timelinePlanCaption,
  timelinePlanDetail,
  timelinePlanText,
  timelineTimeFromValue,
  timelineTimeValue,
} from '../utils/subsidy-timeline'

// 仅注册时间轴实际使用的模块；组件随管理员查询路由加载。
use([CustomChart, GridComponent, TooltipComponent, DataZoomComponent, SVGRenderer])
const props = defineProps({
  segments: { type: Array, required: true },
  range: { type: Array, required: true },
  selectedId: { type: String, default: '' },
  label: { type: String, required: true },
  currentAt: { type: String, required: true },
  cursorAt: { type: String, default: '' },
  zoom: { type: Object, default: () => ({ start: 0, end: 100 }) },
})
const emit = defineEmits(['select', 'cursor-change', 'zoom-change'])
const container = ref(null)
const chartWidth = ref(660)
const rowKey = (segment) => `${segment.platformCode}:${segment.scope}`
const rows = computed(() => {
  const result = []
  for (const segment of props.segments) {
    const key = rowKey(segment)
    if (!result.some((row) => row.key === key)) {
      result.push({
        key,
        platformCode: segment.platformCode,
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
const height = computed(() => rows.value.length * 56 + 90)
const cursorX = computed(() => {
  if (!props.cursorAt) {
    return null
  }
  const start = timelineTimeValue(props.range[0])
  const span = timelineTimeValue(props.range[1]) - start
  const from = start + (span * props.zoom.start) / 100
  const to = start + (span * props.zoom.end) / 100
  const at = timelineTimeValue(props.cursorAt)
  if (to <= from || at < from || at > to) {
    return null
  }
  return 88 + ((at - from) / (to - from)) * (chartWidth.value - 103)
})
const pointerLabelX = computed(() =>
  Math.max(88, Math.min((cursorX.value ?? 88) - 46, chartWidth.value - 111)),
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

function renderChart() {
  if (!chart) {
    return
  }
  const segments = props.segments
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
      grid: { left: 88, right: 15, top: 35, bottom: 48 },
      tooltip: {
        confine: true,
        trigger: 'item',
        backgroundColor: '#fff',
        borderColor: '#e0e8f5',
        textStyle: { color: '#253550', fontSize: 12 },
        formatter: ({ data }) => {
          const item = data.segment
          const endText = item.endAt ? item.endAt.replace('T', ' ') : '当前适用（截至示例时点）'
          return `<strong>${escape(PLATFORM_LABELS[item.platformCode])} · ${escape(timelinePlanText(item))}</strong><br>${escape(timelinePlanDetail(item))}<br>${escape(item.startAt.replace('T', ' '))}<br>至 ${escape(endText)}<br>${escape(item.policyCode)}${item.scope === 'SPECIFIED' ? '<br>独立商品：' + escape(item.goods[0].color) : ''}<br>点击查看明细`
        },
      },
      xAxis: {
        type: 'time',
        position: 'top',
        min: timelineTimeValue(range[0]),
        max: timelineTimeValue(range[1]),
        splitNumber: 4,
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { show: true, lineStyle: { color: '#e8edf5', type: 'dashed' } },
        axisLabel: {
          color: '#71819a',
          fontSize: 11,
          hideOverlap: true,
          formatter: (value) =>
            new Date(value + 8 * 3600000).toISOString().slice(5, 10).replace('-', '/'),
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
      series: [
        {
          type: 'custom',
          clip: true,
          encode: { x: [1, 2], y: 0 },
          data: segments.map((segment) => ({
            value: [
              chartRows.findIndex((row) => row.key === rowKey(segment)),
              timelineTimeValue(segment.startAt),
              timelineTimeValue(segment.endAt ?? range[1]),
            ],
            segment,
          })),
          renderItem(params, api) {
            const item = segments[params.dataIndex]
            const start = api.coord([api.value(1), api.value(0)])
            const end = api.coord([api.value(2), api.value(0)])
            const shape = graphic.clipRectByRect(
              {
                x: start[0] + 2,
                y: start[1] - 21,
                width: Math.max(end[0] - start[0] - 4, 0),
                height: 42,
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
            const selected = item.id === props.selectedId
            const currentX = api.coord([timelineTimeValue(props.currentAt), api.value(0)])[0]
            const current =
              timelineContainsAt(item, props.currentAt) &&
              currentX >= params.coordSys.x - 1 &&
              currentX <= params.coordSys.x + params.coordSys.width + 1
            const narrowRatio = !fixed && shape.width < 96 && !current
            const text =
              shape.width > 42
                ? narrowRatio
                  ? `${timelineDecimal(item.ratio)}%\n${PRICE_BASE_LABELS[item.priceBase] ?? '基数未填写'}`
                  : timelinePlanCaption(item)
                : ''
            const period = current
              ? `${shortDate(item.startAt)} 起`
              : `${shortDate(item.startAt)} — ${shortDate(item.endAt ?? range[1])}`
            return {
              type: 'group',
              children: [
                {
                  type: 'rect',
                  shape: { ...shape, r: 5 },
                  style: {
                    fill,
                    stroke: selected ? color : fixed ? '#c8dcff' : '#ffdbb3',
                    lineWidth: selected ? 2 : 1,
                  },
                  // 悬停只强调边框，显式保留蓝色或橙色底色，避免默认高亮改变填充。
                  emphasis: { style: { fill, stroke: color, lineWidth: 2 } },
                },
                {
                  type: 'text',
                  silent: true,
                  style: {
                    x: shape.x + shape.width / 2,
                    y: shape.y + (narrowRatio ? 21 : 13),
                    text,
                    fill: fixed ? '#2e62aa' : '#99611e',
                    font: '11px Microsoft YaHei',
                    lineHeight: 16,
                    align: 'center',
                    verticalAlign: 'middle',
                    width: Math.max(shape.width - 8, 0),
                    overflow: 'truncate',
                  },
                },
                {
                  type: 'text',
                  silent: true,
                  style: {
                    x: shape.x + (current ? 8 : shape.width / 2),
                    y: shape.y + 31,
                    text: !narrowRatio && shape.width > (current ? 128 : 96) ? period : '',
                    fill: '#7c8ba3',
                    font: '10px Microsoft YaHei',
                    align: current ? 'left' : 'center',
                    verticalAlign: 'middle',
                  },
                },
                ...(current && shape.width > 64
                  ? [
                      {
                        type: 'rect',
                        silent: true,
                        shape: {
                          x: shape.x + shape.width - 54,
                          y: shape.y + 24,
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
                          y: shape.y + 31,
                          text: '当前适用',
                          fill: '#257251',
                          font: '9px Microsoft YaHei',
                          align: 'center',
                          verticalAlign: 'middle',
                        },
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
    (x < 88 || x > chartWidth.value - 15 || y < 35 || y > height.value - 48)
  ) {
    return
  }
  const point = chart.convertFromPixel({ gridIndex: 0 }, [
    Math.max(88, Math.min(x, chartWidth.value - 15)),
    Math.max(35, Math.min(y, height.value - 48)),
  ])
  if (Number.isFinite(point?.[0])) {
    emit('cursor-change', timelineTimeFromValue(point[0]))
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
    if (event.data?.segment) {
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
watch(() => [props.segments, props.range, props.selectedId], renderChart, {
  deep: true,
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
      @pointermove="movePointer"
      @pointerup="stopPointerDrag"
      @pointercancel="stopPointerDrag"
    >
      <div class="subsidy-timeline-platforms" aria-hidden="true">
        <div v-for="row in rows" :key="row.key" class="subsidy-timeline-platform">
          <AppIcon :name="`platform-${row.platformCode}`" :size="19" />
          <span>
            {{ PLATFORM_LABELS[row.platformCode] }}
            <small v-if="row.independent">独立商品</small>
          </span>
        </div>
      </div>
      <div
        ref="container"
        class="subsidy-timeline-chart"
        :style="{ height: `${height}px` }"
        role="img"
        :aria-label="label"
      />
      <svg
        v-if="cursorX !== null"
        class="subsidy-timeline-pointer"
        :width="chartWidth"
        :height="height - 48"
        aria-hidden="true"
      >
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
            {{ cursorAt.slice(5, 16).replace('T', ' ') }}
          </text>
          <path :d="`M${cursorX - 4} 22 L${cursorX + 4} 22 L${cursorX} 27Z`" fill="#526fae" />
        </g>
      </svg>
    </div>
  </div>
</template>

<style scoped>
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
  width: 73px;
  display: grid;
  grid-auto-rows: minmax(0, 1fr);
  pointer-events: none;
}
.subsidy-timeline-platform {
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
