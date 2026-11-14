import { computed, ref } from 'vue'
import { createPolicyDraft, readPolicy, savePolicyDraft, submitPolicy } from '../api/policies'
import { policyIsEditable } from '../constants/policy'
import { createRequestId } from '../utils/request-id'
import { policyDraft, restorePolicyForm } from '../utils/policy-draft'

const uncertain = (error) =>
  ['timeout', 'network', 'parse'].includes(error.kind) || error.status >= 500

export function usePolicyDraft(form, confirming) {
  const record = ref(null)
  const loading = ref(false)
  const writing = ref('')
  const notice = ref('')
  const noticeType = ref('warning')
  const loadError = ref('')
  const conflict = ref(false)
  const busy = computed(() => loading.value || Boolean(writing.value))
  const readOnly = computed(() => Boolean(record.value && !policyIsEditable(record.value.status)))
  const locked = computed(() => busy.value || readOnly.value || Boolean(loadError.value))
  let operation
  let pendingCreate
  let savedForm = JSON.stringify(form.value)

  function markSaved() {
    savedForm = JSON.stringify(form.value)
  }

  function show(message, type = 'warning') {
    notice.value = message
    noticeType.value = type
  }

  function clear() {
    operation?.abort()
    pendingCreate = undefined
    record.value = null
    loading.value = false
    writing.value = ''
    loadError.value = ''
    conflict.value = false
    show('')
  }

  async function load(id, force = false) {
    if (!id || writing.value || (!force && record.value?.id === id && !loadError.value)) {
      return false
    }
    operation?.abort()
    const controller = new AbortController()
    operation = controller
    loading.value = true
    loadError.value = ''
    show('')
    try {
      const detail = await readPolicy(id, controller.signal)
      if (!controller.signal.aborted) {
        form.value = restorePolicyForm(detail)
        record.value = detail
        conflict.value = false
        pendingCreate = undefined
        markSaved()
        return true
      }
    } catch (error) {
      if (!controller.signal.aborted) {
        loadError.value = error.message
      }
    } finally {
      if (!controller.signal.aborted) {
        loading.value = false
      }
    }
    return false
  }

  async function persist(submit) {
    if (locked.value || confirming.value || conflict.value) {
      return
    }
    const controller = new AbortController()
    operation = controller
    writing.value = submit ? 'submit' : 'save'
    show('')
    let phase = 'save'
    try {
      const body = policyDraft(form.value, record.value?.recordVersion)
      if (record.value) {
        const saved = await savePolicyDraft(record.value.id, body, controller.signal)
        if (controller.signal.aborted) {
          return
        }
        record.value = saved
      } else {
        pendingCreate ??= { key: createRequestId(), body }
        const original = pendingCreate.body
        const created = await createPolicyDraft(original, pendingCreate.key, controller.signal)
        if (controller.signal.aborted) {
          return
        }
        record.value = created
        pendingCreate = undefined
        if (JSON.stringify(original) !== JSON.stringify(body)) {
          const saved = await savePolicyDraft(
            created.id,
            { ...body, recordVersion: created.recordVersion },
            controller.signal,
          )
          if (controller.signal.aborted) {
            return
          }
          record.value = saved
        }
      }
      if (controller.signal.aborted) {
        return
      }
      markSaved()
      if (!submit) {
        show('草稿已保存。', 'success')
        return
      }
      phase = 'submit'
      const submitted = await submitPolicy(
        record.value.id,
        record.value.recordVersion,
        controller.signal,
      )
      if (!controller.signal.aborted) {
        record.value = submitted
        show('申请已提交，等待审核。', 'success')
      }
    } catch (error) {
      if (controller.signal.aborted) {
        return
      }
      if (!record.value && !uncertain(error) && error.status !== 409) {
        pendingCreate = undefined
      }
      if (phase === 'submit' && uncertain(error)) {
        const saved = record.value
        try {
          const detail = await readPolicy(saved.id, controller.signal)
          if (controller.signal.aborted) {
            return
          }
          if (
            !policyIsEditable(detail.status) &&
            detail.submissionVersion === saved.submissionVersion + 1 &&
            detail.recordVersion >= saved.recordVersion + 1
          ) {
            record.value = detail
            show('已核对，申请提交成功。', 'success')
            return
          }
        } catch {
          if (controller.signal.aborted) {
            return
          }
        }
      }
      if (
        error.code === 'POLICY_VERSION_CONFLICT' ||
        error.code === 'POLICY_STATE_CONFLICT' ||
        error.code === 'POLICY_CREATE_CONFLICT' ||
        (record.value && uncertain(error))
      ) {
        conflict.value = true
      }
      show(
        uncertain(error)
          ? record.value
            ? '请求结果待核对。当前输入已保留，可读取服务端版本核对状态。'
            : '首次保存结果待核对。再次保存会使用同一个创建标识。'
          : phase === 'submit'
            ? `草稿已保存。提交失败，${error.message}`
            : error.message,
      )
    } finally {
      if (!controller.signal.aborted) {
        writing.value = ''
      }
    }
  }

  return {
    record,
    busy,
    writing,
    readOnly,
    locked,
    loadError,
    conflict,
    notice,
    noticeType,
    clear,
    load,
    markSaved,
    hasUnsavedChanges: () => JSON.stringify(form.value) !== savedForm || Boolean(pendingCreate),
    saveDraft: () => persist(false),
    submit: () => persist(true),
  }
}
