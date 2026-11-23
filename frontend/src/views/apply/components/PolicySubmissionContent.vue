<script setup>
import { computed } from 'vue'
import { CHANNEL_LABELS, PLATFORM_LABELS, VERIFY_LABELS } from '../../../constants/policy'
import { displayValue, policySceneLabel, policyTime } from '../../../utils/policy-adapter'
import { policyAttachmentDownloadUrl } from '../../../api/attachments'
import AttachmentDownload from '../../../components/AttachmentDownload.vue'
import PolicyEndAdjustments from '../../../components/PolicyEndAdjustments.vue'
import PolicySubmissionGoods from './PolicySubmissionGoods.vue'

const props = defineProps({
  detail: { type: Object, required: true },
  logId: { type: String, default: null },
  attachmentUrl: Function,
  editingTime: Boolean,
})
const form = computed(() => props.detail.form)
const platformName = (code) => PLATFORM_LABELS[code] ?? code
const time = (value) => policyTime(value).replace('T', ' ')
</script>

<template>
  <div class="submission-content">
    <el-descriptions :column="1" border>
      <el-descriptions-item label="申请人">{{ detail.applicantName }}</el-descriptions-item>
      <el-descriptions-item v-if="detail.sourcePolicyCode" label="延期原单">
        {{ detail.sourcePolicyCode }}
      </el-descriptions-item>
      <el-descriptions-item label="渠道">
        {{ CHANNEL_LABELS[form.perspective] }}
      </el-descriptions-item>
      <el-descriptions-item label="整单平台">
        {{ form.selectedPlatforms.map(platformName).join('、') || '未选择' }}
      </el-descriptions-item>
      <template v-if="form.perspective === 'RETAIL'">
        <el-descriptions-item label="核销类型">
          {{ VERIFY_LABELS[form.verifyType] ?? '未填写' }}
        </el-descriptions-item>
        <el-descriptions-item label="补贴场景">
          {{ policySceneLabel(form, '未填写') }}
        </el-descriptions-item>
        <el-descriptions-item label="是否预估">
          {{ displayValue(form.estimated) }}
        </el-descriptions-item>
      </template>
      <el-descriptions-item label="开始时间">
        {{ time(form.startTime) || '未填写' }}
      </el-descriptions-item>
      <el-descriptions-item
        :label="
          form.goodsForm.timeAdjustment && !detail.sourcePolicyCode ? '原单填报截止' : '到期时间'
        "
      >
        {{ time(form.endTime) || '未填写' }}
      </el-descriptions-item>
      <el-descriptions-item label="特别备注">
        {{ displayValue(form.specialRemark) }}
      </el-descriptions-item>
      <el-descriptions-item label="附件及链接">
        <span v-if="!form.attachmentInfo.length">未填写</span>
        <div v-for="attachment in form.attachmentInfo" :key="attachment.id ?? attachment.url">
          <AttachmentDownload
            v-if="attachment.type === 'FILE'"
            :url="
              attachmentUrl
                ? attachmentUrl(attachment.id)
                : policyAttachmentDownloadUrl(detail.id, attachment.id, logId)
            "
            :filename="attachment.originalName"
          />
          <span v-else>{{ attachment.url }}</span>
        </div>
      </el-descriptions-item>
    </el-descriptions>

    <PolicyEndAdjustments
      :changes="editingTime ? [] : detail.endAdjustments"
      :time-adjustment="editingTime ? null : form.goodsForm.timeAdjustment"
      :approved-end="detail.approvedEnd"
      :policy-id="detail.id"
      :log-id="logId"
      :attachment-url="attachmentUrl"
    />
    <PolicySubmissionGoods :cards="form.goodsForm.cards" :goods="detail.goods" />
  </div>
</template>

<style scoped>
.submission-content {
  overflow-wrap: anywhere;
}
.submission-content :deep(.el-descriptions__content) {
  white-space: pre-wrap;
}
.submission-content :deep(.el-descriptions__label) {
  width: 98px;
}
</style>
