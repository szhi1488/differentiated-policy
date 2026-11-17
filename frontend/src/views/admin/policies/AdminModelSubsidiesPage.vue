<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import AppIcon from '../../../components/AppIcon.vue'
import WorkspacePageHeading from '../../../components/WorkspacePageHeading.vue'
import PolicySubsidyTimeline from '../../../components/PolicySubsidyTimeline.vue'
import { useSubsidyTimelineNavigation } from '../../../composables/useSubsidyTimelineNavigation'
import { CHANNEL_LABELS, PLATFORM_LABELS } from '../../../constants/policy'
import {
  createSubsidyPreviewSegments,
  SUBSIDY_PREVIEW_CONFIGS,
  SUBSIDY_PREVIEW_DATES,
  SUBSIDY_PREVIEW_MODELS,
} from '../../../mocks/fixtures/model-subsidies'
import {
  timelinePlanDetail,
  timelinePlanCaption,
  timelineContainsAt,
  timelinePlanText,
  timelineTimeText,
  timelineTimeFromValue,
  visibleTimelineSegments,
} from '../../../utils/subsidy-timeline'
import './model-subsidies.css'

const brand = ref('OPPO')
const modelId = ref('find-x8')
const configId = ref('16-512')
const currentAt = ref(timelineTimeFromValue(Date.now()))
const dates = ref([SUBSIDY_PREVIEW_DATES[0], currentAt.value.slice(0, 10)])
const showIndependent = ref(false)
const modelOptions = computed(() =>
  SUBSIDY_PREVIEW_MODELS.filter((model) => model.brand === brand.value),
)
const initialSelection = () => ({
  modelId: modelId.value,
  configId: configId.value,
  range: [`${dates.value[0]}T00:00:00`, `${dates.value[1]}T23:59:59`],
})
const applied = ref(initialSelection())
const model = computed(() =>
  SUBSIDY_PREVIEW_MODELS.find((item) => item.id === applied.value.modelId),
)
const config = computed(() =>
  SUBSIDY_PREVIEW_CONFIGS.find((item) => item.id === applied.value.configId),
)
const range = computed(() => [
  applied.value.range[0],
  applied.value.range[1] > currentAt.value ? currentAt.value : applied.value.range[1],
])
const segments = computed(() =>
  createSubsidyPreviewSegments(applied.value.modelId, applied.value.configId),
)
const visibleSegments = computed(() =>
  visibleTimelineSegments(segments.value, range.value, showIndependent.value),
)
const { cursorAt, zoom, setCursor, setZoom, resetZoom } = useSubsidyTimelineNavigation(range)
const pointPanel = ref(['point'])
const pointRows = computed(() => {
  const scopes = new Map()
  for (const segment of visibleSegments.value) {
    const key = `${segment.channel}:${segment.platformCode}:${segment.scope}`
    if (!scopes.has(key)) {
      scopes.set(key, {
        key,
        channel: segment.channel,
        platformCode: segment.platformCode,
        scope: segment.scope,
        segment: null,
      })
    }
    if (timelineContainsAt(segment, cursorAt.value)) {
      scopes.get(key).segment = segment
    }
  }
  return [...scopes.values()]
})
const selectedId = ref('')
const selected = computed(() => visibleSegments.value.find((item) => item.id === selectedId.value))
const channels = [
  {
    code: 'RETAIL',
    title: '零售补贴时间轴',
    short: '零售',
  },
  {
    code: 'BILLION',
    title: '百亿补贴时间轴',
    short: '百亿',
  },
]
// 指针移动不改变商品范围，保持传给两张图的数据引用稳定，避免悬停时整图重绘。
const segmentsByChannel = computed(() =>
  Object.fromEntries(
    channels.map(({ code }) => [
      code,
      visibleSegments.value.filter((segment) => segment.channel === code),
    ]),
  ),
)

watch(brand, () => {
  modelId.value = modelOptions.value[0].id
})
watch(
  visibleSegments,
  (items) => {
    if (!items.some((item) => item.id === selectedId.value)) {
      selectedId.value =
        items.findLast(
          (item) =>
            item.channel === 'RETAIL' && item.platformCode === 'PDD' && item.scope === 'NORMAL',
        )?.id ??
        items[0]?.id ??
        ''
    }
  },
  { immediate: true },
)

