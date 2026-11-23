<script setup>
import { computed } from 'vue'
import { REVIEW_STAGE_LABELS } from '../constants/review-workflow'
import { POLICY_STATUS_LABELS } from '../constants/policy'
import { policyTime } from '../utils/policy-adapter'

const props = defineProps({ round: { type: Object, required: true } })
const groups = computed(() => [
  { stage: 'PRODUCT_REVIEW', members: props.round.productReviewers ?? [] },
  { stage: 'PURCHASE_REVIEW', members: props.round.purchaseReviewers ?? [] },
])

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
  if (member.memberStatus === 'REMOVED') {
    return '已移除'
  }
  if (member.memberStatus === 'HANDLED') {
    return '已办理'
  }
  if (props.round.reviewStage === stage) {
    return '待办理'
  }
  return props.round.status === 'PENDING' ? '知悉，待前一环节完成' : '本轮未办理'
}
</script>

<template>
  <section class="review-members" aria-label="本轮审核成员">
    <header>
      <h3>本轮审核成员</h3>
      <el-tag size="small" effect="plain">
        {{ REVIEW_STAGE_LABELS[round.reviewStage] || POLICY_STATUS_LABELS[round.status] }}
      </el-tag>
    </header>
    <div class="review-member-groups">
      <section v-for="group in groups" :key="group.stage">
        <h4>{{ REVIEW_STAGE_LABELS[group.stage] }}</h4>
        <p v-if="!group.members.length">本轮无指派成员</p>
        <ul v-else>
          <li v-for="member in group.members" :key="member.assignmentId">
            <div class="member-identity">
              <strong>{{ member.name }}</strong>
              <span>{{ member.department || '部门未记录' }}</span>
            </div>
            <div>
              {{ memberLabel(member, group.stage) }}
              <time v-if="member.decidedAt">
                {{ policyTime(member.decidedAt).replace('T', ' ') }}
              </time>
            </div>
            <p v-if="member.reason" class="member-reason">{{ member.reason }}</p>
          </li>
        </ul>
      </section>
    </div>
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
  grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr));
  gap: 16px;
  margin-top: 12px;
}
.review-member-groups section {
  padding: 12px 16px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
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
.review-member-groups li {
  padding: 8px 0;
  line-height: 1.65;
}
.review-member-groups li + li {
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
