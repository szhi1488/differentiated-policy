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

function render() {
  return mount(App, {
    global: {
      plugins: [createWorkspaceRouter(createMemoryHistory())],
      stubs: {
        ApplicantLayout: { template: '<div>申请人工作台</div>' },
        AdminLayout: { template: '<div>管理员工作台</div>' },
      },
    },
  })
}

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
  expect(wrapper.text()).toContain('申请人工作台')
  expect(startBrowserLogin).not.toHaveBeenCalled()
  expect(loginWithFeishu).not.toHaveBeenCalled()
  wrapper.unmount()
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
  expect(wrapper.text()).toContain('申请人工作台')
  wrapper.unmount()
})
