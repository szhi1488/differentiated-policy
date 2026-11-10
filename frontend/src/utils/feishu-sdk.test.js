import { afterEach, describe, expect, it, vi } from 'vitest'
import { configureFeishuSdk, getSigningUrl, isFeishuClient } from './feishu-sdk'

const signature = {
  appId: 'test-app',
  timestamp: 1700000000123,
  nonceStr: 'test-nonce',
  signature: 'test-signature',
  jsApiList: [],
}

function fakeSdk() {
  const callbacks = {}
  const sdk = {
    error: vi.fn((fn) => {
      callbacks.error = fn
    }),
    config: vi.fn((options) => {
      callbacks.config = options
    }),
    ready: vi.fn((fn) => {
      callbacks.ready = fn
    }),
  }
  return { sdk, callbacks }
}

afterEach(() => vi.useRealTimers())

describe('Feishu initialization contract', () => {
  it('keeps exact query bytes and removes only the fragment', () => {
    expect(getSigningUrl('http://localhost:5173/a?b=%2f&a=1+2&a=3#/apply?x=4')).toBe(
      'http://localhost:5173/a?b=%2f&a=1+2&a=3',
    )
  })

  it('distinguishes a Feishu client from an ordinary browser', () => {
    expect(isFeishuClient('Mozilla/5.0 Lark/7.0')).toBe(true)
    expect(isFeishuClient('Mozilla/5.0 Feishu/7.0')).toBe(true)
    expect(isFeishuClient('Mozilla/5.0 Chrome/120')).toBe(false)
  })

  it('requires both successful configuration and client readiness', async () => {
    const { sdk, callbacks } = fakeSdk()
    const done = vi.fn()
    const promise = configureFeishuSdk(sdk, signature).then(done)
    callbacks.config.onSuccess()
    await Promise.resolve()
    expect(done).not.toHaveBeenCalled()
    callbacks.ready()
    await promise
    expect(done).toHaveBeenCalledOnce()
    expect(sdk.config.mock.calls[0][0]).toMatchObject(signature)
    expect(sdk.error.mock.invocationCallOrder[0]).toBeLessThan(
      sdk.config.mock.invocationCallOrder[0],
    )
    expect(sdk.config.mock.invocationCallOrder[0]).toBeLessThan(
      sdk.ready.mock.invocationCallOrder[0],
    )
  })

  it('does not let ready mask authentication failure', async () => {
    const { sdk, callbacks } = fakeSdk()
    const promise = configureFeishuSdk(sdk, signature)
    callbacks.config.onFail({ secret: 'never-expose-sdk-payload' })
    callbacks.ready()
    callbacks.config.onSuccess()
    await expect(promise).rejects.toThrow('鉴权失败')
  })

  it('does not let an early ready mask a later failure', async () => {
    const { sdk, callbacks } = fakeSdk()
    const promise = configureFeishuSdk(sdk, signature)
    callbacks.ready()
    callbacks.error({ message: 'sensitive-upstream-data' })
    await expect(promise).rejects.toThrow('鉴权失败')
  })

  it('fails on timeout and ignores callbacks that arrive afterward', async () => {
    vi.useFakeTimers()
    const { sdk, callbacks } = fakeSdk()
    const promise = configureFeishuSdk(sdk, signature, 100)
    const assertion = expect(promise).rejects.toThrow('初始化超时')
    await vi.advanceTimersByTimeAsync(101)
    callbacks.config.onSuccess()
    callbacks.ready()
    await assertion
  })
})
