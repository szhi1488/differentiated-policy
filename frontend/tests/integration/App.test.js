// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import App from '../../src/App.vue'
import { createBrowserAuthorization, getFeishuConfig, getLoginSession } from '../../src/api/feishu'
import { completeBrowserLogin, startBrowserLogin } from '../../src/utils/feishu-browser-auth'
import { loginWithFeishu } from '../../src/utils/feishu-auth'
import { isFeishuClient } from '../../src/utils/feishu-sdk'
import { createMemoryHistory } from 'vue-router'
import { createWorkspaceRouter } from '../../src/router'
import { authSession } from '../../src/composables/useAuthSession'

vi.mock('../../src/api/feishu', () => ({
  createBrowserAuthorization: vi.fn(),
  getFeishuConfig: vi.fn(),
  getLoginSession: vi.fn(),
}))
vi.mock('../../src/utils/feishu-sdk', () => ({ isFeishuClient: vi.fn() }))
vi.mock('../../src/utils/feishu-auth', () => ({ loginWithFeishu: vi.fn() }))
// 本组验证会话与路由的联动，工作台和路由页内容各自在组件测试中覆盖。
vi.mock('../../src/views/apply/PolicyApplicationPage.vue', () => ({ default: {} }))
vi.mock('../../src/views/admin/catalog/CatalogSourcesPage.vue', () => ({ default: {} }))
vi.mock('../../src/utils/feishu-browser-auth', async (importOriginal) => ({
  ...(await importOriginal()),
  completeBrowserLogin: vi.fn(),
  startBrowserLogin: vi.fn(),
}))

beforeEach(() => {
  vi.resetAllMocks()
  authSession.invalidate()
  getFeishuConfig.mockResolvedValue({ appId: 'app', sdkEnabled: true })
  getLoginSession.mockResolvedValue(null)
  completeBrowserLogin.mockResolvedValue(null)
})

function render(router = createWorkspaceRouter(createMemoryHistory())) {
  return mount(App, {
    global: {
      plugins: [router],
      stubs: {
        ApplicantLayout: { template: '<div>申请人工作台<input /></div>' },
        AdminLayout: { template: '<div>管理员工作台</div>' },
      },
    },
  })
}

it.each(['端内免登', '网页回调'])(
  '%s 后恢复原轮和节点，会话过期后重新按当前账号读取',
  async (method) => {
    const router = createWorkspaceRouter(createMemoryHistory())
    const target = '/reviews/57?round=1'
    const firstUser = { user: { id: '21', roleType: 'APPLICANT' } }
    if (method === '端内免登') {
      await router.push(target)
      isFeishuClient.mockReturnValue(true)
      loginWithFeishu.mockResolvedValue(firstUser)
    } else {
      await router.push('/')
      completeBrowserLogin.mockImplementationOnce(async (restore) => {
        await restore(target)
        return firstUser
      })
    }
    const wrapper = render(router)
    try {
      await vi.waitFor(() => expect(wrapper.text()).toContain('申请人工作台'))
      expect(router.currentRoute.value.fullPath).toBe(target)
      authSession.invalidate()
      await flushPromises()
      expect(wrapper.text()).not.toContain('申请人工作台')
      getLoginSession.mockResolvedValue({ user: { id: '22', roleType: 'ADMIN' } })
      await authSession.connect()
      await vi.waitFor(() => expect(wrapper.text()).toContain('管理员工作台'))
      expect(router.currentRoute.value.fullPath).toBe(target)
    } finally {
      wrapper.unmount()
    }
  },
)

it('offers browser authorization and starts it on click without using the SDK', async () => {
  const wrapper = render()
  await flushPromises()
  expect(wrapper.text()).toContain('飞书网页授权登录')
  expect(wrapper.text()).toContain('显示飞书二维码')
  expect(startBrowserLogin).not.toHaveBeenCalled()
  expect(createBrowserAuthorization).not.toHaveBeenCalled()
  await wrapper.findAll('button')[1].trigger('click')
  await flushPromises()
  expect(startBrowserLogin).toHaveBeenCalledOnce()
  expect(loginWithFeishu).not.toHaveBeenCalled()
  wrapper.unmount()
})

it('creates the QR code on demand, refreshes it on expiry and releases it for web authorization', async () => {
  const QRLogin = vi.fn(({ id }) => {
    document.getElementById(id).appendChild(document.createElement('iframe'))
    return { matchOrigin: () => true }
  })
  vi.stubGlobal('QRLogin', QRLogin)
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
  createBrowserAuthorization.mockResolvedValue({
    authorizationUrl: 'https://accounts.feishu.cn/open-apis/authen/v1/authorize?state=once',
  })
  const wrapper = render()
  document.body.appendChild(wrapper.element)
  try {
    await flushPromises()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(QRLogin).toHaveBeenCalledOnce()
    await wrapper.get('iframe').trigger('load')
    expect(wrapper.text()).toContain('扫一扫')
    await vi.advanceTimersByTimeAsync(180_000)
    expect(wrapper.text()).toContain('二维码已过期')
    expect(wrapper.find('iframe').exists()).toBe(false)

    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(createBrowserAuthorization).toHaveBeenCalledTimes(2)
    await wrapper.findAll('button')[1].trigger('click')
    await flushPromises()
    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(startBrowserLogin).toHaveBeenCalledOnce()
    expect(loginWithFeishu).not.toHaveBeenCalled()
  } finally {
    wrapper.unmount()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  }
})

