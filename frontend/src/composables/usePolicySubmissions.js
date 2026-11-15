import { reactive, toValue, watch } from 'vue'
import { listMyPolicies, listPolicyAuditLogs, readPolicy } from '../api/policies'
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

  function readDetail() {
    return detail.run((signal) => readPolicy(id, signal))
  }

  return { detail, readDetail }
}

export function usePolicyRejection(policy) {
  const query = useLatestQuery()

  function refresh() {
    const current = toValue(policy)
    return query.run(async (signal) => {
      if (current?.status !== 'REJECTED') {
        return null
      }
      const result = await listPolicyAuditLogs(current.id, { page: 1, pageSize: 1 }, signal)
      // 只显示当前轮次的驳回意见，不能把上一轮意见当成本轮结果。
      return (
        result.items.find(
          (log) =>
            log.submissionVersion === current.submissionVersion && log.auditStatus === 'REJECTED',
        ) ?? null
      )
    })
  }

  watch(
    () => {
      const current = toValue(policy)
      return [current?.id, current?.status, current?.submissionVersion]
    },
    refresh,
    { immediate: true },
  )
  return { ...query, refresh }
}
