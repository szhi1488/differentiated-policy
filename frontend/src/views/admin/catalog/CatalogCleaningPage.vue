<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import cleaningBanner from '../../../assets/catalog-cleaning-banner.png'
import AppIcon from '../../../components/AppIcon.vue'
import { useCatalogCleaning } from '../../../composables/useCatalogCleaning'
import {
  GOODS_BRANDS,
  GOODS_CLEAN_JOB_LABELS,
  GOODS_CLEAN_LOG_ACTIONS,
  GOODS_CLEAN_RESULTS,
} from '../../../constants/catalog'
import CatalogCleaningDetailDialog from './CatalogCleaningDetailDialog.vue'
import './catalog-cleaning.css'

const {
  brand,
  selectBrand,
  filters,
  tab,
  page,
  size,
  total,
  search,
  reset,
  selectTab,
  resize,
  job,
  jobsLoading,
  jobsError,
  ruleVersions,
  refreshJobs,
  canStart,
  canRetryJob,
  canReprocess,
  table,
  loadResults,
  detail,
  detailId,
  openDetail,
  loadDetail,
  review,
  reviewError,
  reviewConflict,
  logs,
  logsJobId,
  logsPage,
  logsSize,
  logsTotal,
  openLogs,
  loadLogs,
  action,
  actionError,
  busy,
  start,
  retry,
  reprocess,
} = useCatalogCleaning()
const { data: resultPage, loading: tableLoading, error: tableError } = table
const { data: sourceDetail, loading: detailLoading, error: detailError } = detail
const { data: logPage, loading: logsLoading, error: logsError } = logs

const formatCount = new Intl.NumberFormat('zh-CN').format
const batchTotal = computed(() => job.value?.total ?? 0)
const progress = computed(() => job.value?.progress ?? 0)
const progressLabel = computed(() =>
  job.value
    ? GOODS_CLEAN_JOB_LABELS[job.value.status]
    : jobsLoading.value
      ? '正在读取'
      : '暂无批次',
)
const results = computed(() =>
  ['AUTO_PASSED', 'REVIEW_REQUIRED', 'ERROR'].map((status) => {
    const count = job.value?.resultCounts[status] ?? 0
    return {
      ...GOODS_CLEAN_RESULTS[status],
      status,
      count,
      percent: batchTotal.value ? ((count / batchTotal.value) * 100).toFixed(1) : '0.0',
    }
  }),
)
const taskFields = computed(() => [
  ['任务编号', job.value?.id],
  ['关联导入', job.value && `${job.value.brandName} · ${job.value.importJobId}`],
  ['规则版本', job.value?.ruleVersion],
  ['创建时间', job.value?.createdAt],
])
const recognizedFields = [
  ['seriesName', '系列', 82],
  ['modelName', '型号', 88],
  ['spec', '配置', 104],
  ['colorName', '颜色', 73],
]
const brandLabels = { OPPO手机: 'OPPO', 一加手机: '一加' }

async function submitTask() {
  const retrying = canRetryJob.value
  if (await start()) {
    ElMessage.success(retrying ? '重试已受理。' : '清洗已受理。')
  }
}

async function submitRetry(row) {
  if (await retry(row)) {
    ElMessage.success('重试已受理。')
  }
}

async function submitReprocess() {
  if (await reprocess()) {
    ElMessage.success('待审商品重新识别已受理。')
  }
}

async function submitReview(body) {
  if (await review(body)) {
    ElMessage.success('审核已保存。')
  }
}
</script>