it('lets the user retry when the QR SDK fails to load', async () => {
  const wrapper = render()
  try {
    await flushPromises()
    await wrapper.get('button').trigger('click')
    document.querySelector('script[src*="LarkSSOSDKWebQRCode"]').dispatchEvent(new Event('error'))
    await flushPromises()
    expect(wrapper.text()).toContain('二维码组件加载失败')
    expect(wrapper.text()).toContain('刷新二维码')
    expect(wrapper.findAll('button')[1].attributes('disabled')).toBeUndefined()
    expect(createBrowserAuthorization).not.toHaveBeenCalled()
  } finally {
    wrapper.unmount()
  }
})

it('服务端 ADMIN 进入管理员工作台，其他角色保持申请人入口', async () => {
  getLoginSession.mockResolvedValue({ user: { id: 'admin-demo', roleType: 'ADMIN' } })
  const wrapper = render()
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.text()).toContain('管理员工作台'))
  expect(wrapper.text()).not.toContain('申请人工作台')
  await vi.waitFor(() =>
    expect(wrapper.vm.$router.currentRoute.value.path).toBe('/admin/catalog/sources'),
  )
  wrapper.unmount()
})

it.each(['callback', 'session'])('opens the workspace after browser %s login', async (source) => {
  if (source === 'callback') {
    completeBrowserLogin.mockResolvedValue({ user: { id: '1' } })
  } else {
    getLoginSession.mockResolvedValue({ user: { id: '1' } })
  }
  const wrapper = render()
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.text()).toContain('申请人工作台'))
  expect(startBrowserLogin).not.toHaveBeenCalled()
  expect(loginWithFeishu).not.toHaveBeenCalled()
  wrapper.unmount()
})

it('从页面缓存或后台返回时按最新角色切换工作台与地址', async () => {
  const applicant = { user: { id: '2', roleType: 'APPLICANT' } }
  const admin = { user: { id: '2', roleType: 'ADMIN' } }
  getLoginSession.mockResolvedValue(applicant)
  const wrapper = render()
  try {
    const router = wrapper.vm.$router
    await vi.waitFor(() => expect(wrapper.text()).toContain('申请人工作台'))
    await router.push('/apply?policyId=123')
    getLoginSession.mockResolvedValue(admin)
    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
    await vi.waitFor(() => expect(wrapper.text()).toContain('管理员工作台'))
    expect(router.currentRoute.value.fullPath).toBe('/admin/catalog/sources')

    await router.push('/admin/users')
    getLoginSession.mockResolvedValue(applicant)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.waitFor(() => expect(wrapper.text()).toContain('申请人工作台'))
    expect(router.currentRoute.value.fullPath).toBe('/apply')
    expect(loginWithFeishu).not.toHaveBeenCalled()
    expect(startBrowserLogin).not.toHaveBeenCalled()
  } finally {
    wrapper.unmount()
  }
})

it('返回时合并重复读取，同一角色保留输入，停用后关闭工作台', async () => {
  const applicant = { user: { id: '2', roleType: 'APPLICANT' } }
  getLoginSession.mockResolvedValue(applicant)
  const wrapper = render()
  try {
    await vi.waitFor(() => expect(wrapper.text()).toContain('申请人工作台'))
    await wrapper.get('input').setValue('未保存的输入')
    let finish
    getLoginSession.mockImplementationOnce(() => new Promise((done) => (finish = done)))
    document.dispatchEvent(new Event('visibilitychange'))
    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
    await flushPromises()
    expect(getLoginSession).toHaveBeenCalledTimes(2)
    expect(wrapper.get('input').element.value).toBe('未保存的输入')
    finish(applicant)
    await flushPromises()
    expect(wrapper.get('input').element.value).toBe('未保存的输入')

    getLoginSession.mockRejectedValueOnce(new Error('暂时无法核对账号角色'))
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(wrapper.get('input').element.value).toBe('未保存的输入')

    getLoginSession.mockRejectedValueOnce(
      Object.assign(new Error('当前账号已停用'), { status: 403 }),
    )
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(wrapper.text()).toContain('当前账号已停用')
    expect(wrapper.text()).not.toContain('申请人工作台')
  } finally {
    wrapper.unmount()
  }
})

it('retains client login and allows explicit retry after a callback failure', async () => {
  isFeishuClient.mockReturnValue(true)
  completeBrowserLogin.mockRejectedValueOnce(new Error('登录请求已失效'))
  loginWithFeishu.mockResolvedValue({ user: { id: '1' } })
  const wrapper = render()
  await flushPromises()
  expect(wrapper.text()).toContain('登录请求已失效')
  expect(wrapper.text()).toContain('重新验证飞书身份')
  expect(wrapper.text()).not.toContain('显示飞书二维码')
  expect(loginWithFeishu).not.toHaveBeenCalled()
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(loginWithFeishu).toHaveBeenCalledWith('app')
  await vi.waitFor(() => expect(wrapper.text()).toContain('申请人工作台'))
  wrapper.unmount()
})
