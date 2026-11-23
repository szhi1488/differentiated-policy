<script setup>
import { computed } from 'vue'
import CoverageComparison from './CoverageComparison.vue'
import ScopeProductList from './ScopeProductList.vue'
import { SCENE_LABELS, VERIFY_LABELS } from '../../../constants/policy'
import { subsidyText } from '../../../utils/policy-coverage'

const props = defineProps({
  range: { type: Object, required: true },
  commonConfiguration: Boolean,
  showSubset: Boolean,
  partlyConfirmed: Boolean,
  choice: { type: [String, Array], default: '' },
  retailSelections: { type: Array, required: true },
})
const emit = defineEmits(['choose', 'selectRetail'])
const codes = (ids) => ids.map((id) => props.range.byId.get(id).policyCode).join('、')
const checkedCounts = computed(() => {
  const counts = new Map()
  for (const selection of props.retailSelections) {
    for (const id of selection) {
      counts.set(id, (counts.get(id) || 0) + 1)
    }
  }
  return counts
})
// 零售每个范围对应一个具体商品和一个平台方案，可直接比较原补贴和本次补贴。
const proposedPlan = computed(() => {
  const pending = props.range.participants.filter((policy) => policy.proposed)
  return props.range.scope.goodsId && pending.length === 1
    ? pending[0].selections[0]?.subsidy
    : null
})
</script>

<template>
  <article class="scope-segment">
    <header class="segment-heading">
      <h4>
        <time>{{ range.fromText }}</time>
        至
        <time>{{ range.throughText }}</time>
      </h4>
      <el-tag size="small" :type="range.groups.length === 1 ? 'warning' : 'primary'" effect="plain">
        {{ range.resultLabel }}
      </el-tag>
    </header>
    <ScopeProductList
      v-if="showSubset"
      label="本段适用颜色"
      :products="range.members.map((member) => member.product)"
    />
    <div class="segment-policies">
      <section v-for="policy in range.participants" :key="policy.policyId" class="segment-policy">
        <h5>
          <span>
            {{
              policy.proposed
                ? policy.policyId.startsWith('extension:')
                  ? '拟延期'
                  : '本次申请'
                : '已通过'
            }}
          </span>
          {{ policy.policyCode }}
        </h5>
        <div v-for="(row, index) in policy.selections" :key="index" class="selection">
          <strong>{{ subsidyText(row.subsidy) }}</strong>
          <p v-if="row.cardType === 'SPECIFIED'">
            指定适用机型 ·
            {{
              row.products
                .map((p) => p.colorName || '颜色未记录（商品 ' + p.goodsId + '）')
                .join('、')
            }}
            · 数量
            {{ row.quantity ?? '未填写' }}
          </p>
          <ScopeProductList
            v-else-if="!commonConfiguration"
            label="适用颜色"
            :products="row.products"
          />
          <CoverageComparison
            v-if="!policy.proposed && row.subsidy && proposedPlan"
            :previous="row.subsidy"
            :proposed="proposedPlan"
          />
        </div>
      </section>
    </div>
    <div class="segment-decision">
      <span v-if="partlyConfirmed" class="partial-choice">
        本段商品的勾选结果不同，请核对本段选择。
      </span>
      <template v-if="range.action === 'SELECT_RETAIL_POLICIES'">
        <p>勾选本段执行的政策。同组最多选一张，跨组选多张可叠加。</p>
        <div class="segment-groups">
          <fieldset
            v-for="group in range.groups"
            :key="group.sceneTypes.join('+') + '/' + group.verifyType"
          >
            <legend>
              {{ group.sceneTypes.map((scene) => SCENE_LABELS[scene]).join('＋') }} ·
              {{ VERIFY_LABELS[group.verifyType] }}
            </legend>
            <el-checkbox
              v-for="id in group.policyIds"
              :key="id"
              :value="id"
              :model-value="checkedCounts.get(id) === range.members.length"
              :indeterminate="checkedCounts.has(id) && checkedCounts.get(id) < range.members.length"
              @change="emit('selectRetail', group.policyIds, id, $event)"
            >
              {{ range.byId.get(id).policyCode }}
            </el-checkbox>
          </fieldset>
        </div>
        <strong v-if="choice.length" aria-live="polite">
          {{ choice.length === 1 ? '本段仅执行' : '本段叠加执行' }} {{ codes(choice) }}
        </strong>
      </template>
      <template v-else>
        <el-radio-group
          :model-value="choice"
          :aria-label="range.fromText + ' 至 ' + range.throughText + ' 的适用决定'"
          @change="emit('choose', $event)"
        >
          <el-radio value="STACK">同意叠加 {{ codes(range.eligiblePolicyIds) }}</el-radio>
          <el-radio
            v-for="policy in range.eligible"
            :key="policy.policyId"
            :value="policy.policyId"
          >
            仅适用 {{ policy.policyCode }}
          </el-radio>
        </el-radio-group>
      </template>
    </div>
  </article>
</template>

<style scoped>
.scope-segment {
  padding: 16px 18px;
  border-top: 1px solid var(--policy-line);
  color: #243852;
}
.segment-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px 12px;
}
.segment-heading h4 {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
  font-variant-numeric: tabular-nums;
}
.segment-heading time {
  display: inline-block;
}
.segment-policies {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(235px, 100%), 1fr));
  gap: 8px;
  margin: 12px 0;
}
.segment-policy {
  min-width: 0;
  padding: 10px 12px;
  border-radius: 6px;
  background: #f5f8fc;
  overflow-wrap: anywhere;
}
.segment-policy h5 {
  margin: 0 0 6px;
  font-size: 13px;
}
.segment-policy h5 span {
  color: var(--policy-muted);
  font-weight: 500;
  margin-right: 4px;
}
.selection {
  font-size: 13px;
  line-height: 1.65;
}
.selection + .selection {
  border-top: 1px solid var(--policy-line);
  margin-top: 6px;
  padding-top: 6px;
}
.selection p,
.partial-choice {
  margin: 3px 0 0;
  color: var(--policy-muted);
}
.segment-groups,
.segment-decision p {
  width: 100%;
  margin: 0;
}
.segment-groups fieldset {
  display: flex;
  flex-wrap: wrap;
  column-gap: 16px;
  border: 0;
  margin: 4px 0;
  padding: 0;
  min-width: 0;
}
.segment-groups legend {
  font-weight: 600;
}
.segment-decision {
  font-size: 13px;
  overflow-wrap: anywhere;
}
.segment-decision {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 12px;
}
.segment-decision :deep(.el-radio-group) {
  gap: 4px 14px;
}
.segment-decision :deep(.el-radio),
.segment-decision :deep(.el-checkbox) {
  white-space: normal;
  margin: 0;
  height: auto;
  min-height: 34px;
  max-width: 100%;
}
.segment-decision :deep(.el-radio__label),
.segment-decision :deep(.el-checkbox__label) {
  line-height: 1.6;
  overflow-wrap: anywhere;
}
@media (max-width: 660px) {
  .scope-segment {
    padding: 12px;
  }
  .segment-decision :deep(.el-radio-group) {
    display: grid;
  }
  .segment-decision :deep(.el-radio),
  .segment-decision :deep(.el-checkbox),
  .segment-decision :deep(.el-button) {
    min-height: 40px;
  }
}
</style>
