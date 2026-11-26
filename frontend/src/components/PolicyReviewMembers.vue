<script setup>
import { computed } from 'vue'
import { REVIEW_STAGE_LABELS } from '../constants/review-workflow'
import { policyStatusLabel, POLICY_STATUS_TYPES } from '../constants/policy'
import { policyTime } from '../utils/policy-adapter'

const props = defineProps({ round: { type: Object, required: true } })
const groups = computed(() => {
  const round = props.round
  const stages = []
  if (round.requiresProductReview) {
    stages.push({ stage: 'PRODUCT_REVIEW', members: round.productReviewers })
  }
  if (round.rejectedAtStage !== 'PRODUCT_REVIEW') {
    stages.push({ stage: 'PURCHASE_REVIEW', members: round.purchaseReviewers })
  }
  return stages
})
const time = (value) => policyTime(value).replace('T', ' ')

function memberLabel(member, stage) {
  if (member.decision === 'REJECT') {
    return stage === 'PRODUCT_REVIEW' ? '已退回修改' : '已驳回'
  }
  if (member.decision === 'APPROVE') {
    return stage === 'PRODUCT_REVIEW' ? '已同意转采购' : '已批准'
  }
  if (member.memberStatus === 'PEER_HANDLED') {
    return '同组他人已处理'
  }
  if (member.memberStatus === 'HANDLED') {
    return '已办理'
  }
  if (props.round.status === 'PENDING' && props.round.reviewStage === stage) {
    return '待办理'
  }
  return props.round.reviewStage === 'PRODUCT_REVIEW' && stage === 'PURCHASE_REVIEW'
    ? '待初审通过'
    : '本轮未办理'
}
</script>

<template>
  <section class="review-members" aria-label="本轮审核进度">
    <header>
      <h3>本轮审核进度</h3>
      <el-tag size="small" effect="plain" :type="POLICY_STATUS_TYPES[round.status]">
        {{ policyStatusLabel(round) }}
      </el-tag>
    </header>
    <ol class="review-member-groups">
      <li>
        <h4>运营提交</h4>
        <strong>{{ round.applicantName }}</strong>
        <time v-if="round.submittedAt" :datetime="round.submittedAt">
          {{ time(round.submittedAt) }}
        </time>
      </li>
      <li
        v-for="group in groups"
        :key="group.stage"
        :aria-current="round.reviewStage === group.stage ? 'step' : undefined"
      >
        <h4>{{ REVIEW_STAGE_LABELS[group.stage] }}</h4>
        <p v-if="!group.members?.length">本轮无指派成员</p>
        <ul v-else>
          <li v-for="member in group.members" :key="member.assignmentId">
            <div class="member-identity">
              <strong>{{ member.name }}</strong>
              <span v-if="member.department">{{ member.department }}</span>
            </div>
            <div>
              {{ memberLabel(member, group.stage) }}
              <time v-if="member.decidedAt" :datetime="member.decidedAt">
                {{ time(member.decidedAt) }}
              </time>
            </div>
            <p v-if="member.reason" class="member-reason">{{ member.reason }}</p>
            <p v-else-if="member.decision === 'REJECT'" class="member-reason">
              审核人未填写驳回原因。
            </p>
          </li>
        </ul>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.review-members {
  min-width: 0;
  overflow-wrap: anywhere;
}
.review-members header,
.member-identity {
  display: flex;
  gap: 6px 12px;
  flex-wrap: wrap;
  align-items: center;
}
.review-members h3 {
  margin: 0;
  font-size: 16px;
}
.review-member-groups {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(185px, 100%), 1fr));
  gap: 10px;
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
  line-height: 1.65;
}
.review-member-groups > li {
  padding: 12px 14px;
  border-inline-start: 3px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
.review-member-groups > li[aria-current='step'] {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.review-member-groups > li > strong,
.review-member-groups > li > time {
  display: block;
}
.review-member-groups h4,
.review-member-groups p,
.review-member-groups ul {
  margin: 0;
}
.review-member-groups ul {
  list-style: none;
  padding: 0;
}
.review-member-groups h4 {
  margin-bottom: 6px;
}
.review-member-groups ul li + li {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.member-identity span,
time {
  color: var(--el-text-color-secondary);
}
time {
  display: inline-block;
}
.member-reason {
  white-space: pre-wrap;
}
</style>
