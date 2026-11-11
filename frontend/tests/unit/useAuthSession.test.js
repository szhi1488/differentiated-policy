import { beforeEach, expect, it, vi } from 'vitest'
import { createAuthSession } from '../../src/composables/useAuthSession'
import { getLoginSession, logout } from '../../src/api/feishu'
vi.mock('../../src/api/feishu', () => ({
  getLoginSession: vi.fn(),
  getFeishuConfig: vi.fn(),
  logout: vi.fn(),
}))
vi.mock('../../src/utils/feishu-browser-auth', () => ({
  completeBrowserLogin: vi.fn(async () => null),
  startBrowserLogin: vi.fn(),
}))
beforeEach(() => vi.clearAllMocks())
it('单一入口共享登录请求，退出时立即隔离并忽略迟到的登录恢复', async () => {
  let resolve
  getLoginSession.mockReturnValue(
    new Promise((done) => {
      resolve = done
    }),
  )
  logout.mockResolvedValue(null)
  const state = createAuthSession()
  const first = state.connect(),
    second = state.connect()
  await Promise.resolve()
  expect(getLoginSession).toHaveBeenCalledOnce()
  await state.logout()
  resolve({ user: { id: '旧账号' } })
  await Promise.all([first, second])
  expect(state.user.value).toBeNull()
})
it('账号、租户、角色变化递增隔离版本，姓名更新不清理表单', () => {
  const state = createAuthSession()
  state.setLogin({ user: { id: '1', tenantKey: 'a', roleType: 'APPLICANT' } })
  const version = state.revision.value
  state.setLogin({ user: { id: '1', tenantKey: 'a', roleType: 'APPLICANT', name: '新姓名' } })
  expect(state.revision.value).toBe(version)
  state.setLogin({ user: { id: '1', tenantKey: 'b', roleType: 'APPLICANT' } })
  expect(state.revision.value).toBe(version + 1)
  state.invalidate()
  expect(state.user.value).toBeNull()
  expect(state.revision.value).toBe(version + 2)
})
