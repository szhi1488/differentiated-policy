<script setup>
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import { computed, reactive, ref } from 'vue'
import AppIcon from '../../../components/AppIcon.vue'
import { useSourceGoods } from '../../../composables/useSourceGoods'
import {
  GOODS_BRANDS,
  GOODS_SYNC_ERRORS,
  GOODS_SYNC_MODES,
  GOODS_SYNC_PHASES,
  GOODS_SYNC_STATUS,
  RAW_GOODS_FIELDS,
  SOURCE_GOODS_FIELDS,
} from '../../../constants/catalog'

const {
  brand,
  input,
  createdDateInput,
  createdDate,
  page,
  size,
  goods,
  latest,
  activeJob,
  configured,
  jobsError,
  actionError,
  action,
  loadGoods,
  search,
  reset,
  selectBrand,
  viewCreatedDate,
  resize,
  refreshJobs,
  start,
  resume,
  cancel,
} = useSourceGoods()
const { data, loading, error } = goods
const fields = [
  ...RAW_GOODS_FIELDS.slice(0, 2),
  ...SOURCE_GOODS_FIELDS,
  ...RAW_GOODS_FIELDS.slice(2),
]
const importOpen = ref(false)
const importForm = reactive({ brandName: '', mode: 'FULL', date: null })
const importModes = ['FULL', 'CREATED']
const displayedJob = computed(() => activeJob.value ?? latest.value[importForm.brandName])
const canStart = computed(
  () =>
    configured.value &&
    !activeJob.value &&
    !action.value &&
    !jobsError.value &&
    Boolean(importForm.brandName) &&
    (importForm.mode === 'FULL' || Boolean(importForm.date)),
)
const businessTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  dateStyle: 'short',
  timeStyle: 'medium',
  hourCycle: 'h23',
})

function formatSourceCell(_row, { property }, value) {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  if (property === 'gmtModified' || property === 'goodsGmtModified') {
    const time = businessTime.format(value)
    const milliseconds = value % 1000
    return milliseconds === 0 ? time : `${time}.${String(milliseconds).padStart(3, '0')}`
  }
  if (property === 'isBlockup') {
    return value ? '已停用' : '启用'
  }
  if (property === 'isPackageGood') {
    return value ? '是' : '否'
  }
  return value
}

function openImport() {
  importForm.brandName =
    activeJob.value?.brandName ?? (brand.value === 'all' ? importForm.brandName : brand.value)
  importOpen.value = true
}

function startImport() {
  if (canStart.value) {
    const { brandName, mode, date } = importForm
    void start(brandName, { mode, date: mode === 'FULL' ? null : date })
  }
}

function showCreatedGoods(job) {
  importOpen.value = false
  void viewCreatedDate(job)
}

function refresh() {
  void loadGoods()
  void refreshJobs()
}

async function confirmCancel(job) {
  try {
    await ElMessageBox.confirm(
      '结束后不能继续此任务，已入库的商品原文会保留。',
      `结束${job.brandName}导入`,
      {
        type: 'warning',
        confirmButtonText: '结束任务',
        cancelButtonText: '返回',
      },
    )
  } catch {
    return
  }
  await cancel(job)
}
</script>

