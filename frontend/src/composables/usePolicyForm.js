import { computed, ref } from 'vue'
import { useAttachments } from './useAttachments'
import { usePolicyDraft } from './usePolicyDraft'
import { confirmPolicyAction } from './confirm-policy-action'
import { BILLION_PLATFORMS, PLATFORM_LABELS, RETAIL_PLATFORMS } from '../constants/policy'
import {
  changeChannel,
  changeSubsidy,
  createForm,
  createPolicyModel,
  createSubsidy,
  occupiedCommonConfigurations,
  policyConfigurationKey,
} from '../utils/policy-form'

/** 维护当前页面的表单状态，不承担服务端提交、权限判断或政策生效计算。 */
export function usePolicyForm(assigned, confirm = confirmPolicyAction, refreshPlatforms) {
  const form = ref(createForm())
  const confirming = ref(false)
  const attachments = useAttachments({
    onChange: (values) => {
      form.value.attachments = values
    },
    canEdit: () => !confirming.value && !persistence.locked.value,
  })
  const { files, attachmentNotice } = attachments
  const persistence = usePolicyDraft(form, confirming, attachments, refreshPlatforms)
  const blocked = () => confirming.value || persistence.locked.value
  const { notice, noticeType } = persistence
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
  const platforms = computed(() => {
    const options = form.value.channel === 'RETAIL' ? RETAIL_PLATFORMS : BILLION_PLATFORMS
    return options.filter(([code]) =>
      assigned.value?.some(
        (scope) => scope.perspective === form.value.channel && scope.platformCode === code,
      ),
    )
  })
  const removedPlatforms = computed(() =>
    assigned.value
      ? form.value.platforms.filter(
          (code) => !platforms.value.some(([allowed]) => allowed === code),
        )
      : [],
  )
  const selectedPlatforms = computed(() =>
    (persistence.readOnly.value
      ? form.value.channel === 'RETAIL'
        ? RETAIL_PLATFORMS
        : BILLION_PLATFORMS
      : platforms.value
    ).filter(([code]) => form.value.platforms.includes(code)),
  )

  async function switchChannel(channel) {
    if (blocked() || !['RETAIL', 'BILLION'].includes(channel)) {
      return false
    }
    if (channel === form.value.channel) {
      return true
    }
    if (
      (form.value.platforms.length || form.value.cards.length) &&
      !(await confirmed(() =>
        confirm('切换渠道会清除已选平台及对应机型明细，是否继续？', {
          confirmButtonText: '确认切换',
          cancelButtonText: '保留内容',
        }),
      ))
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
    for (const model of form.value.cards) {
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
        form.value.cards.some((model) => model.platforms[code]) &&
        !(await confirmed(() =>
          confirm(`取消${PLATFORM_LABELS[code]}会移除该平台的数量和补贴方案分配，是否继续？`, {
            confirmButtonText: '移除平台',
            cancelButtonText: '保留平台',
          }),
        ))
      ) {
        return
      }
      form.value.platforms = form.value.platforms.filter((value) => value !== code)
      for (const model of form.value.cards) {
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

  function matchesSeries(goods, editingKey) {
    const series = new Set(
      [
        ...form.value.cards.filter((card) => card.key !== editingKey).flatMap((card) => card.goods),
        ...goods,
      ].map((item) => [item.brandId, item.seriesId].join('/')),
    )
    if (series.size > 1) {
      notice.value = '每张政策只能申报同一品牌、同一系列的商品。'
      noticeType.value = 'warning'
      return false
    }
    return true
  }

  function addGoods(goodsList, cardKey, removedKeys = []) {
    if (blocked() || !form.value.platforms.length) {
      return false
    }
    const model = cardKey === undefined ? createPolicyModel() : editModel(cardKey)
    if (!model || model.id !== undefined) {
      return false
    }
    const occupied = occupiedCommonConfigurations(form.value.cards, cardKey)
    if (goodsList.some((item) => occupied.has(policyConfigurationKey(item)))) {
      notice.value = '同一机型配置只能加入一张普通商品卡，请编辑已有卡片。'
      noticeType.value = 'warning'
      return false
    }
    const removed = new Set(removedKeys)
    const next = model.goods.filter(({ key }) => !removed.has(key))
    const existing = new Set(next.map(({ key }) => key))
    for (const goods of goodsList) {
      const key = goods.id
      if (goods.status !== 'active' || existing.has(key)) {
        continue
      }
      next.push({ ...goods, key })
      existing.add(key)
    }
    if (!next.length) {
      return cardKey === undefined ? false : removeModel(cardKey)
    }
    if (!matchesSeries(next, cardKey)) {
      return false
    }
    model.goods = next
    if (cardKey === undefined) {
      applyModelPlatforms(model, form.value.platforms)
      form.value.cards.push(model)
    }
    notice.value = ''
    return true
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
      !matchesSeries([goods]) ||
      form.value.cards.some(({ id }) => id === goods.id)
    ) {
      return false
    }
    const model = createPolicyModel()
    model.id = goods.id
    model.goods.push({ ...goods, key: goods.id })
    applyModelPlatforms(model, form.value.platforms)
    form.value.cards.push(model)
    notice.value = ''
    return true
  }

  function addFiles(list) {
    if (blocked()) {
      return
    }
    return attachments.addFiles(list)
  }

  async function reset() {
    if (
      persistence.busy.value ||
      !(await confirmed(() =>
        confirm('确定清空当前填写的内容并打开新申请吗？', {
          confirmButtonText: '新建申请',
          cancelButtonText: '保留内容',
        }),
      ))
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
    attachments.restore()
    notice.value = ''
  }

  function updateField(field, value) {
    if (blocked()) {
      return
    }
    if (
      ['verifyType', 'scenes', 'estimated', 'startTime', 'endTime', 'remark', 'links'].includes(
        field,
      )
    ) {
      form.value[field] = value
    }
  }
  function editModel(cardKey) {
    if (!blocked()) {
      return form.value.cards.find((item) => item.key === cardKey)
    }
  }

  async function setModelPlatforms(codes, cardKey) {
    const model = editModel(cardKey)
    if (!model) {
      return
    }
    const selected = form.value.platforms.filter((code) => codes === null || codes.includes(code))
    const removed = Object.keys(model.platforms).filter((code) => !selected.includes(code))
    if (
      removed.some((code) => hasPlatformDetails(model, code)) &&
      !(await confirmed(() =>
        confirm(
          model.id === undefined
            ? '移除平台会清除对应的补贴方案分配，是否继续？'
            : '移除平台会清除对应的数量和补贴方案分配，是否继续？',
          { confirmButtonText: '移除平台', cancelButtonText: '保留平台' },
        ),
      ))
    ) {
      return
    }
    model.followsOrderPlatforms = selected.length === form.value.platforms.length
    applyModelPlatforms(model, selected)
  }

  function updateModelPlatform(code, field, value, cardKey) {
    const model = editModel(cardKey)
    const entry = model?.platforms[code]
    if (!entry) {
      return
    }
    if (
      (field === 'quantity' && model.id !== undefined) ||
      (field === 'subsidyKey' && model.subsidies.some((item) => item.key === value))
    ) {
      entry[field] = value
    }
  }

  function addSubsidy(cardKey) {
    editModel(cardKey)?.subsidies.push(createSubsidy())
  }

  function updateSubsidy(subsidyKey, field, value, cardKey) {
    const subsidy = editModel(cardKey)?.subsidies.find((item) => item.key === subsidyKey)
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

  async function removeSubsidy(subsidyKey, cardKey) {
    const model = editModel(cardKey)
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
          { confirmButtonText: '删除方案', cancelButtonText: '保留方案' },
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

  async function removeModel(cardKey) {
    const model = editModel(cardKey)
    if (!model) {
      return false
    }
    const message =
      model.id === undefined
        ? '确定移除所选配置及其补贴方案吗？'
        : '确定移除该独立商品及其平台数量和补贴方案吗？'
    if (
      await confirmed(() =>
        confirm(message, { confirmButtonText: '移除商品卡', cancelButtonText: '保留商品卡' }),
      )
    ) {
      form.value.cards = form.value.cards.filter((item) => item.key !== cardKey)
      return true
    }
    return false
  }

  function removeConfiguration(configurationKey, cardKey) {
    const model = editModel(cardKey)
    if (!model || model.id !== undefined) {
      return
    }
    const remaining = model.goods.filter(
      (item) => policyConfigurationKey(item) !== configurationKey,
    )
    if (!remaining.length) {
      return removeModel(cardKey)
    }
    model.goods = remaining
  }
  function removeFile(uid) {
    if (blocked()) {
      return
    }
    const entry = files.value.find((item) => item.id === uid)
    if (entry) {
      return attachments.removeFile(entry)
    }
  }

  async function reloadDraft(id, force = true, revisionRound, signal) {
    if (
      persistence.conflict.value &&
      (!revisionRound || force) &&
      !(await confirmed(() =>
        confirm('读取服务端版本会替换当前填写内容，是否继续？', {
          confirmButtonText: '读取服务端版本',
          cancelButtonText: '保留内容',
        }),
      ))
    ) {
      return
    }
    return persistence.load(id, force, revisionRound, signal)
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
    if (!(await confirmPolicyReplacement())) {
      return false
    }
    clear()
    return true
  }

  async function confirmPolicyReplacement() {
    if (persistence.busy.value || confirming.value) {
      return false
    }
    if (
      persistence.hasUnsavedChanges() &&
      !(await confirmed(() =>
        confirm('打开该申请会替换当前未保存的内容，是否继续？', {
          confirmButtonText: '打开申请',
          cancelButtonText: '保留内容',
        }),
      ))
    ) {
      return false
    }
    return true
  }

  async function deleteDraft(row, confirmDeletion) {
    if (persistence.busy.value || !(await confirmed(confirmDeletion))) {
      return false
    }
    const deleted = await persistence.deleteDraft(row.id, row.recordVersion)
    if (deleted && persistence.record.value?.id === row.id) {
      clear()
    }
    return deleted
  }

  return {
    ...persistence,
    reloadDraft,
    preparePolicyEdit,
    confirmPolicyReplacement,
    deleteDraft,
    form,
    files,
    attachmentNotice,
    retryFile: (id) => {
      const entry = files.value.find((item) => item.id === id)
      if (entry) {
        return attachments.retryFile(entry)
      }
    },
    platforms,
    selectedPlatforms,
    removedPlatforms,
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
    removeConfiguration,
    removeFile,
  }
}
