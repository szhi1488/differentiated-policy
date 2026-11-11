// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import App from './App.vue'
import { getFeishuConfig, getLoginSession } from './api/feishu'
import { completeBrowserLogin, startBrowserLogin } from './utils/feishu-browser-auth'
import { loginWithFeishu } from './utils/feishu-auth'
import { isFeishuClient } from './utils/feishu-sdk'
import { createMemoryHistory } from 'vue-router'
import { createWorkspaceRouter } from './router'

vi.mock('./api/feishu', () => ({ getFeishuConfig: vi.fn(), getLoginSession: vi.fn() }))
vi.mock('./utils/feishu-sdk', () => ({ isFeishuClient: vi.fn() }))
vi.mock('./utils/feishu-auth', () => ({ loginWithFeishu: vi.fn() }))
vi.mock('./utils/feishu-browser-auth', () => ({
  completeBrowserLogin: vi.fn(),
  startBrowserLogin: vi.fn(),
}))

beforeEach(() => {
  vi.resetAllMocks()
  getFeishuConfig.mockResolvedValue({ appId: 'app', sdkEnabled: true })
  getLoginSession.mockResolvedValue(null)
  completeBrowserLogin.mockResolvedValue(null)
})

function render() {
  return mount(App, {
    global: {
      plugins: [createWorkspaceRouter(createMemoryHistory())],
      stubs: {
        ApplicantWorkspace: { template: '<div>申请人工作台</div>' },
        AdminWorkspace: { template: '<div>管理员工作台</div>' },
      },
    },
  })
}

it('offers browser authorization and starts it on click without using the SDK', async () => {
  const wrapper = render()
  await flushPromises()
  expect(wrapper.text()).toContain('飞书网页授权登录')
  expect(startBrowserLogin).not.toHaveBeenCalled()
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(startBrowserLogin).toHaveBeenCalledOnce()
  expect(loginWithFeishu).not.toHaveBeenCalled()
  wrapper.unmount()
})

it('服务端 ADMIN 进入管理员工作台，其他角色保持申请人入口', async () => {
  getLoginSession.mockResolvedValue({ user: { id: 'admin-demo', roleType: 'ADMIN' } })
  const wrapper = render()
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.text()).toContain('管理员工作台'))
  expect(wrapper.text()).not.toContain('申请人工作台')
  expect(wrapper.vm.$router.currentRoute.value.path).toBe('/admin/policies')
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
  expect(loginWithFeishu).not.toHaveBeenCalled()
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(loginWithFeishu).toHaveBeenCalledWith('app')
  expect(wrapper.text()).toContain('申请人工作台')
  wrapper.unmount()
})
