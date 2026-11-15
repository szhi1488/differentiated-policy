import { afterEach, describe, expect, it, vi } from 'vitest'
import { request, setUnauthorizedHandler } from '../../src/api/request'

afterEach(() => {
  setUnauthorizedHandler(() => {})
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('统一请求封装', () => {
  it('文件读取携带当前会话并返回字节，错误响应继续解析实际状态和错误信息', async () => {
    const content = new Blob(['%PDF-1.7 测试文件'], { type: 'application/octet-stream' })
    const fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      blob: async () => content,
      json: vi.fn(),
    })
    vi.stubGlobal('fetch', fetch)
    const result = await request('/api/admin/policies/5/audit-logs/2/attachments/file/content', {
      responseType: 'blob',
    })
    expect(result).toBe(content)
    expect(fetch).toHaveBeenCalledWith(
      '/api/admin/policies/5/audit-logs/2/attachments/file/content',
      expect.objectContaining({ credentials: 'same-origin', cache: 'no-store' }),
    )
    expect(fetch.mock.calls[0][1]).not.toHaveProperty('responseType')
    const expired = vi.fn()
    setUnauthorizedHandler(expired)
    fetch.mockResolvedValue({
      ok: false,
      status: 401,
      json: async () => ({ code: 'LOGIN_REQUIRED', message: '登录已过期，请重新登录。' }),
      blob: vi.fn(),
    })
    await expect(request('/api/file/content', { responseType: 'blob' })).rejects.toMatchObject({
      status: 401,
      code: 'LOGIN_REQUIRED',
      message: '登录已过期，请重新登录。',
    })
    expect(expired).toHaveBeenCalledOnce()
  })

  it.each([401, 403, 409, 503])(
    '非 JSON 或空错误响应仍保留 HTTP %i，不重试写操作',
    async (status) => {
      const expired = vi.fn()
      setUnauthorizedHandler(expired)
      const fetch = vi.fn(async () => ({
        ok: false,
        status,
        json: async () => {
          throw new SyntaxError('非 JSON')
        },
      }))
      vi.stubGlobal('fetch', fetch)
      await expect(request('/api/policies', { method: 'POST' })).rejects.toMatchObject({
        status,
        kind: 'business',
      })
      expect(fetch).toHaveBeenCalledOnce()
      expect(expired).toHaveBeenCalledTimes(status === 401 ? 1 : 0)
      if (status === 401) {
        expired.mockClear()
        await expect(request('/api/auth/session', { sessionProbe: true })).rejects.toMatchObject({
          status: 401,
        })
        expect(expired).not.toHaveBeenCalled()
      }
    },
  )
  it('区分成功响应解析失败与网络失败', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        status: 200,
        json: async () => {
          throw new SyntaxError('坏 JSON')
        },
      })),
    )
    await expect(request('/api/read')).rejects.toMatchObject({ kind: 'parse', status: 200 })
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
    await expect(request('/api/read')).rejects.toMatchObject({ kind: 'network' })
  })
  it('调用方取消覆盖响应体读取，结束清除监听器和定时器，预取消不发送', async () => {
    vi.useFakeTimers()
    const controller = new AbortController()
    const remove = vi.spyOn(controller.signal, 'removeEventListener')
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_path, { signal }) => ({
        ok: true,
        status: 200,
        json: () =>
          new Promise((_yes, no) =>
            signal.addEventListener('abort', () => no(new DOMException('cancel', 'AbortError'))),
          ),
      })),
    )
    const pending = expect(
      request('/api/read', { signal: controller.signal }),
    ).rejects.toMatchObject({ kind: 'cancelled', status: 200 })
    await Promise.resolve()
    controller.abort()
    await pending
    expect(remove).toHaveBeenCalledWith('abort', expect.any(Function))
    expect(vi.getTimerCount()).toBe(0)
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    await expect(request('/api/read', { signal: controller.signal })).rejects.toMatchObject({
      kind: 'cancelled',
    })
    expect(fetch).not.toHaveBeenCalled()
  })
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
