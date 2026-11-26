import { onScopeDispose } from 'vue'

/** 页面可见时查询任务，按最新任务状态决定是否安排下一次查询。 */
export function useVisibleTaskPolling(read, nextDelay) {
  let timer
  let controller
  let disposed = false

  function stop() {
    clearTimeout(timer)
    controller?.abort()
  }

  async function refresh(reason = 'refresh') {
    if (disposed || document.hidden) {
      return
    }
    stop()
    const request = new AbortController()
    controller = request
    try {
      await read(request.signal, reason)
    } finally {
      if (!request.signal.aborted) {
        controller = undefined
        const delay = nextDelay()
        if (delay > 0 && !document.hidden) {
          timer = setTimeout(() => {
            if (nextDelay() > 0) {
              void refresh('poll')
            }
          }, delay)
        }
      }
    }
  }

  function onVisibilityChange() {
    if (document.hidden) {
      stop()
    } else {
      void refresh('visible')
    }
  }

  document.addEventListener('visibilitychange', onVisibilityChange)
  onScopeDispose(() => {
    disposed = true
    document.removeEventListener('visibilitychange', onVisibilityChange)
    stop()
  })

  return { refresh, stop }
}
