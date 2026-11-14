<script setup>
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import AppIcon from '../../../components/AppIcon.vue'
import { useCatalogCenter } from '../../../composables/useCatalogCenter'
import { useCatalogDictionary } from '../../../composables/useCatalogDictionary'
import { CATALOG_SALES_STATES as statuses } from '../../../constants/catalog'
import { catalogConfigLabel as specLabel } from '../../../utils/catalog-dictionary'
import CatalogDictionaryPanel from './CatalogDictionaryPanel.vue'
import CatalogPhonePreview from './CatalogPhonePreview.vue'
import CatalogProductEditor from './CatalogProductEditor.vue'
import './catalog-center.css'

const {
  entries: dictionaryEntries,
  loading: dictionaryLoading,
  error: dictionaryError,
  load: loadDictionary,
  save: saveEntry,
  remove: removeEntry,
} = useCatalogDictionary()
const {
  filters,
  applied,
  categoryKeyword,
  brands,
  stats,
  activeOnly,
  seriesOptions,
  configurations,
  categoryTotal,
  categoryBranches,
  total,
  visibleRows,
  page,
  size,
  list,
  summary,
  detail: detailQuery,
  edit: editQuery,
  detailId,
  load,
  loadSummary,
  loadDetail,
  loadForEdit,
  createProduct,
  updateProduct,
  deleteProduct,
  changeBrand,
  changeSeries,
  changeStatus,
  changeConfig,
  search,
  reset,
  selectCategory,
  toggleActiveOnly,
  resize,
  sort,
} = useCatalogCenter()
const { loading: listLoading, error: listError } = list
const { loading: summaryLoading, error: summaryError } = summary
const { data: detail, loading: detailLoading, error: detailError } = detailQuery
const unknownStatus = { label: '未知', type: 'info' }

const moreFilters = ref(false)
const activeTab = ref('products')
const categoryPanels = ref(window.matchMedia('(max-width: 600px)').matches ? [] : ['categories'])
const collapsed = ref([])
const expandedSeries = ref(new Set())
const selected = ref([])
const table = ref()
const dialogOpen = ref(false)
const dialogTitle = ref('')
const dialogNote = ref('')
const productEditor = ref(null)
const deletingProductId = ref('')
const metrics = computed(() => [
  {
    label: '商品总数',
    value: summaryLoading.value || summaryError.value ? '—' : stats.value.total,
    icon: 'box',
    tone: 'blue',
    note: summaryLoading.value || summaryError.value ? '' : `覆盖 ${brands.value.length} 个品牌`,
  },
  {
    label: '在售商品',
    value: summaryLoading.value || summaryError.value ? '—' : stats.value.active,
    icon: 'check',
    tone: 'green',
    note:
      summaryLoading.value || summaryError.value
        ? ''
        : `占全部商品 ${(stats.value.total ? stats.value.active / stats.value.total : 0).toLocaleString('zh-CN', { style: 'percent', maximumFractionDigits: 1 })}`,
  },
  {
    label: '已停产',
    value: summaryLoading.value || summaryError.value ? '—' : stats.value.stopped,
    icon: 'stop',
    tone: 'slate',
    note: '历史机型 / 已终止销售',
  },
])
const specColumns = [
  ['brand', '品牌', 49],
  ['series', '系列', 67],
  ['model', '机型', 70],
  ['config', '配置', 82],
  ['color', '颜色', 65],
  ['version', '版本', 54],
]
const detailFields = computed(() => {
  const row = detail.value
  return row
    ? [
        ['SKU ID', row.sku ?? '—'],
        ['货号', row.productNo ?? '—'],
        ['来源名称', row.source || '—'],
        ['品牌 / 系列', `${row.brand} / ${row.series}`],
        ['配置', specLabel(row.config)],
        ['颜色', row.color],
        ['版本', row.version || '—'],
        ['更新时间', `${row.date} ${row.time}`],
      ]
    : []
})

function toggleBranch(key) {
  collapsed.value = collapsed.value.includes(key)
    ? collapsed.value.filter((value) => value !== key)
    : [...collapsed.value, key]
}

