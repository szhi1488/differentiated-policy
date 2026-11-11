import { afterEach, expect, it, vi } from 'vitest'
import { exchangeBrowserLoginCode, exchangeLoginCode, getLoginSession } from '../../src/api/feishu'
import { setUnauthorizedHandler } from '../../src/api/request'
afterEach(() => {
  vi.unstubAllGlobals()
  setUnauthorizedHandler(() => {})
})
it('未登录会话返回 null，登录交换失败保留错误给登录页，不打断当前认证流程', async () => {
  const expired = vi.fn()
  setUnauthorizedHandler(expired)
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: false,
      status: 401,
      json: async () => ({ code: 'AUTH_STATE_INVALID', message: '登录请求已失效' }),
    })),
  )
  await expect(getLoginSession()).resolves.toBeNull()
  await expect(exchangeLoginCode('code', 'state')).rejects.toMatchObject({
    status: 401,
    code: 'AUTH_STATE_INVALID',
  })
  await expect(exchangeBrowserLoginCode('code', 'state')).rejects.toMatchObject({
    status: 401,
    message: '登录请求已失效',
  })
  expect(expired).not.toHaveBeenCalled()
})
