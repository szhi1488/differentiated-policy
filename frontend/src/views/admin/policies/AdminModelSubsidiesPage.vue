<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import AppIcon from '../../../components/AppIcon.vue'
import WorkspacePageHeading from '../../../components/WorkspacePageHeading.vue'
import ModelSubsidyResultDetail from './ModelSubsidyResultDetail.vue'
import PolicySubsidyTimeline from '../../../components/PolicySubsidyTimeline.vue'
import { useModelSubsidyQuery } from '../../../composables/useModelSubsidyQuery'
import { useSubsidyTimelineNavigation } from '../../../composables/useSubsidyTimelineNavigation'
import {
  timelineContainsAt,
  timelineLastSecond,
  timelineMonthDates,
} from '../../../utils/subsidy-timeline'
import './model-subsidies.css'

const props = defineProps({ applicant: Boolean })
const {
  brand,
  modelId,
  configId,
  platform,
  platformOptions,
  hasPlatforms,
  platformError,
  loadingPlatforms,
  refreshPlatforms,
  dates,
  brands,
  purchaseScoped,
  modelOptions,
  configOptions,
  catalogStatus,
  catalogError,
  loadCatalog,
  result,
  historyStatus,
  historyError,
  search,
  resetFilters,
  locateCurrentTime,
  retryHistory,
} = useModelSubsidyQuery(props.applicant)
const channels = [
  { code: 'RETAIL', title: '零售补贴时间轴' },
  { code: 'BILLION', title: '百亿补贴时间轴' },
]
const visibleChannels = computed(() => {
  if (!props.applicant) {
    return channels
  }
  const selected = result.value?.selection.params.perspective
  return channels.filter(
    ({ code }) =>
      (!selected || selected === code) &&
      platformOptions.value.some((option) => option.perspective === code),
  )
})
const detailPanel = ref(null)
const inspectedPolicy = ref(null)
const policyRows = computed(() => result.value?.rows ?? [])
const mixedChannels = computed(
  () =>
    new Set(
      policyRows.value
        .filter((row) => row.parts.some((part) => part.mixed))
        .map((row) => row.segment.channel),
    ),
)
const rowsByChannel = computed(() =>
  Object.fromEntries(
    channels.map(({ code }) => [
      code,
      policyRows.value.filter((row) => row.segment.channel === code),
    ]),
  ),
)
const axisRange = computed(() => {
  if (!result.value) {
    return []
  }
  const { dateFrom, dateTo } = result.value.selection.params
  let from = dateFrom + 'T00:00:00',
    through = dateTo + 'T23:59:59'
  const [monthStart, monthEnd] = timelineMonthDates(result.value.selection.requestedAt)
  if (dateFrom === monthStart && dateTo === monthEnd) {
    return [from, through]
  }
  for (const row of result.value.rows) {
    const start =
      dateFrom > result.value.asOf.slice(0, 10) && row.startAt <= result.value.asOf
        ? dateFrom + 'T00:00:00'
        : row.startAt
    if (start < from) {
      from = start
    }
    const last = timelineLastSecond(row.endAt)
    if (last > through) {
      through = last
    }
  }
  return [from, through]
})
const { cursorAt, zoom, setCursor, setZoom } = useSubsidyTimelineNavigation(
  axisRange,
  () => {
    const { selection, asOf } = result.value
    const begin = selection.params.dateFrom + 'T00:00:00'
    const end = selection.params.dateTo + 'T23:59:59'
    const at = [selection.requestedAt, asOf, end].sort()[0]
    if (begin <= asOf) {
      return at < begin ? begin : at
    }
    // 纯未来查询先定位首个可见计划，日期范围开头可能早于所有政策。
    let first
    for (const row of policyRows.value) {
      const part = row.parts.find(
        (item) => item.status && item.endAt > begin && item.startAt <= end,
      )
      if (!part) {
        continue
      }
      const start = part.startAt < begin ? begin : part.startAt
      if (!first || start < first) {
        first = start
      }
    }
    return first ?? begin
  },
  () => result.value?.selection.params,
)
// 移动指针时跟随用户点选的平台和申报商品。百亿的采用名单始终属于整组配置。
function relatedScope(a, b) {
  return (
    a.channel === b.channel &&
    a.platformCode === b.platformCode &&
    (a.channel === 'BILLION' ||
      a.trackId === b.trackId ||
      a.goods.some((item) => b.goods.some((other) => other.id === item.id)))
  )
}
const selected = computed(() => {
  const inspected = inspectedPolicy.value
  const at = cursorAt.value
  const row =
    inspected ?? policyRows.value.find((r) => r.parts.some((p) => timelineContainsAt(p, at)))
  const part = row?.parts.find((p) => timelineContainsAt(p, at))
  if (part?.scopes?.length) {
    const scopes = part.scopes.map(({ scope }) => scope)
    const first = scopes[0]
    const byGoods = first.channel === 'RETAIL' && scopes.length > 1
    return {
      ...first,
      goods: row.segment.goods,
      startAt: part.startAt,
      endAt: part.endAt,
      open: part.open,
      byGoods: byGoods ? scopes : null,
    }
  }
  const active = (result.value?.segments ?? []).filter(
    (s) => timelineContainsAt(s, at) && (!inspected || relatedScope(inspected.segment, s)),
  )
  return active.find((s) => s.policies.length) ?? active[0]
})
const selectedPolicyKey = computed(() => inspectedPolicy.value?.key ?? '')
const upcoming = computed(() => {
  if (!result.value) {
    return []
  }
  const { dateFrom, dateTo } = result.value.selection.params
  const periods = result.value.segments
    .filter(
      (s) => s.planned && s.startAt <= dateTo + 'T23:59:59' && s.endAt > dateFrom + 'T00:00:00',
    )
    .sort((a, b) => a.startAt.localeCompare(b.startAt))
  const scope = inspectedPolicy.value?.segment ?? selected.value ?? periods[0]
  if (!scope) {
    return []
  }
  // 百亿同配置的各颜色行共用一次审核。后续安排按原审核时段列一次。
  const starts = new Set()
  return periods.filter((s) => {
    if (!relatedScope(scope, s)) {
      return false
    }
    if (s.channel !== 'BILLION') {
      return true
    }
    if (starts.has(s.startAt)) {
      return false
    }
    starts.add(s.startAt)
    return true
  })
})
watch(policyRows, (rows) => {
  if (inspectedPolicy.value) {
    inspectedPolicy.value = rows.find((row) => row.key === inspectedPolicy.value.key) ?? null
  }
})
async function selectPolicy(row, at) {
  inspectedPolicy.value = row
  setCursor(at)
  await nextTick()
  const panel = detailPanel.value?.$el
  const top = panel?.getBoundingClientRect().top
  if (top < 0 || top >= window.innerHeight) {
    panel.scrollIntoView({ block: 'start' })
    panel.focus({ preventScroll: true })
  }
}
function reset() {
  resetFilters()
  search()
}
</script>

