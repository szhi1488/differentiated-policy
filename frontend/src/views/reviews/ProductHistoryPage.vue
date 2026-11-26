<script setup>
import { ref, watch } from 'vue'
import { readProductDecisions } from '../../api/policy-reviews'
import { useLatestQuery } from '../../composables/useLatestQuery'
import WorkspacePageHeading from '../../components/WorkspacePageHeading.vue'
const page = ref(1)
const { data, loading, error, run } = useLatestQuery()
const load = () => run((signal) => readProductDecisions(page.value, 20, signal))
watch(page, load, { immediate: true })
</script>
<template>
  <section class="policy-reviews-page">
    <header class="workspace-page-banner">
      <WorkspacePageHeading title="我的初审记录" icon="document" />
    </header>
    <div class="review-panel" v-loading="loading">
      <el-alert v-if="error" type="error" :closable="false" :title="error.message">
        <el-button link @click="load">重试</el-button>
      </el-alert>
      <el-table v-else :data="data?.items ?? []" empty-text="暂无本人已签署的初审记录">
        <el-table-column label="政策单号" prop="policyCode" min-width="125" />
        <el-table-column label="轮次" prop="submissionVersion" width="75" />
        <el-table-column label="初审决定" width="100">
          <template #default="{ row }">{{ row.decision === 'APPROVE' ? '通过' : '退回' }}</template>
        </el-table-column>
        <el-table-column label="意见" prop="reason" min-width="160" />
        <el-table-column label="办理时间" prop="decidedAt" min-width="165" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <router-link
              :to="{
                name: 'review-round',
                params: { id: row.policyId },
                query: { round: row.submissionVersion },
              }"
            >
              查看
            </router-link>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="data?.total > 20"
        v-model:current-page="page"
        layout="prev,pager,next"
        :total="data.total"
        :page-size="20"
      />
    </div>
  </section>
</template>
<style src="./policy-reviews.css"></style>
