import { nextTick, onBeforeUnmount, ref } from 'vue'
import { getBrowserAuthorizationUrl } from '../utils/feishu-browser-auth'
import { loadFeishuQrSdk, mountFeishuQr } from '../utils/feishu-qr-sdk'

// 与服务端一次性 state 的有效期一致。飞书二维码自身也可能先到期。
const AUTHORIZATION_TTL_MS = 180_000
const FRAME_TIMEOUT_MS = 15_000

export function useFeishuQrLogin() {
  const host = ref(null)
  const status = ref('idle')
  const message = ref('')
  const requesting = ref(false)
  let attempt

  function clearAttempt() {
    attempt?.abort()
    attempt = undefined
  }

  async function show() {
    if (status.value === 'loading' || status.value === 'redirecting') {
      return
    }
    clearAttempt()
    const controller = new AbortController()
    const { signal } = controller
    attempt = controller
    status.value = 'loading'
    message.value = '正在加载飞书二维码…'

    function fail(detail, nextStatus = 'error') {
      clearAttempt()
      status.value = nextStatus
      message.value = detail
    }

    try {
      const QRLogin = await loadFeishuQrSdk()
      if (signal.aborted) {
        return
      }
      requesting.value = true
      const deadline = Date.now() + AUTHORIZATION_TTL_MS
      const authorizationUrl = await getBrowserAuthorizationUrl(signal)
      await nextTick()
      if (signal.aborted) {
        return
      }
      const expire = () => fail('二维码已过期，请刷新后重新扫码。', 'expired')
      const frameTimeout = setTimeout(
        () => fail('二维码加载超时，请刷新二维码或使用网页授权。'),
        FRAME_TIMEOUT_MS,
      )
      const expires = setTimeout(expire, Math.max(0, deadline - Date.now()))
      signal.addEventListener(
        'abort',
        () => {
          clearTimeout(frameTimeout)
          clearTimeout(expires)
          host.value?.replaceChildren()
        },
        { once: true },
      )

      const unmount = mountFeishuQr(QRLogin, host.value, authorizationUrl, {
        onLoad() {
          clearTimeout(frameTimeout)
          status.value = 'waiting'
          message.value = '打开手机飞书「扫一扫」，扫码后在手机上确认。'
        },
        onScan(url) {
          if (Date.now() >= deadline) {
            expire()
            return
          }
          clearAttempt()
          status.value = 'redirecting'
          message.value = '正在完成飞书授权…'
          window.location.assign(url)
        },
      })
      signal.addEventListener('abort', unmount, { once: true })
    } catch (error) {
      if (!signal.aborted) {
        fail(error.message || '二维码加载失败，请重试。')
      }
    } finally {
      if (!signal.aborted || !attempt) {
        requesting.value = false
      }
    }
  }

  function reset() {
    clearAttempt()
    status.value = 'idle'
    message.value = ''
    requesting.value = false
  }

  onBeforeUnmount(clearAttempt)
  return { host, status, message, requesting, show, reset }
}
