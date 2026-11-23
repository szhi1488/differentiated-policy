<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import WorkspacePageHeading from '../../components/WorkspacePageHeading.vue'
import PolicyReviewMembers from '../../components/PolicyReviewMembers.vue'
import PolicyReviewChanges from './PolicyReviewChanges.vue'
import PolicySubmissionContent from '../apply/components/PolicySubmissionContent.vue'
import { reviewRoundAttachmentUrl } from '../../api/policy-reviews'
import { usePolicyReviewRound } from '../../composables/usePolicyReviewRound'
import { REVIEW_STAGE_LABELS } from '../../constants/review-workflow'
import { POLICY_STATUS_LABELS } from '../../constants/policy'
import './policy-reviews.css'

const props = defineProps({
  policyId: { type: String, required: true },
  submissionVersion: { type: Number, required: true },
  node: { type: String, default: '' },
})
const router = useRouter()
const {
  round,
  loading,
  error,
  actions,
  linkedStage,
  product,
  reason,
  feedback,
  writing,
  refresh,
  decide,
} = usePolicyReviewRound(props)
const snapshot = computed(() => {
  const submitted = round.value.snapshot
  // 分流依据含每张旧单的截止，提交快照同时保存申请人填写的原因和证明。
  const adjustments = new Map(
    (round.value.adjustments ?? []).map((item) => [item.target.policyId, item]),
  )
  for (const item of submitted.consent?.endAdjustments ?? []) {
    adjustments.set(item.target.policyId, item)
  }
  return { ...submitted, endAdjustments: [...adjustments.values()] }
})
const attachmentUrl = (fileId) =>
  reviewRoundAttachmentUrl(props.policyId, props.submissionVersion, fileId)
</script>

<template>
  <section class="policy-reviews-page">
    <header class="workspace-page-banner">
      <WorkspacePageHeading title="政策审核详情" />
      <el-button @click="router.push('/reviews')">返回待我审核</el-button>
    </header>
    <el-alert
      v-if="feedback"
      :title="feedback.message"
      :type="feedback.type"
      :closable="false"
      show-icon
    />
    <section v-if="loading || error" class="review-panel">
      <p v-if="loading" role="status">正在读取本轮审核详情…</p>
      <el-alert v-else :title="error.message" type="error" :closable="false" show-icon>
        <el-button link type="primary" @click="refresh">重新读取本轮</el-button>
      </el-alert>
    </section>
    <template v-else-if="round">
      <section class="review-panel">
        <h2>{{ round.policyCode }} · 第 {{ round.submissionVersion }} 轮</h2>
        <p v-if="round.currentSubmissionVersion !== round.submissionVersion">
          已有新一轮，本页仅显示所查轮次。
        </p>
        <p v-else-if="round.status !== 'PENDING'">
          本轮已处理 · {{ POLICY_STATUS_LABELS[round.status] }}
        </p>
        <p v-else-if="linkedStage && linkedStage !== round.reviewStage">
          当前环节为{{ REVIEW_STAGE_LABELS[round.reviewStage] }}，此链接仅供查看。
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
      <section v-if="actions.length || reason" class="review-panel round-actions">
        <h2 v-if="actions.length">{{ REVIEW_STAGE_LABELS[round.reviewStage] }}</h2>
        <label v-if="actions.includes('REJECT') || reason" for="round-reject-reason">
          {{
            actions.length
              ? product
                ? '退回原因（退回时必填）'
                : '驳回意见（选填）'
              : '本次填写的意见'
          }}
          <el-input
            id="round-reject-reason"
            v-model="reason"
            :disabled="writing || !actions.includes('REJECT')"
            type="textarea"
            :rows="3"
          />
        </label>
        <div v-if="actions.length">
          <el-button
            v-if="actions.includes('APPROVE')"
            type="primary"
            :disabled="writing"
            @click="decide('APPROVE')"
          >
            {{ product ? '同意，转采购' : '终审通过' }}
          </el-button>
          <el-button
            v-if="actions.includes('REJECT')"
            type="danger"
            plain
            :disabled="writing"
            @click="decide('REJECT')"
          >
            {{ product ? '退回修改' : '驳回' }}
          </el-button>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.round-actions label {
  display: grid;
  gap: 6px;
}
.round-actions > div {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
.round-actions .el-button {
  margin-left: 0;
}
</style>
