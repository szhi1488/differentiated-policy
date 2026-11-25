import { computed, ref } from 'vue'
import {
  createPolicyDraft,
  deletePolicyDraft,
  readPolicy,
  previewPolicySubmission,
  previewPolicyAdjustments,
  savePolicyDraft,
  submitPolicy,
} from '../api/policies'
import { policyIsEditable, policyStatusLabel } from '../constants/policy'
import { RequestError } from '../api/request'
import { readSubmissionPreview } from '../utils/policy-applicability'
import { createRequestId } from '../utils/request-id'
import { policyDraft, restorePolicyForm } from '../utils/policy-draft'

const uncertain = (error) =>
  ['timeout', 'network', 'parse'].includes(error.kind) || error.status >= 500

export function usePolicyDraft(form, confirming, attachments, refreshPlatforms) {
  const record = ref(null)
  const loading = ref(false)
  const writing = ref('')
  const notice = ref('')
  const noticeType = ref('warning')
  const loadError = ref('')
  const conflict = ref(false)
  const busy = computed(
    () => loading.value || Boolean(writing.value) || Boolean(attachments?.attachmentsBusy.value),
  )
  const readOnly = computed(() => Boolean(record.value && !policyIsEditable(record.value.status)))
  const locked = computed(() => busy.value || readOnly.value || Boolean(loadError.value))
  const submissionPreview = ref(null)
  let finishConfirmation
  let operation
  let pendingCreate
  let savedForm = JSON.stringify(form.value)

  function markSaved() {
    savedForm = JSON.stringify(form.value)
  }

  function hasUnsavedChanges() {
    return (
      JSON.stringify(form.value) !== savedForm ||
      Boolean(pendingCreate) ||
      Boolean(attachments?.attachmentsFailed.value)
    )
  }

  function show(message, type = 'warning') {
    notice.value = message
    noticeType.value = type
  }

  function confirmSubmission(decisions = null) {
    finishConfirmation?.(decisions)
    finishConfirmation = undefined
    submissionPreview.value = null
  }

  function clear() {
    confirmSubmission()
    operation?.abort()
    operation = undefined
    pendingCreate = undefined
    record.value = null
    loading.value = false
    writing.value = ''
    loadError.value = ''
    conflict.value = false
    show('')
  }

  async function load(id, force = false, revisionRound, signal) {
    if (
      !id ||
      writing.value ||
      (!revisionRound && !force && record.value?.id === id && !loadError.value)
    ) {
      return false
    }
    operation?.abort()
    const controller = new AbortController()
    const cancel = () => controller.abort()
    if (signal?.aborted) {
      return false
    }
    signal?.addEventListener('abort', cancel, { once: true })
    operation = controller
    loading.value = true
    if (!revisionRound) {
      loadError.value = ''
    }
    show('')
    try {
      const detail = await readPolicy(id, controller.signal)
      if (!controller.signal.aborted) {
        if (revisionRound) {
          if (detail.id !== id || detail.submissionVersion !== revisionRound) {
            throw new Error('单据轮次已变更，此链接无法修改当前申请。')
          }
          if (detail.status !== 'REJECTED') {
            throw new Error('本轮已处理，当前没有退回修改任务。')
          }
          // 此处已核对当前归属、轮次及退回状态。服务端版本有变化时先保留本地输入。
          if (
            !force &&
            record.value?.id === id &&
            record.value.submissionVersion === revisionRound &&
            record.value.status === 'REJECTED'
          ) {
            const sameVersion = record.value.recordVersion === detail.recordVersion
            if (sameVersion || conflict.value || hasUnsavedChanges()) {
              if (!sameVersion) {
                conflict.value = true
              }
              loadError.value = ''
              return true
            }
          }
        }
        form.value = restorePolicyForm(detail)
        attachments?.restore(form.value.attachments)
        record.value = detail
        loadError.value = ''
        conflict.value = false
        pendingCreate = undefined
        markSaved()
        return true
      }
    } catch (error) {
      if (!controller.signal.aborted) {
        if (revisionRound) {
          throw error
        }
        loadError.value = error.message
      }
    } finally {
      signal?.removeEventListener('abort', cancel)
      // 仅当前请求清理状态；取消也须复位，旧请求不得影响后续请求。
      if (operation === controller) {
        operation = undefined
        loading.value = false
      }
    }
    return false
  }

  async function persist(action) {
    if (locked.value || confirming.value || conflict.value) {
      return
    }
    if (attachments?.attachmentsFailed.value) {
      show('有文件上传失败，请重试或移除后再保存或提交。')
      return
    }
    const controller = new AbortController()
    operation = controller
    writing.value = action
    show('')
    let phase = 'save'
    try {
      if (record.value) {
        const current = record.value
        const saved = await savePolicyDraft(
          current.id,
          policyDraft(form.value, current.recordVersion),
          controller.signal,
        )
        if (controller.signal.aborted) {
          return
        }
        record.value = { ...saved, endAdjustments: current.endAdjustments }
      } else {
        const body = policyDraft(form.value)
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
      attachments?.markBound()
      if (action === 'save') {
        return true
      }
      let changed = false
      // 驳回后保存只返回版本摘要，仍须用上次正式提交的批量调整重新核对本轮。
      let previousAdjustments =
        record.value.status === 'REJECTED' ? (record.value.endAdjustments ?? []) : []
      let retriedDirect = false
      let submitted
      while (true) {
        phase = 'preview'
        const { id, recordVersion } = record.value
        const preview = await previewPolicySubmission(id, recordVersion, controller.signal)
        if (controller.signal.aborted) {
          return
        }
        const scopePreview = readSubmissionPreview(preview)
        const request = { recordVersion }
        const options = preview.applicability.adjustmentOptions || []
        if (scopePreview || previousAdjustments.length) {
          const changedTargets = previousAdjustments.some(
            ({ target }) =>
              !options.some(
                (option) =>
                  option.target.policyId === target.policyId &&
                  option.target.auditLogId === target.auditLogId &&
                  option.target.previousEnd === target.previousEnd &&
                  option.target.recordVersion === target.recordVersion,
              ),
          )
          if (changedTargets) {
            show(
              '原调整目标已失去调整资格或实际截止已变化，请核对最新截止时间并移除不可调整的政策。',
              'warning',
            )
          }
          const recalculate = async (changes) => {
            const adjusted = await previewPolicyAdjustments(
              id,
              recordVersion,
              changes,
              controller.signal,
            )
            return {
              ...(readSubmissionPreview(adjusted) || { scopes: [], ranges: [] }),
              confirmationToken: adjusted.confirmationToken,
              options: adjusted.applicability.adjustmentOptions || [],
            }
          }
          const decisions = await new Promise((resolve) => {
            finishConfirmation = resolve
            submissionPreview.value = {
              ...(scopePreview ?? { scopes: [], ranges: [] }),
              changed: changed || changedTargets,
              previousAdjustments,
              options,
              recalculate,
            }
          })
          if (controller.signal.aborted) {
            return
          }
          if (!decisions) {
            show('已返回编辑，表单和附件已保留。', 'info')
            return
          }
          retriedDirect = false
          previousAdjustments = decisions.endAdjustments ?? []
          if (Array.isArray(decisions)) {
            if (scopePreview) {
              request.confirmationToken = preview.confirmationToken
              request.decisions = decisions
            }
          } else {
            Object.assign(request, decisions)
          }
        } else if (changed) {
          // 新预览已无交集时仍可直提；范围连续变化时把控制权交回申请人。
          if (retriedDirect) {
            show('适用范围连续变化，表单和附件已保留。请再次提交以核对最新结果。')
            return
          }
          retriedDirect = true
        }
        phase = 'submit'
        try {
          submitted = await submitPolicy(id, request, controller.signal)
          if (
            submitted?.id !== id ||
            submitted.status !== 'PENDING' ||
            submitted.submissionVersion !== record.value.submissionVersion + 1
          ) {
            throw new RequestError('提交响应缺少本轮待审核结果。', 'parse')
          }
          break
        } catch (error) {
          if (
            ![
              'POLICY_COVERAGE_CHANGED',
              'POLICY_CONFIRMATION_REQUIRED',
              'POLICY_ADJUSTMENT_CHANGED',
            ].includes(error.code) ||
            controller.signal.aborted
          ) {
            throw error
          }
          changed = true
        }
      }
      if (!controller.signal.aborted) {
        record.value = submitted
        show(
          submitted.reviewStage
            ? `申请已提交，${policyStatusLabel(submitted)}。`
            : '申请已提交，等待审核。',
          'success',
        )
        return true
      }
    } catch (error) {
      if (controller.signal.aborted) {
        return
      }
      if (error.code === 'POLICY_RETURN_TO_EDIT' || error.code === 'POLICY_NO_EFFECTIVE_PERIOD') {
        show(error.message)
        return
      }
      if (error.code === 'POLICY_PLATFORM_FORBIDDEN') {
        void refreshPlatforms?.()
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
            show(
              detail.reviewStage
                ? `已核对，申请提交成功，${policyStatusLabel(detail)}。`
                : '已核对，申请提交成功。',
              'success',
            )
            return true
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
        (phase !== 'preview' && record.value && uncertain(error))
      ) {
        conflict.value = true
      }
      show(
        phase === 'preview'
          ? '草稿已保存。' + error.message
          : uncertain(error)
            ? record.value
              ? '请求结果待核对。当前输入已保留，可读取服务端版本核对状态。'
              : '首次保存结果待核对。再次保存会使用同一个创建标识。'
            : phase === 'submit'
              ? `草稿已保存。提交失败，${error.message}`
              : error.message,
        'error',
      )
    } finally {
      if (operation === controller) {
        operation = undefined
        writing.value = ''
      }
    }
  }

  async function deleteDraft(id, recordVersion) {
    if (busy.value || confirming.value) {
      return false
    }
    const controller = new AbortController()
    operation = controller
    writing.value = 'delete'
    try {
      await deletePolicyDraft(id, recordVersion, controller.signal)
      return !controller.signal.aborted
    } catch (error) {
      if (controller.signal.aborted) {
        return false
      }
      if (uncertain(error)) {
        try {
          await readPolicy(id, controller.signal)
        } catch (verificationError) {
          if (
            !controller.signal.aborted &&
            verificationError.status === 404 &&
            verificationError.code === 'POLICY_NOT_FOUND'
          ) {
            return true
          }
        }
      }
      if (!controller.signal.aborted) {
        throw error
      }
      return false
    } finally {
      if (operation === controller) {
        operation = undefined
        writing.value = ''
      }
    }
  }

  return {
    record,
    submissionPreview,
    confirmSubmission,
    loading,
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
    hasUnsavedChanges,
    saveDraft: () => persist('save'),
    deleteDraft,
    submit: () => persist('submit'),
  }
}
