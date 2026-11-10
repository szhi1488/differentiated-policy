const REQUEST_TIMEOUT_MS = 25_000

/** 统一处理同源请求、超时及接口错误；业务 API 只声明路径和参数。 */
export async function request(path, options = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  let response
  let data

  try {
    response = await fetch(path, {
      ...options,
      headers: { 'X-Policy-Request': '1', ...options.headers },
      signal: controller.signal,
      cache: 'no-store',
      credentials: 'same-origin',
    })
    data = response.status === 204 ? null : await response.json()
  } catch (error) {
    if (error.name === 'AbortError') {
      throw new Error('应用请求超时，请稍后重试。')
    }
    throw new Error(
      response
        ? '应用服务返回异常，请确认后端已启动。'
        : '无法连接应用服务，请确认后端已启动后重试。',
    )
  } finally {
    clearTimeout(timer)
  }

  if (!response.ok) {
    const error = new Error(data?.message || '应用请求失败，请稍后重试。')
    error.status = response.status
    error.code = data?.code
    throw error
  }
  return data
}
