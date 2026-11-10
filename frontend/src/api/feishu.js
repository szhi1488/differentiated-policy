async function request(path, options = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 25000)
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
  } catch {
    throw new Error(response
      ? '应用服务返回异常，请确认后端已启动。'
      : '无法连接应用服务，请确认后端已启动后重试。')
  } finally {
    clearTimeout(timer)
  }
  if (!response.ok) {
    const error = new Error(data.message || '应用请求失败，请稍后重试。')
    error.status = response.status
    error.code = data.code
    throw error
  }
  return data
}

export function getFeishuConfig() {
  return request('/api/feishu/config')
}

export function getJsSdkSignature(url) {
  return request('/api/feishu/js-sdk/signature', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url }),
  })
}

export async function getLoginSession() {
  try { return await request('/api/auth/session') } catch (error) {
    if (error.status === 401) return null
    throw error
  }
}

export function createLoginChallenge() {
  return request('/api/auth/challenge', { method: 'POST' })
}

export function exchangeLoginCode(code, state) {
  return request('/api/auth/feishu/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code, state }),
  })
}

export function logout() {
  return request('/api/auth/logout', { method: 'POST' })
}
