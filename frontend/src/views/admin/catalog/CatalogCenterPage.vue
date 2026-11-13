<script setup>
import { computed, ref } from 'vue'
import AppIcon from '../../../components/AppIcon.vue'
import { useCatalogCenterPreview } from '../../../composables/useCatalogCenterPreview'
import { useCatalogDictionaryPreview } from '../../../composables/useCatalogDictionaryPreview'
import { catalogCenterSamples } from '../../../mocks/fixtures/catalog-center'
import { catalogConfigLabel as specLabel } from '../../../utils/catalog-dictionary'
import CatalogDictionaryPanel from './CatalogDictionaryPanel.vue'
import CatalogPhonePreview from './CatalogPhonePreview.vue'
import CatalogProductEditor from './CatalogProductEditor.vue'
import './catalog-center.css'

const statuses = {
  active: { label: '在售', type: 'success' },
  pending: { label: '待上架', type: 'warning' },
  offline: { label: '已下架', type: 'info' },
  stopped: { label: '已停产', type: 'info' },
}
const dictionary = useCatalogDictionaryPreview(catalogCenterSamples)
const { entries: dictionaryEntries, saveEntry, removeEntry } = dictionary
const {
  usedEntryIds,
  filters,
  applied,
  categoryKeyword,
  brands,
  stats,
  activeOnly,
  seriesOptions,
  configurations,
  categoryRows,
  categoryBranches,
  matches,
  visibleRows,
  page,
  size,
  changeBrand,
  search,
  reset,
  selectCategory,
  toggleActiveOnly,
  resize,
  sort,
  savePreviewProduct,
} = useCatalogCenterPreview(dictionary)

const moreFilters = ref(false)
const activeTab = ref('products')
const categoryPanels = ref(window.matchMedia('(max-width: 600px)').matches ? [] : ['categories'])
const collapsed = ref([])
const selected = ref([])
const table = ref()
const dialogOpen = ref(false)
const dialogTitle = ref('')
const dialogNote = ref('')
const detail = ref(null)
const productEditor = ref(null)
const createNotice = ref('')
const metrics = computed(() => [
  {
    label: '商品总数',
    value: stats.value.total,
    icon: 'box',
    tone: 'blue',
    note: `覆盖 ${brands.value.length} 个品牌`,
  },
  {
    label: '在售商品',
    value: stats.value.active,
    icon: 'check',
    tone: 'green',
    note: `占全部商品 ${(stats.value.active / stats.value.total).toLocaleString('zh-CN', { style: 'percent', maximumFractionDigits: 1 })}`,
  },
  {
    label: '已停产',
    value: stats.value.stopped,
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
        ['货号', row.id],
        ['SKU', row.sku],
        ['来源名称', row.source || '—'],
        ['品牌 / 系列', `${row.brand} / ${row.series}`],
        ['配置', specLabel(row.config)],
        ['颜色', row.color],
        ['版本', row.version],
        ['更新时间', `${row.date} ${row.time}`],
      ]
    : []
})

function toggleBranch(brand) {
  collapsed.value = collapsed.value.includes(brand)
    ? collapsed.value.filter((value) => value !== brand)
    : [...collapsed.value, brand]
}

function showDetail(row) {
  detail.value = row
  dialogTitle.value = '商品详情'
  dialogNote.value = ''
  dialogOpen.value = true
}

function showNote(title, note) {
  detail.value = null
  dialogTitle.value = title
  dialogNote.value = note
  dialogOpen.value = true
}

