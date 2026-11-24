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
  configuration: { type: String, required: true },
  at: { type: String, required: true },
  scopeLabel: { type: String, default: '' },
})
const groups = computed(() => {
  if (!props.segment) {
    return []
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
      usePeriod: group.used ? plan.usePeriods.find((p) => timelineContainsAt(p, props.at)) : null,
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
      <div class="model-subsidy-detail-summary">
        <span>
          {{ CHANNEL_LABELS[segment.channel] }} · {{ PLATFORM_LABELS[segment.platformCode] }}
        </span>
        <h3>{{ segment.decision }}</h3>
        <p>{{ configuration }}</p>
        <template v-if="segment.channel === 'BILLION'">
          <p v-if="!segment.fullConfiguration && segment.goods.length">
            本段申报商品 · {{ goodsText(segment.goods) }}
          </p>
          <p>审核范围 · 整组配置</p>
        </template>
        <p v-else-if="segment.fullConfiguration">整组配置</p>
        <p v-else-if="segment.goods.length">
          本段商品 ·
          {{ goodsText(segment.goods) }}
        </p>
        <p v-else>按机型版本、RAM 和 ROM 匹配</p>
      </div>
      <dl class="model-subsidy-detail-fields">
        <div>
          <dt>{{ segment.planned ? '计划开始' : '组合开始' }}</dt>
          <dd>{{ timelineTimeText(segment.startAt) }}</dd>
        </div>
        <div>
          <dt>{{ segment.planned ? '计划结束' : '组合结束' }}</dt>
          <dd>{{ timelineEndText(segment.endAt, segment.open) }}</dd>
        </div>
      </dl>

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
              muted: group.source === segment.excluded,
              inspected: plan.key === planKey,
            }"
          >
            <header>
              <strong>
                {{ plan.policyCode }}
                <small v-if="plan.policyFrom > asOf">待生效</small>
                <span v-if="plan.estimated === true" class="model-subsidy-estimate">预估</span>
              </strong>
              <span :class="plan.subsidyMode.toLowerCase()">{{ timelinePlanText(plan) }}</span>
            </header>
            <p>{{ timelinePlanDetail(plan) }}</p>
            <p v-if="plan.scenes.length || plan.verify">
              {{ [plan.scenes.join('、'), plan.verify].filter(Boolean).join(' · ') }}
            </p>
            <p>
              原单{{ 'quantity' in plan ? '指定商品' : '普通商品' }} ·
              {{ goodsText(plan.goods) }}
            </p>
            <p v-if="'quantity' in plan">该平台数量 {{ plan.quantity ?? '未填写' }}</p>
            <dl>
              <template v-if="plan.usePeriod">
                <div>
                  <dt>实际开始</dt>
                  <dd>{{ timelineTimeText(plan.usePeriod.startAt) }}</dd>
                </div>
                <div>
                  <dt>实际结束</dt>
                  <dd>{{ timelineEndText(plan.usePeriod.endAt, plan.usePeriod.open) }}</dd>
                </div>
              </template>
              <div v-else-if="!segment.planned">
                <dt>采用情况</dt>
                <dd>此时此范围未采用</dd>
              </div>
              <div>
                <dt>政策日期</dt>
                <dd>
                  {{ timelineTimeText(plan.policyFrom) }} 至
                  {{ timelineTimeText(plan.policyThrough) }}
                </dd>
              </div>
              <div>
                <dt>审核通过</dt>
                <dd>{{ timelineTimeText(plan.approvedAt) }}</dd>
              </div>
            </dl>
            <details
              v-if="plan.usePeriods.length > (plan.usePeriod ? 1 : 0)"
              class="model-subsidy-source-periods"
            >
              <summary>该范围实际采用时段（{{ plan.usePeriods.length }} 段）</summary>
              <p v-for="period in plan.usePeriods" :key="period.startAt">
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
