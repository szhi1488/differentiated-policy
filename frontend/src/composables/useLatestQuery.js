import { computed, onScopeDispose, ref } from 'vue'
/** 异步读取忽略过期及卸载后的响应，不承担业务 API 或分页契约。 */
export function useLatestQuery() {
  const data = ref(null),
    error = ref(null),
    loading = ref(false)
  let version = 0,
    controller,
    disposed = false
  async function run(loader) {
    if (disposed) {
      return
    }
    const current = ++version
    controller?.abort()
    controller = new AbortController()
    loading.value = true
    error.value = null
    data.value = null
    try {
      const result = await loader(controller.signal)
      if (!disposed && current === version) {
        data.value = result
      }
    } catch (cause) {
      if (!disposed && current === version && cause.kind !== 'cancelled') {
        error.value = cause
      }
    } finally {
      if (!disposed && current === version) {
        loading.value = false
      }
    }
  }
  onScopeDispose(() => {
    disposed = true
    version++
    controller?.abort()
  })
  const status = computed(() =>
    loading.value
      ? 'loading'
      : error.value
        ? 'error'
        : data.value === null || (Array.isArray(data.value) && !data.value.length)
          ? 'empty'
          : 'success',
  )
  return { data, error, loading, status, run }
}
