import { computed, inject, onMounted, onScopeDispose, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { readApplicantSubsidyOptions } from '../api/catalog'
import { readMemberPlatforms } from '../api/policies'
import { readSubsidyTimeline, SUBSIDY_UPDATE_KEY } from '../api/subsidy-timeline'
import { CHANNEL_LABELS, PLATFORM_LABELS } from '../constants/policy'
import { catalogGoodsNameLabel } from '../utils/catalog-dictionary'
import { readTimelineHistory } from '../utils/policy-timeline'
import { timelineMonthDates, timelineTimeFromValue } from '../utils/subsidy-timeline'
import { authSession } from './useAuthSession'
import { useLatestQuery } from './useLatestQuery'

async function readDirectory(signal) {
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
  const purchaseScoped = computed(() => {
    const user = authSession.user.value
    return (
      !user?.roleCodes?.includes('ADMIN') &&
      (user?.roleCodes?.includes('PURCHASE') ||
        user?.permissions?.includes('policy.purchase.decide_assigned'))
    )
  })
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
    [brand, modelOptions],
    ([currentBrand, options], [previousBrand]) => {
      if (currentBrand !== previousBrand || !options.some((item) => item.spuId === modelId.value)) {
        modelId.value = options[0]?.spuId ?? ''
      }
    },
    { flush: 'sync' },
  )
  watch(
    [modelId, configOptions],
    ([currentModel, options], [previousModel]) => {
      if (currentModel !== previousModel || !options.some((item) => item.id === configId.value)) {
        configId.value = options[0]?.id ?? ''
      }
    },
    { flush: 'sync' },
  )

  const lastQuery = inject('modelSubsidyLastQuery', ref(null))
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
  function loadHistory(selection, keepData = false) {
    const requestedAt = timelineTimeFromValue(Date.now())
    if (!keepData) {
      lastQuery.value = selection
    }
    return timeline.run(
      async (signal) => {
        const response = await readSubsidyTimeline(selection.params, signal, applicant)
        return { ...readTimelineHistory(response), selection: { ...selection, requestedAt } }
      },
      { keepData },
    )
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
  async function loadCatalog(refresh = false) {
    if (catalog.loading.value) {
      return
    }
    await catalog.run(readDirectory, { keepData: refresh })
    const models = catalog.data.value
    if (catalog.loading.value || !models || !hasPlatforms.value) {
      return
    }
    if (!models.length) {
      brand.value = ''
      lastQuery.value = null
      timeline.clear()
      return
    }
    const saved = lastQuery.value?.params
    const savedModel = models.find((item) => item.spuId === saved?.spuId)
    if (
      refresh &&
      brands.value.includes(brand.value) &&
      savedModel?.configs.some((item) => item.ram === saved.ram && item.rom === saved.rom)
    ) {
      return loadHistory(lastQuery.value, true)
    }
    applyFilters(saved)
    return search()
  }
  function locateCurrentTime() {
    const today = timelineTimeFromValue(Date.now()).slice(0, 10)
    dates.value = [dates.value?.[0] && dates.value[0] <= today ? dates.value[0] : today, today]
    return search()
  }
  async function refreshPlatforms(forceReload = false, refreshHistoryOnReturn = false) {
    if (permissions.loading.value) {
      return
    }
    const previous = platformOptions.value
    await permissions.run(readMemberPlatforms, { keepData: true })
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
    } else if (refreshHistoryOnReturn) {
      await refreshHistory()
    }
  }
  function refreshHistory(keepData = true) {
    // 只刷新已应用的查询；表单里尚未提交的筛选继续留给用户编辑。
    if (document.hidden || !hasPlatforms.value || catalog.loading.value) {
      return
    }
    if (applicant && timeline.error.value?.code === 'POLICY_PLATFORM_FORBIDDEN') {
      return refreshPlatforms(true)
    }
    if (purchaseScoped.value || timeline.error.value?.code === 'POLICY_SUBSIDY_BRAND_FORBIDDEN') {
      return loadCatalog(true)
    }
    if (!lastQuery.value || !catalog.data.value?.length) {
      return
    }
    return loadHistory(lastQuery.value, keepData)
  }
  function onVisible() {
    if (!document.hidden) {
      if (applicant) {
        void refreshPlatforms(false, true)
      } else {
        void refreshHistory()
      }
    }
  }
  function onPolicyChange(event) {
    if (event.key === SUBSIDY_UPDATE_KEY && event.newValue) {
      void refreshHistory()
    }
  }
  onMounted(() => {
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('focus', onVisible)
    window.addEventListener('storage', onPolicyChange)
    if (applicant) {
      return refreshPlatforms()
    }
    return loadCatalog()
  })
  onScopeDispose(() => {
    window.removeEventListener('storage', onPolicyChange)
    window.removeEventListener('focus', onVisible)
    document.removeEventListener('visibilitychange', onVisible)
  })
  return {
    brand,
    modelId,
    configId,
    platform,
    platformOptions,
    hasPlatforms,
    platformError: permissions.error,
    loadingPlatforms: computed(() => permissions.loading.value && !permissions.data.value),
    refreshPlatforms,
    dates,
    brands,
    purchaseScoped,
    modelOptions,
    configOptions,
    catalogStatus: catalog.status,
    catalogError: catalog.error,
    loadCatalog,
    result,
    historyStatus: timeline.status,
    historyError: timeline.error,
    search,
    resetFilters: () => applyFilters(),
    locateCurrentTime,
    retryHistory: () => refreshHistory(false),
  }
}
