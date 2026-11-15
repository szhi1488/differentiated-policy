import { computed, ref, shallowRef, watch } from 'vue'
import { POLICY_STATUS_LABELS } from '../constants/policy'
import { ADMIN_POLICY_SAMPLES } from '../mocks/fixtures/admin-policies'

const emptyFilters = () => ({
  perspective: '',
  platform: '',
  verifyType: '',
  sceneType: '',
  applicant: '',
  submittedDates: null,
  keyword: '',
})

export function useAdminPolicyPreview() {
  const filters = ref(emptyFilters())
  const applied = shallowRef(emptyFilters())
  const status = ref('ALL')
  const page = ref(1)
  const pageSize = ref(10)
  const applicants = Object.fromEntries(
    ADMIN_POLICY_SAMPLES.map(({ applicant }) => [applicant, applicant]),
  )

  const matching = computed(() => {
    const { platform, submittedDates, keyword, ...fields } = applied.value
    const entries = Object.entries(fields)
    return ADMIN_POLICY_SAMPLES.filter((policy) => {
      const date = policy.submittedAt.slice(0, 10)
      const searchable = [
        policy.policyCode,
        policy.applicant,
        ...policy.goods.map(({ model }) => model),
      ]
        .join(' ')
        .toLowerCase()
      return (
        entries.every(([key, value]) => !value || policy[key] === value) &&
        (!platform || policy.platforms.includes(platform)) &&
        (!submittedDates || (date >= submittedDates[0] && date <= submittedDates[1])) &&
        searchable.includes(keyword)
      )
    })
  })

  const tabs = computed(() => [
    { value: 'ALL', label: '全部政策', count: matching.value.length },
    ...['PENDING', 'APPROVED', 'REJECTED'].map((value) => ({
      value,
      label: POLICY_STATUS_LABELS[value],
      count: matching.value.filter((policy) => policy.status === value).length,
    })),
  ])
  const filtered = computed(() =>
    status.value === 'ALL'
      ? matching.value
      : matching.value.filter((policy) => policy.status === status.value),
  )
  const total = computed(() => filtered.value.length)
  const visibleRows = computed(() => {
    const start = (page.value - 1) * pageSize.value
    return filtered.value.slice(start, start + pageSize.value)
  })
  watch([status, applied, pageSize], () => {
    page.value = 1
  })

  function search() {
    const { submittedDates, keyword, ...fields } = filters.value
    applied.value = {
      ...fields,
      submittedDates: submittedDates?.slice() ?? null,
      keyword: keyword.trim().toLowerCase(),
    }
  }

  function reset() {
    filters.value = emptyFilters()
    search()
  }

  return {
    filters,
    status,
    tabs,
    page,
    pageSize,
    total,
    visibleRows,
    applicants,
    search,
    reset,
  }
}
