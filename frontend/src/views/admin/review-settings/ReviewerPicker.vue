<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { listReviewerCandidates } from '../../../api/policy-review-settings'
import { useLatestQuery } from '../../../composables/useLatestQuery'

const props = defineProps({
  group: { type: String, required: true },
  modelValue: { type: Array, required: true },
  disabled: Boolean,
})
const emit = defineEmits(['update:modelValue'])
const label = computed(() => (props.group === 'PRODUCT' ? '产品运营审核人' : '采购审核人'))
const open = ref(false)
const page = ref(1)
const pageSize = 10
const filters = reactive({ name: '', department: '' })
const applied = ref({ ...filters })
const { data, error, loading, run } = useLatestQuery()
const selectedIds = computed(() => new Set(props.modelValue.map((person) => person.id)))

function load() {
  return run((signal) =>
    listReviewerCandidates(
      { reviewGroup: props.group, ...applied.value, page: page.value, pageSize },
      signal,
    ),
  )
}
watch([open, page, applied], () => {
  if (open.value) {
    void load()
  }
})
function search() {
  page.value = 1
  applied.value = { name: filters.name.trim(), department: filters.department.trim() }
}
function select(person, checked) {
  if (props.disabled || checked === selectedIds.value.has(person.id)) {
    return
  }
  emit(
    'update:modelValue',
    checked
      ? [...props.modelValue, person]
      : props.modelValue.filter((item) => item.id !== person.id),
  )
}
</script>

<template>
  <section class="reviewer-picker" :aria-label="label">
    <div class="picker-heading">
      <h3>
        {{ label }}
        <small>{{ modelValue.length }} 人</small>
      </h3>
      <el-button size="small" :disabled="disabled" :aria-expanded="open" @click="open = !open">
        {{ open ? '收起选择' : '选择人员' }}
      </el-button>
    </div>
    <ul v-if="modelValue.length" class="picked-people" :aria-label="`已选${label}`">
      <li v-for="person in modelValue" :key="person.id">
        <span>{{ person.name }} · {{ person.department || '部门未填写' }}</span>
        <button
          type="button"
          :aria-label="`移除 ${person.name} · ${person.department || '部门未填写'} · ID ${person.id}`"
          :disabled="disabled"
          @click="select(person, false)"
        >
          ×
        </button>
      </li>
    </ul>
    <p v-else class="picker-empty">尚未选择人员</p>
    <div v-if="open" class="picker-search">
      <form @submit.prevent="search">
        <el-input
          v-model="filters.name"
          aria-label="姓名"
          placeholder="姓名"
          maxlength="128"
          clearable
        />
        <el-input
          v-model="filters.department"
          aria-label="部门"
          placeholder="部门"
          maxlength="128"
          clearable
        />
        <el-button native-type="submit">查询</el-button>
      </form>
      <p v-if="loading" role="status">正在查找人员…</p>
      <el-alert v-else-if="error" :title="error.message" type="error" :closable="false">
        <el-button link @click="load">重新查询</el-button>
      </el-alert>
      <template v-else-if="data">
        <p v-if="!data.items.length" role="status">没有符合条件的人员</p>
        <ul v-else class="candidate-rows" :aria-label="`${label}候选`">
          <li v-for="person in data.items" :key="person.id">
            <el-checkbox
              :model-value="selectedIds.has(person.id)"
              :disabled="disabled"
              @change="select(person, $event)"
            >
              <strong>{{ person.name }}</strong>
              · {{ person.department || '部门未填写' }}
              <small>ID {{ person.id }}</small>
            </el-checkbox>
          </li>
        </ul>
        <el-pagination
          v-if="data.total > pageSize"
          v-model:current-page="page"
          size="small"
          layout="prev, pager, next"
          :pager-count="5"
          :page-size="pageSize"
          :total="data.total"
        />
      </template>
    </div>
  </section>
</template>

<style scoped>
.reviewer-picker {
  min-width: 0;
  color: #243a58;
}
.picker-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.picker-heading h3 {
  margin: 0;
  font-size: 16px;
}
.picker-heading small,
.candidate-rows small {
  font-size: 12px;
  font-weight: 400;
  color: #526581;
  margin-left: 6px;
}
.picked-people,
.candidate-rows {
  list-style: none;
  padding: 0;
}
.picked-people {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px 0 0;
}
.picked-people li {
  display: flex;
  align-items: center;
  gap: 5px;
  max-width: 100%;
  padding: 3px 4px 3px 8px;
  background: #eff5ff;
  border-radius: 5px;
  color: #315b94;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.picked-people button {
  border: 0;
  color: inherit;
  font: inherit;
  font-size: 18px;
  cursor: pointer;
  background: transparent;
}
.picker-empty,
.picker-search p {
  color: #60728a;
  font-size: 14px;
}
.picker-search {
  margin-top: 12px;
}
.picker-search form {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 8px;
}
.candidate-rows {
  max-height: 255px;
  overflow-y: auto;
  margin: 10px 0;
}
.candidate-rows li {
  padding: 3px 2px;
  border-bottom: 1px solid #edf1f7;
}
.candidate-rows :deep(.el-checkbox) {
  height: auto;
  min-height: 30px;
  white-space: normal;
}
.candidate-rows :deep(.el-checkbox__label) {
  overflow-wrap: anywhere;
}
</style>
