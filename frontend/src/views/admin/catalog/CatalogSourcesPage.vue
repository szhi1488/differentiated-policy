<script setup>
import { ElMessageBox } from 'element-plus'
import AppIcon from '../../../components/AppIcon.vue'
import { useSourceGoods } from '../../../composables/useSourceGoods'
import {
  GOODS_BRANDS,
  GOODS_SYNC_ERRORS,
  GOODS_SYNC_STATUS,
  RAW_GOODS_FIELDS,
} from '../../../constants/catalog'

const {
  brand,
  input,
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
  resize,
  refreshJobs,
  start,
  resume,
  cancel,
} = useSourceGoods()
const { data, loading, error } = goods

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
        <p>按品牌从吉客云导入商品原文。每次只导入一个品牌，完成后可发起另一个品牌。</p>
      </div>
    </header>

    <el-alert
      v-if="jobsError"
      class="source-notice"
      :title="jobsError"
      type="error"
      :closable="false"
      show-icon
    />
    <el-alert
      v-else-if="!configured"
      class="source-notice"
      title="请完成吉客云凭证和导入配置后再发起导入。"
      type="info"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="actionError"
      class="source-notice"
      :title="actionError"
      type="error"
      :closable="false"
      show-icon
    />
    <div class="source-jobs" aria-label="分品牌导入">
      <div v-for="name in GOODS_BRANDS" :key="name" class="source-job">
        <div class="source-job-heading">
          <h2>{{ name }}</h2>
          <el-tag
            v-if="latest[name]"
            size="small"
            :type="GOODS_SYNC_STATUS[latest[name].status]?.[1]"
          >
            {{ GOODS_SYNC_STATUS[latest[name].status]?.[0] }}
          </el-tag>
        </div>
        <p v-if="latest[name]" aria-live="polite">
          已读取 {{ latest[name].readCount }} 条 · 本任务去重 {{ latest[name].contentCount }} 条
        </p>
        <p v-else>尚未导入 · 仅查询启用商品</p>
        <p
          v-if="latest[name]?.errorCode && latest[name].status === 'PAUSED'"
          class="source-job-error"
        >
          {{ GOODS_SYNC_ERRORS[latest[name].errorCode] ?? '导入暂时中断，请核对后继续。' }}
        </p>
        <p
          v-else-if="
            latest[name]?.status === 'SUCCEEDED' && latest[name].cleanStatus === 'NOT_STARTED'
          "
        >
          商品原文已保存，等待清洗。
        </p>
        <div class="source-job-actions">
          <template v-if="latest[name]?.status === 'PAUSED'">
            <el-button
              type="primary"
              :disabled="!configured || !latest[name].canResume || Boolean(action || jobsError)"
              @click="resume(latest[name])"
            >
              {{ latest[name].canResume ? '继续导入' : '等待冷却' }}
            </el-button>
            <el-button
              :disabled="Boolean(action || jobsError)"
              @click="confirmCancel(latest[name])"
            >
              结束任务
            </el-button>
          </template>
          <el-button
            v-else
            type="primary"
            :loading="action === name"
            :disabled="!configured || Boolean(activeJob || action || jobsError)"
            @click="start(name)"
          >
            导入{{ name }}
          </el-button>
        </div>
      </div>
    </div>

    <div class="source-panel">
      <div class="source-panel-top">
        <el-tabs :model-value="brand" aria-label="商品品牌" @update:model-value="selectBrand">
          <el-tab-pane name="all" label="全部" />
          <el-tab-pane v-for="name in GOODS_BRANDS" :key="name" :name="name" :label="name" />
        </el-tabs>
        <el-button text type="primary" :disabled="Boolean(action)" @click="refresh">刷新</el-button>
      </div>

      <el-form class="source-filters" @submit.prevent="search">
        <el-form-item>
          <el-input
            v-model="input"
            placeholder="请输入商品名称、货号或 SKU 名称"
            aria-label="商品关键词"
            maxlength="128"
            clearable
          />
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
        :empty-text="loading ? '正在读取商品…' : '暂无已入库商品'"
        row-key="id"
        class="source-table"
        aria-label="商品原文"
      >
        <el-table-column prop="brandName" label="品牌" width="105" />
        <el-table-column
          v-for="[field, label, width] in RAW_GOODS_FIELDS"
          :key="field"
          :prop="field"
          :label="label"
          :min-width="width"
          show-overflow-tooltip
        >
          <template #header>
            <span class="source-field-title">
              {{ label }}
              <small>{{ field }}</small>
            </span>
          </template>
          <template #default="{ row }">
            <span :class="{ 'source-goods-no': field === 'goodsNo' }">{{ row[field] ?? '—' }}</span>
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
  </section>
</template>

<style scoped>
.source-goods {
  min-width: 0;
}
.source-banner {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
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
.source-notice {
  margin-top: 12px;
}
.source-jobs {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 12px;
}
.source-job {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 13px 16px;
  border: 1px solid #e8eef8;
  border-radius: 10px;
  background: #fff;
}
.source-job-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.source-job-heading h2 {
  font-size: 15px;
  color: #10213a;
}
.source-job p {
  font-size: 13px;
  color: #465b77;
}
.source-job p.source-job-error {
  color: #975b13;
}
.source-job-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: auto;
}
.source-job-actions :deep(.el-button + .el-button) {
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
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px 18px;
  align-items: center;
  padding-block: 17px;
}
.source-filters :deep(.el-form-item) {
  margin-bottom: 0;
}
.source-filter-actions {
  display: flex;
  justify-content: flex-end;
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
.source-goods-no {
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
    grid-template-columns: auto minmax(0, 1fr);
  }
  .source-heading h1 {
    font-size: 19px;
  }
  .source-heading p {
    font-size: 12px;
  }
  .source-jobs {
    grid-template-columns: minmax(0, 1fr);
  }
  .source-panel {
    padding-inline: 10px;
  }
  .source-panel-top :deep(.el-tabs__item) {
    padding-inline: 8px;
    font-size: 12px;
  }
  .source-filters {
    grid-template-columns: 1fr;
    padding-block: 12px;
  }
  .source-pagination :deep(.el-pagination__sizes) {
    display: none;
  }
}
</style>
