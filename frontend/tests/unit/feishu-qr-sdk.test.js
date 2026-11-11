// @vitest-environment jsdom
import { expect, it, vi } from 'vitest'
import { mountFeishuQr } from '../../src/utils/feishu-qr-sdk'

it('only accepts a code from the mounted Feishu iframe and removes the listener on teardown', () => {
  const host = document.createElement('div')
  host.id = 'qr-test'
  document.body.appendChild(host)
  const frame = document.createElement('iframe')
  const onScan = vi.fn()
  const origin = 'https://passport.feishu.cn'
  const unmount = mountFeishuQr(
    () => {
      host.appendChild(frame)
      return { matchOrigin: (value) => value.startsWith(origin) }
    },
    host,
    'https://accounts.feishu.cn/open-apis/authen/v1/authorize?state=original',
    { onLoad: vi.fn(), onScan },
  )
  const source = frame.contentWindow
  const send = (overrides) => {
    window.dispatchEvent(new MessageEvent('message', { source, origin, data: 'one', ...overrides }))
  }
  try {
    send({ source: window })
    send({ origin: 'https://passport.feishu.cn.example.com' })
    send({ data: { code: 'one' } })
    expect(onScan).not.toHaveBeenCalled()
    send({ data: 'one&state=other' })
    expect(onScan).toHaveBeenCalledOnce()
    const url = new URL(onScan.mock.calls[0][0])
    expect(url.origin + url.pathname).toBe(`${origin}/suite/passport/oauth/authorize`)
    expect(url.searchParams.get('tmp_code')).toBe('one&state=other')
    expect(url.searchParams.get('state')).toBe('original')
    unmount()
    send({})
    expect(onScan).toHaveBeenCalledOnce()
  } finally {
    unmount()
    host.remove()
  }
})
