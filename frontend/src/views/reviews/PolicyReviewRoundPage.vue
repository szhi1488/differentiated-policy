<script setup>
import { useRouter } from 'vue-router'
import WorkspacePageHeading from '../../components/WorkspacePageHeading.vue'
import PolicyReviewRoundContent from './PolicyReviewRoundContent.vue'
import { authSession } from '../../composables/useAuthSession'
import { usePolicyReviewRound } from '../../composables/usePolicyReviewRound'
import { reviewReturnLocation } from '../../utils/workspace-route'
import './policy-reviews.css'

const props = defineProps({
  policyId: { type: String, required: true },
  submissionVersion: { type: Number, required: true },
})
const router = useRouter()
const { round, loading, error, refresh } = usePolicyReviewRound(props)
</script>

<template>
  <section class="policy-reviews-page">
    <header class="workspace-page-banner">
      <WorkspacePageHeading title="政策审核详情" />
      <el-button @click="router.push(reviewReturnLocation(authSession.user.value))">
        返回工作台
      </el-button>
    </header>
    <section v-if="loading || error" class="review-panel">
      <p v-if="loading" role="status">正在读取本轮审核详情…</p>
      <el-alert v-else :title="error.message" type="error" :closable="false" show-icon>
        <el-button link type="primary" @click="refresh">重新读取本轮</el-button>
      </el-alert>
    </section>
    <PolicyReviewRoundContent v-else-if="round" :round="round" />
  </section>
</template>
