import { computed, ref, shallowRef, watch } from 'vue'
import { listAdminPolicies } from '../api/admin-policies'
import { POLICY_STATUS_LABELS } from '../constants/policy'
import { policyTime } from '../utils/policy-adapter'
import { useLatestQuery } from './useLatestQuery'

const emptyFilters = () => ({
  perspective: '',
  platformCode: '',
  verifyType: '',
  sceneType: '',
  applicant: '',
  submittedDates: null,
  keyword: '',
})

export function useAdminPolicies() {
  const filters = ref(emptyFilters())
  const applied = shallowRef({})
  const status = ref('ALL')
  const page = ref(1)
  const pageSize = ref(10)
  const query = useLatestQuery()
  const info = shallowRef(null)
  const total = computed(() => info.value?.total ?? 0)
  const counts = computed(() => info.value?.counts ?? {})
  const tabs = computed(() => [
    {
      value: 'ALL',
      label: '全部政策',
      count: Object.values(counts.value).reduce((sum, n) => sum + n, 0),
    },
    ...['PENDING', 'APPROVED', 'REJECTED'].map((value) => ({
      value,
      label: POLICY_STATUS_LABELS[value],
      count: counts.value[value] ?? 0,
    })),
  ])
  const visibleRows = computed(() =>
    (query.data.value?.items ?? []).map((row) => ({
      ...row,
      submittedAt: policyTime(row.submittedAt).replace('T', ' '),
    })),
  )

  function reload() {
    return query.run((signal) =>
      listAdminPolicies(
        {
          ...applied.value,
          status: status.value === 'ALL' ? '' : status.value,
          page: page.value,
          pageSize: pageSize.value,
        },
        signal,
      ),
    )
  }

  watch(
    [status, pageSize],
    () => {
      page.value = 1
    },
    { flush: 'sync' },
  )
  watch([status, page, pageSize, applied], reload, { immediate: true })
  watch(query.data, (data) => {
    if (data) {
      info.value = data
      const lastPage = Math.max(1, Math.ceil(data.total / pageSize.value))
      if (page.value > lastPage) {
        page.value = lastPage
      }
    }
  })

  function search() {
    const { submittedDates, keyword, applicant, ...fields } = filters.value
    page.value = 1
    applied.value = {
      ...fields,
      submittedFrom: submittedDates?.[0],
      submittedTo: submittedDates?.[1],
      applicant: applicant.trim(),
      keyword: keyword.trim(),
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
    search,
    reset,
    reload,
    listBusy: query.loading,
    listError: query.error,
  }
}
