<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { listMyPolicyReviews } from '../../api/policy-reviews'
import WorkspacePageHeading from '../../components/WorkspacePageHeading.vue'
import { useLatestQuery } from '../../composables/useLatestQuery'
import { REVIEW_STAGE_LABELS } from '../../constants/review-workflow'
import { policyTime } from '../../utils/policy-adapter'
import './policy-reviews.css'

const router = useRouter()
const page = ref(1)
const pageSize = 20
const { data, error, loading, run } = useLatestQuery()
async function refresh() {
  await run((signal) => listMyPolicyReviews({ page: page.value, pageSize }, signal))
  if (data.value) {
    page.value = Math.min(page.value, Math.max(1, Math.ceil(data.value.total / pageSize)))
  }
}
function openRound(row) {
  return router.push({
    path: '/reviews/' + row.id,
    query: { round: String(row.submissionVersion), node: row.reviewStage },
  })
}
watch(page, refresh, { immediate: true })
</script>

<template>
  <section class="policy-reviews-page">
    <header class="workspace-page-banner">
      <WorkspacePageHeading title="待我审核" icon="list" />
      <el-button :loading="loading" @click="refresh">刷新</el-button>
    </header>
    <section class="review-panel" aria-label="当前待办">
      <p v-if="loading" role="status">正在读取待审核政策…</p>
      <el-alert v-else-if="error" :title="error.message" type="error" :closable="false" show-icon>
        <el-button link type="primary" @click="refresh">重试</el-button>
      </el-alert>
      <template v-else-if="data">
        <el-empty v-if="!data.total" description="当前没有待我审核的政策" />
        <template v-else>
          <h2>待办理（{{ data.total }}）</h2>
          <article
            v-for="row in data.items"
            :key="row.id + '/' + row.submissionVersion"
            class="review-inbox-row"
          >
            <div>
              <header>
                <strong>{{ row.policyCode }}</strong>
                <el-tag size="small" effect="plain">
                  {{ REVIEW_STAGE_LABELS[row.reviewStage] }}
                </el-tag>
              </header>
              <p class="review-time">
                第 {{ row.submissionVersion }} 轮 ·
                {{ policyTime(row.submittedAt).replace('T', ' ') }} 提交
              </p>
              <p class="review-summary">{{ row.summary }}</p>
            </div>
            <el-button
              type="primary"
              plain
              :aria-label="'办理 ' + row.policyCode"
              @click="openRound(row)"
            >
              办理
            </el-button>
          </article>
          <el-pagination
            v-if="data.total > pageSize"
            v-model:current-page="page"
            :page-size="pageSize"
            :total="data.total"
            :pager-count="5"
            layout="prev, pager, next"
          />
        </template>
      </template>
    </section>
  </section>
</template>

<style scoped>
.review-inbox-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  padding: 14px 0;
}
.review-inbox-row > div {
  min-width: 0;
}
.review-inbox-row header {
  display: flex;
  align-items: center;
  gap: 6px 12px;
  flex-wrap: wrap;
}
.review-time {
  margin: 6px 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
@media (max-width: 600px) {
  .review-inbox-row {
    flex-direction: column;
  }
}
</style>
