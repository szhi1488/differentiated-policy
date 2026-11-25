import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { readApplicantCatalogOptions, readApplicantGoods } from '../api/catalog'
import { applicantGood } from '../utils/policy-draft'
import { catalogConfigLabel } from '../utils/catalog-dictionary'
import { policyConfigurationKey } from '../utils/policy-form'

const PAGE_SIZE = 20
const CONFIG_PAGE_SIZE = 100
const ALL_CONFIGS = 'ALL_CONFIGS'

export function usePolicyGoodsPicker(props) {
  const mode = ref('COMMON')
  const special = computed(() => mode.value === 'SPECIFIED')
  const initial = props.initialGoods.find((item) => item.brandId) ?? props.initialGoods[0]
  const brandId = ref(initial?.brandId ?? '')
  const seriesId = ref(initial?.seriesId ?? '')
  const spuId = ref(initial?.spuId ?? '')
  const prefilledConfigs = new Set(props.existingGoods.map(policyConfigurationKey))
  const config = ref([...new Set(props.initialGoods.map(policyConfigurationKey))])
  const keyword = ref('')
  const search = ref('')
  const page = ref(1)
  const total = ref(0)
  const rows = ref([])
  const options = ref(null)
  const optionsError = ref('')
  const listError = ref('')
  const selectionError = ref('')
  const optionsBusy = ref(false)
  const listBusy = ref(false)
  const selecting = ref(false)
  const commonGoods = shallowRef(new Map())
  const specifiedGood = shallowRef(new Map())
  const selected = computed(() => (special.value ? specifiedGood.value : commonGoods.value))
  const removed = shallowRef(new Set())
  const added = computed(() => new Set(special.value ? props.specifiedIds : props.addedKeys))
  const isBlocked = (item) =>
    !special.value && props.blockedConfigurations.has(policyConfigurationKey(item))
  const brands = computed(() => options.value?.brands ?? [])
  const series = computed(() =>
    (options.value?.series ?? []).filter(
      (item) => !brandId.value || item.brandId === brandId.value,
    ),
  )
  const models = computed(() =>
    (options.value?.models ?? []).filter(
      (item) =>
        (!seriesId.value || item.seriesId === seriesId.value) &&
        series.value.some(({ id }) => id === item.seriesId),
    ),
  )
  const configs = computed(() =>
    (options.value?.configurations ?? [])
      .filter((item) =>
        spuId.value ? item.spuId === spuId.value : models.value.some(({ id }) => id === item.spuId),
      )
      .map((item) => {
        const model = models.value.find(({ id }) => id === item.spuId)
        const name = model?.edition ? `${model.name}（${model.edition}）` : model?.name
        const capacity = catalogConfigLabel(
          `${item.ram.replace(/GB$/, '')}+${item.rom.replace(/GB$/, '')}`,
        )
        const disabled = isBlocked(item)
        const label = spuId.value ? capacity : `${name} · ${capacity}`
        return {
          key: policyConfigurationKey(item),
          label: disabled ? `${label}（已在其他卡片）` : label,
          disabled,
        }
      }),
  )
  const configOptions = computed(() => [
    { key: ALL_CONFIGS, label: '全部配置', disabled: configs.value.every((item) => item.disabled) },
    ...configs.value,
  ])

  function scope() {
    return {
      brandId: brandId.value,
      seriesId: seriesId.value,
      spuId: spuId.value,
      configuration: config.value.includes(ALL_CONFIGS) ? [] : config.value,
    }
  }

  function selectedConfigKeys() {
    return new Set(
      config.value.includes(ALL_CONFIGS) ? configs.value.map(({ key }) => key) : config.value,
    )
  }

  // 指定模式的配置仅筛选列表，切回普通模式时恢复当前筛选范围内已选的整组。
  function changeMode(value) {
    if (props.editing || selecting.value || mode.value === value) {
      return
    }
    selectionError.value = ''
    mode.value = value
    if (!special.value) {
      reconcileConfig()
    }
    page.value = 1
  }
  let optionsRequest
  let listRequest
  let selectionRequest

  async function loadOptions() {
    optionsRequest?.abort()
    const controller = new AbortController()
    optionsRequest = controller
    optionsBusy.value = true
    optionsError.value = ''
    try {
      const data = await readApplicantCatalogOptions(controller.signal)
      if (!controller.signal.aborted) {
        options.value = data
        if (prefilledConfigs.size) {
          if (!brands.value.some(({ id }) => id === brandId.value)) {
            brandId.value = ''
          }
          if (!series.value.some(({ id }) => id === seriesId.value)) {
            seriesId.value = ''
          }
          reconcileModel()
        }
      }
    } catch (error) {
      if (!controller.signal.aborted) {
        optionsError.value = error.message
      }
    } finally {
      if (optionsRequest === controller) {
        optionsBusy.value = false
      }
    }
  }

  async function loadPage() {
    listRequest?.abort()
    const controller = new AbortController()
    listRequest = controller
    listBusy.value = true
    listError.value = ''
    rows.value = []
    try {
      const result = await readApplicantGoods(
        {
          ...scope(),
          keyword: search.value,
          page: page.value,
          size: PAGE_SIZE,
        },
        controller.signal,
      )
      if (!controller.signal.aborted) {
        total.value = result.total
        const last = Math.max(1, Math.ceil(result.total / PAGE_SIZE))
        if (page.value > last) {
          page.value = last
        } else {
          rows.value = result.items.map(applicantGood)
        }
      }
    } catch (error) {
      if (!controller.signal.aborted) {
        listError.value = error.message
      }
    } finally {
      if (listRequest === controller) {
        listBusy.value = false
      }
    }
  }

  function changeBrand(value) {
    brandId.value = value || ''
    if (!series.value.some(({ id }) => id === seriesId.value)) {
      seriesId.value = ''
    }
    reconcileModel()
  }

  function changeSeries(value) {
    seriesId.value = value || ''
    reconcileModel()
  }

  function reconcileModel() {
    if (!models.value.some(({ id }) => id === spuId.value)) {
      spuId.value = ''
    }
    reconcileConfig()
  }

  function changeModel(value) {
    spuId.value = value || ''
    reconcileConfig()
  }

  function reconcileConfig() {
    const available = new Set(configs.value.map(({ key }) => key))
    if (special.value) {
      config.value = config.value.filter((key) => available.has(key))
    } else {
      const chosen = new Set([...commonGoods.value.values()].map(policyConfigurationKey))
      for (const item of props.existingGoods) {
        if (!removed.value.has(item.id)) {
          chosen.add(policyConfigurationKey(item))
        }
      }
      config.value = [...chosen].filter((key) => available.has(key))
    }
    page.value = 1
    if (selectionError.value) {
      void selectConfigurations()
    }
  }

  async function selectConfigurations() {
    if (special.value) {
      return
    }
    selectionRequest?.abort()
    selecting.value = false
    selectionError.value = ''
    const all = config.value.includes(ALL_CONFIGS)
    // 回填的配置用于定位已选商品。主动新增或重选配置时才自动勾选该配置的商品。
    const keys = new Set(
      [...selectedConfigKeys()].filter(
        (key) => !prefilledConfigs.has(key) && !props.blockedConfigurations.has(key),
      ),
    )
    if (!keys.size) {
      return
    }
    const filters = { ...scope(), configuration: all ? [] : [...keys] }
    const controller = new AbortController()
    selectionRequest = controller
    selecting.value = true
    const found = new Map()
    try {
      let expected
      for (let current = 1; ; current++) {
        const result = await readApplicantGoods(
          {
            ...filters,
            page: current,
            size: CONFIG_PAGE_SIZE,
          },
          controller.signal,
        )
        expected ??= result.total
        if (expected !== result.total) {
          throw new Error('在售商品已更新，请重试勾选此配置。')
        }
        for (const item of result.items) {
          found.set(item.goodsId, applicantGood(item))
        }
        if (current * CONFIG_PAGE_SIZE >= expected) {
          break
        }
      }
      if (controller.signal.aborted) {
        return
      }
      if (found.size !== expected) {
        throw new Error('在售商品已更新，请重试勾选此配置。')
      }
      const foundKeys = new Set([...found.values()].map(policyConfigurationKey))
      if ([...keys].some((key) => !foundKeys.has(key))) {
        throw new Error('所选配置的在售商品已变化，请重新选择配置。')
      }
      const next = new Map(commonGoods.value)
      const nextRemoved = new Set(removed.value)
      for (const item of props.existingGoods) {
        if (keys.has(policyConfigurationKey(item)) && !found.has(item.id)) {
          nextRemoved.add(item.id)
        }
      }
      for (const [id, item] of next) {
        if (keys.has(policyConfigurationKey(item)) && !found.has(id)) {
          next.delete(id)
        }
      }
      for (const [id, item] of found) {
        if (keys.has(policyConfigurationKey(item))) {
          if (added.value.has(id)) {
            nextRemoved.delete(id)
          } else {
            next.set(id, item)
          }
        }
      }
      commonGoods.value = next
      removed.value = nextRemoved
    } catch (error) {
      if (!controller.signal.aborted) {
        selectionError.value = error.message
      }
    } finally {
      if (selectionRequest === controller) {
        selecting.value = false
      }
    }
  }

  function reselectGroups() {
    for (const key of selectedConfigKeys()) {
      prefilledConfigs.delete(key)
    }
    return selectConfigurations()
  }

  function changeConfig(values) {
    const before = selectedConfigKeys()
    const wasAll = config.value.includes(ALL_CONFIGS)
    config.value =
      !wasAll && values.includes(ALL_CONFIGS)
        ? [ALL_CONFIGS]
        : values.filter((key) => key !== ALL_CONFIGS)
    page.value = 1
    if (!special.value) {
      const after = selectedConfigKeys()
      // 回填商品不在本次新增集合中，按完整卡片清理才能覆盖列表其他页。
      const nextRemoved = new Set(removed.value)
      for (const item of props.existingGoods) {
        const key = policyConfigurationKey(item)
        if (before.has(key) && !after.has(key)) {
          nextRemoved.add(item.id)
        }
      }
      removed.value = nextRemoved
      for (const key of prefilledConfigs) {
        if (before.has(key) && !after.has(key)) {
          prefilledConfigs.delete(key)
        }
      }
      commonGoods.value = new Map(
        [...commonGoods.value].filter(([, item]) => {
          const key = policyConfigurationKey(item)
          return !before.has(key) || after.has(key)
        }),
      )
      void selectConfigurations()
    }
  }

  function applySearch() {
    search.value = keyword.value.trim()
    page.value = 1
  }

  function toggle(item, checked) {
    if (special.value && checked && !added.value.has(item.id)) {
      specifiedGood.value = new Map([[item.id, item]])
    }
  }

  watch([brandId, seriesId, spuId, config, search, page], loadPage, { immediate: true })
  void loadOptions()
  onScopeDispose(() => {
    optionsRequest?.abort()
    listRequest?.abort()
    selectionRequest?.abort()
  })

  return {
    mode,
    special,
    changeMode,
    brandId,
    seriesId,
    spuId,
    config,
    keyword,
    page,
    total,
    rows,
    brands,
    series,
    models,
    configOptions,
    optionsBusy,
    listBusy,
    selecting,
    optionsError,
    listError,
    selectionError,
    added,
    isBlocked,
    selected,
    removed,
    changeBrand,
    changeSeries,
    changeModel,
    changeConfig,
    toggle,
    applySearch,
    loadOptions,
    loadPage,
    selectConfigurations,
    reselectGroups,
    pageSize: PAGE_SIZE,
  }
}
