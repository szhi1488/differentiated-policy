import { createLoginChallenge, exchangeLoginCode, getLoginSession } from '../api/feishu'
import { initializeFeishuSdk } from './feishu-sdk'
import { feishuFailure, getFeishuErrorCode } from './feishu-error'

let loginPromise

export function requestFeishuAccess(appId, state, bridge = window.tt, timeoutMs = 60000) {
  return new Promise((resolve, reject) => {
    let settled = false
    let legacyRequested = false
    const timer = setTimeout(() => finish(new Error('飞书授权等待超时，请重新登录。')), timeoutMs)
    function finish(error, code) {
      if (settled) {
        return
      }
      settled = true
      clearTimeout(timer)
      if (error) {
        reject(error)
      } else {
        resolve(code)
      }
    }
    function success(result) {
      if (
        typeof result?.code !== 'string' ||
        !result.code.trim() ||
        (result.state !== undefined && result.state !== state)
      ) {
        finish(new Error('未取得有效的飞书授权码，请重新登录。'))
      } else {
        finish(null, result.code)
      }
    }
    function requestLegacyCode() {
      if (settled || legacyRequested) {
        return
      }
      legacyRequested = true
      if (typeof bridge?.requestAuthCode !== 'function') {
        finish(new Error('当前飞书客户端不支持授权登录，请升级至 6.9 或更高版本。'))
        return
      }
      try {
        bridge.requestAuthCode({
          appId,
          success,
          fail: (error) => finish(feishuFailure('飞书免登授权', error)),
        })
      } catch {
        finish(new Error('无法发起飞书授权，请重新打开应用后重试。'))
      }
    }
    try {
      if (typeof bridge?.requestAccess !== 'function') {
        requestLegacyCode()
        return
      }
      bridge.requestAccess({
        appID: appId,
        scopeList: [],
        state,
        success,
        fail(error) {
          if (settled) {
            return
          }
          // 仅在客户端不支持时使用官方兼容方案。
          // 用户拒绝授权或配置错误时，不重试或降级调用。
          if (getFeishuErrorCode(error) === '103') {
            requestLegacyCode()
          } else {
            finish(feishuFailure('飞书免登授权', error))
          }
        },
      })
    } catch {
      finish(new Error('无法发起飞书授权，请重新打开应用后重试。'))
    }
  })
}

export function loginWithFeishu(appId) {
  if (loginPromise) {
    return loginPromise
  }
  loginPromise = (async () => {
    const existing = await getLoginSession()
    if (existing) {
      return existing
    }
    await initializeFeishuSdk()
    const { state } = await createLoginChallenge()
    const code = await requestFeishuAccess(appId, state)
    // 授权码只能使用一次；交换失败后必须重新执行整个登录流程。
    return exchangeLoginCode(code, state)
  })().finally(() => {
    loginPromise = undefined
  })
  return loginPromise
}
