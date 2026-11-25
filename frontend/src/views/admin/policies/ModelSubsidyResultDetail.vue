<script setup>
import { computed } from 'vue'
import AppIcon from '../../../components/AppIcon.vue'
import { CHANNEL_LABELS, PLATFORM_LABELS } from '../../../constants/policy'
import {
  timelineContainsAt,
  timelinePlanText,
  timelinePlanDetail,
  timelineTimeText,
  timelineEndText,
} from '../../../utils/subsidy-timeline'

const props = defineProps({
  segment: { type: Object, default: null },
  upcoming: { type: Array, default: () => [] },
  asOf: { type: String, required: true },
  includesFuture: Boolean,
  planKey: { type: String, default: '' },
  at: { type: String, required: true },
  scopeLabel: { type: String, default: '' },
})
const groups = computed(() => {
  const segment = props.segment
  if (!segment) {
    return []
  }
  if (segment.byGoods) {
    const allocations = new Map()
    for (const scope of segment.byGoods) {
      for (const [used, plans] of [
        [true, scope.policies],
        [false, scope.excluded],
      ]) {
        for (const plan of plans) {
          let entry = allocations.get(plan.rowKey)
          if (!entry) {
            entry = { plan, used: false, excluded: false }
            allocations.set(plan.rowKey, entry)
          }
          if (used) {
            entry.used = true
          } else {
            entry.excluded = true
          }
        }
      }
    }
    const used = [],
      mixed = [],
      excluded = []
    for (const entry of allocations.values()) {
      const target = entry.used ? (entry.excluded ? mixed : used) : excluded
      target.push({
        ...entry.plan,
        key: entry.plan.rowKey,
        // 各商品可能在不同时间采用，整组卡仅展示共同核对的本段。
        otherUsePeriods: [],
      })
    }
    const prefix = segment.planned ? '本段计划' : '该时点'
    return [
      { title: prefix + '采用', plans: used, used: true },
      { title: prefix + '部分采用', plans: mixed, used: true },
      { title: prefix + '未采用', plans: excluded, used: false },
    ].map((group) => ({ ...group, count: new Set(group.plans.map((p) => p.auditLogId)).size }))
  }
  return [
    {
      title: props.segment.planned ? '本段计划采用' : '该时点采用',
      source: props.segment.policies,
      used: true,
    },
    {
      title: props.segment.planned ? '本段计划未采用' : '该时点未采用',
      source: props.segment.excluded,
      used: false,
    },
  ].map((group) => ({
    ...group,
    count: new Set(group.source.map((p) => p.auditLogId)).size,
    plans: group.source.map((plan) => ({
      ...plan,
      otherUsePeriods: plan.usePeriods.filter((period) => !timelineContainsAt(period, props.at)),
    })),
  }))
})
const goodsText = (goods) =>
  goods.map((g) => [g.color, '#' + g.id].filter(Boolean).join(' ')).join('、')
const policyCodes = (plans) => [...new Set(plans.map((plan) => plan.policyCode))].join('、')
const scheduleScope = (period) =>
  period.channel === 'BILLION' ? '审核范围 · 整组配置' : period.label
const upcomingScopes = computed(
  () =>
    new Set(props.upcoming.map((s) => (s.channel === 'BILLION' ? 'configuration' : s.trackId)))
      .size,
)
const showUpcoming = computed(() => props.at <= props.asOf && props.upcoming.length > 0)
function policyStatus(plan) {
  if (props.asOf < plan.policyFrom) {
    return '待生效'
  }
  return props.asOf > plan.effectiveThrough ? '已结束' : '生效中'
}
</script>

