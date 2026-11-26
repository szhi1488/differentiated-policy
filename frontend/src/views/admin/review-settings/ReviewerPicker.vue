<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { listReviewerCandidates } from '../../../api/policy-review-settings'
import { useLatestQuery } from '../../../composables/useLatestQuery'

const props = defineProps({
  group: { type: String, required: true },
  title: { type: String, required: true },
  modelValue: { type: Object, default: null },
})
const emit = defineEmits(['select', 'close'])
const roleName = computed(() => (props.group === 'PRODUCT' ? '产品运营' : '采购'))
const dialogTitle = computed(() =>
  props.group === 'PRODUCT' ? '选择产品运营初审人' : '选择采购终审人',
)
const selected = ref(props.modelValue)
const page = ref(1)
const pageSize = 10
const filters = reactive({ name: '', department: '' })
const applied = ref({ ...filters })
const { data, error, loading, run } = useLatestQuery()

function load() {
  return run((signal) =>
    listReviewerCandidates(
      { reviewGroup: props.group, ...applied.value, page: page.value, pageSize },
      signal,
    ),
  )
}
watch([page, applied], load, { immediate: true })

function search() {
  page.value = 1
  applied.value = { name: filters.name.trim(), department: filters.department.trim() }
}

function choose(personId) {
  selected.value = data.value.items.find((person) => person.id === personId)
}
</script>

<template>
  <el-dialog
    :model-value="true"
    :title="dialogTitle"
    class="reviewer-picker-dialog"
    width="min(540px, calc(100% - 24px))"
    align-center
    append-to-body
    @update:model-value="!$event && emit('close')"
  >
    <template #header="{ titleId, titleClass }">
      <h2 :id="titleId" :class="titleClass">{{ dialogTitle }}</h2>
      <p class="picker-context">{{ title }}</p>
    </template>

    <form class="picker-filters" aria-label="查找审核人" @submit.prevent="search">
      <el-input
        v-model="filters.name"
        aria-label="姓名"
        placeholder="输入姓名"
        maxlength="128"
        clearable
      />
      <el-input
        v-model="filters.department"
        aria-label="部门"
        placeholder="输入部门"
        maxlength="128"
        clearable
      />
      <el-button native-type="submit">查询</el-button>
    </form>

    <p v-if="loading" class="picker-status" role="status">正在查找人员…</p>
    <el-alert v-else-if="error" :title="error.message" type="error" :closable="false" show-icon>
      <el-button link @click="load">重新查询</el-button>
    </el-alert>
    <template v-else-if="data">
      <p v-if="!data.items.length" class="picker-status" role="status">
        暂无符合条件的{{ roleName }}人员
      </p>
      <el-radio-group
        v-else
        :model-value="selected?.id"
        class="candidate-rows"
        aria-label="候选审核人"
        @update:model-value="choose"
      >
        <el-radio
          v-for="person in data.items"
          :key="person.id"
          :value="person.id"
          class="candidate-row"
        >
          <span class="candidate-identity">
            <strong>{{ person.name }}</strong>
            <span>{{ person.department || '部门未填写' }}</span>
          </span>
          <small class="candidate-id">ID {{ person.id }}</small>
        </el-radio>
      </el-radio-group>
      <el-pagination
        v-if="data.total > pageSize"
        v-model:current-page="page"
        class="picker-pagination"
        size="small"
        layout="prev, pager, next"
        :pager-count="5"
        :page-size="pageSize"
        :total="data.total"
      />
    </template>

    <div class="picker-selection" aria-live="polite">
      <span class="selection-label">当前选择</span>
      <span v-if="selected" class="selection-name">
        {{ selected.name }} · {{ selected.department || '部门未填写' }}
        <small>ID {{ selected.id }}</small>
      </span>
      <span v-else>未指定</span>
      <el-button v-if="selected" type="primary" link @click="selected = null">清除选择</el-button>
    </div>

    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" @click="emit('select', selected)">确认选择</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
:global(.reviewer-picker-dialog.el-dialog) {
  --el-dialog-padding-primary: 20px;
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - 24px);
  border-radius: 12px;
}

:global(.reviewer-picker-dialog .el-dialog__body) {
  min-height: 0;
  overflow-y: auto;
}

:global(.reviewer-picker-dialog .el-dialog__title) {
  margin: 0;
  color: #203651;
  font-size: 18px;
  font-weight: 650;
}

.picker-context {
  margin: 4px 0 0;
  color: #596c84;
  font-size: 13px;
  overflow-wrap: anywhere;
}

.picker-filters {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
  gap: 8px;
  margin-bottom: 14px;
}

.candidate-rows {
  display: flex;
  flex-flow: column nowrap;
  align-items: stretch;
  width: 100%;
  max-height: min(340px, 40dvh);
  overflow-y: auto;
  border: 1px solid #e1e7ef;
  border-radius: 8px;
}

.candidate-row {
  flex: none;
  height: auto;
  min-height: 48px;
  margin: 0;
  padding: 8px 12px;
  white-space: normal;
}

.candidate-row + .candidate-row {
  border-top: 1px solid #eef1f6;
}

.candidate-row:hover,
.candidate-row:focus-within,
.candidate-row.is-checked {
  background: #f2f7ff;
}

.candidate-row :deep(.el-radio__label) {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 4px 12px;
  min-width: 0;
}

.candidate-identity,
.selection-name {
  overflow-wrap: anywhere;
}

.candidate-identity strong {
  color: #223751;
}

.candidate-identity span,
.candidate-id,
.selection-name small {
  color: #596c84;
  font-size: 12px;
}

.candidate-identity span {
  margin-left: 8px;
}

.candidate-id,
.selection-name small {
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}

.picker-pagination {
  justify-content: flex-end;
  margin-top: 8px;
}

.picker-status {
  padding: 26px 12px;
  color: #596c84;
  text-align: center;
}

.picker-selection {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 10px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #e1e7ef;
  color: #223751;
  font-size: 13px;
}

.selection-label {
  color: #596c84;
}

.picker-selection .el-button {
  margin-left: auto;
}

@media (max-width: 600px) {
  :global(.reviewer-picker-dialog.el-dialog) {
    --el-dialog-padding-primary: 16px;
  }

  .candidate-id {
    width: 100%;
  }
}
</style>
