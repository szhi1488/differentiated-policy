<script setup>
import AppIcon from '../../../components/AppIcon.vue'
import { CHANNEL_LABELS, PLATFORM_LABELS } from '../../../constants/policy'
import {
  timelinePlanText,
  timelinePlanDetail,
  timelineTimeText,
  timelineEndText,
} from '../../../utils/subsidy-timeline'

defineProps({
  segment: { type: Object, default: null },
  policyCode: { type: String, default: '' },
  configuration: { type: String, required: true },
  at: { type: String, required: true },
  scopeLabel: { type: String, default: '' },
})
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
    <template v-if="segment">
      <div class="model-subsidy-detail-summary">
        <span>
          {{ CHANNEL_LABELS[segment.channel] }} · {{ PLATFORM_LABELS[segment.platformCode] }}
        </span>
        <h3>{{ segment.decision }}</h3>
        <p>{{ configuration }}</p>
        <p v-if="segment.goods">
          {{ segment.scope === 'SPECIFIED' ? '指定商品' : '本段商品' }} ·
          {{ segment.goods.map((g) => g.color + ' #' + g.id).join('、') }}
        </p>
        <p v-else>按机型版本、RAM 和 ROM 匹配</p>
      </div>
      <dl class="model-subsidy-detail-fields">
        <div>
          <dt>组合开始</dt>
          <dd>{{ timelineTimeText(segment.startAt) }}</dd>
        </div>
        <div>
          <dt>组合结束</dt>
          <dd>{{ timelineEndText(segment.endAt) }}</dd>
        </div>
      </dl>
      <p v-if="segment.note" class="model-subsidy-decision-note">{{ segment.note }}</p>
      <div
        v-for="group in [
          { title: '该时点采用', plans: segment.policies },
          { title: '该时点未采用', plans: segment.excluded },
        ]"
        :key="group.title"
      >
        <template v-if="group.plans.length">
          <h3 class="model-subsidy-source-heading">
            {{ group.title }}
            <small>{{ group.plans.length }} 单</small>
          </h3>
          <article
            v-for="plan in group.plans"
            :key="plan.policyCode"
            class="model-subsidy-source"
            :class="{
              muted: group.plans === segment.excluded,
              inspected: plan.policyCode === policyCode,
            }"
          >
            <header>
              <strong>
                {{ plan.policyCode }}
                <span v-if="plan.estimated === true" class="model-subsidy-estimate">预估</span>
              </strong>
              <span :class="plan.subsidyMode.toLowerCase()">{{ timelinePlanText(plan) }}</span>
            </header>
            <p>{{ timelinePlanDetail(plan) }}</p>
            <p v-if="plan.scenes">{{ plan.scenes.join('、') }} · {{ plan.verify }}</p>
            <p>
              原单{{ 'quantity' in plan ? '指定商品' : '普通商品' }} ·
              {{ plan.goods.map((g) => g.color + ' #' + g.id).join('、') }}
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
                  <dd>{{ timelineEndText(plan.usePeriod.endAt) }}</dd>
                </div>
              </template>
              <div v-else>
                <dt>采用情况</dt>
                <dd>此时此范围未采用</dd>
              </div>
              <div>
                <dt>政策日期</dt>
                <dd>{{ plan.policyFrom }} 至 {{ plan.policyThrough }}</dd>
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
                {{ timelineTimeText(period.startAt) }} 至 {{ timelineEndText(period.endAt) }}
              </p>
            </details>
          </article>
        </template>
      </div>
    </template>
    <el-empty
      v-else
      :image-size="50"
      :description="scopeLabel ? scopeLabel + '在该时点暂无适用政策' : '暂无可展示的补贴明细'"
    />
  </section>
</template>
