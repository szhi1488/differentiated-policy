<script setup>
import AppIcon from '../../../components/AppIcon.vue'
import { RAW_GOODS_FIELDS } from '../../../constants/catalog'
import { sourceGoodsPreview } from '../../../mocks/fixtures/source-goods'

const brandTabs = [
  { label: '全部', count: sourceGoodsPreview.length, name: 'all' },
  { label: '一加', count: sourceGoodsPreview.filter(({ brand }) => brand === '一加').length },
  { label: 'OPPO', count: sourceGoodsPreview.filter(({ brand }) => brand === 'OPPO').length },
]
</script>

<template>
  <section class="source-goods" aria-labelledby="source-goods-title">
    <header class="source-banner">
      <span class="source-banner-icon"><AppIcon name="document" :size="27" /></span>
      <div class="source-heading">
        <h1 id="source-goods-title">源商品库（吉客云）</h1>
        <p>集中查看一加和 OPPO 的七个商品原始字段，为后续清洗提供数据。</p>
      </div>
      <el-button type="primary" class="source-import" disabled>
        <AppIcon name="upload" :size="16" />
        <span>导入商品</span>
      </el-button>
    </header>

    <div class="source-panel">
      <div class="source-panel-top">
        <el-tabs model-value="all" aria-label="商品品牌">
          <el-tab-pane
            v-for="tab in brandTabs"
            :key="tab.label"
            :name="tab.name || tab.label"
            :label="`${tab.label}（${tab.count}）`"
            :disabled="!tab.name"
          />
        </el-tabs>
        <el-tag type="info" effect="plain" size="small">示例数据</el-tag>
      </div>

      <el-form class="source-filters" @submit.prevent>
        <el-form-item label="品牌">
          <el-select model-value="all" aria-label="品牌" disabled>
            <el-option label="全部" value="all" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-input
            placeholder="请输入商品名称、货号或 SKU 名称"
            aria-label="商品关键词"
            disabled
          />
        </el-form-item>
        <div class="source-filter-actions">
          <el-button disabled>重置</el-button>
          <el-button type="primary" disabled>查询</el-button>
        </div>
      </el-form>

      <el-table
        :data="sourceGoodsPreview"
        row-key="goodsNo"
        class="source-table"
        aria-label="商品原文示例"
      >
        <el-table-column type="index" label="序号" width="62" align="center" />
        <el-table-column prop="brand" label="品牌" width="76" />
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

      <footer class="source-pagination">
        <span>共 {{ sourceGoodsPreview.length }} 条示例</span>
        <el-pagination
          :total="sourceGoodsPreview.length"
          :default-page-size="10"
          :page-sizes="[10, 20, 50]"
          layout="prev, pager, next, sizes"
          background
          size="small"
          disabled
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
.source-import :deep(span) {
  display: inline-flex;
  align-items: center;
  gap: 7px;
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
  grid-template-columns: 190px minmax(180px, 1fr) auto;
  gap: 12px 18px;
  align-items: center;
  padding-block: 17px;
}
.source-filters :deep(.el-form-item) {
  margin-bottom: 0;
}
.source-filters :deep(.el-form-item__label) {
  font-weight: 600;
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
@media (max-width: 850px) {
  .source-filters {
    grid-template-columns: 160px minmax(0, 1fr);
    gap: 9px 12px;
  }
  .source-filter-actions {
    grid-column: 1 / -1;
  }
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
  .source-import {
    grid-column: 2;
    justify-self: start;
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
}
</style>
