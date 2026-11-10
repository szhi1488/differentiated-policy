import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createLoginChallenge, exchangeLoginCode, getLoginSession } from '../api/feishu'
import { initializeFeishuSdk } from './feishu-sdk'
import { loginWithFeishu, requestFeishuAccess } from './feishu-auth'

vi.mock('../api/feishu', () => ({
  createLoginChallenge: vi.fn(),
  exchangeLoginCode: vi.fn(),
  getLoginSession: vi.fn(),
}))
vi.mock('./feishu-sdk', () => ({ initializeFeishuSdk: vi.fn() }))
beforeEach(() => vi.resetAllMocks())
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('Feishu user authorization', () => {
  it('passes appID, state and only the minimal identity scope', async () => {
    const requestAccess = vi.fn((options) =>
      options.success({ code: 'once', state: options.state }),
    )
    await expect(requestFeishuAccess('test-app', 'state', { requestAccess })).resolves.toBe('once')
    expect(requestAccess.mock.calls[0][0]).toMatchObject({
      appID: 'test-app',
      scopeList: [],
      state: 'state',
    })
  })

  it('rejects cancelled authorization and hides SDK payloads', async () => {
    const requestAuthCode = vi.fn()
    await expect(
      requestFeishuAccess('app', 'state', {
        requestAuthCode,
        requestAccess: (options) => options.fail({ message: 'private SDK response' }),
      }),
    ).rejects.toThrow('免登授权失败')
    expect(requestAuthCode).not.toHaveBeenCalled()
  })

  it('uses the official legacy API only when requestAccess is unavailable or unsupported', async () => {
    for (const requestAccess of [undefined, (options) => options.fail({ errno: 103 })]) {
      const requestAuthCode = vi.fn((options) => options.success({ code: 'legacy-once' }))
      await expect(
        requestFeishuAccess('test-app', 'state', { requestAccess, requestAuthCode }),
      ).resolves.toBe('legacy-once')
      expect(requestAuthCode).toHaveBeenCalledOnce()
      expect(requestAuthCode.mock.calls[0][0]).toMatchObject({ appId: 'test-app' })
    }
  })

  it('retains authorization error codes without downgrading on configuration failures', async () => {
    const requestAuthCode = vi.fn()
    await expect(
      requestFeishuAccess('app', 'state', {
        requestAccess: (options) =>
          options.fail({ errno: 2700002, errString: 'code: 2000; msg: private-details' }),
        requestAuthCode,
      }),
    ).rejects.toThrow('飞书错误码 2700002，内层错误码 2000')
    expect(requestAuthCode).not.toHaveBeenCalled()
  })

  it('rejects empty codes, mismatched state and unsupported clients', async () => {
    for (const result of [{ code: '' }, { code: 'once', state: 'foreign' }]) {
      await expect(
        requestFeishuAccess('app', 'state', {
          requestAccess: (options) => options.success(result),
        }),
      ).rejects.toThrow('有效的飞书授权码')
    }
    await expect(requestFeishuAccess('app', 'state', {})).rejects.toThrow('升级')
  })

  it('times out and ignores late callbacks', async () => {
    vi.useFakeTimers()
    let callbacks
    const promise = requestFeishuAccess(
      'app',
      'state',
      {
        requestAccess: (options) => {
          callbacks = options
        },
      },
      100,
    )
    const assertion = expect(promise).rejects.toThrow('超时')
    await vi.advanceTimersByTimeAsync(101)
    callbacks.success({ code: 'too-late' })
    await assertion
  })

  it('restores an existing server session without requesting a new code', async () => {
    const session = { user: { openId: 'ou_verified' } }
    getLoginSession.mockResolvedValue(session)
    await expect(loginWithFeishu('app')).resolves.toEqual(session)
    expect(initializeFeishuSdk).not.toHaveBeenCalled()
    expect(createLoginChallenge).not.toHaveBeenCalled()
  })

  it('deduplicates login and obtains a fresh code after a failed exchange', async () => {
    getLoginSession.mockResolvedValue(null)
    createLoginChallenge.mockResolvedValue({ state: 'state' })
    let codeNumber = 0
    vi.stubGlobal('window', {
      tt: { requestAccess: (options) => options.success({ code: `once-${++codeNumber}` }) },
    })
    exchangeLoginCode
      .mockRejectedValueOnce(new Error('expired'))
      .mockResolvedValueOnce({ user: { openId: 'verified' } })
    const first = loginWithFeishu('app')
    expect(loginWithFeishu('app')).toBe(first)
    await expect(first).rejects.toThrow('expired')
    await expect(loginWithFeishu('app')).resolves.toEqual({ user: { openId: 'verified' } })
    expect(exchangeLoginCode.mock.calls).toEqual([
      ['once-1', 'state'],
      ['once-2', 'state'],
    ])
    expect(createLoginChallenge).toHaveBeenCalledTimes(2)
  })
})