function search() {
  if (!dates.value?.[0] || !dates.value?.[1]) {
    ElMessage.warning('请选择完整的查询日期范围')
    return
  }
  currentAt.value = timelineTimeFromValue(Date.now())
  applied.value = initialSelection()
}
async function locateCurrentTime() {
  currentAt.value = timelineTimeFromValue(Date.now())
  const today = currentAt.value.slice(0, 10)
  // 历史或未来日期范围也能定位此刻，仍按当前选择的机型配置展示静态示例。
  dates.value = [dates.value?.[0] && dates.value[0] <= today ? dates.value[0] : today, today]
  applied.value = initialSelection()
  await nextTick()
  resetZoom()
  setCursor(currentAt.value)
}
function reset() {
  brand.value = 'OPPO'
  modelId.value = 'find-x8'
  configId.value = '16-512'
  currentAt.value = timelineTimeFromValue(Date.now())
  dates.value = [SUBSIDY_PREVIEW_DATES[0], currentAt.value.slice(0, 10)]
  showIndependent.value = false
  search()
}
</script>

<template>
  <section class="model-subsidies" aria-label="机型补贴查询">
    <header class="model-subsidy-header workspace-page-banner">
      <WorkspacePageHeading title="机型补贴查询" icon="history" />
      <el-tag type="warning" effect="light">静态预览 · 示例数据</el-tag>
    </header>

    <section class="model-subsidy-surface model-subsidy-query" aria-label="查询条件">
      <el-form label-position="top" class="model-subsidy-filters" @submit.prevent="search">
        <el-form-item label="品牌">
          <el-select v-model="brand" aria-label="品牌">
            <el-option label="OPPO" value="OPPO" />
            <el-option label="一加" value="一加" />
          </el-select>
        </el-form-item>
        <el-form-item label="机型 / 版本" class="model-subsidy-model-field">
          <el-select v-model="modelId" aria-label="机型及版本" filterable>
            <el-option
              v-for="item in modelOptions"
              :key="item.id"
              :label="`${item.name} · ${item.edition}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="配置" class="model-subsidy-config-field">
          <el-select v-model="configId" aria-label="配置">
            <el-option
              v-for="item in SUBSIDY_PREVIEW_CONFIGS"
              :key="item.id"
              :label="item.label"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="查询日期" class="model-subsidy-date-field">
          <el-date-picker
            v-model="dates"
            type="daterange"
            value-format="YYYY-MM-DD"
            format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :editable="false"
            :clearable="false"
            popper-class="model-subsidy-date-popper"
          />
        </el-form-item>
        <div class="model-subsidy-query-actions">
          <el-button @click="locateCurrentTime">当前时间</el-button>
          <el-button @click="reset">重置</el-button>
          <el-button type="primary" native-type="submit">
            <AppIcon name="search" :size="15" />
            查询
          </el-button>
          <el-checkbox v-model="showIndependent" class="model-subsidy-independent">
            <AppIcon
              v-if="showIndependent"
              name="check"
              :size="12"
              class="model-subsidy-independent-check"
            />
            展开独立商品
          </el-checkbox>
        </div>
      </el-form>
      <div class="model-subsidy-query-note">
        <AppIcon name="alert" :size="14" />
        <span>当前展示内置示例，仅供确认页面效果。</span>
      </div>
    </section>

    <div class="model-subsidy-workspace">
      <div class="model-subsidy-charts">
        <section
          v-for="channel in channels"
          :key="channel.code"
          class="model-subsidy-surface model-subsidy-chart-card"
          :aria-label="channel.title"
        >
          <header class="model-subsidy-chart-header">
            <h2>
              <AppIcon name="timeline" :size="17" />
              {{ channel.title }}
            </h2>
            <div class="model-subsidy-legend">
              <span>
                <AppIcon name="legend" class="fixed" :size="14" />
                固定补贴
              </span>
              <span>
                <AppIcon name="legend" class="ratio" :size="14" />
                比例补贴
              </span>
            </div>
          </header>
          <PolicySubsidyTimeline
            v-if="segmentsByChannel[channel.code].length"
            :segments="segmentsByChannel[channel.code]"
            :range="range"
            :selected-id="selectedId"
            :current-at="currentAt"
            :cursor-at="cursorAt"
            :zoom="zoom"
            :label="`${channel.title}，${model.name}，${config.label}。点击色块查看完整明细。`"
            @select="selectedId = $event.id"
            @cursor-change="setCursor"
            @zoom-change="setZoom"
          />
          <el-empty v-else :image-size="64" description="所选日期范围内暂无示例政策" />
        </section>
      </div>

      <aside class="model-subsidy-details" aria-label="时点补贴及明细">
        <section
          class="model-subsidy-surface model-subsidy-point-card"
          aria-label="所选时点各平台补贴"
        >
          <el-collapse v-model="pointPanel">
            <el-collapse-item name="point" title="所选时点补贴">
              <p class="model-subsidy-point-time" aria-live="polite">
                {{ cursorAt ? timelineTimeText(cursorAt) : '暂无可查询时点' }}
              </p>
              <div
                v-for="channel in channels"
                :key="channel.code"
                class="model-subsidy-point-channel"
              >
                <h3>{{ channel.short }}</h3>
                <ul>
                  <li
                    v-for="row in pointRows.filter((item) => item.channel === channel.code)"
                    :key="row.key"
                  >
                    <span>
                      {{ PLATFORM_LABELS[row.platformCode] }}
                      <small v-if="row.scope === 'SPECIFIED'">独立商品</small>
                    </span>
                    <el-button
                      v-if="row.segment"
                      link
                      :aria-label="`查看该时点${channel.short}${PLATFORM_LABELS[row.platformCode]}${row.scope === 'SPECIFIED' ? '独立商品' : ''}补贴`"
                      @click="selectedId = row.segment.id"
                    >
                      {{ timelinePlanCaption(row.segment) }}
                    </el-button>
                    <small v-else>暂无适用政策</small>
                  </li>
                </ul>
              </div>
            </el-collapse-item>
          </el-collapse>
        </section>
        <section class="model-subsidy-surface model-subsidy-detail-card" aria-label="所选补贴明细">
          <header>
            <h2>补贴明细</h2>
          </header>
          <template v-if="selected">
            <div
              class="model-subsidy-selected-plan"
              :class="selected.subsidyMode === 'FIXED' ? 'fixed' : 'ratio'"
            >
              <span>
                {{ CHANNEL_LABELS[selected.channel] }} ·
                {{ PLATFORM_LABELS[selected.platformCode] }}
              </span>
              <strong>{{ timelinePlanText(selected) }}</strong>
              <small>{{ timelinePlanDetail(selected) }}</small>
            </div>
            <dl class="model-subsidy-detail-fields">
              <div>
                <dt>政策单号</dt>
                <dd>{{ selected.policyCode }}</dd>
              </div>
              <div>
                <dt>审核状态</dt>
                <dd><el-tag type="success" size="small">已通过 · 示例</el-tag></dd>
              </div>
              <div>
                <dt>适用开始</dt>
                <dd>{{ timelineTimeText(selected.startAt) }}</dd>
              </div>
              <div>
                <dt>适用结束</dt>
                <dd>
                  {{ selected.endAt ? timelineTimeText(selected.endAt) : '截至当前时点仍适用' }}
                </dd>
              </div>
              <div>
                <dt>选品范围</dt>
                <dd>
                  {{ selected.scope === 'SPECIFIED' ? '独立商品' : '配置商品组' }} ·
                  {{ selected.goods.length }} 件
                </dd>
              </div>
              <div v-if="selected.scope === 'SPECIFIED'">
                <dt>参考数量</dt>
                <dd>{{ selected.quantity ?? '未填写' }}</dd>
              </div>
            </dl>
            <div class="model-subsidy-goods">
              <h3>该段实际适用商品</h3>
              <ul>
                <li v-for="goods in selected.goods" :key="goods.id">
                  <span>{{ goods.color }}</span>
                </li>
              </ul>
            </div>
          </template>
          <el-empty v-else :image-size="56" description="暂无可展示的补贴明细" />
        </section>
      </aside>
    </div>
  </section>
</template>
