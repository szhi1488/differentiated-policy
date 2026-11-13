import { computed, reactive, ref, shallowRef } from 'vue'

const emptyFilters = () => ({ keyword: '', brand: '', status: '', series: '', config: '' })
const previewTime = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai',
  dateStyle: 'short',
  timeStyle: 'medium',
})

export function useCatalogCenterPreview(dictionary) {
  const savedProducts = shallowRef(dictionary.initialProducts)
  let nextPreviewNumber = 0
  const products = computed(() => savedProducts.value.map(dictionary.projectProduct))
  const usedEntryIds = computed(
    () =>
      new Set(
        savedProducts.value.flatMap(({ brandId, seriesId, colorId }) => [
          brandId,
          seriesId,
          colorId,
        ]),
      ),
  )
  const filters = reactive(emptyFilters())
  const applied = ref(emptyFilters())
  const categoryKeyword = ref('')
  const page = ref(1)
  const size = ref(10)
  const sortOrder = ref('descending')
  const brands = computed(() => [...new Set(products.value.map((row) => row.brand))])
  const configurations = computed(() => [...new Set(products.value.map((row) => row.config))])
  const stats = computed(() => ({
    total: products.value.length,
    active: products.value.filter((row) => row.status === 'active').length,
    stopped: products.value.filter((row) => row.status === 'stopped').length,
  }))
  const activeOnly = computed(() => applied.value.status === 'active')
  const seriesOptions = computed(() => seriesOf(filters.brand))
  const categoryRows = computed(() =>
    activeOnly.value ? products.value.filter((row) => row.status === 'active') : products.value,
  )
  const categoryBranches = computed(() => {
    const query = categoryKeyword.value.trim().toLowerCase()
    return brands.value.flatMap((brand) => {
      const rows = categoryRows.value.filter((row) => row.brand === brand)
      const series = seriesOf(brand)
        .filter((name) => `${brand} ${name}`.toLowerCase().includes(query))
        .map((name) => ({ name, count: rows.filter((row) => row.series === name).length }))
      return series.length ? [{ brand, series, count: rows.length }] : []
    })
  })

  const matches = computed(() => {
    const { keyword, brand, status, series, config } = applied.value
    const query = keyword.trim().toLowerCase()
    const rows = products.value.filter(
      (row) =>
        (!brand || row.brand === brand) &&
        (!status || row.status === status) &&
        (!series || row.series === series) &&
        (!config || row.config === config) &&
        [row.name, row.source, row.id, row.sku, row.color].some((value) =>
          value.toLowerCase().includes(query),
        ),
    )
    return sortOrder.value === 'ascending' ? rows.reverse() : rows
  })
  const visibleRows = computed(() =>
    matches.value.slice((page.value - 1) * size.value, page.value * size.value),
  )

  function seriesOf(brand) {
    return [
      ...new Set(
        products.value.filter((row) => !brand || row.brand === brand).map((row) => row.series),
      ),
    ].sort((a, b) => a.localeCompare(b, 'zh-CN'))
  }

  function changeBrand(brand) {
    filters.brand = brand
    if (!seriesOf(brand).includes(filters.series)) {
      filters.series = ''
    }
  }

  function search() {
    applied.value = { ...filters }
    page.value = 1
  }

  function reset() {
    Object.assign(filters, emptyFilters())
    categoryKeyword.value = ''
    search()
  }

  function selectCategory(brand, series = '') {
    filters.brand = brand
    filters.series = series
    search()
  }

  function toggleActiveOnly(enabled) {
    filters.status = enabled ? 'active' : ''
    search()
  }

  function resize(value) {
    size.value = value
    page.value = 1
  }

  function sort({ order }) {
    sortOrder.value = order
    page.value = 1
  }

  function savePreviewProduct(
    id,
    { name, brandId, seriesId, model, config, colorId, version, status },
  ) {
    const series = dictionary.byId.value.get(seriesId)
    if (
      series?.type !== 'series' ||
      series.parentId !== brandId ||
      dictionary.byId.value.get(colorId)?.type !== 'color'
    ) {
      return
    }
    const changes = { name, brandId, seriesId, model, config, colorId, version, status }
    if (id === null) {
      const [date, time] = previewTime.format(new Date()).split(' ')
      const number = String(++nextPreviewNumber).padStart(3, '0')
      const created = {
        ...changes,
        id: `P-DEMO-${number}`,
        sku: `SKU-DEMO-${number}`,
        source: '',
        tint: '#7d9cbf',
        date,
        time,
      }
      savedProducts.value = [created, ...savedProducts.value]
      const index = matches.value.findIndex((row) => row.id === created.id)
      if (index >= 0) {
        page.value = Math.floor(index / size.value) + 1
      }
      return { visible: index >= 0 }
    }
    savedProducts.value = savedProducts.value.map((row) =>
      row.id === id ? { ...row, ...changes } : row,
    )
    page.value = Math.min(page.value, Math.max(1, Math.ceil(matches.value.length / size.value)))
  }

  return {
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
  }
}