<template>
  <section class="model-subsidies" aria-label="机型补贴查询">
    <header class="model-subsidy-header workspace-page-banner">
      <WorkspacePageHeading title="机型补贴查询" icon="history" />
    </header>

    <section class="model-subsidy-surface model-subsidy-query" aria-label="查询条件">
      <el-form
        :disabled="
          catalogStatus !== 'success' ||
          !hasPlatforms ||
          historyError?.code === 'POLICY_SUBSIDY_BRAND_FORBIDDEN'
        "
        label-position="top"
        class="model-subsidy-filters"
        @submit.prevent="search"
      >
        <el-form-item label="品牌">
          <el-select v-model="brand" aria-label="品牌">
            <el-option v-for="name in brands" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
        <el-form-item label="机型 / 版本" class="model-subsidy-model-field">
          <el-select v-model="modelId" aria-label="机型及版本" filterable>
            <el-option
              v-for="item in modelOptions"
              :key="item.spuId"
              :label="item.label"
              :value="item.spuId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="配置" class="model-subsidy-config-field">
          <el-select v-model="configId" aria-label="配置">
            <el-option
              v-for="item in configOptions"
              :key="item.id"
              :label="item.label"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="平台" :class="{ 'model-subsidy-assigned-field': applicant }">
          <el-select
            v-model="platform"
            aria-label="平台"
            :placeholder="applicant ? '全部负责平台' : '全部平台'"
          >
            <el-option
              v-if="hasPlatforms"
              :label="applicant ? '全部负责平台' : '全部平台'"
              value=""
            />
            <el-option
              v-for="option in platformOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
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
        </div>
      </el-form>
    </section>

    <div v-if="platformError" class="model-subsidy-surface model-subsidy-status" role="alert">
      <span>负责平台加载失败 · {{ platformError.message }}</span>
      <el-button @click="refreshPlatforms(true)">重试</el-button>
    </div>
    <div
      v-else-if="loadingPlatforms"
      class="model-subsidy-surface model-subsidy-status"
      role="status"
    >
      正在加载负责平台…
    </div>
    <el-empty
      v-else-if="applicant && !hasPlatforms"
      class="model-subsidy-surface"
      description="尚未分配负责平台，请联系管理员分配后再查询"
    >
      <el-button @click="refreshPlatforms(true)">刷新分工</el-button>
    </el-empty>
    <div
      v-else-if="catalogError || historyError"
      class="model-subsidy-surface model-subsidy-status"
      role="alert"
    >
      <span>
        {{ catalogError ? '机型目录加载失败' : '补贴历史加载失败' }} ·
        {{ (catalogError || historyError).message }}
      </span>
      <el-button @click="catalogError ? loadCatalog() : retryHistory()">重试</el-button>
    </div>
    <div
      v-else-if="catalogStatus === 'loading' || (historyStatus === 'loading' && !result)"
      class="model-subsidy-surface model-subsidy-status"
      role="status"
    >
      {{ catalogStatus === 'loading' ? '正在加载机型目录…' : '正在查询补贴历史…' }}
    </div>
    <el-empty
      v-else-if="catalogStatus === 'empty'"
      class="model-subsidy-surface"
      :description="purchaseScoped ? '暂无可查询的采购品牌机型' : '商品目录暂无可查询的机型配置'"
    >
      <el-button v-if="purchaseScoped" @click="loadCatalog()">刷新目录</el-button>
    </el-empty>
    <div v-else-if="result" class="model-subsidy-workspace">
      <div class="model-subsidy-charts">
        <section
          v-for="channel in visibleChannels"
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
              <span v-if="channel.code === 'RETAIL'" class="model-subsidy-estimate">预估政策</span>
              <span>
                <i class="model-subsidy-fill-key used" aria-hidden="true"></i>
                采用
              </span>
              <span v-if="mixedChannels.has(channel.code)">
                <i class="model-subsidy-fill-key mixed" aria-hidden="true"></i>
                部分采用
              </span>
              <span>
                <i class="model-subsidy-fill-key" aria-hidden="true"></i>
                未采用
              </span>
            </div>
          </header>
          <PolicySubsidyTimeline
            v-if="rowsByChannel[channel.code].length"
            :rows="rowsByChannel[channel.code]"
            :range="axisRange"
            :policy-key="selectedPolicyKey"
            :current-at="result.asOf"
            :cursor-at="cursorAt"
            :zoom="zoom"
            :label="`${channel.title}，${result.selection.label}。点击色块查看完整明细。`"
            @select-policy="selectPolicy"
            @cursor-change="setCursor"
            @zoom-change="setZoom"
          />
          <el-empty v-else :image-size="64" description="所选条件下暂无已通过政策" />
        </section>
      </div>

      <ModelSubsidyResultDetail
        ref="detailPanel"
        :segment="selected"
        :upcoming="upcoming"
        :plan-key="selectedPolicyKey"
        :as-of="result.asOf"
        :includes-future="result.selection.params.dateTo + 'T23:59:59' > result.asOf"
        :at="cursorAt"
        :scope-label="inspectedPolicy?.segment.label"
      />
    </div>
  </section>
</template>
