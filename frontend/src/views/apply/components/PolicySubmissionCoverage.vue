<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { PLATFORM_LABELS } from '../../../constants/policy'
import { groupSubmissionScopes } from '../../../utils/policy-applicability'
import PolicyScopeSegment from './PolicyScopeSegment.vue'
import ScopeProductList from './ScopeProductList.vue'

const props = defineProps({ preview: { type: Object, required: true } })
const emit = defineEmits(['confirm', 'edit'])
const choices = reactive(new Map())
const platform = ref('')
const keyword = ref('')
const resultLabel = ref('')
const resultLabels = computed(() => [
  ...new Set(props.preview.ranges.map((range) => range.resultLabel)),
])
const page = ref(1)
const pageSize = 10
const platforms = computed(() => [
  ...new Set(props.preview.scopes.map((s) => s.scope.platformCode)),
])
const cards = computed(() => groupSubmissionScopes(props.preview.scopes))
const decisionGroups = computed(() => cards.value.flatMap((card) => card.ranges))
const confirmedCount = computed(
  () =>
    decisionGroups.value.filter((range) => range.members.every((member) => choices.has(member.key)))
      .length,
)
const filtered = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  return platform.value || resultLabel.value || search
    ? groupSubmissionScopes(props.preview.scopes, platform.value, resultLabel.value, search)
    : cards.value
})
const visiblePlatforms = computed(() => {
  const grouped = new Map()
  for (const scope of filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize)) {
    const code = scope.scope.platformCode
    if (!grouped.has(code)) {
      grouped.set(code, [])
    }
    grouped.get(code).push(scope)
  }
  return grouped
})
watch([platform, resultLabel, keyword], () => {
  page.value = 1
})
watch(
  () => props.preview,
  () => {
    choices.clear()
    platform.value = ''
    resultLabel.value = ''
    keyword.value = ''
    page.value = 1
  },
)
function choose(members, value) {
  for (const { key } of members) {
    if (value) {
      choices.set(key, value)
    } else {
      choices.delete(key)
    }
  }
}
function groupChoice(members) {
  const first = choices.get(members[0].key)
  return members.every(({ key }) => JSON.stringify(choices.get(key)) === JSON.stringify(first))
    ? first
    : ''
}
function selectRetail(members, groupIds, id, checked) {
  for (const member of members) {
    const previous = choices.get(member.key) || []
    const selected = checked
      ? [...previous.filter((value) => !groupIds.includes(value)), id].sort()
      : previous.filter((value) => value !== id)
    if (selected.length) {
      choices.set(member.key, selected)
    } else {
      choices.delete(member.key)
    }
  }
}
function confirm() {
  if (choices.size !== props.preview.ranges.length) {
    return
  }
  const decisions = props.preview.ranges.map(({ key, scope, through }) => {
    const choice = choices.get(key)
    if (Array.isArray(choice)) {
      return { scope, through, choice: 'SELECT', selectedPolicyIds: choice }
    }
    return choice === 'STACK'
      ? { scope, through, choice }
      : { scope, through, choice: 'CHOOSE_ONE', policyId: choice }
  })
  emit('confirm', decisions)
}
</script>

