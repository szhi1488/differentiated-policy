import { onMounted, onScopeDispose, watch } from 'vue'
import { readPolicyReviewRound } from '../api/policy-reviews'
import { useLatestQuery } from './useLatestQuery'

export function usePolicyReviewRound(props) {
  const query = useLatestQuery()
  function refresh() {
    return query.run((signal) =>
      readPolicyReviewRound(props.policyId, props.submissionVersion, signal),
    )
  }
  function refreshOnReturn() {
    if (!document.hidden && !query.loading.value) {
      void refresh()
    }
  }
  watch([() => props.policyId, () => props.submissionVersion], refresh, { immediate: true })
  onMounted(() => {
    window.addEventListener('focus', refreshOnReturn)
    document.addEventListener('visibilitychange', refreshOnReturn)
  })
  onScopeDispose(() => {
    window.removeEventListener('focus', refreshOnReturn)
    document.removeEventListener('visibilitychange', refreshOnReturn)
  })
  return { round: query.data, loading: query.loading, error: query.error, refresh }
}