function toggleSeries(id) {
  if (expandedSeries.value.has(id)) {
    expandedSeries.value.delete(id)
  } else {
    expandedSeries.value.add(id)
  }
}

function selectSeries(brand, series) {
  expandedSeries.value.add(series)
  applyAndClear(selectCategory, brand, series)
}

function showDetail(row) {
  detailId.value = row.id
  dialogTitle.value = '商品详情'
  dialogNote.value = ''
  dialogOpen.value = true
}

function showNote(title, note) {
  detailId.value = ''
  dialogTitle.value = title
  dialogNote.value = note
  dialogOpen.value = true
}

function downloadProducts(products, filename) {
  const headers = [
    '货号',
    ...(products ? ['SKU'] : []),
    '商品名称',
    '品牌',
    '系列',
    '机型',
    '配置',
    '颜色',
    '版本',
  ]
  const rows =
    products?.map((row) => [
      row.productNo,
      row.sku,
      row.name,
      row.brand,
      row.series,
      row.model,
      specLabel(row.config),
      row.color,
      row.version,
    ]) ?? []
  const csvCell = (value) => {
    const cell = String(value ?? '')
    const safe = /^[\s]*[=+@-]/.test(cell) || /^[\t\r\n]/.test(cell) ? "'" + cell : cell
    return '"' + safe.replaceAll('"', '""') + '"'
  }
  const csv = [headers, ...rows].map((row) => row.map(csvCell).join(',')).join('\r\n')
  const url = URL.createObjectURL(new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function applyAndClear(action, ...args) {
  table.value?.clearSelection()
  action(...args)
}

async function copyProductNo(productNo) {
  try {
    await navigator.clipboard.writeText(productNo)
    ElMessage.success('货号已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制货号')
  }
}

const editorEntries = computed(() => {
  const row = productEditor.value?.product
  if (!row) {
    return dictionaryEntries.value
  }
  const selectedEntries = [
    { id: row.brandId, type: 'brand', name: row.brand, parentId: null },
    { id: row.seriesId, type: 'series', name: row.series, parentId: row.brandId },
    { id: row.colorId, type: 'color', name: row.color, parentId: null },
  ]
  const existing = new Set(dictionaryEntries.value.map(({ id }) => id))
  return [...dictionaryEntries.value, ...selectedEntries.filter(({ id }) => !existing.has(id))]
})

function refreshDictionary() {
  if (!dictionaryLoading.value) {
    void loadDictionary()
  }
}

async function openProductEditor(row = null) {
  if (productEditor.value || editQuery.loading.value) {
    return
  }
  let product = null
  if (row) {
    await loadForEdit(row.id)
    if (editQuery.error.value) {
      ElMessage.error(editQuery.error.value.message)
      return
    }
    product = editQuery.data.value
    if (!product) {
      return
    }
  }
  refreshDictionary()
  productEditor.value = {
    product: product && {
      ...product,
      brandId: 'brand-' + product.brandId,
      seriesId: 'series-' + product.seriesId,
      colorId: 'color-' + product.colorId,
    },
  }
}
function saveProduct(product) {
  const existing = productEditor.value.product
  return existing
    ? updateProduct(existing.id, product, existing.editVersion)
    : createProduct(product)
}

function onProductSaved() {
  table.value?.clearSelection()
  ElMessage.success(productEditor.value.product ? '商品修改已保存' : '商品已新增')
}

async function confirmDeleteProduct(row) {
  if (deletingProductId.value) {
    return
  }
  deletingProductId.value = row.id
  try {
    try {
      await ElMessageBox.confirm(`确定永久删除「${row.name}」？删除后无法恢复。`, '删除商品', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
    await deleteProduct(row.id)
    void loadDictionary()
    table.value?.toggleRowSelection(row, false)
    ElMessage.success('商品已删除')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    deletingProductId.value = ''
  }
}
</script>

<template>
  <section class="catalog-center" aria-labelledby="catalog-center-title">
    <header class="catalog-center-heading">
      <div>
        <div class="catalog-center-title">
          <h1 id="catalog-center-title">商品中心</h1>
        </div>
        <p>集中查看手机商品、规格和销售状态</p>
      </div>
      <div class="catalog-center-actions">
        <el-button size="small" @click="downloadProducts(null, '商品导入模板-示例.csv')">
          <AppIcon name="download" :size="14" />
          下载导入模板
        </el-button>
        <el-button
          size="small"
          @click="showNote('批量导入', '批量导入待接入，可先下载导入模板查看字段。')"
        >
          <AppIcon name="upload" :size="14" />
          批量导入
        </el-button>
        <el-button type="success" size="small" @click="openProductEditor()">
          <AppIcon name="plus" :size="14" />
          新增商品
        </el-button>
        <el-dropdown trigger="click">
          <el-button size="small">
            更多操作
            <AppIcon name="chevron" :size="14" />
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                :disabled="!visibleRows.length"
                @click="downloadProducts(visibleRows, '商品中心-当前页.csv')"
              >
                导出当前页
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div class="catalog-center-metrics" aria-label="商品统计">
      <article
        v-for="metric in metrics"
        :key="metric.label"
        class="catalog-center-metric"
        :data-tone="metric.tone"
      >
        <h2>
          <span><AppIcon :name="metric.icon" :size="16" /></span>
          {{ metric.label }}
        </h2>
        <div>
          <strong>{{ metric.value }}</strong>
          件商品
        </div>
        <p>{{ metric.note }}</p>
      </article>
    </div>

    <el-tabs
      v-model="activeTab"
      class="catalog-center-tabs"
      aria-label="商品中心视图"
      @tab-change="(tab) => tab === 'dictionary' && refreshDictionary()"
    >
      <el-tab-pane label="商品列表" name="products">
        <el-alert
          v-if="summaryError"
          :title="summaryError.message"
          type="error"
          :closable="false"
          show-icon
          class="catalog-center-create-notice"
        >
          <el-button link type="primary" @click="loadSummary">重试读取商品统计</el-button>
        </el-alert>
        <div class="catalog-center-grid">
          <el-collapse
            v-model="categoryPanels"
            class="catalog-center-panel catalog-center-categories"
          >
            <el-collapse-item name="categories">
              <template #title>
                <AppIcon name="box" :size="16" />
                商品分类 / 品牌系列
              </template>
              <div class="catalog-center-category-body">
                <el-input
                  v-model="categoryKeyword"
                  size="small"
                  clearable
                  aria-label="搜索品牌、系列或机型"
                  placeholder="搜索品牌、系列或机型"
                >
                  <template #prefix><AppIcon name="search" :size="14" /></template>
                </el-input>
                <label class="catalog-center-switch">
                  仅看在售商品
                  <el-switch
                    :model-value="activeOnly"
                    size="small"
                    aria-label="仅看在售商品"
                    @change="applyAndClear(toggleActiveOnly, $event)"
                  />
                </label>
                <el-button
                  class="catalog-center-tree-row"
                  text
                  :aria-pressed="!applied.brand && !applied.series"
                  @click="applyAndClear(selectCategory, '')"
                >
                  <AppIcon name="list" :size="14" />
                  全部商品
                  <small>{{ categoryTotal }}</small>
                </el-button>
                <p
                  v-if="!summaryLoading && !summaryError && !categoryBranches.length"
                  class="catalog-center-category-empty"
                >
                  暂无匹配分类
                </p>
                <div
                  v-for="branch in categoryBranches"
                  :key="branch.brand"
                  class="catalog-center-branch"
                >
                  <div class="catalog-center-branch-heading">
                    <el-button
                      text
                      class="catalog-center-branch-toggle"
                      :aria-label="`${branch.brand} 系列`"
                      :aria-expanded="
                        Boolean(categoryKeyword.trim()) || !collapsed.includes(branch.brand)
                      "
                      :disabled="Boolean(categoryKeyword.trim())"
                      @click="toggleBranch(branch.brand)"
                    >
                      <AppIcon name="chevron" :size="14" />
                    </el-button>
                    <el-button
                      class="catalog-center-tree-row"
                      text
                      :aria-pressed="applied.brand === branch.brand && !applied.series"
                      @click="applyAndClear(selectCategory, branch.brand)"
                    >
                      {{ branch.brand }}
                      <small>{{ branch.count }}</small>
                    </el-button>
                  </div>
                  <div v-show="categoryKeyword.trim() || !collapsed.includes(branch.brand)">
                    <div v-for="series in branch.series" :key="series.id">
                      <div class="catalog-center-branch-heading catalog-center-series">
                        <el-button
                          v-if="series.models.length"
                          text
                          class="catalog-center-branch-toggle"
                          :aria-label="`${series.name} 机型`"
                          :aria-expanded="
                            Boolean(categoryKeyword.trim()) || expandedSeries.has(series.id)
                          "
                          :disabled="Boolean(categoryKeyword.trim())"
                          @click="toggleSeries(series.id)"
                        >
                          <AppIcon name="chevron" :size="14" />
                        </el-button>
                        <el-button
                          text
                          class="catalog-center-tree-row"
                          :aria-pressed="applied.series === series.id && !applied.model"
                          @click="selectSeries(branch.brand, series.id)"
                        >
                          <span class="catalog-center-category-name" :title="series.name">
                            {{ series.name }}
                          </span>
                          <small>{{ series.count }}</small>
                        </el-button>
                      </div>
                      <div v-show="categoryKeyword.trim() || expandedSeries.has(series.id)">
                        <el-button
                          v-for="model in series.models"
                          :key="model.id"
                          text
                          class="catalog-center-tree-row catalog-center-model"
                          :aria-pressed="applied.model === model.id"
                          @click="applyAndClear(selectCategory, branch.brand, series.id, model.id)"
                        >
                          <span class="catalog-center-category-name" :title="model.name">
                            {{ model.name }}
                          </span>
                          <small>{{ model.count }}</small>
                        </el-button>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </el-collapse-item>
          </el-collapse>

          <div class="catalog-center-results">
            <el-form
              class="catalog-center-panel catalog-center-filters"
              size="small"
              @submit.prevent="applyAndClear(search)"
            >
              <div class="catalog-center-filter-row">
                <el-form-item class="catalog-center-keyword">
                  <el-input
                    v-model="filters.keyword"
                    clearable
                    maxlength="128"
                    aria-label="商品关键词"
                    placeholder="搜索商品、机型、货号或 SKU"
                    @clear="applyAndClear(search)"
                  >
                    <template #prefix><AppIcon name="search" :size="14" /></template>
                  </el-input>
                </el-form-item>
                <el-form-item label="品牌">
                  <el-select
                    :model-value="filters.brand"
                    placeholder="全部"
                    aria-label="品牌"
                    filterable
                    @change="applyAndClear(changeBrand, $event)"
                  >
                    <el-option label="全部" value="" />
                    <el-option v-for="brand in brands" :key="brand" :label="brand" :value="brand" />
                  </el-select>
                </el-form-item>
                <el-form-item label="商品状态">
                  <el-select
                    :model-value="filters.status"
                    placeholder="全部"
                    aria-label="商品状态"
                    @change="applyAndClear(changeStatus, $event)"
                  >
                    <el-option label="全部" value="" />
                    <el-option
                      v-for="(state, key) in statuses"
                      :key="key"
                      :label="state.label"
                      :value="key"
                    />
                  </el-select>
                </el-form-item>
                <el-button
                  :aria-expanded="moreFilters"
                  aria-controls="catalog-center-more-filters"
                  @click="moreFilters = !moreFilters"
                >
                  <AppIcon name="filter" :size="14" />
                  更多筛选
                </el-button>
                <div class="catalog-center-filter-actions">
                  <el-button
                    :disabled="!selected.length || listLoading"
                    @click="downloadProducts(selected, '商品中心-已选商品.csv')"
                  >
                    <AppIcon name="download" :size="14" />
                    导出
                  </el-button>
                  <el-button @click="applyAndClear(reset)">
                    <AppIcon name="sync" :size="14" />
                    重置
                  </el-button>
                  <el-button type="primary" native-type="submit">
                    <AppIcon name="search" :size="14" />
                    查询
                  </el-button>
                </div>
              </div>
              <div
                v-show="moreFilters"
                id="catalog-center-more-filters"
                class="catalog-center-filter-row catalog-center-extra"
              >
                <el-form-item label="系列">
                  <el-select
                    :model-value="filters.series"
                    placeholder="全部"
                    aria-label="系列"
                    filterable
                    @change="applyAndClear(changeSeries, $event)"
                  >
                    <el-option label="全部" value="" />
                    <el-option
                      v-for="series in seriesOptions"
                      :key="series.seriesId"
                      :label="series.seriesName"
                      :value="series.seriesId"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="配置">
                  <el-select
                    :model-value="filters.config"
                    placeholder="全部"
                    aria-label="配置"
                    filterable
                    @change="applyAndClear(changeConfig, $event)"
                  >
                    <el-option label="全部" value="" />
                    <el-option
                      v-for="config in configurations"
                      :key="config"
                      :label="specLabel(config)"
                      :value="config"
                    />
                  </el-select>
                </el-form-item>
              </div>
            </el-form>

            <div class="catalog-center-panel catalog-center-table">
              <el-alert
                v-if="listError"
                :title="listError.message"
                type="error"
                :closable="false"
                show-icon
              >
                <el-button link type="primary" @click="load">重新读取</el-button>
              </el-alert>
              <el-table
                v-loading="listLoading"
                ref="table"
                :data="visibleRows"
                row-key="id"
                size="small"
                border
                aria-label="商品列表"
                :empty-text="
                  listLoading
                    ? '正在读取商品…'
                    : listError
                      ? '商品列表读取失败'
                      : '暂无符合条件的商品'
                "
                :default-sort="{ prop: 'date', order: 'descending' }"
                @sort-change="sort"
                @selection-change="selected = $event"
              >
                <el-table-column type="selection" width="35" :resizable="false" reserve-selection />
                <el-table-column
                  label="商品信息"
                  min-width="160"
                  label-class-name="catalog-center-resizable"
                >
                  <template #default="{ row }">
                    <div
                      class="catalog-center-item"
                      :title="`SKU ID ${row.sku ?? '—'} · 货号 ${row.productNo ?? '—'}`"
                    >
                      <strong>{{ row.sku ?? '—' }}</strong>
                      <div class="catalog-center-product-no">
                        <small>货号 {{ row.productNo ?? '—' }}</small>
                        <el-button
                          v-if="row.productNo"
                          link
                          type="primary"
                          class="catalog-center-copy"
                          aria-label="复制货号"
                          title="复制货号"
                          @click.stop="copyProductNo(row.productNo)"
                        >
                          <AppIcon name="copy" :size="13" />
                        </el-button>
                      </div>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column
                  label="商品名称"
                  min-width="126"
                  label-class-name="catalog-center-resizable"
                >
                  <template #default="{ row }">
                    <strong>{{ row.name }}</strong>
                  </template>
                </el-table-column>
                <el-table-column
                  v-for="[prop, label, width] in specColumns"
                  :key="prop"
                  :prop="prop"
                  :label="label"
                  :min-width="width"
                  label-class-name="catalog-center-resizable"
                  show-overflow-tooltip
                >
                  <template #default="{ row }">
                    <span v-if="prop === 'config'" class="catalog-center-spec">
                      {{ specLabel(row.config) }}
                    </span>
                    <template v-else>{{ row[prop] || '—' }}</template>
                  </template>
                </el-table-column>
                <el-table-column
                  label="商品状态"
                  min-width="73"
                  label-class-name="catalog-center-resizable"
                >
                  <template #default="{ row }">
                    <el-tag
                      :type="(statuses[row.status] ?? unknownStatus).type"
                      size="small"
                      disable-transitions
                    >
                      <i class="catalog-center-dot"></i>
                      {{ (statuses[row.status] ?? unknownStatus).label }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column
                  label="更新时间"
                  prop="date"
                  min-width="88"
                  label-class-name="catalog-center-resizable"
                  sortable="custom"
                  :sort-orders="['descending', 'ascending']"
                >
                  <template #default="{ row }">
                    {{ row.date }}
                    <small>{{ row.time }}</small>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="102" fixed="right" :resizable="false">
                  <template #default="{ row }">
                    <div class="catalog-center-row-actions">
                      <el-button link type="primary" size="small" @click="showDetail(row)">
                        查看
                      </el-button>
                      <el-button
                        link
                        type="primary"
                        size="small"
                        :disabled="editQuery.loading.value"
                        @click="openProductEditor(row)"
                      >
                        编辑
                      </el-button>
                      <el-button
                        link
                        type="danger"
                        size="small"
                        :disabled="Boolean(deletingProductId)"
                        @click="confirmDeleteProduct(row)"
                      >
                        删除
                      </el-button>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
              <footer v-show="!listLoading && !listError" class="catalog-center-footer">
                <span aria-live="polite">
                  共
                  <strong>{{ total }}</strong>
                  条商品
                  <span v-if="selected.length">· 已选 {{ selected.length }} 条</span>
                </span>
                <el-pagination
                  v-model:current-page="page"
                  :page-size="size"
                  :total="total"
                  :page-sizes="[10, 20, 30]"
                  :pager-count="5"
                  layout="prev, pager, next, sizes"
                  size="small"
                  background
                  @size-change="resize"
                />
              </footer>
            </div>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane label="数据字典" name="dictionary">
        <CatalogDictionaryPanel
          :entries="dictionaryEntries"
          :loading="dictionaryLoading"
          :load-error="dictionaryError?.message"
          :save-entry="saveEntry"
          :remove-entry="removeEntry"
          @reload="refreshDictionary"
        />
      </el-tab-pane>
    </el-tabs>

    <CatalogProductEditor
      v-if="productEditor"
      :key="productEditor.product?.id ?? 'new-product'"
      :product="productEditor.product"
      :entries="editorEntries"
      :dictionary-loading="dictionaryLoading"
      :dictionary-error="dictionaryError?.message"
      @reload-dictionary="refreshDictionary"
      :statuses="statuses"
      :save-product="saveProduct"
      @saved="onProductSaved"
      @close="productEditor = null"
    />

    <el-dialog
      v-model="dialogOpen"
      :title="dialogTitle"
      width="min(520px, calc(100% - 24px))"
      destroy-on-close
      @close="detailId = ''"
    >
      <p v-if="detailId && detailLoading" aria-live="polite">正在读取商品详情…</p>
      <el-alert
        v-else-if="detailId && detailError"
        :title="detailError.message"
        type="error"
        :closable="false"
        show-icon
      >
        <el-button link type="primary" @click="loadDetail">重新读取详情</el-button>
      </el-alert>
      <div v-else-if="detailId && detail" class="catalog-center-detail">
        <CatalogPhonePreview tint="#7d9cbf" />
        <div>
          <strong>{{ detail.name }}</strong>
          <p>{{ specLabel(detail.config) }} · {{ detail.color }}</p>
        </div>
        <el-tag :type="(statuses[detail.status] ?? unknownStatus).type" size="small">
          {{ (statuses[detail.status] ?? unknownStatus).label }}
        </el-tag>
      </div>
      <p v-if="dialogNote">{{ dialogNote }}</p>
      <dl v-if="detailId && detail" class="catalog-center-fields">
        <div v-for="[label, value] in detailFields" :key="label">
          <dt>{{ label }}</dt>
          <dd>{{ value }}</dd>
        </div>
      </dl>
      <template #footer><el-button @click="dialogOpen = false">关闭</el-button></template>
    </el-dialog>
  </section>
</template>
