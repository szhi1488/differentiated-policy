import { computed, onMounted, onScopeDispose, ref, watch } from 'vue'
import { readPolicyReviewRound, reviewProductPolicy } from '../api/policy-reviews'
import { reviewAdminPolicy } from '../api/admin-policies'
import { useLatestQuery } from './useLatestQuery'

export function usePolicyReviewRound(props) {
  const query = useLatestQuery()
  const round = query.data
  const reason = ref('')
  const feedback = ref(null)
  const writing = ref(false)
  const linkedStage = ref('')
  let writeRequest
  const product = computed(() => round.value?.reviewStage === 'PRODUCT_REVIEW')
  const actions = computed(() => {
    const result = round.value
    if (
      !result ||
      result.status !== 'PENDING' ||
      result.submissionVersion !== result.currentSubmissionVersion ||
      (linkedStage.value && linkedStage.value !== result.reviewStage) ||
      !['PRODUCT_REVIEW', 'PURCHASE_REVIEW'].includes(result.reviewStage)
    ) {
      return []
    }
    return result.allowedActions
  })

  async function refresh() {
    const { policyId, submissionVersion } = props
    await query.run(async (signal) => {
      const result = await readPolicyReviewRound(policyId, submissionVersion, signal)
      if (result.id !== policyId || result.submissionVersion !== submissionVersion) {
        throw new Error('返回内容与所查单据或轮次不符，请重新读取。')
      }
      return result
    })
    // 通用查看链接也固定首次显示的环节，避免已填意见随异步刷新流入下一节点。
    if (!linkedStage.value && round.value?.reviewStage) {
      linkedStage.value = round.value.reviewStage
    }
  }
  function refreshOnReturn() {
    if (!document.hidden && !writing.value && !query.loading.value) {
      void refresh()
    }
  }
  watch(
    () => [props.policyId, props.submissionVersion, props.node],
    () => {
      writeRequest?.abort()
      writing.value = false
      linkedStage.value = props.node
      reason.value = ''
      feedback.value = null
      void refresh()
    },
    { immediate: true },
  )
  onMounted(() => {
    window.addEventListener('focus', refreshOnReturn)
    document.addEventListener('visibilitychange', refreshOnReturn)
  })
  onScopeDispose(() => {
    writeRequest?.abort()
    window.removeEventListener('focus', refreshOnReturn)
    document.removeEventListener('visibilitychange', refreshOnReturn)
  })

  async function decide(decision) {
    if (writing.value || !actions.value.includes(decision)) {
      return
    }
    const current = round.value
    const remark =
      decision === 'REJECT' ? reason.value.replace(/^\p{White_Space}+|\p{White_Space}+$/gu, '') : ''
    if ((decision === 'REJECT' && product.value && !remark) || [...remark].length > 512) {
      feedback.value = {
        type: 'error',
        message: remark ? '审核意见最多 512 字。' : '请填写初审退回原因。',
      }
      return
    }
    writing.value = true
    feedback.value = null
    const controller = new AbortController()
    writeRequest = controller
    const submit = product.value ? reviewProductPolicy : reviewAdminPolicy
    try {
      await submit(
        current.id,
        {
          submissionVersion: current.submissionVersion,
          recordVersion: current.recordVersion,
          decision,
          reviewRemark: remark || null,
        },
        controller.signal,
      )
      if (controller.signal.aborted) {
        return
      }
      feedback.value = { type: 'success', message: '已提交审核决定。' }
      reason.value = ''
      await refresh()
    } catch (error) {
      if (controller.signal.aborted) {
        return
      }
      const uncertain = ['network', 'timeout', 'parse'].includes(error.kind) || error.status >= 500
      feedback.value = {
        type: 'error',
        message: uncertain ? error.message + ' 请按本轮实际办理记录核对结果。' : error.message,
      }
      if (uncertain || [403, 404, 409].includes(error.status)) {
        await refresh()
      }
    } finally {
      if (!controller.signal.aborted) {
        writing.value = false
      }
    }
  }
  return {
    round,
    loading: query.loading,
    error: query.error,
    actions,
    linkedStage,
    product,
    reason,
    feedback,
    writing,
    refresh,
    decide,
  }
}