<template>
  <el-dialog
    :model-value="true"
    title="确认政策适用范围"
    class="submission-coverage-dialog"
    width="min(1000px, calc(100% - 20px))"
    align-center
    :close-on-click-modal="false"
    @update:model-value="emit('edit')"
  >
    <el-alert
      v-if="preview.changed"
      title="适用范围已变化，请按最新结果重新确认各时段。"
      type="warning"
      :closable="false"
      show-icon
    />
    <p class="scope-instructions">
      普通商品按机型和配置汇总。每次确认仅适用于所示商品和交叉时段。以下均为北京时间，起止时刻均有效。
    </p>
    <div class="scope-filters">
      <label>
        平台
        <el-select v-model="platform" aria-label="筛选平台" placeholder="全部平台" clearable>
          <el-option
            v-for="code in platforms"
            :key="code"
            :value="code"
            :label="PLATFORM_LABELS[code]"
          />
        </el-select>
      </label>
      <label>
        判定结果
        <el-select v-model="resultLabel" aria-label="筛选判定结果" placeholder="全部结果" clearable>
          <el-option v-for="label in resultLabels" :key="label" :value="label" :label="label" />
        </el-select>
      </label>
      <label>
        商品或单号
        <el-input
          v-model="keyword"
          aria-label="覆盖商品关键词"
          placeholder="机型、配置、颜色、商品 ID 或政策单号"
          clearable
        />
      </label>
    </div>
    <section v-for="[code, scopes] in visiblePlatforms" :key="code" class="scope-platform">
      <h3>{{ PLATFORM_LABELS[code] || code }}</h3>
      <div v-for="scope in scopes" :key="scope.key" class="scope-card">
        <header class="scope-heading">
          <strong>{{ scope.title }}</strong>
          <ScopeProductList v-if="scope.commonConfiguration" :products="scope.products" />
          <span v-else>{{ scope.target }}</span>
        </header>
        <PolicyScopeSegment
          v-for="range in scope.ranges"
          :key="range.key"
          :range="range"
          :common-configuration="Boolean(scope.commonConfiguration)"
          :show-subset="
            Boolean(scope.commonConfiguration) && range.members.length < scope.products.length
          "
          :choice="groupChoice(range.members)"
          :retail-selections="range.members.map((member) => choices.get(member.key) || [])"
          :partly-confirmed="
            range.members.some((member) => choices.has(member.key)) && !groupChoice(range.members)
          "
          @choose="choose(range.members, $event)"
          @select-retail="
            (groupIds, id, checked) => selectRetail(range.members, groupIds, id, checked)
          "
        />
      </div>
    </section>
    <el-empty v-if="!filtered.length" description="没有符合条件的交叉时段" :image-size="48" />
    <el-pagination
      v-if="filtered.length > pageSize"
      v-model:current-page="page"
      :page-size="pageSize"
      :total="filtered.length"
      :pager-count="5"
      layout="prev, pager, next"
      size="small"
    />
    <template #footer>
      <div class="scope-footer">
        <span aria-live="polite">
          整单已确认
          <strong>{{ confirmedCount }} / {{ decisionGroups.length }}</strong>
          项
        </span>
        <div>
          <el-button @click="emit('edit')">返回编辑</el-button>
          <el-button
            type="primary"
            :disabled="choices.size !== preview.ranges.length"
            @click="confirm"
          >
            确认整单并提交
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.scope-instructions {
  margin: 0 0 12px;
  color: var(--policy-muted);
  line-height: 1.6;
  font-size: 13px;
}
.scope-filters {
  display: grid;
  grid-template-columns: minmax(100px, 1fr) minmax(120px, 1fr) minmax(150px, 2fr);
  gap: 12px;
  padding: 12px;
  background: #f5f8fc;
  border-radius: 6px;
}
.scope-filters label {
  display: grid;
  gap: 5px;
  min-width: 0;
  color: var(--policy-muted);
  font-size: 12px;
}
.scope-platform h3 {
  margin: 16px 0 8px;
  color: #1d4778;
  font-size: 16px;
}
.scope-card {
  border: 1px solid var(--policy-line);
  border-radius: 8px;
  overflow: hidden;
  margin: 8px 0 14px;
}
.scope-heading,
.scope-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 4px 12px;
}
.scope-heading {
  padding: 10px 18px;
  background: #edf4fc;
  color: #173a65;
  overflow-wrap: anywhere;
}
.scope-heading span,
.scope-footer span {
  color: var(--policy-muted);
  font-size: 13px;
}
.scope-footer strong {
  color: #173a65;
}
@media (max-width: 660px) {
  .scope-filters {
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }
  .scope-filters label:last-child {
    grid-column: 1/-1;
  }
  .scope-heading {
    padding: 9px 12px;
  }
  .scope-footer {
    gap: 8px;
  }
}
</style>
