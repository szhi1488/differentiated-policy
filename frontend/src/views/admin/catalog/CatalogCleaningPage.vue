<script setup>
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import {
  GOODS_BRANDS,
  GOODS_CLEAN_JOB_LABELS,
  GOODS_CLEAN_RESULTS,
} from '../../../constants/catalog'
import { useCatalogCleaning } from '../../../composables/useCatalogCleaning'
import CatalogCleaningDetailDialog from './CatalogCleaningDetailDialog.vue'
import CatalogCleaningJobProgress from './CatalogCleaningJobProgress.vue'
import './catalog-cleaning.css'

const {
  filters,
  tab,
  page,
  size,
  total,
  table,
  loadResults,
  search,
  reset,
  selectTab,
  resize,
  startOpen,
  scope,
  preview,
  previewPage,
  previewSize,
  previewSearch,
  previewReady,
  submittedJob,
  openStart,
  loadPreview,
  searchPreview,
  confirmedScope,
  start,
  historyOpen,
  history,
  historyPage,
  historySize,
  openHistory,
  loadHistory,
  active,
  progressError,
  action,
  actionError,
  busy,
  retry,
  detailId,
  detail,
  openDetail,
  loadDetail,
  review,
  reviewError,
  reviewConflict,
} = useCatalogCleaning()

const { data: resultPage, loading: tableLoading, error: tableError } = table
const { data: previewData, loading: previewLoading, error: previewError } = preview
const { data: historyData, loading: historyLoading, error: historyError } = history
const { data: sourceDetail, loading: detailLoading, error: detailError } = detail
const confirming = ref(false)
const formatCount = new Intl.NumberFormat('zh-CN').format
const tabs = [
  ['', '全部'],
  ['PENDING', '待清洗'],
  ['REVIEW_REQUIRED', '待人工审核'],
  ['PASSED', '已通过'],
  ['ERROR', '技术失败'],
  ['EXCLUDED', '已排除'],
]
const canStart = computed(() => !confirming.value && Boolean(confirmedScope()))
const closeStart = computed(() => !confirming.value && action.value !== 'start')

function recognized(row) {
  const variant = [row.modelName, row.spec, row.colorName || row.colorCandidates?.join(' / ')]
    .filter(Boolean)
    .join(' · ')
  return [row.seriesName, variant].filter(Boolean).join(' / ') || '—'
}

function jobScope(job) {
  if (job.targetSourceId) {
    return '单条技术重试'
  }
  return job.createdDate || '该品牌全部已入库商品'
}

async function submitStart() {
  const request = confirmedScope()
  if (!request || confirming.value) {
    return
  }
  const range = request.createdDate ? request.createdDate + ' 建档的商品' : '该品牌全部已入库商品'
  const extra = request.includeReviews ? '，含未建立映射的待审商品' : ''
  confirming.value = true
  try {
    await ElMessageBox.confirm(
      '确认处理 ' +
        request.brandName +
        ' · ' +
        range +
        '，共 ' +
        formatCount(request.expectedTotal) +
        ' 条' +
        extra +
        '？',
      '确认开始清洗',
      { type: 'warning', confirmButtonText: '确认清洗', cancelButtonText: '返回预览' },
    )
    if (await start(request)) {
      ElMessage.success('清洗任务已受理。')
    }
  } catch {
    // 取消确认时保留原来的范围和预览。
  } finally {
    confirming.value = false
  }
}

