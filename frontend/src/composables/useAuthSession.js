import { computed, readonly, ref } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { getFeishuConfig, getLoginSession, logout as logoutApi } from '../api/feishu'
import { setUnauthorizedHandler } from '../api/request'
import { completeBrowserLogin, startBrowserLogin } from '../utils/feishu-browser-auth'
import { loginWithFeishu } from '../utils/feishu-auth'
import { isFeishuClient } from '../utils/feishu-sdk'

/** 每个应用实例只有一个入口；测试可创建隔离实例，不在导入时请求。 */
export function createAuthSession() {
  const login = ref(null),
    status = ref('loading'),
    message = ref('正在通过飞书验证身份…')
  const revision = ref(0)
  let generation = 0,
    pending = null
  function setLogin(value) {
    if (
      login.value?.user?.id !== value?.user?.id ||
      login.value?.user?.tenantKey !== value?.user?.tenantKey ||
      JSON.stringify([...(login.value?.user?.permissions ?? [])].sort()) !==
        JSON.stringify([...(value?.user?.permissions ?? [])].sort()) ||
      JSON.stringify([...(login.value?.user?.roleCodes ?? [])].sort()) !==
        JSON.stringify([...(value?.user?.roleCodes ?? [])].sort())
    ) {
      revision.value++
    }
    login.value = value
    status.value = value ? 'ready' : 'browser'
  }
  function invalidate() {
    generation++
    pending = null
    setLogin(null)
    message.value = '会话已失效，请重新登录。'
  }
  async function connect(startAuthorization = false, restoreTarget) {
    if (pending) {
      return pending
    }
    const current = ++generation
    if (!login.value) {
      status.value = 'loading'
      message.value = '正在通过飞书验证身份…'
    }
    const task = (async () => {
      try {
        const restored =
          (!login.value && (await completeBrowserLogin(restoreTarget))) || (await getLoginSession())
        if (current !== generation) {
          return
        }
        if (restored) {
          setLogin(restored)
          return
        }
        // 会话探测已确认过期，立即卸载旧工作台，再进入原有授权流程。
        setLogin(null)
        status.value = 'loading'
        const config = await getFeishuConfig()
        if (current !== generation) {
          return
        }
        if (!config.sdkEnabled) {
          throw new Error('后端飞书应用凭证尚未配置，请完成本地配置后重启服务。')
        }
        if (!isFeishuClient()) {
          if (startAuthorization) {
            status.value = 'redirecting'
            message.value = '正在前往飞书授权页面…'
            await startBrowserLogin()
          } else {
            status.value = 'browser'
            message.value = ''
          }
          return
        }
        const result = await loginWithFeishu(config.appId)
        if (current === generation) {
          setLogin(result)
        }
      } catch (error) {
        if (current !== generation) {
          return
        }
        if (login.value && error.status !== 403 && error.status !== 401) {
          // 返回前台遇到临时故障时保留正在编辑的表单；业务请求仍由服务端逐次授权。
          ElMessage.warning(error.message || '暂时无法核对账号角色，请稍后重试。')
          return
        }
        setLogin(null)
        status.value = 'error'
        message.value = error.message || '登录失败，请重试。'
      } finally {
        if (current === generation) {
          pending = null
        }
      }
    })()
    pending = task
    return task
  }
  async function logout() {
    // 先使正在进行的登录及旧页面状态失效，避免退出响应前的迟到登录恢复身份。
    invalidate()
    try {
      await logoutApi()
    } finally {
      invalidate()
    }
  }
  return {
    login: readonly(login),
    user: computed(() => login.value?.user ?? null),
    status: readonly(status),
    message: readonly(message),
    revision: readonly(revision),
    setLogin,
    connect,
    invalidate,
    logout,
  }
}
export const authSession = createAuthSession()
setUnauthorizedHandler(() => authSession.invalidate())