<template>
  <section class="catalog-cleaning" aria-labelledby="cleaning-title">
    <header class="cleaning-banner">
      <img :src="cleaningBanner" class="cleaning-banner-image" alt="" width="2172" height="724" />
      <div class="cleaning-banner-copy">
        <h1 id="cleaning-title">商品清洗与识别</h1>
        <p>对已落库的吉客云源商品进行规则清洗，提取系列、型号、配置和颜色，并按结果分流处理。</p>
      </div>
    </header>

    <div class="cleaning-overview">
      <section class="cleaning-card cleaning-task" aria-labelledby="cleaning-task-title">
        <h2 id="cleaning-task-title" class="cleaning-section-title">清洗任务</h2>
        <dl class="cleaning-task-fields">
          <div class="cleaning-task-brand">
            <dt>源商品品牌</dt>
            <dd>
              <el-select
                :model-value="brand"
                aria-label="源商品品牌"
                placeholder="请选择品牌"
                :disabled="busy"
                size="small"
                clearable
                @update:model-value="selectBrand"
              >
                <el-option v-for="name in GOODS_BRANDS" :key="name" :label="name" :value="name" />
              </el-select>
            </dd>
          </div>
          <div
            v-for="[label, value] in taskFields"
            :key="label"
            :class="{ 'cleaning-import-reference': label === '关联导入' }"
          >
            <dt>{{ label }}</dt>
            <dd>{{ value ?? '—' }}</dd>
          </div>
        </dl>
        <p v-if="!jobsLoading && !jobsError && !brand" class="cleaning-task-hint">
          选择品牌后可启动该品牌的清洗任务。
        </p>
        <p v-else-if="!jobsLoading && !jobsError && !job" class="cleaning-task-hint">
          该品牌暂无成功导入的批次。
        </p>
      </section>

      <section class="cleaning-card cleaning-progress" aria-labelledby="cleaning-progress-title">
        <div class="cleaning-progress-heading">
          <h2 id="cleaning-progress-title" class="cleaning-section-title">清洗进度</h2>
          <div class="cleaning-task-actions">
            <el-button :disabled="!job" @click="openLogs">
              <AppIcon name="document" :size="15" />
              查看处理日志
            </el-button>
            <el-button
              v-if="brand && (job?.reprocessable || action === 'reprocess')"
              :disabled="!canReprocess"
              :loading="action === 'reprocess'"
              @click="submitReprocess"
            >
              重新识别待审{{ job?.reprocessable ? `（${formatCount(job.reprocessable)}）` : '' }}
            </el-button>
            <el-button
              type="primary"
              :disabled="!canStart && !canRetryJob"
              :loading="action === 'start' || action === 'batch-retry'"
              @click="submitTask"
            >
              <AppIcon name="sync" :size="15" />
              {{ canRetryJob || action === 'batch-retry' ? '重试失败' : '开始清洗' }}
            </el-button>
          </div>
        </div>
        <div class="cleaning-progress-content">
          <el-progress
            type="circle"
            :percentage="progress"
            :width="108"
            :stroke-width="8"
            color="#10a43a"
            aria-label="清洗进度"
          >
            <div class="cleaning-progress-text">
              <strong>{{ progress }}%</strong>
              <span>{{ progressLabel }}</span>
            </div>
          </el-progress>
          <div class="cleaning-summary">
            <p class="cleaning-total">
              处理总数
              <span>{{ formatCount(job?.processed ?? 0) }} / {{ formatCount(batchTotal) }}</span>
            </p>
            <div class="cleaning-stats">
              <div
                v-for="result in results"
                :key="result.status"
                class="cleaning-stat"
                :class="`cleaning-stat--${result.tone}`"
              >
                <p>
                  <AppIcon :name="result.icon" :size="15" />
                  {{ result.label }}
                </p>
                <strong>{{ formatCount(result.count) }}</strong>
                <small>({{ result.percent }}%)</small>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>

    <div v-if="jobsError || actionError" class="cleaning-error">
      <el-alert :title="actionError || jobsError" type="error" :closable="false" show-icon />
      <el-button v-if="jobsError" link type="primary" @click="refreshJobs()">刷新任务</el-button>
    </div>

    <section class="cleaning-card cleaning-results" aria-label="清洗结果">
      <div class="cleaning-results-heading">
        <el-tabs :model-value="tab" aria-label="清洗结果分类" @update:model-value="selectTab">
          <el-tab-pane name="all" :label="`全部（${formatCount(batchTotal)}）`" />
          <el-tab-pane
            v-for="result in results"
            :key="result.status"
            :name="result.status"
            :label="`${result.label}（${formatCount(result.count)}）`"
          />
        </el-tabs>
      </div>

      <el-form class="cleaning-filters" size="small" @submit.prevent="search">
        <el-form-item label="处理结果">
          <el-select v-model="filters.status" placeholder="全部">
            <el-option label="全部" value="" />
            <el-option
              v-for="(result, status) in GOODS_CLEAN_RESULTS"
              :key="status"
              :label="result.label"
              :value="status"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="规则版本">
          <el-select v-model="filters.ruleVersion" placeholder="全部">
            <el-option label="全部" value="" />
            <el-option
              v-for="version in ruleVersions"
              :key="version"
              :label="version"
              :value="version"
            />
          </el-select>
        </el-form-item>
        <el-form-item class="cleaning-search">
          <el-input
            v-model="filters.keyword"
            placeholder="搜索商品名称或货号"
            aria-label="商品关键词"
            maxlength="128"
            clearable
          >
            <template #prefix>
              <AppIcon name="search" :size="14" />
            </template>
          </el-input>
        </el-form-item>
        <div class="cleaning-filter-actions">
          <el-button @click="reset">重置</el-button>
          <el-button native-type="submit" type="primary">查询</el-button>
        </div>
      </el-form>

      <div v-if="tableError" class="cleaning-error">
        <el-alert :title="tableError.message" type="error" :closable="false" show-icon />
        <el-button link type="primary" @click="loadResults">重新查询</el-button>
      </div>
      <el-table
        v-loading="tableLoading || jobsLoading"
        :data="resultPage?.items ?? []"
        row-key="id"
        :empty-text="job ? '暂无符合条件的商品' : '暂无成功导入的批次'"
        class="cleaning-table"
        aria-label="清洗识别结果"
      >
        <el-table-column
          type="index"
          label="序号"
          width="52"
          :index="(index) => (page - 1) * size + index + 1"
        />
        <el-table-column label="品牌" width="66">
          <template #default="{ row }">{{ brandLabels[row.brandName] }}</template>
        </el-table-column>
        <el-table-column
          prop="goodsName"
          label="商品名称（goodsName）"
          min-width="178"
          show-overflow-tooltip
        />
        <el-table-column label="货号（goodsNo）" width="120">
          <template #default="{ row }">
            <span class="cleaning-goods-no">{{ row.goodsNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="清洗识别结果（结构化数据）" header-align="center">
          <el-table-column
            v-for="[field, label, width] in recognizedFields"
            :key="field"
            :label="label"
            :width="width"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{ row[field] || (field === 'colorName' && row.colorCandidates?.join(' / ')) || '—' }}
            </template>
          </el-table-column>
        </el-table-column>
        <el-table-column label="处理结果" width="108">
          <template #default="{ row }">
            <el-tag
              size="small"
              effect="light"
              disable-transitions
              class="cleaning-result-tag"
              :class="`cleaning-result-tag--${GOODS_CLEAN_RESULTS[row.status]?.tone ?? 'neutral'}`"
            >
              {{ GOODS_CLEAN_RESULTS[row.status]?.label ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="evidence"
          label="识别说明 / 命中证据"
          min-width="158"
          show-overflow-tooltip
        />
        <el-table-column label="操作" width="102" align="center">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'ERROR'"
              type="primary"
              link
              :disabled="busy || job?.status === 'RUNNING' || Boolean(jobsError)"
              :loading="action === row.id"
              @click="submitRetry(row)"
            >
              重试
            </el-button>
            <el-button
              v-if="row.status === 'REVIEW_REQUIRED'"
              type="primary"
              link
              @click="openDetail(row)"
            >
              审核
            </el-button>
            <el-button v-else type="primary" link @click="openDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <footer class="cleaning-pagination">
        <span>共 {{ formatCount(total) }} 条</span>
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          :pager-count="5"
          layout="prev, pager, next, sizes"
          background
          size="small"
          @update:page-size="resize"
        />
      </footer>
    </section>

    <CatalogCleaningDetailDialog
      :open="Boolean(detailId)"
      :detail="sourceDetail"
      :loading="detailLoading"
      :error="detailError?.message"
      :busy="action === 'review'"
      :conflict="reviewConflict"
      :review-error="reviewError"
      @close="detailId = ''"
      @reload="loadDetail"
      @review="submitReview"
    />
    <el-dialog
      :model-value="Boolean(logsJobId)"
      :title="`处理日志 · CLN-${logsJobId}`"
      width="min(860px, calc(100vw - 24px))"
      class="cleaning-log-dialog"
      @update:model-value="(value) => !value && (logsJobId = '')"
    >
      <div v-if="logsError" class="cleaning-error">
        <el-alert :title="logsError.message" type="error" :closable="false" show-icon />
        <el-button link type="primary" @click="loadLogs">重新读取</el-button>
      </div>
      <el-table
        v-loading="logsLoading"
        :data="logPage?.items ?? []"
        row-key="id"
        empty-text="暂无处理日志"
      >
        <el-table-column prop="createdAt" label="时间" width="158" />
        <el-table-column label="操作者" width="100" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.actorType === 'SYSTEM' ? '系统' : row.operatorName || '管理员' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="108">
          <template #default="{ row }">
            {{ GOODS_CLEAN_LOG_ACTIONS[row.action] ?? row.action }}
          </template>
        </el-table-column>
        <el-table-column prop="sourceId" label="来源 ID" width="90" />
        <el-table-column prop="reason" label="处理说明" min-width="180" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <div class="cleaning-pagination">
          <span>共 {{ formatCount(logsTotal) }} 条</span>
          <el-pagination
            v-model:current-page="logsPage"
            :page-size="logsSize"
            :total="logsTotal"
            :pager-count="5"
            size="small"
            layout="prev, pager, next"
          />
        </div>
      </template>
    </el-dialog>
  </section>
</template>