<template>
  <section class="source-goods" aria-labelledby="source-goods-title">
    <header class="source-banner">
      <span class="source-banner-icon"><AppIcon name="document" :size="27" /></span>
      <div class="source-heading">
        <h1 id="source-goods-title">源商品库（吉客云）</h1>
        <p>按品牌查询和导入吉客云商品。</p>
      </div>
      <div class="source-banner-actions">
        <el-tag v-if="activeJob" :type="GOODS_SYNC_STATUS[activeJob.status]?.[1]" size="small">
          {{ GOODS_SYNC_STATUS[activeJob.status]?.[0] }}
        </el-tag>
        <el-button type="primary" @click="openImport">
          <AppIcon name="upload" :size="15" />
          <span>导入商品</span>
        </el-button>
      </div>
    </header>

    <div class="source-panel">
      <div class="source-panel-top">
        <el-tabs :model-value="brand" aria-label="商品品牌" @update:model-value="selectBrand">
          <el-tab-pane name="all" label="全部" />
          <el-tab-pane v-for="name in GOODS_BRANDS" :key="name" :name="name" :label="name" />
        </el-tabs>
        <el-button text type="primary" :disabled="Boolean(action)" @click="refresh">刷新</el-button>
      </div>

      <el-form class="source-filters" size="small" @submit.prevent="search">
        <el-form-item label="建档日" class="source-created-filter">
          <el-date-picker
            v-model="createdDateInput"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="全部日期"
            aria-label="建档日筛选"
          />
        </el-form-item>
        <el-form-item class="source-search">
          <el-input
            v-model="input"
            placeholder="搜索商品、货号、SKU 或分类"
            aria-label="商品关键词"
            maxlength="128"
            clearable
          >
            <template #prefix>
              <AppIcon name="search" :size="14" />
            </template>
          </el-input>
        </el-form-item>
        <div class="source-filter-actions">
          <el-button @click="reset">重置</el-button>
          <el-button native-type="submit" type="primary">查询</el-button>
        </div>
      </el-form>

      <el-alert v-if="error" :title="error.message" type="error" :closable="false" show-icon />
      <el-table
        v-else
        :data="data?.items ?? []"
        :empty-text="
          loading
            ? '正在读取商品…'
            : createdDate
              ? '暂无符合条件的建档日导入记录'
              : '暂无已入库商品'
        "
        row-key="id"
        class="source-table"
        aria-label="商品原文"
      >
        <el-table-column prop="brandName" label="品牌" width="105" />
        <el-table-column
          v-for="[field, label, width] in fields"
          :key="field"
          :prop="field"
          :label="label"
          :min-width="width"
          :formatter="formatSourceCell"
          :class-name="field === 'goodsNo' || field === 'skuId' ? 'source-goods-no' : undefined"
          show-overflow-tooltip
        >
          <template #header>
            <span class="source-field-title">
              {{ label }}
              <small v-if="field === 'createdDate'">按建档日导入确认</small>
              <small v-else>{{ field }}</small>
            </span>
          </template>
        </el-table-column>
      </el-table>

      <footer v-if="data" class="source-pagination">
        <span>共 {{ data.total }} 条</span>
        <el-pagination
          v-model:current-page="page"
          :total="data.total"
          :page-size="size"
          :page-sizes="[10, 20, 50]"
          :pager-count="5"
          layout="prev, pager, next, sizes, jumper"
          background
          size="small"
          @update:page-size="resize"
        />
      </footer>
    </div>

    <el-dialog
      v-model="importOpen"
      title="导入商品"
      width="min(460px, calc(100vw - 24px))"
      destroy-on-close
    >
      <el-alert v-if="jobsError" :title="jobsError" type="error" :closable="false" show-icon />
      <el-alert
        v-else-if="!configured"
        title="请完成吉客云凭证和导入配置后再发起导入。"
        type="info"
        :closable="false"
        show-icon
      />
      <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" show-icon />
      <el-form
        v-if="!activeJob"
        :model="importForm"
        :disabled="Boolean(action)"
        label-position="top"
        class="source-import-form"
        @submit.prevent
      >
        <el-form-item label="品牌" required>
          <el-select v-model="importForm.brandName" placeholder="选择商品品牌">
            <el-option v-for="name in GOODS_BRANDS" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
        <el-form-item label="导入范围" required>
          <el-select v-model="importForm.mode">
            <el-option
              v-for="mode in importModes"
              :key="mode"
              :value="mode"
              :label="GOODS_SYNC_MODES[mode]"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="importForm.mode === 'CREATED'" label="建档日" required>
          <el-date-picker
            v-model="importForm.date"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择北京时间日期"
          />
        </el-form-item>
      </el-form>

      <div v-if="displayedJob" class="source-job" aria-live="polite">
        <div class="source-job-heading">
          <strong>{{ displayedJob.brandName }}</strong>
          <el-tag :type="GOODS_SYNC_STATUS[displayedJob.status]?.[1]" size="small">
            {{ GOODS_SYNC_STATUS[displayedJob.status]?.[0] }}
          </el-tag>
        </div>
        <p>
          {{ GOODS_SYNC_MODES[displayedJob.syncMode] }}
          <template v-if="displayedJob.syncDate">· {{ displayedJob.syncDate }}</template>
          <template v-if="GOODS_SYNC_PHASES[displayedJob.syncPhase]">
            · {{ GOODS_SYNC_PHASES[displayedJob.syncPhase] }}
          </template>
        </p>
        <p>
          已读取 {{ displayedJob.readCount }} 条 · 本任务去重 {{ displayedJob.contentCount }} 条
        </p>
        <p
          v-if="displayedJob.errorCode && displayedJob.status === 'PAUSED'"
          class="source-job-error"
        >
          {{ GOODS_SYNC_ERRORS[displayedJob.errorCode] ?? '导入暂时中断，请核对后继续。' }}
        </p>
        <el-button
          v-if="displayedJob.syncMode === 'CREATED' && displayedJob.contentCount > 0"
          type="primary"
          link
          @click="showCreatedGoods(displayedJob)"
        >
          查看该日商品
        </el-button>
      </div>
      <template #footer>
        <div class="source-import-actions">
          <el-button @click="importOpen = false">关闭</el-button>
          <template v-if="activeJob?.status === 'PAUSED'">
            <el-button :disabled="Boolean(action || jobsError)" @click="confirmCancel(activeJob)">
              结束任务
            </el-button>
            <el-button
              type="primary"
              :disabled="!configured || !activeJob.canResume || Boolean(action || jobsError)"
              @click="resume(activeJob)"
            >
              {{ activeJob.canResume ? '继续导入' : '等待冷却' }}
            </el-button>
          </template>
          <el-button
            v-else-if="!activeJob"
            type="primary"
            :loading="Boolean(action)"
            :disabled="!canStart"
            @click="startImport"
          >
            开始导入
          </el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.source-goods {
  min-width: 0;
}
.source-banner {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 16px;
  padding: 16px 18px;
  min-height: 86px;
  border: 1px solid #e3ecfb;
  border-radius: 10px;
  background: linear-gradient(105deg, #e6efff, #f0f5ff 76%, #e8f1ff);
}
.source-banner-icon {
  display: grid;
  place-items: center;
  width: 44px;
  height: 46px;
  border-radius: 9px;
  background: linear-gradient(150deg, #5ca5ff, #267bf2);
  color: #fff;
  box-shadow: 0 4px 10px #3079dc24;
}
.source-heading h1 {
  font-size: 23px;
  font-weight: 700;
  color: #10213a;
}
.source-heading p {
  margin-top: 5px;
  color: #465b77;
  line-height: 1.6;
}
.source-banner-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.source-banner-actions svg {
  margin-right: 6px;
}
.source-import-form {
  margin-top: 12px;
}
.source-import-form :deep(.el-select),
.source-import-form :deep(.el-date-editor.el-input) {
  width: 100%;
}
.source-job {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 6px;
  background: #f5f8fd;
}
.source-job-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.source-job-heading strong {
  color: #10213a;
}
.source-job p {
  margin-top: 4px;
  font-size: 13px;
  color: #465b77;
}
.source-job p.source-job-error {
  color: #975b13;
}
.source-job .el-button {
  margin-top: 6px;
}
.source-import-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}
.source-import-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.source-panel {
  min-width: 0;
  margin-top: 12px;
  padding: 0 16px 4px;
  border: 1px solid #e8eef8;
  border-radius: 10px;
  background: #fff;
}
.source-panel-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border-bottom: 1px solid #ebf0f7;
}
.source-panel-top :deep(.el-tabs__header) {
  margin: 0;
}
.source-panel-top :deep(.el-tabs__nav-wrap::after) {
  display: none;
}
.source-panel-top :deep(.el-tabs__item) {
  height: 49px;
  font-weight: 600;
  font-size: 13px;
}
.source-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  align-items: center;
  padding-block: 15px;
}
.source-filters :deep(.el-form-item) {
  margin-bottom: 0;
}
.source-created-filter :deep(.el-date-editor.el-input) {
  width: 144px;
}
.source-search {
  flex: 1 1 160px;
  min-width: 0;
  max-width: 318px;
}
.source-filter-actions {
  display: flex;
  justify-content: flex-end;
  margin-left: auto;
}
.source-table {
  width: 100%;
  --el-table-header-bg-color: #f4f7fb;
  --el-table-header-text-color: #233550;
  --el-table-text-color: #2d3e58;
  --el-table-border-color: #edf1f7;
  border: 1px solid var(--el-table-border-color);
  border-radius: 8px;
}
.source-table :deep(.el-table__cell) {
  padding-block: 12px;
}
.source-field-title small {
  display: block;
  color: #5f718b;
  font-size: 11px;
  font-weight: 500;
  line-height: 1.35;
}
.source-table :deep(td.source-goods-no .cell) {
  color: #284f86;
  font-variant-numeric: tabular-nums;
}
.source-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding: 15px 0;
  color: #465b77;
}
.source-pagination :deep(.el-pagination) {
  min-width: 0;
  flex-wrap: wrap;
  row-gap: 6px;
}
@media (max-width: 600px) {
  .source-banner {
    gap: 10px;
    padding: 13px 12px;
    grid-template-columns: minmax(0, 1fr) auto;
  }
  .source-banner-icon {
    display: none;
  }
  .source-banner-actions {
    flex-direction: column-reverse;
    align-items: flex-end;
    gap: 4px;
  }
  .source-heading h1 {
    font-size: 19px;
  }
  .source-heading p {
    font-size: 12px;
  }
  .source-panel {
    padding-inline: 10px;
  }
  .source-panel-top :deep(.el-tabs__item) {
    padding-inline: 8px;
    font-size: 12px;
  }
  .source-filters {
    gap: 8px 12px;
    padding-block: 12px;
  }
  .source-search {
    max-width: none;
  }
  .source-pagination :deep(.el-pagination__sizes) {
    display: none;
  }
}
</style>