async function submitRetry(row) {
  if (await retry(row)) {
    ElMessage.success('重试已受理。')
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
    <header class="cleaning-page-heading">
      <h1 id="cleaning-title">商品清洗与识别</h1>
      <div class="cleaning-page-actions">
        <el-button @click="openHistory">{{ active ? '清洗记录（进行中）' : '清洗记录' }}</el-button>
        <el-button type="primary" :disabled="busy" @click="openStart">开始清洗</el-button>
      </div>
    </header>

    <el-alert
      v-if="actionError && !startOpen"
      :title="actionError"
      type="error"
      :closable="false"
      show-icon
    />

    <section class="cleaning-card" aria-label="清洗结果">
      <el-form class="cleaning-filters" size="small" @submit.prevent="search">
        <el-form-item label="品牌">
          <el-select
            v-model="filters.brandName"
            aria-label="筛选品牌"
            class="cleaning-brand-select"
            :empty-values="[null, undefined]"
          >
            <el-option label="全部品牌" value="" />
            <el-option v-for="brand in GOODS_BRANDS" :key="brand" :label="brand" :value="brand" />
          </el-select>
        </el-form-item>
        <el-form-item label="建档日期">
          <el-select
            v-model="filters.dateMode"
            aria-label="筛选建档日期范围"
            class="cleaning-date-select"
          >
            <el-option label="不限" value="ALL" />
            <el-option label="指定一天" value="DATE" />
            <el-option label="建档日未知" value="UNKNOWN" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="filters.dateMode === 'DATE'">
          <el-date-picker
            v-model="filters.createdDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择建档日期"
            aria-label="筛选建档日期"
          />
        </el-form-item>
        <el-form-item class="cleaning-keyword">
          <el-input
            v-model="filters.keyword"
            aria-label="商品关键词"
            placeholder="商品名称或货号"
            maxlength="128"
            clearable
          />
        </el-form-item>
        <div class="cleaning-filter-actions">
          <el-button @click="reset">重置</el-button>
          <el-button
            native-type="submit"
            type="primary"
            :disabled="filters.dateMode === 'DATE' && !filters.createdDate"
          >
            查询
          </el-button>
        </div>
      </el-form>

      <el-tabs
        :model-value="tab"
        class="cleaning-tabs"
        aria-label="清洗结果分类"
        @update:model-value="selectTab"
      >
        <el-tab-pane
          v-for="[key, label] in tabs"
          :key="key"
          :name="key"
          :label="
            resultPage
              ? label + '（' + formatCount(resultPage.counts[key || 'ALL'] ?? 0) + '）'
              : label
          "
        />
      </el-tabs>

      <div v-if="tableError" class="cleaning-inline-error">
        <el-alert :title="tableError.message" type="error" :closable="false" show-icon />
        <el-button link type="primary" @click="loadResults">重新查询</el-button>
      </div>
      <el-table
        v-else
        v-loading="tableLoading"
        :data="resultPage?.items ?? []"
        row-key="id"
        empty-text="暂无符合条件的商品"
        aria-label="清洗识别结果"
      >
        <el-table-column prop="brandName" label="品牌" width="102" />
        <el-table-column prop="goodsName" label="商品名称" min-width="230" show-overflow-tooltip />
        <el-table-column prop="goodsNo" label="货号" width="128" show-overflow-tooltip />
        <el-table-column label="建档日期" width="112">
          <template #default="{ row }">{{ row.createdDate || '未知' }}</template>
        </el-table-column>
        <el-table-column label="识别结果" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ recognized(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="116">
          <template #default="{ row }">
            <el-tag
              size="small"
              disable-transitions
              :type="
                { passed: 'success', review: 'warning', failed: 'danger', neutral: 'info' }[
                  GOODS_CLEAN_RESULTS[row.status]?.tone
                ]
              "
            >
              {{ GOODS_CLEAN_RESULTS[row.status]?.label ?? row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="118" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'ERROR'"
              type="primary"
              link
              :disabled="busy || Boolean(active)"
              :loading="action === row.id"
              @click="submitRetry(row)"
            >
              重试
            </el-button>
            <el-button type="primary" link @click="openDetail(row)">
              {{ row.status === 'REVIEW_REQUIRED' ? '审核' : '查看' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <footer v-if="resultPage" class="cleaning-pagination">
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

    <el-dialog
      v-model="startOpen"
      title="开始清洗"
      width="min(1060px, calc(100vw - 24px))"
      class="cleaning-dialog"
      :close-on-click-modal="false"
      :show-close="closeStart"
      :close-on-press-escape="closeStart"
    >
      <template v-if="submittedJob">
        <div class="cleaning-job-heading">
          <strong>{{ submittedJob.brandName }} · {{ jobScope(submittedJob) }}</strong>
        </div>
        <CatalogCleaningJobProgress :job="submittedJob" />
        <el-alert
          v-if="progressError"
          :title="progressError"
          type="error"
          :closable="false"
          show-icon
        />
      </template>
      <template v-else>
        <el-form
          class="cleaning-scope-form"
          label-position="top"
          :disabled="busy || confirming"
          @submit.prevent="searchPreview"
        >
          <div class="cleaning-scope-fields">
            <el-form-item label="品牌" required>
              <el-select v-model="scope.brandName" aria-label="清洗品牌" placeholder="请选择品牌">
                <el-option
                  v-for="brand in GOODS_BRANDS"
                  :key="brand"
                  :label="brand"
                  :value="brand"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="清洗范围" required>
              <el-select v-model="scope.dateMode" aria-label="清洗范围">
                <el-option label="指定建档日期" value="DATE" />
                <el-option label="该品牌全部已入库商品" value="ALL" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="scope.dateMode === 'DATE'" label="建档日期" required>
              <el-date-picker
                v-model="scope.createdDate"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="请选择建档日期"
                aria-label="清洗建档日期"
              />
            </el-form-item>
          </div>
          <el-checkbox v-model="scope.includeReviews">同时重新识别未建立映射的待审商品</el-checkbox>
          <div v-if="previewReady" class="cleaning-preview-heading">
            <h3>商品预览</h3>
            <div class="cleaning-preview-search">
              <el-input
                v-model="previewSearch"
                aria-label="预览商品关键词"
                placeholder="商品名称或货号"
                maxlength="128"
                clearable
                @clear="searchPreview"
              />
              <el-button native-type="submit">查询</el-button>
            </div>
          </div>
        </el-form>
        <p v-if="!previewReady" class="cleaning-hint">选好品牌和清洗范围后查看对应商品。</p>
        <div v-else-if="previewError" class="cleaning-inline-error">
          <el-alert :title="previewError.message" type="error" :closable="false" show-icon />
          <el-button link type="primary" @click="loadPreview">重新读取</el-button>
        </div>
        <template v-else>
          <el-table
            v-loading="previewLoading"
            :data="previewData?.items ?? []"
            row-key="id"
            empty-text="暂无符合条件的商品"
            max-height="380"
            aria-label="待清洗范围商品预览"
          >
            <el-table-column
              prop="goodsName"
              label="商品名称"
              min-width="220"
              show-overflow-tooltip
            />
            <el-table-column prop="goodsNo" label="货号" width="130" show-overflow-tooltip />
            <el-table-column
              prop="skuName"
              label="SKU 名称"
              min-width="140"
              show-overflow-tooltip
            />
            <el-table-column label="建档日期" width="112">
              <template #default="{ row }">{{ row.createdDate || '未知' }}</template>
            </el-table-column>
            <el-table-column label="已识别机型" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ recognized(row) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="112">
              <template #default="{ row }">
                {{ GOODS_CLEAN_RESULTS[row.status]?.label ?? row.status }}
              </template>
            </el-table-column>
          </el-table>
          <div v-if="previewData" class="cleaning-pagination">
            <span>预览共 {{ formatCount(previewData.total) }} 条</span>
            <el-pagination
              v-model:current-page="previewPage"
              :page-size="previewSize"
              :total="previewData.total"
              :pager-count="5"
              size="small"
              layout="prev, pager, next"
              :disabled="busy || confirming"
            />
          </div>
        </template>
        <el-alert
          v-if="actionError"
          :title="actionError"
          type="error"
          :closable="false"
          show-icon
        />
      </template>
      <template #footer>
        <div class="cleaning-dialog-footer">
          <span v-if="!submittedJob && previewData" aria-live="polite">
            {{
              previewData.eligible
                ? '本次待清洗 ' + formatCount(previewData.eligible) + ' 条'
                : '当前范围暂无可清洗商品'
            }}
          </span>
          <div class="cleaning-page-actions">
            <el-button :disabled="!closeStart" @click="startOpen = false">关闭</el-button>
            <el-button
              v-if="!submittedJob"
              type="primary"
              :disabled="!canStart"
              :loading="action === 'start'"
              @click="submitStart"
            >
              清洗
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="historyOpen"
      title="清洗记录"
      width="min(900px, calc(100vw - 24px))"
      class="cleaning-dialog"
    >
      <template v-if="active">
        <div class="cleaning-job-heading">
          <strong>{{ active.brandName }} · {{ jobScope(active) }}</strong>
        </div>
        <CatalogCleaningJobProgress :job="active" />
      </template>
      <el-alert
        v-if="progressError"
        :title="progressError"
        type="error"
        :closable="false"
        show-icon
      />
      <div v-if="historyError" class="cleaning-inline-error">
        <el-alert :title="historyError.message" type="error" :closable="false" show-icon />
        <el-button link type="primary" @click="loadHistory">重新读取</el-button>
      </div>
      <el-table
        v-else
        v-loading="historyLoading"
        :data="historyData?.items ?? []"
        row-key="id"
        empty-text="暂无清洗记录"
      >
        <el-table-column prop="createdAt" label="发起时间" width="164" />
        <el-table-column prop="brandName" label="品牌" width="100" />
        <el-table-column label="范围" min-width="165">
          <template #default="{ row }">{{ jobScope(row) }}</template>
        </el-table-column>
        <el-table-column prop="requesterName" label="发起人" width="100" show-overflow-tooltip />
        <el-table-column label="状态" width="128">
          <template #default="{ row }">{{ GOODS_CLEAN_JOB_LABELS[row.status] }}</template>
        </el-table-column>
        <el-table-column label="进度" width="92">
          <template #default="{ row }">{{ row.processed }} / {{ row.total }}</template>
        </el-table-column>
        <el-table-column label="处理结果" min-width="228">
          <template #default="{ row }">
            通过
            {{ (row.resultCounts?.AUTO_PASSED ?? 0) + (row.resultCounts?.MANUAL_PASSED ?? 0) }} ·
            待审 {{ row.resultCounts?.REVIEW_REQUIRED ?? 0 }} · 失败
            {{ row.resultCounts?.ERROR ?? 0 }} · 跳过 {{ row.resultCounts?.SKIPPED ?? 0 }}
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-pagination
          v-if="historyData?.total"
          v-model:current-page="historyPage"
          :page-size="historySize"
          :total="historyData.total"
          :pager-count="5"
          size="small"
          layout="prev, pager, next"
        />
      </template>
    </el-dialog>

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
  </section>
</template>
