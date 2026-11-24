<script setup>
import { computed } from 'vue'
import PolicyReviewMembers from '../../components/PolicyReviewMembers.vue'
import PolicyReviewChanges from './PolicyReviewChanges.vue'
import PolicySubmissionContent from '../apply/components/PolicySubmissionContent.vue'
import { reviewRoundAttachmentUrl } from '../../api/policy-reviews'
import { REVIEW_STAGE_LABELS } from '../../constants/review-workflow'
import { POLICY_STATUS_LABELS } from '../../constants/policy'

const props = defineProps({
  round: { type: Object, required: true },
  node: { type: String, default: 'PRODUCT_REVIEW' },
})
const snapshot = computed(() => {
  const submitted = props.round.snapshot
  // 分流依据含每张旧单的截止，提交快照同时保存申请人填写的原因和证明。
  const adjustments = new Map(props.round.adjustments.map((item) => [item.target.policyId, item]))
  for (const item of submitted.consent?.endAdjustments ?? []) {
    adjustments.set(item.target.policyId, item)
  }
  return { ...submitted, endAdjustments: [...adjustments.values()] }
})
const attachmentUrl = (fileId) =>
  reviewRoundAttachmentUrl(props.round.id, props.round.submissionVersion, fileId)
</script>

<template>
  <section class="review-panel">
    <h2>{{ round.policyCode }} · 第 {{ round.submissionVersion }} 轮</h2>
    <p v-if="round.currentSubmissionVersion !== round.submissionVersion">
      已有新一轮，本页仅显示所查轮次。
    </p>
    <p v-else-if="round.status !== 'PENDING'">
      本轮已处理 · {{ POLICY_STATUS_LABELS[round.status] }}
    </p>
    <p v-else-if="node !== round.reviewStage">
      当前环节为{{ REVIEW_STAGE_LABELS[round.reviewStage] }}，此链接仅供查看。
    </p>
    <p v-else-if="round.reviewStage === 'PRODUCT_REVIEW' && round.allowedActions?.length">
      请回到飞书私聊审核卡确认本轮申请。
    </p>
    <p v-if="round.status === 'REJECTED'">
      本轮已由{{ REVIEW_STAGE_LABELS[round.rejectedAtStage] || '审核人' }}退回给原申请人{{
        snapshot.applicantName
      }}。
    </p>
    <p v-if="round.changeSummary" class="review-summary">{{ round.changeSummary.summary }}</p>
  </section>
  <section v-if="round.settingsVersion != null" class="review-panel">
    <PolicyReviewMembers :round="round" />
  </section>
  <section v-if="round.changes.length" class="review-panel">
    <PolicyReviewChanges :changes="round.changes" />
  </section>
  <section class="review-panel">
    <h2>本轮提交内容</h2>
    <PolicySubmissionContent :detail="snapshot" :attachment-url="attachmentUrl" />
  </section>
</template>
