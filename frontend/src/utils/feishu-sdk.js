import { getJsSdkSignature } from '../api/feishu'
import { feishuFailure } from './feishu-error'

// Fixed version recommended by the Feishu H5 documentation.
export const FEISHU_SDK_URL = 'https://lf-scm-cn.feishucdn.com/lark/op/h5-js-sdk-1.5.48.js'
let scriptPromise
let initializationPromise

export function isFeishuClient(userAgent = navigator.userAgent) {
  return /Lark|Feishu/i.test(userAgent)
}

export function getSigningUrl(href = window.location.href) {
  // Do not decode, sort, or reconstruct the query: these exact bytes are signed.
  return href.split('#')[0]
}

export function loadFeishuSdk() {
  if (window.h5sdk) return Promise.resolve(window.h5sdk)
  if (scriptPromise) return scriptPromise
  scriptPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    const timer = setTimeout(() => fail(), 15000)
    function fail() {
      clearTimeout(timer)
      script.onload = null
      script.onerror = null
      script.remove()
      reject(new Error('飞书 JS-SDK 加载失败，请检查网络后重试。'))
    }
    script.src = FEISHU_SDK_URL
    script.async = true
    script.onerror = fail
    script.onload = () => {
      if (!window.h5sdk) return fail()
      clearTimeout(timer)
      resolve(window.h5sdk)
    }
    document.head.appendChild(script)
  }).catch((error) => {
    scriptPromise = undefined
    throw error
  })
  return scriptPromise
}

export function configureFeishuSdk(sdk, signature, timeoutMs = 15000) {
  return new Promise((resolve, reject) => {
    let settled = false
    let configured = false
    let bridgeReady = false
    const timer = setTimeout(() => fail('飞书初始化超时，请检查客户端和可信域名配置后重试。'), timeoutMs)
    function fail(message = '飞书 JS-SDK 鉴权失败，请检查应用凭证和可信域名配置。') {
      if (settled) return
      settled = true
      clearTimeout(timer)
      reject(new Error(message))
    }
    function finish() {
      // Version 1.5.48 may call ready even when config failed.
      if (settled || !configured || !bridgeReady) return
      settled = true
      clearTimeout(timer)
      resolve()
    }
    try {
      sdk.error((error) => fail(feishuFailure('飞书 JS-SDK 鉴权', error).message))
      sdk.config({
        appId: signature.appId,
        timestamp: signature.timestamp,
        nonceStr: signature.nonceStr,
        signature: signature.signature,
        jsApiList: signature.jsApiList,
        onSuccess() { configured = true; finish() },
        onFail(error) { fail(feishuFailure('飞书 JS-SDK 鉴权', error).message) },
      })
      sdk.ready(() => { bridgeReady = true; finish() })
    } catch {
      fail()
    }
  })
}

export function initializeFeishuSdk() {
  if (!isFeishuClient()) return Promise.reject(new Error('请在飞书客户端中打开此网页。'))
  if (initializationPromise) return initializationPromise
  // Always sign the actual document URL, including query and excluding fragment.
  const url = getSigningUrl()
  initializationPromise = (async () => {
    const sdk = await loadFeishuSdk()
    const signature = await getJsSdkSignature(url)
    await configureFeishuSdk(sdk, signature)
  })().finally(() => { initializationPromise = undefined })
  return initializationPromise
}
