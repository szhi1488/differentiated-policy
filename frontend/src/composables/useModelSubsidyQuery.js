import { computed, inject, onMounted, onScopeDispose, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { readApplicantSubsidyOptions, readCatalogGoodsSummary } from '../api/catalog'
import { readAssignedPlatforms } from '../api/policies'
import { readSubsidyTimeline } from '../api/subsidy-timeline'
import { CHANNEL_LABELS, PLATFORM_LABELS } from '../constants/policy'
import { catalogGoodsNameLabel } from '../utils/catalog-dictionary'
import { readTimelineHistory } from '../utils/policy-timeline'
import { timelineMonthDates, timelineTimeFromValue } from '../utils/subsidy-timeline'
import { useLatestQuery } from './useLatestQuery'

async function readDirectory(applicant, signal) {
  if (applicant) {
    const { brands, series, models, configurations } = await readApplicantSubsidyOptions(signal)
    const names = new Map(brands.map(({ id, name }) => [id, name]))
    const seriesBrands = new Map(series.map(({ id, brandId }) => [id, names.get(brandId)]))
    return directoryModels(
      models.map(({ id, seriesId, name, edition }) => ({
        spuId: id,
        brand: seriesBrands.get(seriesId),
        modelName: name,
        edition,
      })),
      configurations,
    )
  }
  const summary = await readCatalogGoodsSummary(signal, { includeModelConfigurations: true })
  const seriesBrands = new Map(
    summary.series.map(({ seriesId, brandName }) => [seriesId, brandName]),
  )
  return directoryModels(
    summary.models.map((model) => ({ ...model, brand: seriesBrands.get(model.seriesId) })),
    summary.modelConfigurations,
  )
}

function directoryModels(models, configurations) {
  const configs = new Map()
  for (const { spuId, ram, rom } of configurations) {
    if (!configs.has(spuId)) {
      configs.set(spuId, [])
    }
    configs
      .get(spuId)
      .push({ id: ram + '+' + rom, ram, rom, label: catalogGoodsNameLabel(ram + ' + ' + rom) })
  }
  return models
    .filter((m) => configs.has(m.spuId))
    .map((m) => ({
      ...m,
      label: [m.modelName, m.edition].filter(Boolean).join(' · '),
      configs: configs.get(m.spuId),
    }))
}

function defaultDates() {
  return timelineMonthDates(timelineTimeFromValue(Date.now()))
}

export function useModelSubsidyQuery(applicant) {
  const catalog = useLatestQuery()
  const timeline = useLatestQuery()
  const permissions = useLatestQuery()
  const brand = ref(''),
    modelId = ref(''),
    configId = ref(''),
    platform = ref('')
  const platformOptions = computed(() =>
    applicant
      ? (permissions.data.value ?? []).map(({ perspective, platformCode }) => ({
          value: perspective + ':' + platformCode,
          label:
            CHANNEL_LABELS[perspective].replace('渠道', '') + ' · ' + PLATFORM_LABELS[platformCode],
          perspective,
          platformCode,
        }))
      : Object.entries(PLATFORM_LABELS).map(([platformCode, label]) => ({
          value: platformCode,
          label,
          platformCode,
        })),
  )
  const hasPlatforms = computed(() => platformOptions.value.length > 0)
  const dates = ref(defaultDates())
  const brands = computed(() => [...new Set(catalog.data.value?.map((m) => m.brand) ?? [])])
  const modelOptions = computed(
    () => catalog.data.value?.filter((m) => m.brand === brand.value) ?? [],
  )
  const model = computed(() => modelOptions.value.find((m) => m.spuId === modelId.value))
  const configOptions = computed(() => model.value?.configs ?? [])
  const config = computed(() => configOptions.value.find((c) => c.id === configId.value))
  watch(
    modelOptions,
    (options) => {
      modelId.value = options[0]?.spuId ?? ''
    },
    { flush: 'sync' },
  )
  watch(
    configOptions,
    (options) => {
      configId.value = options[0]?.id ?? ''
    },
    { flush: 'sync' },
  )

  const { lastQuery = ref(null), showIndependent = ref(false) } = inject(
    'modelSubsidyPreferences',
    {},
  )
  const result = computed(() => {
    const history = timeline.data.value
    if (!applicant || !history) {
      return history
    }
    const { perspective, platformCode } = history.selection.params
    const scopes = new Set(
      platformOptions.value
        .filter(
          (option) =>
            !platformCode ||
            (option.perspective === perspective && option.platformCode === platformCode),
        )
        .map((option) => option.value),
    )
    const visible = (segment) => scopes.has(segment.channel + ':' + segment.platformCode)
    return {
      ...history,
      rows: history.rows.filter((row) => visible(row.segment)),
      segments: history.segments.filter(visible),
    }
  })
  function loadHistory(selection) {
    const requestedAt = timelineTimeFromValue(Date.now())
    lastQuery.value = selection
    return timeline.run(async (signal) => {
      const response = await readSubsidyTimeline(selection.params, signal, applicant)
      return { ...readTimelineHistory(response), selection: { ...selection, requestedAt } }
    })
  }
  function search() {
    if (!model.value || !config.value || !hasPlatforms.value) {
      return
    }
    if (!dates.value?.[0] || !dates.value?.[1]) {
      ElMessage.warning('请选择完整的查询日期范围')
      return
    }
    const [dateFrom, dateTo] = dates.value
    const scope = platformOptions.value.find((option) => option.value === platform.value)
    if (platform.value && !scope) {
      return
    }
    return loadHistory({
      params: {
        spuId: modelId.value,
        ram: config.value.ram,
        rom: config.value.rom,
        platformCode: scope?.platformCode,
        perspective: scope?.perspective,
        dateFrom,
        dateTo,
      },
      label: model.value.label + ' · ' + config.value.label,
    })
  }
  function applyFilters(params) {
    const savedModel = catalog.data.value?.find((item) => item.spuId === params?.spuId)
    brand.value = savedModel?.brand ?? brands.value[0] ?? ''
    modelId.value = savedModel?.spuId ?? modelOptions.value[0]?.spuId ?? ''
    const savedConfig = savedModel?.configs.find(
      (item) => item.ram === params.ram && item.rom === params.rom,
    )
    configId.value = savedConfig?.id ?? configOptions.value[0]?.id ?? ''
    dates.value = params ? [params.dateFrom, params.dateTo] : defaultDates()
    platform.value =
      platformOptions.value.find(
        (option) =>
          option.perspective === params?.perspective &&
          option.platformCode === params?.platformCode,
      )?.value ?? ''
  }
  async function loadCatalog() {
    await catalog.run((signal) => readDirectory(applicant, signal))
    if (catalog.data.value?.length && hasPlatforms.value) {
      applyFilters(lastQuery.value?.params)
      await search()
    }
  }
  function locateCurrentTime() {
    const today = timelineTimeFromValue(Date.now()).slice(0, 10)
    dates.value = [dates.value?.[0] && dates.value[0] <= today ? dates.value[0] : today, today]
    return search()
  }
  async function refreshPlatforms(forceReload = false) {
    if (permissions.loading.value) {
      return
    }
    const previous = platformOptions.value
    await permissions.run(readAssignedPlatforms)
    const current = platformOptions.value
    if (!current.length) {
      platform.value = ''
      return
    }
    if (
      forceReload ||
      (!timeline.data.value && !timeline.loading.value && !timeline.error.value) ||
      previous.length !== current.length ||
      !previous.every((item) => current.some((option) => option.value === item.value))
    ) {
      await loadCatalog()
    }
  }
  function onVisible() {
    if (!document.hidden) {
      void refreshPlatforms()
    }
  }
  onMounted(() => {
    if (applicant) {
      document.addEventListener('visibilitychange', onVisible)
      return refreshPlatforms()
    }
    return loadCatalog()
  })
  onScopeDispose(() => document.removeEventListener('visibilitychange', onVisible))
  return {
    brand,
    modelId,
    configId,
    platform,
    platformOptions,
    hasPlatforms,
    platformError: permissions.error,
    loadingPlatforms: permissions.loading,
    refreshPlatforms,
    dates,
    brands,
    modelOptions,
    configOptions,
    catalogStatus: catalog.status,
    catalogError: catalog.error,
    loadCatalog,
    result,
    historyStatus: timeline.status,
    historyError: timeline.error,
    showIndependent,
    search,
    resetFilters: () => applyFilters(),
    locateCurrentTime,
    retryHistory: () =>
      applicant && timeline.error.value?.code === 'POLICY_PLATFORM_FORBIDDEN'
        ? refreshPlatforms(true)
        : loadHistory(lastQuery.value),
  }
}
