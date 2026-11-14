import { reactive, watch } from 'vue'
import {
  listMyPolicies,
  listPolicyAuditLogs,
  readPolicy,
  readPolicyAuditLog,
} from '../api/policies'
import { useLatestQuery } from './useLatestQuery'

const pageSize = 20

export function usePolicySubmissions() {
  const filters = reactive({ page: 1, status: '', perspective: '', platformCode: '' })
  const list = useLatestQuery()

  async function refresh() {
    await list.run((signal) => listMyPolicies({ ...filters, pageSize }, signal))
    const result = list.data.value
    const lastPage = Math.max(1, Math.ceil((result?.total ?? 0) / pageSize))
    if (result && filters.page > lastPage) {
      filters.page = lastPage
    }
  }

  function setFilter(key, value) {
    filters[key] = value
    filters.page = 1
  }

  watch(filters, refresh, { immediate: true })
  return { filters, setFilter, refresh, ...list }
}

export function usePolicySubmissionDetail(id) {
  const detail = useLatestQuery()
  const history = useLatestQuery()
  const snapshot = useLatestQuery()

  function readDetail() {
    return detail.run((signal) => readPolicy(id, signal))
  }

  function readHistory(page) {
    return history.run((signal) => listPolicyAuditLogs(id, { page, pageSize }, signal))
  }

  function readSnapshot(logId) {
    return snapshot.run((signal) => readPolicyAuditLog(id, logId, signal))
  }

  return { detail, history, snapshot, readDetail, readHistory, readSnapshot }
}
