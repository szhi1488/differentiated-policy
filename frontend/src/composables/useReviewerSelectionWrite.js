import { onScopeDispose, ref } from 'vue'

/** 保存名单发生冲突或结果未知时，锁住重复写入，等待管理员核对服务端名单。 */
export function useReviewerSelectionWrite() {
  const saving = ref(false)
  const error = ref('')
  const needsReload = ref(false)
  const controller = new AbortController()
  onScopeDispose(() => controller.abort())

  function reset() {
    error.value = ''
    needsReload.value = false
  }

  async function run(write, saved) {
    if (saving.value || needsReload.value) {
      return
    }
    saving.value = true
    error.value = ''
    try {
      const result = await write(controller.signal)
      if (!controller.signal.aborted) {
        saved(result)
      }
    } catch (cause) {
      if (controller.signal.aborted) {
        return
      }
      const unknown = !cause.status || cause.status >= 500 || cause.kind === 'parse'
      needsReload.value = unknown || [401, 403, 404, 409].includes(cause.status)
      error.value = unknown ? `保存结果待核对。${cause.message}` : cause.message
    } finally {
      saving.value = false
    }
  }
  return { saving, error, needsReload, reset, run }
}
