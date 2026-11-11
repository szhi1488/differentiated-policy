const REQUEST_TIMEOUT_MS = 25_000
let onUnauthorized = () => {}
export function setUnauthorizedHandler(handler) {
  onUnauthorized = handler
}
export class RequestError extends Error {
  constructor(message, kind, status, code) {
    super(message)
    this.name = 'RequestError'
    this.kind = kind
    this.status = status
    this.code = code
  }
}
/** 保留 HTTP 状态，取消覆盖响应体读取；请求层不弹窗、不自动重试写操作。 */
export async function request(path, options = {}) {
  const { signal, timeoutMs = REQUEST_TIMEOUT_MS, sessionProbe = false, ...init } = options
  const controller = new AbortController()
  let timedOut = false
  let response
  const cancel = () => {
    if (!controller.signal.aborted) {
      controller.abort()
    }
  }
  signal?.addEventListener('abort', cancel, { once: true })
  if (signal?.aborted) {
    cancel()
  }
  const timer = setTimeout(() => {
    if (!controller.signal.aborted) {
      timedOut = true
      controller.abort()
    }
  }, timeoutMs)
  try {
    if (controller.signal.aborted) {
      throw new DOMException('aborted', 'AbortError')
    }
    response = await fetch(path, {
      ...init,
      headers: { 'X-Policy-Request': '1', ...init.headers },
      signal: controller.signal,
      cache: 'no-store',
      credentials: 'same-origin',
    })
    if (response.status === 401 && !sessionProbe) {
      onUnauthorized()
    }
    let data = null
    let parseError = false
    if (response.status !== 204) {
      try {
        data = await response.json()
      } catch (error) {
        if (controller.signal.aborted || error.name === 'AbortError') {
          throw error
        }
        if (error instanceof SyntaxError) {
          parseError = true
        } else {
          throw error
        }
      }
    }
    if (controller.signal.aborted) {
      throw new DOMException('aborted', 'AbortError')
    }
    if (!response.ok) {
      const fallback =
        {
          401: '会话已失效，请重新登录。',
          403: '无权访问此内容，请联系管理员。',
          409: '内容或状态已变化，请保留编辑内容并刷新核对。',
        }[response.status] || '应用请求失败，请稍后重试。'
      throw new RequestError(data?.message || fallback, 'business', response.status, data?.code)
    }
    if (parseError) {
      throw new RequestError('应用响应解析失败。', 'parse', response.status)
    }
    return data
  } catch (error) {
    if (error instanceof RequestError) {
      throw error
    }
    if (controller.signal.aborted || error.name === 'AbortError') {
      throw new RequestError(
        timedOut ? '应用请求超时，请稍后重试。' : '请求已取消。',
        timedOut ? 'timeout' : 'cancelled',
        response?.status,
      )
    }
    throw new RequestError(
      '无法连接应用服务，请确认后端已启动后重试。',
      'network',
      response?.status,
    )
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', cancel)
  }
}
