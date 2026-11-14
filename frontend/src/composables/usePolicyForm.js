import { computed, ref } from 'vue'
import { createRequestId } from '../utils/request-id'
import { usePolicyDraft } from './usePolicyDraft'
import { BILLION_PLATFORMS, PLATFORM_LABELS, RETAIL_PLATFORMS } from '../constants/policy'
import {
  changeChannel,
  changeSubsidy,
  createForm,
  createPolicyModel,
  createSubsidy,
  policyGoodsKey,
} from '../utils/policy-form'

/** 维护当前页面的表单状态，不承担服务端提交、权限判断或政策生效计算。 */
export function usePolicyForm(confirm = (message) => window.confirm(message)) {
  const form = ref(createForm())
  const files = ref([])
  const confirming = ref(false)
  const persistence = usePolicyDraft(form, confirming)
  const blocked = () => confirming.value || persistence.locked.value
  const { notice } = persistence
  let revision = 0
  async function confirmed(action) {
    if (confirming.value || persistence.busy.value) {
      return false
    }
    confirming.value = true
    const current = revision
    try {
      return (await action()) && current === revision
    } finally {
      confirming.value = false
    }
  }
  const platforms = computed(() =>
    form.value.channel === 'RETAIL' ? RETAIL_PLATFORMS : BILLION_PLATFORMS,
  )
  const selectedPlatforms = computed(() =>
    platforms.value.filter(([code]) => form.value.platforms.includes(code)),
  )
  const models = computed(() => [form.value.model, ...form.value.specialGoods].filter(Boolean))

  async function switchChannel(channel) {
    if (blocked() || !['RETAIL', 'BILLION'].includes(channel)) {
      return false
    }
    if (channel === form.value.channel) {
      return true
    }
    if (
      (form.value.platforms.length || models.value.length) &&
      !(await confirmed(() => confirm('切换渠道会清除已选平台及对应机型明细，是否继续？')))
    ) {
      return false
    }
    changeChannel(form.value, channel)
    notice.value = ''
    return true
  }

  function addPlatform(code) {
    if (blocked() || !platforms.value.some(([value]) => value === code)) {
      return
    }
    if (form.value.platforms.includes(code)) {
      return
    }
    form.value.platforms.push(code)
    for (const model of models.value) {
      if (model.followsOrderPlatforms) {
        model.platforms[code] = createPlatformEntry(model)
      }
    }
  }

  async function togglePlatform(code) {
    if (blocked()) {
      return
    }
    if (form.value.platforms.includes(code)) {
      if (
        models.value.some((model) => model.platforms[code]) &&
        !(await confirmed(() =>
          confirm(`取消${PLATFORM_LABELS[code]}会移除该平台的数量和补贴方案分配，是否继续？`),
        ))
      ) {
        return
      }
      form.value.platforms = form.value.platforms.filter((value) => value !== code)
      for (const model of models.value) {
        delete model.platforms[code]
      }
    } else {
      addPlatform(code)
    }
  }

  function selectAll() {
    if (blocked()) {
      return
    }
    platforms.value.forEach(([code]) => addPlatform(code))
  }

  function addGoods(goodsList) {
    if (blocked() || !form.value.platforms.length) {
      return
    }
    const model = form.value.model ?? createPolicyModel()
    const existing = new Set(model.goods.map(({ key }) => key))
    for (const goods of goodsList) {
      const key = policyGoodsKey(goods)
      if (goods.status !== 'active' || existing.has(key)) {
        continue
      }
      model.goods.push({ ...goods, key })
      existing.add(key)
    }
    if (!form.value.model && model.goods.length) {
      applyModelPlatforms(model, form.value.platforms)
      form.value.model = model
    }
    notice.value = ''
  }

  function applyModelPlatforms(model, codes) {
    model.platforms = Object.fromEntries(
      codes.map((code) => [code, model.platforms[code] ?? createPlatformEntry(model)]),
    )
  }

  function createPlatformEntry(model) {
    return model.id === undefined
      ? { subsidyKey: model.subsidies[0].key }
      : { quantity: '', subsidyKey: model.subsidies[0].key }
  }

  function hasPlatformDetails(model, code) {
    const entry = model.platforms[code]
    return Boolean(
      entry &&
      ((model.id !== undefined && entry.quantity !== '') ||
        entry.subsidyKey !== model.subsidies[0].key),
    )
  }

  function addSpecialGood(goods) {
    if (
      blocked() ||
      !form.value.platforms.length ||
      goods?.status !== 'active' ||
      !goods.color ||
      form.value.specialGoods.some(({ id }) => id === goods.id)
    ) {
      return
    }
    const model = createPolicyModel()
    model.id = goods.id
    model.goods.push({ ...goods, key: policyGoodsKey(goods) })
    applyModelPlatforms(model, form.value.platforms)
    form.value.specialGoods.push(model)
    notice.value = ''
  }

  function addFiles(list) {
    if (blocked()) {
      return
    }
    files.value.push(...Array.from(list || []).map((file) => ({ id: createRequestId(), file })))
  }

  async function reset() {
    if (
      persistence.busy.value ||
      !(await confirmed(() => confirm('确定清空当前填写的内容并打开新申请吗？')))
    ) {
      return false
    }
    clear()
    return true
  }

  function clear() {
    revision++
    persistence.clear()
    form.value = createForm()
    persistence.markSaved()
    files.value = []
    notice.value = ''
  }

  function updateField(field, value) {
    if (blocked()) {
      return
    }
    if (
      [
        'verifyType',
        'scene',
        'estimated',
        'startTime',
        'endTime',
        'remark',
        'explanation',
        'links',
      ].includes(field)
    ) {
      form.value[field] = value
    }
  }
  function editModel(id) {
    if (!blocked()) {
      return id === undefined
        ? form.value.model
        : form.value.specialGoods.find((item) => item.id === id)
    }
  }

  async function setModelPlatforms(codes, id) {
    const model = editModel(id)
    if (!model) {
      return
    }
    const selected = form.value.platforms.filter((code) => codes === null || codes.includes(code))
    const removed = Object.keys(model.platforms).filter((code) => !selected.includes(code))
    if (
      removed.some((code) => hasPlatformDetails(model, code)) &&
      !(await confirmed(() =>
        confirm(
          id === undefined
            ? '移除平台会清除对应的补贴方案分配，是否继续？'
            : '移除平台会清除对应的数量和补贴方案分配，是否继续？',
        ),
      ))
    ) {
      return
    }
    model.followsOrderPlatforms = selected.length === form.value.platforms.length
    applyModelPlatforms(model, selected)
  }

  function updateModelPlatform(code, field, value, id) {
    const model = editModel(id)
    const entry = model?.platforms[code]
    if (!entry) {
      return
    }
    if (
      (field === 'quantity' && id !== undefined) ||
      (field === 'subsidyKey' && model.subsidies.some((item) => item.key === value))
    ) {
      entry[field] = value
    }
  }

  function addSubsidy(id) {
    editModel(id)?.subsidies.push(createSubsidy())
  }

  function updateSubsidy(subsidyKey, field, value, id) {
    const subsidy = editModel(id)?.subsidies.find((item) => item.key === subsidyKey)
    if (!subsidy || subsidy[field] === value) {
      return
    }
    if (field === 'mode' && ['FIXED', 'RATIO'].includes(value)) {
      changeSubsidy(subsidy, value)
    } else {
      const fields =
        subsidy.mode === 'FIXED' ? ['fixedAmount', 'maxAmount'] : ['ratio', 'priceBase']
      if (fields.includes(field)) {
        subsidy[field] = value
      }
    }
  }

  async function removeSubsidy(subsidyKey, id) {
    const model = editModel(id)
    const [defaultSubsidy] = model?.subsidies ?? []
    if (!defaultSubsidy || subsidyKey === defaultSubsidy.key) {
      return
    }
    const affected = Object.values(model.platforms).filter(
      (entry) => entry.subsidyKey === subsidyKey,
    )
    if (
      !(await confirmed(() =>
        confirm(
          affected.length
            ? '删除该方案后，使用此方案的平台将改用方案 1，是否继续？'
            : '确定删除该补贴方案吗？',
        ),
      ))
    ) {
      return
    }
    for (const entry of affected) {
      entry.subsidyKey = defaultSubsidy.key
    }
    model.subsidies = model.subsidies.filter((item) => item.key !== subsidyKey)
  }

  async function removeModel(id) {
    const message =
      id === undefined
        ? '确定移除所选配置及其补贴方案吗？'
        : '确定移除该特殊商品及其平台数量和补贴方案吗？'
    if (editModel(id) && (await confirmed(() => confirm(message)))) {
      if (id === undefined) {
        form.value.model = null
      } else {
        form.value.specialGoods = form.value.specialGoods.filter((item) => item.id !== id)
      }
    }
  }

  function removeGoods(key) {
    const model = editModel()
    if (!model?.goods.some((item) => item.key === key)) {
      return
    }
    if (model.goods.length === 1) {
      return removeModel()
    }
    model.goods = model.goods.filter((item) => item.key !== key)
  }
  function removeFile(uid) {
    if (blocked()) {
      return
    }
    files.value = files.value.filter((entry) => entry.file.uid !== uid)
  }

  async function reloadDraft(id, force = true) {
    if (
      persistence.conflict.value &&
      !(await confirmed(() => confirm('读取服务端版本会替换当前填写内容，是否继续？')))
    ) {
      return
    }
    const previousId = persistence.record.value?.id
    if ((await persistence.load(id, force)) && id !== previousId) {
      files.value = []
    }
  }

  async function preparePolicyEdit(id, recordVersion) {
    if (persistence.busy.value || confirming.value) {
      return false
    }
    const current = persistence.record.value
    if (
      current?.id === id &&
      current.recordVersion === recordVersion &&
      !persistence.conflict.value &&
      !persistence.loadError.value
    ) {
      return true
    }
    if (
      (persistence.hasUnsavedChanges() || files.value.length) &&
      !(await confirmed(() => confirm('打开该申请会替换当前未保存的内容，是否继续？')))
    ) {
      return false
    }
    clear()
    return true
  }

  return {
    ...persistence,
    reloadDraft,
    preparePolicyEdit,
    form,
    files,
    platforms,
    selectedPlatforms,
    models,
    switchChannel,
    togglePlatform,
    selectAll,
    addGoods,
    addSpecialGood,
    addFiles,
    reset,
    clear,
    confirming,
    updateField,
    setModelPlatforms,
    updateModelPlatform,
    addSubsidy,
    updateSubsidy,
    removeSubsidy,
    removeModel,
    removeGoods,
    removeFile,
  }
}
