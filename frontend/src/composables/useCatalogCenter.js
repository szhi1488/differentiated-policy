import { computed, reactive, ref, watch } from 'vue'
import {
  createCatalogGood,
  deleteCatalogGood,
  readCatalogGood,
  readCatalogGoods,
  readCatalogGoodsSummary,
} from '../api/catalog'
import { CATALOG_SALES_STATES } from '../constants/catalog'
import { catalogGoodsNameLabel } from '../utils/catalog-dictionary'
import { useLatestQuery } from './useLatestQuery'

const emptyFilters = () => ({
  keyword: '',
  brand: '',
  status: '',
  series: '',
  model: '',
  config: '',
})
const statusCodes = Object.fromEntries(
  Object.entries(CATALOG_SALES_STATES).map(([key, value]) => [value.code, key]),
)

function configKey(ram, rom) {
  return ram.slice(0, -2) + '+' + rom.slice(0, -2)
}

function toProduct(row) {
  const [date, time] = row.updatedAt.split(' ')
  return {
    id: row.id,
    sku: row.sourceSkuId,
    productNo: row.productNo,
    name: catalogGoodsNameLabel(row.goodsName),
    source: row.sourceName,
    brandId: row.brandId,
    brand: row.brandName,
    seriesId: row.seriesId,
    series: row.seriesName,
    model: row.modelName,
    config: configKey(row.ram, row.rom),
    colorId: row.colorId,
    color: row.colorName,
    version: row.edition,
    status: statusCodes[row.status] ?? '',
    date,
    time,
  }
}

export function useCatalogCenter() {
  const filters = reactive(emptyFilters())
  const applied = ref(emptyFilters())
  const page = ref(1)
  const size = ref(10)
  const ascending = ref(false)
  const categoryKeyword = ref('')
  const list = useLatestQuery()
  const summary = useLatestQuery()
  const detail = useLatestQuery()
  const detailId = ref('')
  const total = ref(0)
  const visibleRows = computed(() => list.data.value?.items ?? [])
  const activeOnly = computed(() => applied.value.status === 'active')
  const stats = computed(() => summary.data.value ?? { total: 0, active: 0, stopped: 0 })
  const categoryTotal = computed(() => (activeOnly.value ? stats.value.active : stats.value.total))
  const series = computed(() => summary.data.value?.series ?? [])
  const brands = computed(() => [...new Set(series.value.map((row) => row.brandName))])
  const seriesOptions = computed(() =>
    series.value.filter((row) => !filters.brand || row.brandName === filters.brand),
  )
  const modelsBySeries = computed(() => {
    const groups = new Map()
    for (const model of summary.data.value?.models ?? []) {
      const models = groups.get(model.seriesId) ?? []
      models.push(model)
      groups.set(model.seriesId, models)
    }
    return groups
  })
  const configurations = computed(() =>
    (summary.data.value?.configurations ?? []).map(({ ram, rom }) => configKey(ram, rom)),
  )
  const categoryBranches = computed(() => {
    const query = categoryKeyword.value.trim().toLowerCase()
    const branches = new Map()
    for (const row of series.value) {
      const count = activeOnly.value ? row.active : row.total
      const branch = branches.get(row.brandName) ?? { brand: row.brandName, count: 0, series: [] }
      branch.count += count
      const path = row.brandName + ' ' + row.seriesName
      const models = (modelsBySeries.value.get(row.seriesId) ?? [])
        .map((model) => ({
          id: model.spuId,
          name: model.modelName + (model.edition ? `（${model.edition}）` : ''),
          count: activeOnly.value ? model.active : model.total,
        }))
        .filter((model) => (path + ' ' + model.name).toLowerCase().includes(query))
      if (path.toLowerCase().includes(query) || models.length) {
        branch.series.push({ id: row.seriesId, name: row.seriesName, count, models })
      }
      branches.set(row.brandName, branch)
    }
    return [...branches.values()].filter((branch) => branch.series.length)
  })

  function load() {
    const { brand, series, model, config, keyword, status } = applied.value
    const [ram, rom] = config.split('+')
    return list.run(async (signal) => {
      const result = await readCatalogGoods(
        {
          brandName: brand,
          seriesId: series,
          spuId: model,
          ram: ram && ram + 'GB',
          rom: rom && rom + 'GB',
          keyword,
          status: CATALOG_SALES_STATES[status]?.code,
          page: page.value,
          size: size.value,
          ascending: ascending.value,
        },
        signal,
      )
      return { total: result.total, items: result.items.map(toProduct) }
    })
  }

  function loadSummary() {
    return summary.run(readCatalogGoodsSummary)
  }

  function loadDetail() {
    const id = detailId.value
    return detail.run(async (signal) => (id ? toProduct(await readCatalogGood(id, signal)) : null))
  }

  async function createProduct(product) {
    const created = await createCatalogGood(product)
    void loadSummary()
    if (page.value !== 1 || ascending.value) {
      ascending.value = false
      page.value = 1
    } else {
      void load()
    }
    return created
  }

  async function deleteProduct(id) {
    await deleteCatalogGood(id)
    void load()
    void loadSummary()
  }

  watch([applied, page, size, ascending], load, { immediate: true })
  watch(detailId, loadDetail)
  watch(list.data, (result) => {
    if (result) {
      total.value = result.total
      page.value = Math.min(page.value, Math.max(1, Math.ceil(result.total / size.value)))
    }
  })
  void loadSummary()

  function changeBrand(brand) {
    filters.brand = brand
    if (!seriesOptions.value.some((row) => row.seriesId === filters.series)) {
      filters.series = ''
      filters.model = ''
    }
    search()
  }

  function changeSeries(series) {
    filters.series = series
    filters.model = ''
    search()
  }

  function changeStatus(status) {
    filters.status = status
    search()
  }

  function changeConfig(config) {
    filters.config = config
    search()
  }

  function search() {
    applied.value = { ...filters, keyword: filters.keyword.trim() }
    page.value = 1
  }

  function reset() {
    Object.assign(filters, emptyFilters())
    categoryKeyword.value = ''
    search()
  }

  function selectCategory(brand, series = '', model = '') {
    filters.brand = brand
    filters.series = series
    filters.model = model
    search()
  }

  function toggleActiveOnly(enabled) {
    changeStatus(enabled ? 'active' : '')
  }

  function resize(value) {
    size.value = value
    page.value = 1
  }

  function sort({ order }) {
    ascending.value = order === 'ascending'
    page.value = 1
  }

  return {
    filters,
    applied,
    page,
    size,
    total,
    visibleRows,
    categoryKeyword,
    categoryTotal,
    categoryBranches,
    brands,
    stats,
    activeOnly,
    seriesOptions,
    configurations,
    list,
    summary,
    detail,
    detailId,
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
    load,
    loadSummary,
    loadDetail,
    createProduct,
    deleteProduct,
  }
}
