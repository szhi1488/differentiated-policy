import { computed, reactive, ref, watch } from 'vue'
import { listUsers, readUser, readUserStats } from '../api/admin-users'
import { useLatestQuery } from './useLatestQuery'

const initialFilters = () => ({ keyword: '', roleCode: '', status: '' })

export function useUserManagement() {
  const filters = reactive(initialFilters())
  const applied = ref(initialFilters())
  const page = ref(1)
  const pageSize = ref(10)
  const list = useLatestQuery()
  const stats = useLatestQuery()
  const detail = useLatestQuery()
  const detailId = ref('')
  const detailOpen = computed({
    get: () => Boolean(detailId.value),
    set: (open) => {
      if (!open) {
        detailId.value = ''
      }
    },
  })

  function loadList() {
    return list.run((signal) =>
      listUsers({ ...applied.value, page: page.value, pageSize: pageSize.value }, signal),
    )
  }
  function loadStats() {
    return stats.run(readUserStats)
  }
  function loadDetail() {
    return detail.run((signal) => (detailId.value ? readUser(detailId.value, signal) : null))
  }
  function refresh() {
    return Promise.all([loadList(), loadStats(), loadDetail()])
  }
  function search() {
    applied.value = { ...filters, keyword: filters.keyword.trim() }
    page.value = 1
  }
  function reset() {
    Object.assign(filters, initialFilters())
    search()
  }
  watch(
    pageSize,
    () => {
      page.value = 1
    },
    { flush: 'sync' },
  )
  watch([page, pageSize, applied], loadList, { immediate: true })
  watch(list.data, (result) => {
    if (result) {
      page.value = Math.min(page.value, Math.max(1, Math.ceil(result.total / pageSize.value)))
    }
  })
  watch(detailId, loadDetail, { flush: 'sync' })
  void loadStats()

  return {
    filters,
    page,
    pageSize,
    detailId,
    detailOpen,
    search,
    reset,
    refresh,
    loadList,
    loadStats,
    loadDetail,
    usersPage: list.data,
    listBusy: list.loading,
    listError: list.error,
    counts: stats.data,
    statsBusy: stats.loading,
    statsError: stats.error,
    selectedUser: detail.data,
    detailBusy: detail.loading,
    detailError: detail.error,
  }
}
