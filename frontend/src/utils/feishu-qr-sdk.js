const SDK_URL =
  'https://sf3-cn.feishucdn.com/obj/feishu-static/lark/passport/qrcode/LarkSSOSDKWebQRCode-1.0.2.js'
let sdkPromise

export function loadFeishuQrSdk() {
  if (typeof window.QRLogin === 'function') {
    return Promise.resolve(window.QRLogin)
  }
  if (!sdkPromise) {
    sdkPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script')
      const timeout = setTimeout(fail, 15000)
      function fail() {
        clearTimeout(timeout)
        script.remove()
        reject(new Error('二维码组件加载失败，请刷新二维码或使用网页授权。'))
      }
      script.src = SDK_URL
      script.async = true
      script.onerror = fail
      script.onload = () => {
        if (typeof window.QRLogin !== 'function') {
          fail()
          return
        }
        clearTimeout(timeout)
        resolve(window.QRLogin)
      }
      document.head.appendChild(script)
    }).catch((error) => {
      sdkPromise = undefined
      throw error
    })
  }
  return sdkPromise
}

export function mountFeishuQr(QRLogin, host, authorizationUrl, { onLoad, onScan }) {
  const url = new URL(authorizationUrl)
  // 内嵌扫码使用二维码 SDK 的 Passport 入口，沿用后端生成的回调地址与 state。
  url.host = 'passport.feishu.cn'
  url.pathname = '/suite/passport/oauth/authorize'
  const login = QRLogin({ id: host.id, goto: url.href, width: '264', height: '292' })
  const frame = host.querySelector('iframe')
  if (!frame || typeof login?.matchOrigin !== 'function') {
    throw new Error('飞书二维码初始化失败，请重试。')
  }
  frame.title = '飞书扫码登录二维码'
  frame.addEventListener('load', onLoad, { once: true })

  function handleMessage(event) {
    if (
      event.source !== frame.contentWindow ||
      event.origin !== url.origin ||
      !login.matchOrigin(event.origin) ||
      typeof event.data !== 'string' ||
      !event.data.trim()
    ) {
      return
    }
    // SDK 返回扫码临时票据，飞书授权页再将正式 code 和 state 回调给应用。
    url.searchParams.set('tmp_code', event.data)
    onScan(url.href)
  }

  window.addEventListener('message', handleMessage)
  return () => {
    window.removeEventListener('message', handleMessage)
    frame.removeEventListener('load', onLoad)
    frame.remove()
  }
}
