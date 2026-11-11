import { createBrowserAuthorization, exchangeBrowserLoginCode } from '../api/feishu'

export async function getBrowserAuthorizationUrl(signal) {
  const { authorizationUrl } = await createBrowserAuthorization(signal)
  let url
  try {
    url = new URL(authorizationUrl)
  } catch {
    throw new Error('飞书授权地址无效，请联系管理员。')
  }
  if (
    url.origin !== 'https://accounts.feishu.cn' ||
    url.pathname !== '/open-apis/authen/v1/authorize'
  ) {
    throw new Error('飞书授权地址无效，请联系管理员。')
  }
  return url
}

export async function startBrowserLogin() {
  const url = await getBrowserAuthorizationUrl()
  window.location.assign(url.href)
}

export async function completeBrowserLogin() {
  const url = new URL(window.location.href)
  const keys = ['code', 'state', 'error', 'error_description', 'error_uri']
  if (!keys.some((key) => url.searchParams.has(key))) {
    return null
  }
  const values = Object.fromEntries(keys.map((key) => [key, url.searchParams.getAll(key)]))
  // 在任何网络请求前清除授权参数，避免刷新重放及 URL 泄露。
  keys.forEach((key) => url.searchParams.delete(key))
  window.history.replaceState(window.history.state, '', url.pathname + url.search + url.hash)
  if (values.error.length) {
    throw new Error(
      values.error[0] === 'access_denied'
        ? '你已取消飞书授权，可以点击下方按钮重新登录。'
        : '飞书网页授权失败，请重新登录。',
    )
  }
  if (
    values.code.length !== 1 ||
    !values.code[0].trim() ||
    values.state.length !== 1 ||
    !values.state[0].trim()
  ) {
    throw new Error('飞书授权回调不完整，请重新登录。')
  }
  return exchangeBrowserLoginCode(values.code[0], values.state[0])
}
