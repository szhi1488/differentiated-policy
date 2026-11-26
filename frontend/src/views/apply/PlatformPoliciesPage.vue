<script setup>
import { ref } from 'vue'
import WorkspacePageHeading from '../../components/WorkspacePageHeading.vue'
import PolicySubmissionContent from './components/PolicySubmissionContent.vue'
import { usePolicySubmissions } from '../../composables/usePolicySubmissions'
import { useLatestQuery } from '../../composables/useLatestQuery'
import {
  listSharedPolicies,
  readMemberPlatforms,
  readSharedPolicy,
  sharedPolicyAttachmentUrl,
} from '../../api/policies'
import {
  CHANNEL_LABELS,
  PLATFORM_LABELS,
  POLICY_STATUS_LABELS,
  policyStatusLabel,
} from '../../constants/policy'
const { data, loading, error, filters, setFilter, refresh } =
  usePolicySubmissions(listSharedPolicies)
const scopes = useLatestQuery()
const detail = useLatestQuery()
void scopes.run(readMemberPlatforms)
const selected = ref(null)
function open(row) {
  selected.value = row
  void detail.run((signal) => readSharedPolicy(row.id, signal))
}
function chooseScope(key) {
  const [channel = '', platform = ''] = (key || '').split('/')
  setFilter('perspective', channel)
  setFilter('platformCode', platform)
}
</script>
<template>
  <section class="platform-policies">
    <header class="workspace-page-banner">
      <WorkspacePageHeading title="平台政策" icon="document" />
    </header>
    <section class="panel">
      <div class="filters">
        <el-select
          clearable
          placeholder="全部负责平台"
          aria-label="负责平台"
          :model-value="
            filters.platformCode ? filters.perspective + '/' + filters.platformCode : ''
          "
          @update:model-value="chooseScope"
        >
          <el-option
            v-for="s in scopes.data.value ?? []"
            :key="s.perspective + '/' + s.platformCode"
            :value="s.perspective + '/' + s.platformCode"
            :label="CHANNEL_LABELS[s.perspective] + ' · ' + PLATFORM_LABELS[s.platformCode]"
          />
        </el-select>
        <el-select
          clearable
          placeholder="全部状态"
          aria-label="政策状态"
          :model-value="filters.status"
          @update:model-value="setFilter('status', $event || '')"
        >
          <el-option
            v-for="status in ['PENDING', 'APPROVED', 'REJECTED']"
            :key="status"
            :value="status"
            :label="POLICY_STATUS_LABELS[status]"
          />
        </el-select>
        <el-button @click="refresh">刷新</el-button>
      </div>
      <el-alert
        v-if="scopes.error.value"
        :title="scopes.error.value.message"
        type="error"
        :closable="false"
      >
        <el-button link @click="scopes.run(readMemberPlatforms)">重试平台</el-button>
      </el-alert>
      <el-alert v-if="error" :title="error.message" type="error" :closable="false">
        <el-button link @click="refresh">重试</el-button>
      </el-alert>
      <el-table
        v-else
        v-loading="loading"
        :data="data?.items ?? []"
        row-key="id"
        empty-text="暂无负责平台的已提交政策"
      >
        <el-table-column label="政策单号" prop="policyCode" min-width="130" />
        <el-table-column label="渠道">
          <template #default="{ row }">{{ CHANNEL_LABELS[row.perspective] }}</template>
        </el-table-column>
        <el-table-column label="涉及平台" min-width="140">
          <template #default="{ row }">
            {{ row.selectedPlatforms?.map((p) => PLATFORM_LABELS[p] ?? p).join('、') }}
          </template>
        </el-table-column>
        <el-table-column label="状态">
          <template #default="{ row }">{{ policyStatusLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="85">
          <template #default="{ row }">
            <el-button link type="primary" @click="open(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="data?.total > 20"
        v-model:current-page="filters.page"
        :page-size="20"
        :total="data.total"
        layout="prev,pager,next"
      />
    </section>
    <el-drawer
      :model-value="!!selected"
      :title="selected?.policyCode + ' · 正式提交内容'"
      size="min(720px,100%)"
      @close="selected = null"
    >
      <p v-if="detail.loading.value">正在读取政策…</p>
      <el-alert
        v-else-if="detail.error.value"
        :title="detail.error.value.message"
        type="error"
        :closable="false"
      >
        <el-button link @click="open(selected)">重试</el-button>
      </el-alert>
      <PolicySubmissionContent
        v-else-if="detail.data.value && selected"
        :detail="detail.data.value"
        :attachment-url="
          (id) =>
            sharedPolicyAttachmentUrl(detail.data.value.id, detail.data.value.submissionVersion, id)
        "
        @retry-progress="open(selected)"
      />
    </el-drawer>
  </section>
</template>
<style scoped>
.platform-policies {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
}
.panel {
  min-width: 0;
  padding: 18px;
  background: white;
  border: 1px solid #dfe7f3;
  border-radius: 10px;
}
.filters {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.filters .el-select {
  width: 175px;
}
</style>
