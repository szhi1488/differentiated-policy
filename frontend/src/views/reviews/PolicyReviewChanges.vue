<script setup>
import { computed, ref } from 'vue'
import { CHANNEL_LABELS, PLATFORM_LABELS, VERIFY_LABELS } from '../../constants/policy'
import { policySceneLabel, policyTime } from '../../utils/policy-adapter'
import { subsidyText } from '../../utils/policy-coverage'
import ScopeProductList from '../apply/components/ScopeProductList.vue'

const props = defineProps({ changes: { type: Array, required: true } })
const page = ref(1)
const pageSize = 8
const visibleChanges = computed(() =>
  props.changes.slice((page.value - 1) * pageSize, page.value * pageSize),
)
const kinds = {
  STACK: '新增叠加',
  REPLACEMENT: '替换政策',
  REMOVE_STACK: '取消叠加',
  ORDINARY_ADDITION: '新增普通政策',
  UNCHANGED: '执行名单沿用',
  REMOVAL: '结束执行',
}
const time = (value) => policyTime(value).replace('T', ' ')
const roundKey = (policy) => `${policy.policyId}/${policy.submissionVersion}`
const roundName = (policy) => `${policy.policyCode}（第 ${policy.submissionVersion} 轮）`
const roundList = (policies) => policies.map(roundName).join('、')
function policyGroups(change) {
  const candidates = change.candidates ?? []
  const groups = [
    { label: '原执行名单', policies: change.before },
    {
      label: candidates.length ? '拟执行名单（申请人已选）' : '拟执行名单',
      policies: change.after,
    },
  ]
  const selected = new Set(change.after.map(roundKey))
  const omitted = candidates.filter((policy) => !selected.has(roundKey(policy)))
  if (omitted.length) {
    groups.push({
      label: '申请人未选的候选政策',
      policies: omitted,
      shownBefore: new Set(change.before.map(roundKey)),
    })
  }
  return groups
}
function scopeLabel(change) {
  const scope = change.scope
  const product = [...change.after, ...change.before, ...(change.candidates ?? [])]
    .flatMap((policy) => policy.selections)
    .flatMap((row) => row.products)
    .find((item) => !scope.goodsId || item.goodsId === scope.goodsId)
  const model =
    product?.modelName || product?.goodsName || (scope.spuId ? '机型 ' + scope.spuId : '')
  const config = [product?.ram || scope.ram, product?.rom || scope.rom].filter(Boolean).join('+')
  return [
    model,
    product?.edition,
    config,
    scope.goodsId ? product?.colorName || '商品 ' + scope.goodsId : '整组配置',
  ]
    .filter(Boolean)
    .join(' · ')
}
</script>

<template>
  <section v-if="changes.length" class="review-changes" aria-label="逐段执行依据">
    <h3>逐段执行依据</h3>
    <article
      v-for="change in visibleChanges"
      :key="JSON.stringify([change.perspective, change.scope, change.from, change.through])"
    >
      <header>
        <strong>
          {{ PLATFORM_LABELS[change.scope.platformCode] ?? change.scope.platformCode }} ·
          {{ CHANNEL_LABELS[change.perspective] }} · {{ scopeLabel(change) }}
        </strong>
        <el-tag size="small" effect="plain">{{ kinds[change.kind] }}</el-tag>
      </header>
      <p class="change-time">
        <time>{{ time(change.from) }}</time>
        至
        <time>{{ time(change.through) }}</time>
      </p>
      <p v-if="change.added.length">加入 · {{ roundList(change.added) }}</p>
      <p v-if="change.removed.length">退出 · {{ roundList(change.removed) }}</p>
      <p v-if="change.retained.length">保留 · {{ roundList(change.retained) }}</p>
      <div class="review-change-sides">
        <section v-for="group in policyGroups(change)" :key="group.label">
          <h4>{{ group.label }}</h4>
          <p v-if="!group.policies.length">无执行政策</p>
          <div v-for="policy in group.policies" :key="roundKey(policy)" class="change-policy">
            <strong>{{ roundName(policy) }}</strong>
            <p v-if="group.shownBefore?.has(roundKey(policy))">补贴与商品见原执行名单</p>
            <template v-else>
              <p v-if="change.perspective === 'RETAIL'">
                {{ policySceneLabel(policy) }} ·
                {{ VERIFY_LABELS[policy.verifyType] ?? '核销类型未记录' }}
              </p>
              <div
                v-for="row in policy.selections"
                :key="JSON.stringify([row.cardType, row.products.map((p) => p.goodsId)])"
                class="change-selection"
              >
                {{ subsidyText(row.subsidy) }}
                <span v-if="row.cardType === 'SPECIFIED'">
                  · 指定数量 {{ row.quantity ?? '未填写' }}
                </span>
                <ScopeProductList :products="row.products" label="适用商品" />
              </div>
            </template>
          </div>
        </section>
      </div>
    </article>
    <el-pagination
      v-if="changes.length > pageSize"
      v-model:current-page="page"
      :page-size="pageSize"
      :total="changes.length"
      :pager-count="5"
      layout="prev, pager, next"
    />
  </section>
</template>

<style scoped>
.review-changes {
  min-width: 0;
  overflow-wrap: anywhere;
}
.review-changes h3 {
  margin: 0;
  font-size: 16px;
}
.review-changes article {
  padding: 16px 0;
  border-top: 1px solid var(--el-border-color-lighter);
}
.review-changes h3 + article {
  margin-top: 10px;
}
.review-changes header {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px 12px;
}
.review-changes p,
.review-changes h4 {
  margin: 3px 0;
}
.change-time {
  color: var(--el-text-color-secondary);
}
time {
  display: inline-block;
}
.review-change-sides {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(250px, 100%), 1fr));
  gap: 10px;
  margin-top: 8px;
}
.review-change-sides section {
  background: var(--el-fill-color-light);
  padding: 10px 14px;
  border-radius: 8px;
}
.change-policy {
  padding-top: 6px;
}
.change-policy + .change-policy {
  border-top: 1px solid var(--el-border-color-light);
  margin-top: 6px;
}
.change-selection {
  padding: 4px 0;
}
</style>
