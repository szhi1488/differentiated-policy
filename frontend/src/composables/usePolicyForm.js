import { computed, ref } from 'vue'
import { BILLION_PLATFORMS, RETAIL_PLATFORMS } from '../constants/policy'
import { changeChannel, createDetail, createForm, hasDetailInput } from '../utils/policy-form'

/** 维护当前页面的表单状态，不承担服务端提交、权限判断或政策生效计算。 */
export function usePolicyForm(confirm = (message) => window.confirm(message)) {
  const form = ref(createForm())
  const activePlatform = ref('')
  const files = ref([])
  const notice = ref('')
  const platforms = computed(() =>
    form.value.channel === 'RETAIL' ? RETAIL_PLATFORMS : BILLION_PLATFORMS,
  )
  const selectedPlatforms = computed(() =>
    platforms.value.filter(([code]) => form.value.platforms.includes(code)),
  )
  const activePlatformName = computed(
    () => platforms.value.find(([code]) => code === activePlatform.value)?.[1] || '',
  )
  const rows = computed({
    get: () => form.value.details[activePlatform.value] || [],
    set: (value) => {
      if (activePlatform.value) {
        form.value.details[activePlatform.value] = value
      }
    },
  })

  function switchChannel(channel) {
    if (channel === form.value.channel) {
      return true
    }
    if (
      form.value.platforms.length &&
      !confirm('切换渠道会清除已选平台及对应机型明细，是否继续？')
    ) {
      return false
    }
    changeChannel(form.value, channel)
    activePlatform.value = ''
    notice.value = ''
    return true
  }

  function addPlatform(code) {
    if (form.value.platforms.includes(code)) {
      return
    }
    form.value.platforms.push(code)
    form.value.details[code] = [createDetail()]
  }

  function togglePlatform(code) {
    if (form.value.platforms.includes(code)) {
      if (
        form.value.details[code]?.some(hasDetailInput) &&
        !confirm('取消该平台会清除该平台已填写的机型及补贴信息，是否继续？')
      ) {
        return
      }
      form.value.platforms = form.value.platforms.filter((value) => value !== code)
      delete form.value.details[code]
      if (activePlatform.value === code) {
        activePlatform.value = form.value.platforms[0] || ''
      }
    } else {
      addPlatform(code)
      activePlatform.value = code
    }
  }

  function selectAll() {
    platforms.value.forEach(([code]) => addPlatform(code))
    if (!activePlatform.value) {
      activePlatform.value = form.value.platforms[0]
    }
  }

  function addModel() {
    if (!activePlatform.value) {
      notice.value = '请先选择平台，再添加该平台的机型。'
      return
    }
    rows.value.push(createDetail())
    notice.value = ''
  }

  function addFiles(list) {
    files.value.push(...Array.from(list || []).map((file) => ({ id: crypto.randomUUID(), file })))
  }

  function reset() {
    if (!confirm('确定清空当前填写的内容吗？')) {
      return
    }
    form.value = createForm()
    activePlatform.value = ''
    files.value = []
    notice.value = ''
  }

  function submit() {
    notice.value = '政策提交功能尚未接入，当前填写内容未提交。'
  }

  return {
    form,
    activePlatform,
    files,
    notice,
    platforms,
    selectedPlatforms,
    activePlatformName,
    rows,
    switchChannel,
    togglePlatform,
    selectAll,
    addModel,
    addFiles,
    reset,
    submit,
  }
}
