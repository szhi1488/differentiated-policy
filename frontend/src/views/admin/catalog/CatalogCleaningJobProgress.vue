<script setup>
import { GOODS_CLEAN_JOB_LABELS } from '../../../constants/catalog'

defineProps({ job: { type: Object, required: true } })
</script>

<template>
  <section v-if="!job.legacy" class="cleaning-job-progress" aria-label="清洗进度">
    <div class="cleaning-job-heading">
      <strong>{{ GOODS_CLEAN_JOB_LABELS[job.status] }}</strong>
      <span>已处理 {{ job.processed }} / {{ job.total }} 条</span>
    </div>
    <el-progress :percentage="job.progress" />
    <div class="cleaning-job-counts">
      <span>自动通过 {{ job.resultCounts.AUTO_PASSED ?? 0 }}</span>
      <span>人工通过 {{ job.resultCounts.MANUAL_PASSED ?? 0 }}</span>
      <span>待人工审核 {{ job.resultCounts.REVIEW_REQUIRED ?? 0 }}</span>
      <span>技术失败 {{ job.resultCounts.ERROR ?? 0 }}</span>
      <span v-if="job.resultCounts.SKIPPED">源商品已更新 {{ job.resultCounts.SKIPPED }}</span>
    </div>
  </section>
</template>
