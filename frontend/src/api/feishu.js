async function request(path, options = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 25000)
  let response
  let data
  try {
    response = await fetch(path, {
      ...options,
      signal: controller.signal,
      cache: 'no-store',
      credentials: 'same-origin',
    })
    data = await response.json()
  } catch {
    throw new Error(response
      ? '应用服务返回异常，请确认后端已启动。'
      : '无法连接应用服务，请确认后端已启动后重试。')
  } finally {
    clearTimeout(timer)
  }
  if (!response.ok) {
    throw new Error(data.message || '飞书配置请求失败，请稍后重试。')
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
