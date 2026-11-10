import { afterEach, describe, expect, it, vi } from 'vitest'
import { request } from './request'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('统一请求封装', () => {
  it('附带同源会话及写请求标识，并处理 204 响应', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, status: 204 })
    vi.stubGlobal('fetch', fetch)
    await expect(request('/api/auth/logout', { method: 'POST' })).resolves.toBeNull()
    expect(fetch).toHaveBeenCalledWith(
      '/api/auth/logout',
      expect.objectContaining({
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { 'X-Policy-Request': '1' },
      }),
    )
  })

  it('保留业务错误码，空错误响应使用统一提示', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        json: async () => ({ code: 'LOGIN_REQUIRED', message: '请重新登录。' }),
      }),
    )
    await expect(request('/api/auth/session')).rejects.toMatchObject({
      status: 401,
      code: 'LOGIN_REQUIRED',
      message: '请重新登录。',
    })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: false, status: 503, json: async () => null }),
    )
    await expect(request('/api/auth/session')).rejects.toMatchObject({
      status: 503,
      message: '应用请求失败，请稍后重试。',
    })
  })

  it('超时中止请求，显示超时提示并清理定时器', async () => {
    vi.useFakeTimers()
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_path, { signal }) =>
          new Promise((_resolve, reject) => {
            signal.addEventListener('abort', () =>
              reject(new DOMException('aborted', 'AbortError')),
            )
          }),
      ),
    )
    const assertion = expect(request('/api/auth/session')).rejects.toThrow('应用请求超时')
    await vi.advanceTimersByTimeAsync(25_000)
    await assertion
    expect(vi.getTimerCount()).toBe(0)
  })
})
