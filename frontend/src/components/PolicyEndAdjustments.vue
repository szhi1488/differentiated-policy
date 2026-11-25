<script setup>
import { timeChangeAttachmentUrl } from '../api/policy-reviews'
import { policyTime } from '../utils/policy-adapter'
import AttachmentDownload from './AttachmentDownload.vue'
import { policyAttachmentDownloadUrl } from '../api/attachments'
defineProps({
  changes: Array,
  history: Object,
  submissionVersion: Number,
  policyId: String,
  logId: String,
  attachmentUrl: Function,
  admin: Boolean,
})
const time = (t) => policyTime(t).replace('T', ' ')
</script>
<template>
  <section v-if="history?.changes.length" class="policy-end-changes" aria-label="时间调整记录">
    <h3>时间调整记录</h3>
    <article v-for="change in history.changes" :key="change.id">
      <strong>{{ change.direction === 'EXTEND' ? '延期' : '提前结束' }}</strong>
      <p>{{ time(change.previousEnd) }} → {{ time(change.actualEnd) }}</p>
      <p v-if="change.requestedEnd !== change.actualEnd">
        申请截止 {{ time(change.requestedEnd) }}
      </p>
      <p>调整原因：{{ change.reason }}</p>
      <p>{{ change.actorName }} · {{ time(change.changedAt) }} 办理</p>
      <p>生效时间 {{ time(change.effectiveAt) }}</p>
      <p v-if="change.sourcePolicyCode">随 {{ change.sourcePolicyCode }} 获批</p>
      <div v-for="file in change.attachmentInfo" :key="file.id">
        <AttachmentDownload
          :url="timeChangeAttachmentUrl(policyId, submissionVersion, change.id, file.id)"
          :filename="file.originalName"
        />
      </div>
    </article>
  </section>
  <section v-if="changes?.length" class="policy-end-changes">
    <h3>本轮政策时间调整</h3>
    <article v-for="change in changes" :key="change.target.policyId">
      <strong>ZC-{{ change.target.policyId.padStart(6, '0') }} · 全部平台和商品</strong>
      <p>
        原截止 {{ time(change.target.previousEnd) }} →
        {{
          policyTime(change.endTime) > policyTime(change.target.previousEnd) ? '延长至' : '缩短至'
        }}
        {{ time(change.endTime) }}
      </p>
      <p>调整原因：{{ change.reason || '未记录' }}</p>
      <div v-for="file in change.attachmentInfo ?? []" :key="file.id">
        <AttachmentDownload
          :url="
            attachmentUrl
              ? attachmentUrl(file.id)
              : policyAttachmentDownloadUrl(policyId, file.id, logId, admin)
          "
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
