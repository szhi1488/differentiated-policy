<script setup>
import { usePolicySubmissionDetail } from '../../../composables/usePolicySubmissions'
import { policyStatusLabel, POLICY_STATUS_TYPES, policyIsEditable } from '../../../constants/policy'
import PolicySubmissionContent from './PolicySubmissionContent.vue'

const props = defineProps({
  id: { type: String, required: true },
  editDisabled: { type: Boolean, default: false },
})
defineEmits(['edit'])

const { detail, readDetail } = usePolicySubmissionDetail(props.id)
const { data: application, loading, error } = detail
void readDetail()
</script>

<template>
  <section class="submission-detail" aria-label="申请内容">
    <p v-if="loading" role="status">正在读取申请…</p>
    <template v-else-if="error">
      <el-alert type="error" :title="error.message" :closable="false" show-icon />
      <el-button class="retry" @click="readDetail">重试</el-button>
    </template>
    <template v-else-if="application">
      <header class="submission-heading">
        <div class="submission-identity">
          <span>申请单号</span>
          <strong>{{ application.policyCode }}</strong>
        </div>
        <el-tag :type="POLICY_STATUS_TYPES[application.status]">
          {{ policyStatusLabel(application) }}
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
      <PolicySubmissionContent :detail="application" @retry-progress="readDetail" />
    </template>
  </section>
</template>

<style scoped>
.submission-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin: 0 0 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.submission-identity {
  display: grid;
  flex: 1;
  min-width: 0;
  gap: 6px;
  overflow-wrap: anywhere;
}
.submission-identity > span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.submission-identity strong {
  font-size: 15px;
}
.retry {
  margin-top: 12px;
}
</style>
