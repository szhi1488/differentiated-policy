import { computed, reactive, ref } from 'vue'
import { demoPolicies } from '../views/admin/data/demo'
const defaults = () => ({
  channel: '',
  platform: '',
  verifyType: '',
  scene: '',
  applicant: '',
  dates: ['', ''],
  keyword: '',
})
export function useAdminPolicies(reviewOnly = false) {
  const draft = reactive(defaults())
  const filters = ref(defaults())
  const status = ref(reviewOnly ? 'PENDING' : 'ALL')
  const page = ref(1)
  const pageSize = ref(5)
  const selected = ref(null)
  const dateError = computed(() =>
    draft.dates[0] && draft.dates[1] && draft.dates[0] > draft.dates[1]
      ? '开始日期不能晚于结束日期'
      : '',
  )
  const baseRows = computed(() =>
    demoPolicies.filter((item) => !reviewOnly || item.status === 'PENDING'),
  )
  const filtered = computed(() =>
    baseRows.value.filter((item) => {
      const f = filters.value
      const keyword = f.keyword.trim().toLowerCase()
      return (
        (status.value === 'ALL' || item.status === status.value) &&
        (!f.channel || item.channel === f.channel) &&
        (!f.platform || item.platforms.includes(f.platform)) &&
        (!f.verifyType || item.verifyType === f.verifyType) &&
        (!f.scene || item.scene === f.scene) &&
        (!f.applicant || item.applicant === f.applicant) &&
        (!f.dates[0] || item.submittedAt >= `${f.dates[0]} 00:00:00`) &&
        (!f.dates[1] || item.submittedAt <= `${f.dates[1]} 23:59:59`) &&
        (!keyword ||
          [item.code, item.title, item.applicant, ...item.details.map((row) => row.model)]
            .join(' ')
            .toLowerCase()
            .includes(keyword))
      )
    }),
  )
  const rows = computed(() =>
    filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value),
  )
  function search() {
    if (dateError.value) {
      return
    }
    filters.value = { ...draft, dates: [...(draft.dates || [])] }
    page.value = 1
    selected.value = null
  }
  function reset() {
    Object.assign(draft, defaults())
    search()
  }
  function changeStatus() {
    page.value = 1
    selected.value = null
  }
  function changeChannel() {
    draft.platform = ''
    draft.verifyType = ''
    draft.scene = ''
  }
  return {
    draft,
    status,
    page,
    pageSize,
    selected,
    dateError,
    baseRows,
    filtered,
    rows,
    search,
    reset,
    changeStatus,
    changeChannel,
  }
}