function downloadSample(template = false) {
  const headers = ['货号', 'SKU', '商品名称', '品牌', '系列', '机型', '配置', '颜色', '版本']
  const rows = template
    ? []
    : matches.value.map((row) => [
        row.id,
        row.sku,
        row.name,
        row.brand,
        row.series,
        row.model,
        specLabel(row.config),
        row.color,
        row.version,
      ])
  const csv = [headers, ...rows]
    .map((row) => row.map((value) => '"' + value.replaceAll('"', '""') + '"').join(','))
    .join('\r\n')
  const url = URL.createObjectURL(new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = template ? '商品导入模板-示例.csv' : '商品中心-示例数据.csv'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function applyAndClear(action, ...args) {
  table.value?.clearSelection()
  action(...args)
}

function openProductEditor(product = null) {
  createNotice.value = ''
  productEditor.value = { product }
}

function saveProduct(id, fields) {
  const created = savePreviewProduct(id, fields)
  if (created) {
    activeTab.value = 'products'
    createNotice.value = created.visible ? '已新增预览商品' : '已新增预览商品。重置筛选后可查看。'
  }
}
</script>

<template>
  <section class="catalog-center" aria-labelledby="catalog-center-title">
    <header class="catalog-center-heading">
      <div>
        <div class="catalog-center-title">
          <h1 id="catalog-center-title">商品中心</h1>
          <el-tag size="small" effect="light">静态预览</el-tag>
        </div>
        <p>集中查看手机商品、规格和销售状态</p>
      </div>
      <div class="catalog-center-actions">
        <el-button size="small" @click="downloadSample(true)">
          <AppIcon name="download" :size="14" />
          下载导入模板
        </el-button>
        <el-button
          size="small"
          @click="showNote('批量导入', '可下载示例模板查看字段。静态预览暂不导入商品。')"
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
              <el-dropdown-item @click="downloadSample()">导出当前示例列表</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div class="catalog-center-metrics" aria-label="示例商品统计">
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

    <el-tabs v-model="activeTab" class="catalog-center-tabs" aria-label="商品中心视图">
      <el-tab-pane label="商品列表" name="products">
        <el-alert
          v-if="createNotice"
          :title="createNotice"
          type="success"
          show-icon
          class="catalog-center-create-notice"
          @close="createNotice = ''"
        />
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
                  aria-label="搜索品牌或系列"
                  placeholder="搜索品牌或系列"
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
                  <small>{{ categoryRows.length }}</small>
                </el-button>
                <p v-if="!categoryBranches.length" class="catalog-center-category-empty">
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
                    <el-button
                      v-for="series in branch.series"
                      :key="series.name"
                      text
                      class="catalog-center-tree-row catalog-center-series"
                      :aria-pressed="
                        applied.brand === branch.brand && applied.series === series.name
                      "
                      @click="applyAndClear(selectCategory, branch.brand, series.name)"
                    >
                      <AppIcon name="box" :size="13" />
                      {{ series.name }}
                      <small>{{ series.count }}</small>
                    </el-button>
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
                    placeholder="搜索商品名称、货号、SKU…"
                  >
                    <template #prefix><AppIcon name="search" :size="14" /></template>
                  </el-input>
                </el-form-item>
                <el-form-item label="品牌">
                  <el-select
                    :model-value="filters.brand"
                    placeholder="全部"
                    aria-label="品牌"
                    @change="changeBrand"
                  >
                    <el-option label="全部" value="" />
                    <el-option v-for="brand in brands" :key="brand" :label="brand" :value="brand" />
                  </el-select>
                </el-form-item>
                <el-form-item label="商品状态">
                  <el-select v-model="filters.status" placeholder="全部" aria-label="商品状态">
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
                  <el-select v-model="filters.series" placeholder="全部" aria-label="系列">
                    <el-option label="全部" value="" />
                    <el-option
                      v-for="series in seriesOptions"
                      :key="series"
                      :label="series"
                      :value="series"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="配置">
                  <el-select v-model="filters.config" placeholder="全部" aria-label="配置">
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
              <el-table
                ref="table"
                :data="visibleRows"
                row-key="id"
                size="small"
                border
                aria-label="商品列表"
                empty-text="暂无符合条件的商品"
                :default-sort="{ prop: 'date', order: 'descending' }"
                @sort-change="sort"
                @selection-change="selected = $event"
              >
                <el-table-column type="selection" width="35" :resizable="false" reserve-selection />
                <el-table-column
                  label="商品信息"
                  min-width="124"
                  label-class-name="catalog-center-resizable"
                >
                  <template #default="{ row }">
                    <div class="catalog-center-item" :title="`货号 ${row.id} · SKU ${row.sku}`">
                      <strong>{{ row.id }}</strong>
                      <small>SKU {{ row.sku }}</small>
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
                    <small>{{ specLabel(row.config) }} · {{ row.color }}</small>
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
                    <template v-else>{{ row[prop] }}</template>
                  </template>
                </el-table-column>
                <el-table-column
                  label="商品状态"
                  min-width="73"
                  label-class-name="catalog-center-resizable"
                >
                  <template #default="{ row }">
                    <el-tag :type="statuses[row.status].type" size="small" disable-transitions>
                      <i class="catalog-center-dot"></i>
                      {{ statuses[row.status].label }}
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
                <el-table-column label="操作" width="76" fixed="right" :resizable="false">
                  <template #default="{ row }">
                    <div class="catalog-center-row-actions">
                      <el-button link type="primary" size="small" @click="showDetail(row)">
                        查看
                      </el-button>
                      <el-button link type="primary" size="small" @click="openProductEditor(row)">
                        编辑
                      </el-button>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
              <footer class="catalog-center-footer">
                <span aria-live="polite">
                  共
                  <strong>{{ matches.length }}</strong>
                  条商品
                  <span v-if="selected.length">· 已选 {{ selected.length }} 条</span>
                </span>
                <el-pagination
                  v-model:current-page="page"
                  :page-size="size"
                  :total="matches.length"
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
          :used-entry-ids="usedEntryIds"
          :save-entry="saveEntry"
          :remove-entry="(id) => removeEntry(id, usedEntryIds)"
        />
      </el-tab-pane>
    </el-tabs>

    <CatalogProductEditor
      v-if="productEditor"
      :key="productEditor.product?.id ?? 'new-product'"
      :product="productEditor.product"
      :entries="dictionaryEntries"
      :statuses="statuses"
      @save="saveProduct"
      @close="productEditor = null"
    />

    <el-dialog
      v-model="dialogOpen"
      :title="dialogTitle"
      width="min(520px, calc(100% - 24px))"
      destroy-on-close
    >
      <div v-if="detail" class="catalog-center-detail">
        <CatalogPhonePreview :tint="detail.tint" />
        <div>
          <strong>{{ detail.name }}</strong>
          <p>{{ specLabel(detail.config) }} · {{ detail.color }}</p>
        </div>
        <el-tag :type="statuses[detail.status].type" size="small">
          {{ statuses[detail.status].label }}
        </el-tag>
      </div>
      <p v-if="dialogNote">{{ dialogNote }}</p>
      <dl v-if="detail" class="catalog-center-fields">
        <div v-for="[label, value] in detailFields" :key="label">
          <dt>{{ label }}</dt>
          <dd>{{ value }}</dd>
        </div>
      </dl>
      <template #footer><el-button @click="dialogOpen = false">关闭</el-button></template>
    </el-dialog>
  </section>
</template>
