// @vitest-environment jsdom
import { beforeEach, expect, it, vi } from 'vitest'
import { createBrowserAuthorization, exchangeBrowserLoginCode } from '../../src/api/feishu'
import {
  completeBrowserLogin,
  getBrowserAuthorizationUrl,
  startBrowserLogin,
} from '../../src/utils/feishu-browser-auth'

vi.mock('../../src/api/feishu', () => ({
  createBrowserAuthorization: vi.fn(),
  exchangeBrowserLoginCode: vi.fn(),
}))

beforeEach(() => {
  vi.resetAllMocks()
  window.sessionStorage.clear()
  window.history.replaceState(null, '', '/')
})

it('扫码和网页授权共用的回调只还原本标签页同一次授权的单据及节点', async () => {
  const path = '/reviews/9007199254740993?round=2&node=PRODUCT_REVIEW'
  window.history.replaceState(null, '', '/#' + path)
  createBrowserAuthorization.mockResolvedValue({
    authorizationUrl: 'https://accounts.feishu.cn/open-apis/authen/v1/authorize?state=bound',
  })
  await getBrowserAuthorizationUrl()
  const restore = vi.fn()
  window.history.replaceState(null, '', '/?code=old&state=other')
  await completeBrowserLogin(restore)
  expect(restore).not.toHaveBeenCalled()
  window.history.replaceState(null, '', '/?code=once&state=bound')
  await completeBrowserLogin(restore)
  expect(restore).toHaveBeenCalledExactlyOnceWith(path)
  window.history.replaceState(null, '', '/?code=replay&state=bound')
  await completeBrowserLogin(restore)
  expect(restore).toHaveBeenCalledTimes(1)
})

it('拒绝回跳到任意地址及带额外参数的单据，保留修改链接的原轮次', async () => {
  createBrowserAuthorization.mockResolvedValue({
    authorizationUrl: 'https://accounts.feishu.cn/open-apis/authen/v1/authorize?state=bound',
  })
  for (const path of [
    '//foreign.example',
    '/admin/users',
    '/reviews/7?round=1&next=/apply',
    '/apply?policyId=7&round=1',
  ]) {
    window.history.replaceState(null, '', '/#' + path)
    await getBrowserAuthorizationUrl()
    window.history.replaceState(null, '', '/?code=once&state=bound')
    const restore = vi.fn()
    await completeBrowserLogin(restore)
    if (path.startsWith('/apply?')) {
      expect(restore).toHaveBeenCalledExactlyOnceWith('/reviews/7?round=1&node=REVISION')
    } else {
      expect(restore).not.toHaveBeenCalled()
    }
  }
})

it('exchanges the callback once and removes credentials while retaining unrelated URL parts', async () => {
  window.history.replaceState(null, '', '/?from=link&code=once&state=bound#section')
  exchangeBrowserLoginCode.mockImplementation(async (code, state) => {
    expect(window.location.search).toBe('?from=link')
    expect(window.location.hash).toBe('#section')
    expect([code, state]).toEqual(['once', 'bound'])
    return { user: { id: '1' } }
  })
  await expect(completeBrowserLogin()).resolves.toEqual({ user: { id: '1' } })
  await expect(completeBrowserLogin()).resolves.toBeNull()
  expect(exchangeBrowserLoginCode).toHaveBeenCalledOnce()
})

it('handles denial without displaying provider details or starting another authorization', async () => {
  window.history.replaceState(
    null,
    '',
    '/?error=access_denied&state=bound&error_description=private',
  )
  await expect(completeBrowserLogin()).rejects.toThrow('取消飞书授权')
  expect(window.location.search).toBe('')
  expect(exchangeBrowserLoginCode).not.toHaveBeenCalled()
  expect(createBrowserAuthorization).not.toHaveBeenCalled()
})

it('rejects missing, empty and duplicate callback values without exchanging a code', async () => {
  for (const query of [
    '?code=once',
    '?code=&state=bound',
    '?code=a&code=b&state=bound',
    '?code=a&state=x&state=y',
  ]) {
    window.history.replaceState(null, '', '/' + query)
    await expect(completeBrowserLogin()).rejects.toThrow('回调不完整')
    expect(window.location.search).toBe('')
  }
  expect(exchangeBrowserLoginCode).not.toHaveBeenCalled()
})

it('does not replay a failed callback when retrying', async () => {
  window.history.replaceState(null, '', '/?code=once&state=bound')
  exchangeBrowserLoginCode.mockRejectedValue(new Error('登录请求已失效'))
  await expect(completeBrowserLogin()).rejects.toThrow('已失效')
  await expect(completeBrowserLogin()).resolves.toBeNull()
  expect(exchangeBrowserLoginCode).toHaveBeenCalledOnce()
})

it('navigates to the official authorization page and rejects foreign destinations', async () => {
  const assign = vi.fn()
  vi.stubGlobal('window', { location: { assign } })
  try {
    createBrowserAuthorization.mockResolvedValue({
      authorizationUrl: 'https://accounts.feishu.cn/open-apis/authen/v1/authorize?state=bound',
    })
    await startBrowserLogin()
    expect(assign).toHaveBeenCalledWith(
      'https://accounts.feishu.cn/open-apis/authen/v1/authorize?state=bound',
    )
    createBrowserAuthorization.mockResolvedValue({ authorizationUrl: 'https://attacker.example/' })
    await expect(startBrowserLogin()).rejects.toThrow('授权地址无效')
    expect(assign).toHaveBeenCalledOnce()
  } finally {
    vi.unstubAllGlobals()
  }
})
