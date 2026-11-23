<script setup>
import { computed } from 'vue'
import { policyTime } from '../utils/policy-adapter'
import AttachmentDownload from './AttachmentDownload.vue'
import { policyAttachmentDownloadUrl } from '../api/attachments'
const props = defineProps({
  changes: Array,
  timeAdjustment: Object,
  approvedEnd: Object,
  policyId: String,
  logId: String,
  admin: Boolean,
})
const time = (t) => policyTime(t).replace('T', ' ')
const adjustments = computed(() => {
  const records = props.changes ?? []
  if (!props.timeAdjustment) {
    return records
  }
  return [
    props.timeAdjustment,
    ...records.filter((change) => change.target.policyId !== props.timeAdjustment.target.policyId),
  ]
})
</script>
<template>
  <section v-if="approvedEnd" class="policy-end-changes">
    <h3>整单提前结束已获批</h3>
    <strong>实际截止 {{ time(approvedEnd.actualEnd) }}</strong>
    <p>由 {{ approvedEnd.requestPolicyCode }} 于 {{ time(approvedEnd.approvedAt) }} 审核通过。</p>
    <p>调整原因：{{ approvedEnd.change.reason }}</p>
  </section>
  <section v-if="adjustments.length" class="policy-end-changes">
    <h3>本轮政策时间调整</h3>
    <article v-for="change in adjustments" :key="change.target.policyId">
      <strong>ZC-{{ change.target.policyId.padStart(6, '0') }} · 全部平台和商品</strong>
      <p>
        原截止 {{ time(change.target.previousEnd) }} →
        {{
          policyTime(change.endTime) > policyTime(change.target.previousEnd) ? '延长至' : '缩短至'
        }}
        {{ time(change.endTime) }}
      </p>
      <p>调整原因：{{ change.reason }}</p>
      <div v-for="file in change.attachmentInfo ?? []" :key="file.id">
        <AttachmentDownload
          :url="policyAttachmentDownloadUrl(policyId, file.id, logId, admin)"
          :filename="file.originalName"
        />
      </div>
    </article>
  </section>
</template>
<style scoped>
.policy-end-changes {
  overflow-wrap: anywhere;
}
article {
  border-top: 1px solid var(--el-border-color-light);
  padding: 8px 0;
}
p {
  margin: 4px 0;
  white-space: pre-wrap;
}
</style>
