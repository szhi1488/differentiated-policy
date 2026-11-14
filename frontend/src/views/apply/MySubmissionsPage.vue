<script setup>
import { inject, ref } from 'vue'
import { useRouter } from 'vue-router'
import { usePolicySubmissions } from '../../composables/usePolicySubmissions'
import {
  CHANNEL_LABELS,
  PLATFORM_LABELS,
  POLICY_STATUS_LABELS,
  POLICY_STATUS_TYPES,
  policyIsEditable,
} from '../../constants/policy'
import { policyTime } from '../../utils/policy-adapter'
import PolicySubmissionDrawer from './components/PolicySubmissionDrawer.vue'

const router = useRouter()
const { busy, confirming, preparePolicyEdit } = inject('policyForm')
const { filters, data, error, loading, refresh, setFilter } = usePolicySubmissions()
const selected = ref(null)
const detailOpen = ref(false)
const filterOptions = [
  { key: 'status', label: '状态', options: POLICY_STATUS_LABELS },
  { key: 'perspective', label: '渠道', options: CHANNEL_LABELS },
  { key: 'platformCode', label: '整单平台', options: PLATFORM_LABELS },
]

function openDetail(row) {
  selected.value = row
  detailOpen.value = true
}

async function edit(row) {
  if (await preparePolicyEdit(row.id, row.recordVersion)) {
    await router.push({ name: 'apply', query: { policyId: row.id } })
  }
}
</script>

<template>
  <section class="submissions-panel" aria-labelledby="submissions-title">
    <header class="submissions-header">
      <h2 id="submissions-title">我的提交</h2>
      <el-button @click="refresh">刷新</el-button>
    </header>

    <div class="submissions-filters">
      <el-select
        v-for="{ key, label, options } in filterOptions"
        :key="key"
        :model-value="filters[key]"
        :aria-label="label"
        :placeholder="`全部${label}`"
        clearable
        @update:model-value="setFilter(key, $event)"
      >
        <el-option v-for="(name, value) in options" :key="value" :label="name" :value="value" />
      </el-select>
    </div>

    <p v-if="loading" class="submissions-message" role="status">正在读取申请…</p>
    <el-alert v-else-if="error" type="error" :closable="false" show-icon :title="error.message" />
    <el-empty v-else-if="!data?.items.length" description="没有符合条件的申请" :image-size="90" />
    <template v-else>
      <el-table :data="data.items" row-key="id">
        <el-table-column prop="policyCode" label="申请单号" min-width="260" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="POLICY_STATUS_TYPES[row.status]">
              {{ POLICY_STATUS_LABELS[row.status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="渠道" width="110">
          <template #default="{ row }">{{ CHANNEL_LABELS[row.perspective] }}</template>
        </el-table-column>
        <el-table-column label="整单平台" min-width="135">
          <template #default="{ row }">
            {{
              row.selectedPlatforms.map((code) => PLATFORM_LABELS[code] ?? code).join('、') || '—'
            }}
          </template>
        </el-table-column>
        <el-table-column label="提交轮次" width="95">
          <template #default="{ row }">{{ row.submissionVersion || '—' }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="174">
          <template #default="{ row }">{{ policyTime(row.updatedAt).replace('T', ' ') }}</template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="137">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="policyIsEditable(row.status)"
              link
              type="primary"
              :disabled="busy || confirming"
              @click="edit(row)"
            >
              继续编辑
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <footer class="submissions-footer">
        <span>共 {{ data.total }} 条</span>
        <el-pagination
          v-if="data.total > data.pageSize"
          v-model:current-page="filters.page"
          :page-size="data.pageSize"
          :total="data.total"
          :pager-count="5"
          layout="prev, pager, next"
        />
      </footer>
    </template>

    <el-drawer
      v-if="selected"
      v-model="detailOpen"
      title="申请详情"
      class="policy-submission-drawer"
      size="min(720px, 100%)"
      destroy-on-close
    >
      <PolicySubmissionDrawer
        :key="selected.id"
        :id="selected.id"
        :edit-disabled="busy || confirming"
        @edit="edit"
      />
    </el-drawer>
  </section>
</template>

<style scoped>
.submissions-panel {
  padding: clamp(14px, 2vw, 24px);
  min-width: 0;
  min-height: 400px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background: #fff;
  overflow: hidden;
}
.submissions-header,
.submissions-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.submissions-header h2 {
  margin: 0;
  font-size: 18px;
}
.submissions-filters {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(94px, 156px));
  gap: 8px;
  margin: 18px 0;
}
.submissions-footer,
.submissions-message {
  margin-top: 14px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
