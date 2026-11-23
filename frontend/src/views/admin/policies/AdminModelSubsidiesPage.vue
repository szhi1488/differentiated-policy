<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import AppIcon from '../../../components/AppIcon.vue'
import WorkspacePageHeading from '../../../components/WorkspacePageHeading.vue'
import ModelSubsidyResultDetail from './ModelSubsidyResultDetail.vue'
import PolicySubsidyTimeline from '../../../components/PolicySubsidyTimeline.vue'
import { useSubsidyTimelineNavigation } from '../../../composables/useSubsidyTimelineNavigation'
import { PLATFORM_LABELS } from '../../../constants/policy'
import {
  createSubsidyPreviewSegments,
  SUBSIDY_PREVIEW_CONFIGS,
  RETAIL_EXAMPLE,
  previewDate,
  SUBSIDY_PREVIEW_MODELS,
} from '../../../mocks/fixtures/model-subsidies'
import {
  timelineContainsAt,
  timelineTimeFromValue,
  visibleTimelineSegments,
  withPolicyUsePeriods,
} from '../../../utils/subsidy-timeline'
import { retailPolicyRows } from '../../../utils/retail-policy-timeline'
import { timelineLastSecond } from '../../../utils/subsidy-timeline'
import './model-subsidies.css'

const brand = ref('OPPO')
const modelId = ref('find-x8')
const configId = ref('16-512')
const platformCode = ref('')
const currentAt = ref(timelineTimeFromValue(Date.now()))
const exampleDay = currentAt.value.slice(0, 10)
const exampleStart = previewDate(exampleDay, -20)
const dates = ref([exampleStart, exampleDay])
const showIndependent = ref(false)
const modelOptions = computed(() =>
  SUBSIDY_PREVIEW_MODELS.filter((model) => model.brand === brand.value),
)
const configOptions = computed(() => {
  const currentModel = modelOptions.value.find((item) => item.id === modelId.value)
  return SUBSIDY_PREVIEW_CONFIGS.filter((item) => currentModel?.configs.includes(item.id))
})
const initialSelection = () => ({
  modelId: modelId.value,
  configId: configId.value,
  platformCode: platformCode.value,
  range: [`${dates.value[0]}T00:00:00`, `${dates.value[1]}T23:59:59`],
})
const applied = ref(initialSelection())
const model = computed(() =>
  SUBSIDY_PREVIEW_MODELS.find((item) => item.id === applied.value.modelId),
)
const config = computed(() =>
  SUBSIDY_PREVIEW_CONFIGS.find((item) => item.id === applied.value.configId),
)
const range = computed(() => {
  const [from, through] = applied.value.range
  // 固定日期的审核示例可完整回放；「当前时间」和普通查询仍取真实时钟。
  return [from, applied.value.fullExample || through < currentAt.value ? through : currentAt.value]
})
const segments = computed(() =>
  withPolicyUsePeriods(
    createSubsidyPreviewSegments(applied.value.modelId, applied.value.configId, exampleDay),
  ),
)
const scopeSegments = computed(() =>
  segments.value.filter(
    (segment) =>
      (!applied.value.platformCode || segment.platformCode === applied.value.platformCode) &&
      (showIndependent.value || segment.scope === 'NORMAL'),
  ),
)
const retailRows = computed(() => retailPolicyRows(scopeSegments.value, range.value))
// 查询日期决定命中的政策。共用坐标轴容纳这些原单的完整日期，初始指针仍定位查询时点。
const axisRange = computed(() => {
  let [from, through] = range.value
  for (const row of retailRows.value) {
    if (row.startAt < from) {
      from = row.startAt
    }
    const last = timelineLastSecond(row.endAt)
    if (last > through) {
      through = last
    }
  }
  return [from, through]
})
const visibleSegments = computed(() =>
  visibleTimelineSegments(scopeSegments.value, axisRange.value, true, ''),
)
const { cursorAt, zoom, setCursor, setZoom, resetZoom } = useSubsidyTimelineNavigation(
  axisRange,
  () => range.value[1],
)
const detailPanel = ref(null)
const focusTrack = ref('')
const inspectedPolicy = ref(null)
const focusedScope = computed(() =>
  visibleSegments.value.find((item) => item.trackId === focusTrack.value),
)
const activeSegment = computed(() =>
  visibleSegments.value.find(
    (item) =>
      (!focusedScope.value || item.trackId === focusTrack.value) &&
      timelineContainsAt(item, cursorAt.value),
  ),
)
const selected = computed(() => {
  const active = activeSegment.value
  const row = inspectedPolicy.value
  if (!row || !timelineContainsAt(row, cursorAt.value)) {
    return active
  }
  const member = [...(active?.policies ?? []), ...(active?.excluded ?? [])].some(
    (p) => p.policyCode === row.plan.policyCode,
  )
  if (member) {
    return active
  }
  // 原单日期内、审核通过前也能点选。此时明细保留实际采用的其他政策。
  const plan = { ...row.plan, usePeriod: null }
  if (active) {
    return { ...active, excluded: [...active.excluded, plan] }
  }
  const part = row.parts.find((p) => timelineContainsAt(p, cursorAt.value))
  return {
    ...row.segment,
    ...part,
    id: row.key,
    decision: '该时点未采用',
    note: '',
    policies: [],
    excluded: [plan],
  }
})
const selectedId = computed(() => selected.value?.id ?? '')
const channels = [
  {
    code: 'RETAIL',
    title: '零售补贴时间轴',
  },
  {
    code: 'BILLION',
    title: '百亿补贴时间轴',
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
watch(modelId, () => {
  if (!configOptions.value.some((item) => item.id === configId.value)) {
    configId.value = configOptions.value[0]?.id ?? ''
  }
})
async function selectSegment(segment, policy = null) {
  inspectedPolicy.value = policy
  focusTrack.value = segment.trackId
  if (!timelineContainsAt(segment, cursorAt.value)) {
    setCursor(segment.startAt < axisRange.value[0] ? axisRange.value[0] : segment.startAt)
  }
  await nextTick()
  const panel = detailPanel.value?.$el
  const top = panel?.getBoundingClientRect().top
  if (top < 0 || top >= window.innerHeight) {
    panel.scrollIntoView({ block: 'start' })
    panel.focus({ preventScroll: true })
  }
}
function selectRetailPolicy(row, at) {
  setCursor(at)
  const part = row.parts.find((item) => timelineContainsAt(item, at))
  if (part) {
    selectSegment({ trackId: row.segment.trackId, ...part }, row)
  }
}
watch(retailRows, () => {
  inspectedPolicy.value = null
})
function search() {
  if (!dates.value?.[0] || !dates.value?.[1]) {
    ElMessage.warning('请选择完整的查询日期范围')
    return
  }
  currentAt.value = timelineTimeFromValue(Date.now())
  focusTrack.value = ''
  const selection = initialSelection()
  // 手动查询固定案例时也可看完整所选区间，定位今天仍按实际时钟。
  selection.fullExample =
    selection.modelId === RETAIL_EXAMPLE.modelId && dates.value[1] > currentAt.value.slice(0, 10)
  applied.value = selection
}
async function openRetailExample() {
  brand.value = 'OPPO'
  await nextTick()
  modelId.value = RETAIL_EXAMPLE.modelId
  configId.value = RETAIL_EXAMPLE.configId
  platformCode.value = 'JD'
  dates.value = [RETAIL_EXAMPLE.from, RETAIL_EXAMPLE.through]
  focusTrack.value = ''
  currentAt.value = timelineTimeFromValue(Date.now())
  applied.value = { ...initialSelection(), fullExample: true }
  await nextTick()
  setCursor(RETAIL_EXAMPLE.from + 'T00:00:00')
}
async function locateCurrentTime() {
  currentAt.value = timelineTimeFromValue(Date.now())
  const today = currentAt.value.slice(0, 10)
  // 历史或未来日期范围也能定位此刻，仍按当前选择的机型配置展示静态示例。
  dates.value = [dates.value?.[0] && dates.value[0] <= today ? dates.value[0] : today, today]
  focusTrack.value = ''
  applied.value = initialSelection()
  await nextTick()
  resetZoom()
  setCursor(currentAt.value)
}
function reset() {
  brand.value = 'OPPO'
  modelId.value = 'find-x8'
  configId.value = '16-512'
  platformCode.value = ''
  currentAt.value = timelineTimeFromValue(Date.now())
  dates.value = [exampleStart, currentAt.value.slice(0, 10)]
  showIndependent.value = false
  search()
}
</script>

<template>
  <section class="model-subsidies" aria-label="机型补贴查询">
    <header class="model-subsidy-header workspace-page-banner">
      <WorkspacePageHeading title="机型补贴查询" icon="history" />
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
              :label="[item.name, item.edition].filter(Boolean).join(' · ')"
              :value="item.id"
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
        <el-form-item label="平台">
          <el-select v-model="platformCode" aria-label="平台" placeholder="全部平台">
            <el-option label="全部平台" value="" />
            <el-option
              v-for="(label, code) in PLATFORM_LABELS"
              :key="code"
              :label="label"
              :value="code"
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
        <span>审核结果预览 · 以下单号、商品和补贴均为示例。</span>
        <el-button link type="primary" class="model-subsidy-example" @click="openRetailExample">
          查看京东逐段示例
        </el-button>
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
              <template v-if="channel.code === 'RETAIL'">
                <span class="model-subsidy-estimate">预估政策</span>
                <span>
                  <i class="model-subsidy-fill-key used" aria-hidden="true"></i>
                  采用
                </span>
                <span>
                  <i class="model-subsidy-fill-key" aria-hidden="true"></i>
                  未采用
                </span>
              </template>
            </div>
          </header>
          <PolicySubsidyTimeline
            v-if="
              channel.code === 'RETAIL' ? retailRows.length : segmentsByChannel[channel.code].length
            "
            :segments="segmentsByChannel[channel.code]"
            :retail-rows="channel.code === 'RETAIL' ? retailRows : null"
            :plot-left="retailRows.length ? 204 : 108"
            :range="axisRange"
            :selected-id="selectedId"
            :policy-key="inspectedPolicy?.key"
            :current-at="currentAt"
            :cursor-at="cursorAt"
            :zoom="zoom"
            :label="`${channel.title}，${model.name}，${config.label}。点击色块查看完整明细。`"
            @select="selectSegment"
            @select-policy="selectRetailPolicy"
            @cursor-change="setCursor"
            @zoom-change="setZoom"
          />
          <el-empty v-else :image-size="64" description="所选条件下暂无示例政策" />
        </section>
      </div>

      <ModelSubsidyResultDetail
        ref="detailPanel"
        :segment="selected"
        :policy-code="inspectedPolicy?.plan.policyCode"
        :at="cursorAt"
        :scope-label="
          focusedScope
            ? PLATFORM_LABELS[focusedScope.platformCode] + ' · ' + focusedScope.label
            : ''
        "
        :configuration="[model.name, model.edition, config.label].filter(Boolean).join(' · ')"
      />
    </div>
  </section>
</template>
