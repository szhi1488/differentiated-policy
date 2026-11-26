import { computed, onScopeDispose, provide, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useLatestQuery } from './useLatestQuery'
import { readAssignedPlatforms } from '../api/policies'
import { usePolicyForm } from './usePolicyForm'
import { authSession } from './useAuthSession'

export function useWorkspacePolicyForm() {
  const router = useRouter()
  const permissions = useLatestQuery()
  const refreshPlatforms = () => permissions.run(readAssignedPlatforms)
  const state = usePolicyForm(permissions.data, undefined, refreshPlatforms)
  provide('platformPermissions', { ...permissions, refreshPlatforms })
  const applicationRoute = computed(() => ({
    name: 'apply',
    query: state.record.value ? { policyId: state.record.value.id } : {},
  }))
  provide('policyForm', state)

  // 在地址变更前确认，取消导航时保留原地址、表单和附件；加载由申请页负责。
  const removePolicyGuard = router.beforeEach((to) => {
    const id = to.query.policyId
    if (to.name === 'apply' && to.query.round && (state.busy.value || state.confirming.value)) {
      return false
    }
    if (
      to.name === 'apply' &&
      typeof id === 'string' &&
      (id !== state.record.value?.id ||
        state.loading.value ||
        (to.query.round && Number(to.query.round) !== state.record.value?.submissionVersion))
    ) {
      return state.confirmPolicyReplacement()
    }
    return true
  })
  onScopeDispose(removePolicyGuard)
  watch(
    authSession.revision,
    () => {
      state.clear()
    },
    { flush: 'sync' },
  )
  onScopeDispose(state.clear)
  return applicationRoute
}
