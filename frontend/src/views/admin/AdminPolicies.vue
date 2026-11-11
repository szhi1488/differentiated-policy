<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { POLICY_STATUS } from '../../constants/admin'
import { RETAIL_PLATFORMS, BILLION_PLATFORMS } from '../../constants/policy'
import { useAdminPolicies } from '../../composables/useAdminPolicies'
import PolicyDetail from './components/PolicyDetail.vue'
const props = defineProps({ reviewOnly: Boolean })
const route = useRoute()
const {
  draft,
  status,
  page,
  pageSize,
  selected,
  dateError,
  baseRows,
  filtered,
  rows,
  search,
  reset,
  changeStatus,
  changeChannel,
} = useAdminPolicies(props.reviewOnly)
const platforms = computed(() =>
  draft.channel === '百亿渠道' ? BILLION_PLATFORMS : RETAIL_PLATFORMS,
)
const applicants = computed(() => [...new Set(baseRows.value.map((item) => item.applicant))])
if (typeof route.query.keyword === 'string') {
  draft.keyword = route.query.keyword
  search()
}
if (route.query.policy) {
  selected.value = baseRows.value.find((item) => item.id === route.query.policy) || null
}
</script>
<template>
  <section class="admin-card admin-policy-list">
    <div v-if="reviewOnly" class="admin-section-head">
      <div>
        <h2>
          待审核队列
          <el-tag type="warning" size="small">{{ baseRows.length }} 条演示申请</el-tag>
        </h2>
        <p>审核针对当前提交轮次，打开详情可核对逐平台明细。</p>
      </div>
      <el-button disabled>批量操作暂不开放</el-button>
    </div>
    <el-tabs v-else v-model="status" class="admin-status-tabs" @tab-change="changeStatus">
      <el-tab-pane :label="`全部政策 (${baseRows.length})`" name="ALL" />
      <el-tab-pane
        v-for="(item, key) in POLICY_STATUS"
        :key="key"
        :label="`${item.label} (${baseRows.filter((row) => row.status === key).length})`"
        :name="key"
      />
    </el-tabs>
    <el-form :model="draft" label-position="top" class="admin-filter-form" @submit.prevent="search">
      <div class="admin-filter-grid">
        <el-form-item label="渠道">
          <el-select
            v-model="draft.channel"
            placeholder="全部渠道"
            clearable
            @change="changeChannel"
          >
            <el-option
              v-for="item in ['零售渠道', '百亿渠道']"
              :key="item"
              :label="item"
              :value="item"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="平台">
          <el-select v-model="draft.platform" placeholder="全部平台" clearable>
            <el-option
              v-for="[code, label] in platforms"
              :key="code"
              :label="label"
              :value="label"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="核销类型">
          <el-select
            v-model="draft.verifyType"
            :disabled="draft.channel === '百亿渠道'"
            placeholder="全部类型"
            clearable
          >
            <el-option
              v-for="item in ['销售维度', '激活维度']"
              :key="item"
              :value="item"
              :label="item"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="涉及场景">
          <el-select
            v-model="draft.scene"
            :disabled="draft.channel === '百亿渠道'"
            placeholder="全部场景"
            clearable
          >
            <el-option
              v-for="item in ['国补', '地补', '自补']"
              :key="item"
              :value="item"
              :label="item"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="申请人">
          <el-select v-model="draft.applicant" placeholder="全部申请人" clearable>
            <el-option v-for="item in applicants" :key="item" :value="item" :label="item" />
          </el-select>
        </el-form-item>
      </div>
      <div class="admin-filter-bottom">
        <el-form-item label="搜索政策">
          <el-input
            v-model="draft.keyword"
            clearable
            placeholder="政策编号、申请人或机型关键词"
            aria-label="搜索政策"
          />
        </el-form-item>
        <el-form-item label="提交日期（北京时间）" :error="dateError">
          <div class="admin-date-pair">
            <el-date-picker
              v-model="draft.dates[0]"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="开始日期"
              aria-label="提交开始日期"
            />
            <span>至</span>
            <el-date-picker
              v-model="draft.dates[1]"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="结束日期"
              aria-label="提交结束日期"
            />
          </div>
        </el-form-item>
        <div class="admin-actions">
          <el-button @click="reset">重置</el-button>
          <el-button type="primary" native-type="submit">查询</el-button>
        </div>
      </div>
    </el-form>
    <el-table
      :data="rows"
      row-key="id"
      stripe
      highlight-current-row
      :row-class-name="({ row }) => (selected?.id === row.id ? 'admin-selected-row' : '')"
      @row-click="selected = $event"
    >
      <el-table-column label="政策编号 / 申请主题" min-width="250">
        <template #default="{ row }">
          <div class="admin-table-title">{{ row.code }}</div>
          <div class="admin-table-subtitle">{{ row.title }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="channel" label="渠道" width="100" />
      <el-table-column label="平台" min-width="130">
        <template #default="{ row }">{{ row.platforms.join('、') }}</template>
      </el-table-column>
      <el-table-column label="核销 / 场景" min-width="135">
        <template #default="{ row }">
          {{ row.verifyType || '不适用' }}
          <div class="admin-table-subtitle">{{ row.scene || '百亿渠道' }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="applicant" label="申请人" min-width="130" />
      <el-table-column prop="submittedAt" label="提交时间" width="180" />
      <el-table-column label="审核状态" width="105">
        <template #default="{ row }">
          <el-tag :type="POLICY_STATUS[row.status].type" size="small" effect="light">
            {{ POLICY_STATUS[row.status].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="selected = row">查看</el-button>
          <router-link
            v-if="!reviewOnly && row.status === 'PENDING'"
            :to="{ path: '/admin/reviews', query: { policy: row.id } }"
            class="admin-text-link"
          >
            审核预览
          </router-link>
          <el-button v-else-if="reviewOnly" link type="primary" @click.stop="selected = row">
            审核预览
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="没有匹配的演示政策，请调整筛选条件" :image-size="72" />
      </template>
    </el-table>
    <div class="admin-pagination">
      <span>共 {{ filtered.length }} 条演示政策</span>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="filtered.length"
        :page-sizes="[5, 10, 20]"
        layout="prev, pager, next, sizes"
        size="small"
        background
        @size-change="page = 1"
      />
    </div>
  </section>
  <PolicyDetail v-if="selected" :policy="selected" :review="reviewOnly" @close="selected = null" />
  <div v-else class="admin-detail-hint">点击政策行或“查看”，在此展开完整申请与审核历史。</div>
</template>
