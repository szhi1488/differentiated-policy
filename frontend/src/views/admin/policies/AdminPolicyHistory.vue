<script setup>
import { toRef } from 'vue'
import { useAdminPolicyHistory } from '../../../composables/useAdminPolicyHistory'
import { AUDIT_STATUS_LABELS, AUDIT_STATUS_TYPES } from '../../../constants/policy'
import { policyTime } from '../../../utils/policy-adapter'
import AdminPolicyDetail from './AdminPolicyDetail.vue'

const props = defineProps({ policyId: { type: String, required: true } })
const { page, pageSize, selectedLogId, list, snapshot, detail, refresh, readSnapshot } =
  useAdminPolicyHistory(toRef(props, 'policyId'))
const { data: records, loading: listBusy, error: listError } = list
const { loading: snapshotBusy, error: snapshotError } = snapshot
const time = (value) => policyTime(value).replace('T', ' ') || '—'
</script>

<template>
  <section class="admin-policy-history" aria-label="审核记录">
    <template v-if="!selectedLogId">
      <p v-if="listBusy" role="status">正在读取审核记录…</p>
      <el-alert v-else-if="listError" :title="listError.message" type="error" :closable="false">
        <el-button link type="primary" @click="refresh">重试读取审核记录</el-button>
      </el-alert>
      <template v-else-if="records">
        <el-table :data="records.items" row-key="id" size="small" class="admin-policy-table">
          <el-table-column label="提交轮次" width="92">
            <template #default="{ row }">第 {{ row.submissionVersion }} 轮</template>
          </el-table-column>
          <el-table-column label="提交时间" min-width="164">
            <template #default="{ row }">{{ time(row.submittedAt) }}</template>
          </el-table-column>
          <el-table-column label="审核结果" width="100">
            <template #default="{ row }">
              <el-tag :type="AUDIT_STATUS_TYPES[row.auditStatus]" size="small">
                {{ AUDIT_STATUS_LABELS[row.auditStatus] ?? row.auditStatus }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="审核人" min-width="90">
            <template #default="{ row }">{{ row.auditorName || '—' }}</template>
          </el-table-column>
          <el-table-column label="审核时间" min-width="164">
            <template #default="{ row }">{{ time(row.auditTime) }}</template>
          </el-table-column>
          <el-table-column label="审核意见" min-width="220">
            <template #default="{ row }">
              <span class="admin-policy-note">
                {{
                  row.auditComment?.trim()
                    ? row.auditComment
                    : row.auditStatus === 'SUBMITTED'
                      ? '待审核'
                      : row.auditStatus === 'REJECTED'
                        ? '未填写审核意见'
                        : '—'
                }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :aria-label="`查看第 ${row.submissionVersion} 轮内容`"
                @click="selectedLogId = row.id"
              >
                查看内容
              </el-button>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无审核记录" :image-size="60" /></template>
        </el-table>
        <div v-if="records.total" class="admin-policy-footer">
          <span>共 {{ records.total }} 轮提交</span>
          <el-pagination
            v-model:current-page="page"
            :page-size="pageSize"
            :total="records.total"
            :pager-count="5"
            layout="prev, pager, next"
            size="small"
          />
        </div>
      </template>
    </template>
    <template v-else>
      <div class="admin-policy-history-toolbar">
        <el-button @click="selectedLogId = ''">返回审核记录</el-button>
        <strong v-if="detail">第 {{ detail.audit.submissionVersion }} 轮提交内容</strong>
      </div>
      <p v-if="snapshotBusy" role="status">正在读取本轮提交内容…</p>
      <el-alert
        v-else-if="snapshotError"
        :title="snapshotError.message"
        type="error"
        :closable="false"
      >
        <el-button link type="primary" @click="readSnapshot">重试读取本轮内容</el-button>
      </el-alert>
      <template v-else-if="detail">
        <p class="admin-policy-history-summary">
          提交时间：{{ time(detail.audit.submittedAt) }} · 审核结果：{{
            AUDIT_STATUS_LABELS[detail.audit.auditStatus]
          }}
        </p>
        <AdminPolicyDetail :key="selectedLogId" :detail="detail" />
      </template>
    </template>
  </section>
</template>

<style scoped>
.admin-policy-history {
  min-width: 0;
  padding: 16px 14px;
}
.admin-policy-history-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.admin-policy-history-summary {
  margin: 16px 0;
  overflow-wrap: anywhere;
  color: var(--policy-muted);
}
</style>
