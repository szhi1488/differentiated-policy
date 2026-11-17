import { computed, ref } from 'vue'
import { readAdminPolicy } from '../api/admin-policies'
import { coverageSignature, sameReviewVersion } from '../utils/policy-approval'
import { useLatestQuery } from './useLatestQuery'

/** 批准前独立复核，保留原审核窗口的筛选、分页和驳回原因。 */
export function useAdminPolicyApproval() {
  const opened = ref(false)
  const original = ref(null)
  const acknowledged = ref(false)
  const { data: latest, error, loading, run } = useLatestQuery()
  const changed = computed(
    () =>
      latest.value &&
      coverageSignature(original.value.coverage) !== coverageSignature(latest.value.coverage),
  )
  const canConfirm = computed(
    () => !!latest.value && !loading.value && (!changed.value || acknowledged.value),
  )
  const needsReload = computed(() => error.value?.code === 'REVIEW_CHANGED')

  function load() {
    acknowledged.value = false
    const source = original.value
    return run(async (signal) => {
      const result = await readAdminPolicy(source.policy.id, signal)
      if (
        !sameReviewVersion(source.policy, result.policy) ||
        result.policy.status !== 'PENDING' ||
        !result.policy.reviewable
      ) {
        throw Object.assign(new Error('申请状态或内容已变化，请重新读取政策后审核。'), {
          code: 'REVIEW_CHANGED',
        })
      }
      if (!Array.isArray(source.coverage?.items) || !Array.isArray(result.coverage?.items)) {
        throw new Error('未取得完整覆盖结果，请重新核对后再确认通过。')
      }
      return result
    })
  }

  function open(detail) {
    if (opened.value || !detail?.policy.reviewable) {
      return
    }
    original.value = detail
    opened.value = true
    void load()
  }

  function close() {
    opened.value = false
    // 取消在途读取并清除旧结果，迟到响应不能成为下次批准的依据。
    void run(() => null)
  }

  return {
    opened,
    original,
    latest,
    error,
    loading,
    changed,
    acknowledged,
    canConfirm,
    needsReload,
    open,
    close,
    load,
  }
}
