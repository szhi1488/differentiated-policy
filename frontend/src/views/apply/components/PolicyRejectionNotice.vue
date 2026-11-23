<script setup>
import { usePolicyRejection } from '../../../composables/usePolicySubmissions'
import { policyTime } from '../../../utils/policy-adapter'
import { REVIEW_STAGE_LABELS } from '../../../constants/review-workflow'

const props = defineProps({ policy: { type: Object, required: true } })
const { data: audit, loading, error, refresh } = usePolicyRejection(() => props.policy)
</script>

<template>
  <el-alert
    v-if="policy.status === 'REJECTED'"
    class="policy-rejection"
    :type="error ? 'error' : 'warning'"
    title="驳回原因"
    :closable="false"
    show-icon
  >
    <p v-if="loading" role="status">正在读取驳回原因…</p>
    <template v-else-if="error">
      <p>{{ error.message }}</p>
      <el-button link type="primary" @click="refresh">重试读取驳回原因</el-button>
    </template>
    <template v-else-if="audit">
      <p>退回环节：{{ REVIEW_STAGE_LABELS[audit.rejectedAtStage] ?? '未记录' }}</p>
      <p class="policy-rejection-comment">
        {{ audit.auditComment?.trim() ? audit.auditComment : '审核人未填写驳回原因。' }}
      </p>
      <p class="policy-rejection-meta">
        审核人：{{ audit.auditorName ?? '—' }}
        <span v-if="audit.auditTime">· {{ policyTime(audit.auditTime).replace('T', ' ') }}</span>
      </p>
    </template>
    <template v-else>
      <p>暂时无法获取驳回原因，请刷新后重试。</p>
      <el-button link type="primary" @click="refresh">重试读取驳回原因</el-button>
    </template>
  </el-alert>
</template>

<style scoped>
.policy-rejection {
  margin-bottom: 16px;
  overflow-wrap: anywhere;
}
.policy-rejection :deep(.el-alert__content) {
  min-width: 0;
}
.policy-rejection p {
  margin: 4px 0;
  line-height: 1.65;
}
.policy-rejection-comment {
  white-space: pre-wrap;
}
.policy-rejection .policy-rejection-meta {
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