<template>
  <section
    class="model-subsidy-surface model-subsidy-detail-card"
    aria-label="所选补贴明细"
    tabindex="-1"
  >
    <header class="model-subsidy-section-heading">
      <h2>
        <AppIcon name="document" :size="17" />
        补贴明细
      </h2>
      <el-tag v-if="segment" type="success" size="small" effect="plain">审核已通过</el-tag>
    </header>
    <p v-if="at" class="model-subsidy-detail-at">
      核对时点
      <time>{{ timelineTimeText(at) }}</time>
      北京时间
    </p>
    <section v-if="showUpcoming" class="model-subsidy-upcoming" aria-label="后续采用安排">
      <h3>后续采用安排</h3>
      <p>
        {{ CHANNEL_LABELS[upcoming[0].channel] }} · {{ PLATFORM_LABELS[upcoming[0].platformCode] }}
        <template v-if="upcomingScopes === 1">· {{ scheduleScope(upcoming[0]) }}</template>
      </p>
      <ol>
        <li v-for="period in upcoming" :key="period.trackId + '/' + period.startAt">
          <span v-if="upcomingScopes > 1">{{ scheduleScope(period) }}</span>
          <span>{{ timelineTimeText(period.startAt) }} 至 {{ timelineEndText(period.endAt) }}</span>
          <strong>{{ policyCodes(period.policies) || '本段无采用政策' }}</strong>
        </li>
      </ol>
    </section>
    <template v-if="segment">
      <dl class="model-subsidy-detail-fields">
        <div>
          <dt>{{ segment.planned ? '本段计划开始' : '本段实际开始' }}</dt>
          <dd>{{ timelineTimeText(segment.startAt) }}</dd>
        </div>
        <div>
          <dt>{{ segment.planned ? '本段计划结束' : '本段实际结束' }}</dt>
          <dd>{{ timelineEndText(segment.endAt, segment.open) }}</dd>
        </div>
      </dl>

      <section v-if="segment.byGoods" aria-label="逐商品审核结果">
        <h3 class="model-subsidy-source-heading">逐商品审核结果</h3>
        <ol class="model-subsidy-goods-results">
          <li v-for="scope in segment.byGoods" :key="scope.trackId">
            <strong>{{ goodsText(scope.goods) }}</strong>
            <span>
              {{ segment.planned ? '计划采用' : '采用' }} ·
              {{ policyCodes(scope.policies) || '无' }}
            </span>
            <span v-if="scope.excluded.length">
              {{ segment.planned ? '计划未采用' : '未采用' }} · {{ policyCodes(scope.excluded) }}
            </span>
          </li>
        </ol>
      </section>
      <div v-for="group in groups" :key="group.title">
        <template v-if="group.plans.length">
          <h3 class="model-subsidy-source-heading">
            {{ group.title }}
            <small>{{ group.count }} 单</small>
          </h3>
          <article
            v-for="plan in group.plans"
            :key="plan.key"
            class="model-subsidy-source"
            :class="{
              muted: !group.used,
              inspected: plan.rowKey === planKey,
            }"
          >
            <header>
              <strong>{{ plan.policyCode }}</strong>
              <small>{{ policyStatus(plan) }}</small>
              <span :class="plan.subsidyMode.toLowerCase()">{{ timelinePlanText(plan) }}</span>
              <span>{{ timelinePlanDetail(plan) }}</span>
            </header>
            <p v-if="plan.estimated || plan.scenes.length || plan.verify">
              <span v-if="plan.estimated" class="model-subsidy-estimate">预估</span>
              {{ [plan.scenes.join('、'), plan.verify].filter(Boolean).join(' · ') }}
            </p>
            <p>
              原单{{ 'quantity' in plan ? '指定商品' : '普通商品' }} ·
              {{
                goodsText(
                  segment.channel === 'RETAIL' ? (plan.declaredGoods ?? plan.goods) : plan.goods,
                )
              }}
            </p>
            <p v-if="'quantity' in plan">该平台数量 {{ plan.quantity ?? '未填写' }}</p>
            <details v-if="plan.otherUsePeriods.length" class="model-subsidy-source-periods">
              <summary>该范围其他实际采用时段（{{ plan.otherUsePeriods.length }} 段）</summary>
              <p v-for="period in plan.otherUsePeriods" :key="period.startAt">
                {{ timelineTimeText(period.startAt) }} 至
                {{ timelineEndText(period.endAt, period.open) }}
              </p>
            </details>
          </article>
        </template>
      </div>
    </template>
    <el-empty
      v-else-if="!showUpcoming"
      :image-size="50"
      :description="
        at > asOf
          ? includesFuture
            ? '该时点没有匹配的已通过计划'
            : '该时点超出已知历史，截至 ' + timelineTimeText(asOf)
          : scopeLabel
            ? scopeLabel + '在该时点暂无记录'
            : '暂无可展示的补贴明细'
      "
    />
  </section>
</template>

<style scoped>
.model-subsidy-goods-results {
  list-style: none;
  padding: 0;
  margin: 6px 0;
  max-height: 16em;
  overflow: auto;
}
.model-subsidy-goods-results li {
  display: grid;
  gap: 3px;
  padding: 6px 0;
  color: var(--el-text-color-regular);
  font-size: 12px;
  overflow-wrap: anywhere;
}
</style>
