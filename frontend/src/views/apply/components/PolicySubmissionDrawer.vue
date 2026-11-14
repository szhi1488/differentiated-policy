<script setup>
import { ref, watch } from 'vue'
import { usePolicySubmissionDetail } from '../../../composables/usePolicySubmissions'
import {
  POLICY_STATUS_LABELS,
  POLICY_STATUS_TYPES,
  policyIsEditable,
} from '../../../constants/policy'
import { policyTime } from '../../../utils/policy-adapter'
import PolicySubmissionContent from './PolicySubmissionContent.vue'

const props = defineProps({
  id: { type: String, required: true },
  editDisabled: { type: Boolean, default: false },
})
defineEmits(['edit'])

const tab = ref('detail')
const historyPage = ref(1)
const logId = ref(null)
const { detail, history, snapshot, readDetail, readHistory, readSnapshot } =
  usePolicySubmissionDetail(props.id)
const { data: application, loading: detailLoading, error: detailError } = detail
const { data: logs, loading: historyLoading, error: historyError } = history
const { data: round, loading: snapshotLoading, error: snapshotError } = snapshot
const auditStatus = (status) => (status === 'SUBMITTED' ? 'PENDING' : status)
const time = (value) => policyTime(value).replace('T', ' ') || '—'

void readDetail()
watch([tab, historyPage], ([active, page]) => {
  if (active === 'history') {
    void readHistory(page)
  }
})

function openSnapshot(id) {
  logId.value = id
  void readSnapshot(id)
}
</script>

<template>
  <el-tabs v-model="tab">
    <el-tab-pane name="detail" label="申请内容">
      <p v-if="detailLoading" role="status">正在读取申请…</p>
      <template v-else-if="detailError">
        <el-alert type="error" :title="detailError.message" :closable="false" show-icon />
        <el-button class="retry" @click="readDetail">重试</el-button>
      </template>
      <template v-else-if="application">
        <header class="submission-heading">
          <strong>{{ application.policyCode }}</strong>
          <el-tag :type="POLICY_STATUS_TYPES[application.status]">
            {{ POLICY_STATUS_LABELS[application.status] }}
          </el-tag>
          <el-button
            v-if="policyIsEditable(application.status)"
            type="primary"
            :disabled="editDisabled"
            @click="$emit('edit', application)"
          >
            继续编辑
          </el-button>
        </header>
        <PolicySubmissionContent :detail="application" />
      </template>
    </el-tab-pane>

    <el-tab-pane name="history" label="历轮审核">
      <template v-if="logId">
        <el-button link type="primary" @click="logId = null">返回历轮审核</el-button>
        <p v-if="snapshotLoading" role="status">正在读取提交快照…</p>
        <template v-else-if="snapshotError">
          <el-alert type="error" :title="snapshotError.message" :closable="false" show-icon />
          <el-button class="retry" @click="readSnapshot(logId)">重试</el-button>
        </template>
        <template v-else-if="round">
          <header class="submission-heading">
            <h3>第 {{ round.log.submissionVersion }} 轮提交快照</h3>
            <el-tag :type="POLICY_STATUS_TYPES[auditStatus(round.log.auditStatus)]">
              {{ POLICY_STATUS_LABELS[auditStatus(round.log.auditStatus)] }}
            </el-tag>
          </header>
          <dl class="audit-meta">
            <div>
              <dt>提交时间</dt>
              <dd>{{ time(round.log.submittedAt) }}</dd>
            </div>
            <div>
              <dt>审核人</dt>
              <dd>{{ round.log.auditorName ?? '—' }}</dd>
            </div>
            <div>
              <dt>审核时间</dt>
              <dd>{{ time(round.log.auditTime) }}</dd>
            </div>
            <div>
              <dt>审核意见</dt>
              <dd>{{ round.log.auditComment ?? '—' }}</dd>
            </div>
          </dl>
          <PolicySubmissionContent :detail="round.snapshot" />
        </template>
      </template>
      <template v-else>
        <p v-if="historyLoading" role="status">正在读取审核记录…</p>
        <template v-else-if="historyError">
          <el-alert type="error" :title="historyError.message" :closable="false" show-icon />
          <el-button class="retry" @click="readHistory(historyPage)">重试</el-button>
        </template>
        <el-empty
          v-else-if="logs && !logs.items.length"
          description="暂无提交记录"
          :image-size="80"
        />
        <template v-else-if="logs">
          <ol class="audit-list">
            <li v-for="log in logs.items" :key="log.id">
              <header class="submission-heading">
                <h3>第 {{ log.submissionVersion }} 轮</h3>
                <el-tag :type="POLICY_STATUS_TYPES[auditStatus(log.auditStatus)]">
                  {{ POLICY_STATUS_LABELS[auditStatus(log.auditStatus)] }}
                </el-tag>
                <el-button link type="primary" @click="openSnapshot(log.id)">查看快照</el-button>
              </header>
              <dl class="audit-meta">
                <div>
                  <dt>提交时间</dt>
                  <dd>{{ time(log.submittedAt) }}</dd>
                </div>
                <div>
                  <dt>审核人</dt>
                  <dd>{{ log.auditorName ?? '—' }}</dd>
                </div>
                <div>
                  <dt>审核时间</dt>
                  <dd>{{ time(log.auditTime) }}</dd>
                </div>
                <div>
                  <dt>审核意见</dt>
                  <dd>{{ log.auditComment ?? '—' }}</dd>
                </div>
              </dl>
            </li>
          </ol>
          <el-pagination
            v-if="logs.total > logs.pageSize"
            v-model:current-page="historyPage"
            :page-size="logs.pageSize"
            :total="logs.total"
            :pager-count="5"
            layout="prev, pager, next"
          />
        </template>
      </template>
    </el-tab-pane>
  </el-tabs>
</template>

<style scoped>
.submission-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  margin: 12px 0;
  overflow-wrap: anywhere;
}
.submission-heading h3 {
  margin: 0;
  font-size: 15px;
}
.audit-list {
  display: grid;
  gap: 12px;
  margin: 0 0 12px;
  padding: 0;
  list-style: none;
}
.audit-list li {
  padding: 0 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}
.audit-meta {
  display: grid;
  gap: 6px;
  margin: 0 0 12px;
}
.audit-meta div {
  display: grid;
  grid-template-columns: 66px minmax(0, 1fr);
  gap: 8px;
}
.audit-meta dt {
  color: var(--el-text-color-secondary);
}
.audit-meta dd {
  margin: 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.retry {
  margin-top: 12px;
}
</style>
