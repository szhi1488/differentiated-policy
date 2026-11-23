import { computed, ref, toValue, watch } from 'vue'
import { listAdminPolicyAuditLogs, readAdminPolicyAuditLog } from '../api/admin-policies'
import { useLatestQuery } from './useLatestQuery'

/** 历轮列表与快照独立读取，切换轮次或申请时丢弃迟到响应。 */
export function useAdminPolicyHistory(policyId) {
  const page = ref(1)
  const pageSize = 10
  const selectedLogId = ref('')
  const list = useLatestQuery()
  const snapshot = useLatestQuery()

  function refresh() {
    const id = toValue(policyId)
    return list.run((signal) =>
      listAdminPolicyAuditLogs(id, { page: page.value, pageSize }, signal),
    )
  }

  function readSnapshot() {
    const id = toValue(policyId)
    const logId = selectedLogId.value
    return snapshot.run((signal) => (logId ? readAdminPolicyAuditLog(id, logId, signal) : null))
  }

  watch(
    () => toValue(policyId),
    () => {
      selectedLogId.value = ''
      if (page.value !== 1) {
        page.value = 1
      } else {
        void refresh()
      }
    },
    { immediate: true },
  )
  watch(page, () => {
    selectedLogId.value = ''
    void refresh()
  })
  watch([() => toValue(policyId), selectedLogId], readSnapshot)

  // 只使用指定轮次的快照和审核信息，不带入当前政策的状态、覆盖结果或写入权限。
  const detail = computed(() => {
    const result = snapshot.data.value
    if (!result) {
      return null
    }
    return {
      policy: {
        id: result.snapshot.id,
        policyCode: result.snapshot.policyCode,
        status: result.log.auditStatus === 'SUBMITTED' ? 'PENDING' : result.log.auditStatus,
        reviewable: false,
      },
      snapshot: result.snapshot,
      audit: result.log,
      coverage: null,
      round: result.round,
    }
  })

  return { page, pageSize, selectedLogId, list, snapshot, detail, refresh, readSnapshot }
}
