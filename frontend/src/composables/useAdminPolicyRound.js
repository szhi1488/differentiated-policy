import { computed, watch } from 'vue'
import { isApprovedBatchExtension, verifyCurrentRound } from '../api/admin-policies'
import { readPolicyReviewRound } from '../api/policy-reviews'
import { useLatestQuery } from './useLatestQuery'

/** 成员与办理资格独立读取，审核服务失败时仍可查看已获授权的申请快照。 */
export function useAdminPolicyRound(detail, { historical = false } = {}) {
  const query = useLatestQuery()
  const target = computed(() => {
    const current = detail.value
    return current?.audit &&
      !current.round &&
      !isApprovedBatchExtension(current.audit, current.snapshot)
      ? current
      : null
  })
  function loadRound() {
    const current = target.value
    return query.run(async (signal) => {
      if (!current) {
        return null
      }
      const round = await readPolicyReviewRound(
        current.policy.id,
        current.audit.submissionVersion,
        signal,
      )
      return historical ? round : verifyCurrentRound(current, round)
    })
  }
  watch(target, loadRound, { immediate: true, flush: 'sync' })
  const round = computed(() => detail.value?.round ?? (target.value ? query.data.value : null))
  const roundBusy = computed(() => Boolean(target.value && query.loading.value))
  return { round, roundBusy, roundError: query.error, loadRound }
}
