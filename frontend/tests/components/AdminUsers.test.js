// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ElMessage, ElPagination, ElCheckbox, ElSelect } from 'element-plus'
import { createMemoryHistory } from 'vue-router'
import { createWorkspaceRouter } from '../../src/router'
import AdminLayout from '../../src/layouts/AdminLayout.vue'
import PlatformTeamsPage from '../../src/views/admin/users/PlatformTeamsPage.vue'
import AdminUsersPage from '../../src/views/admin/users/AdminUsersPage.vue'
import { authSession } from '../../src/composables/useAuthSession'

const base = '/api/admin/users'
const owner = {
  id: '1',
  name: '当前管理员',
  roleCodes: ['ADMIN'],
  permissions: ['users.manage', 'roles.manage'],
  authVersion: 3,
  status: 'ENABLED',
  createdAt: '2026-11-17 09:00:00',
}
const target = {
  ...owner,
  id: '9007199254740993',
  name: '采购同事',
  roleCodes: ['OPERATIONS'],
  department: null,
}
const targetPath = base + '/' + target.id
const profile = { ...target, openId: 'ou_purchase' }
const counts = { total: 20, enabledAdmins: 2, enabledUsers: 16, disabledUsers: 2 }
const failure = (status, message) => Response.json({ message }, { status })
const button = (root, label) => root.findAll('button').find((item) => item.text() === label)
const dialog = () => wrapper.get('.admin-user-action-dialog')
const drawer = () => wrapper.get('.admin-user-drawer')
const table = () => wrapper.get('.el-table__body')
let wrapper, list, stats, detail, role, status, platformOwners, updatePlatforms

beforeEach(() => {
  authSession.setLogin({ user: owner })
  list = vi.fn(() => ({ items: [owner, target], total: 20 }))
  stats = vi.fn(() => counts)
  detail = vi.fn(() => profile)
  role = vi.fn()
  status = vi.fn()
  platformOwners = vi.fn()
  updatePlatforms = vi.fn(() => new Response(null, { status: 204 }))
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path, init) => {
      const { pathname, searchParams } = new URL(path, 'http://localhost')
      const get = init.method === undefined || init.method === 'GET'
      let result
      if (get && pathname === '/api/admin/roles') {
        result = {
          roles: [
            { id: '1', roleCode: 'ADMIN', roleName: '系统管理员', enabled: true },
            { id: '3', roleCode: 'OPERATIONS', roleName: '运营', enabled: true },
          ],
          permissions: [],
        }
      } else if (get && pathname === base + '/platform-candidates') {
        result = {
          items: [
            { ...owner, canSubmit: true },
            { ...target, canSubmit: true },
            { id: '3', name: '只读同事', canSubmit: false },
          ],
          total: 3,
        }
      } else if (get && pathname === base) {
        result = await list(Object.fromEntries(searchParams))
      } else if (get && pathname === base + '/stats') {
        result = await stats(searchParams.toString())
      } else if (get && pathname === base + '/platform-assignments') {
        result = await platformOwners()
      } else if (init.method === 'PUT' && pathname === base + '/platform-assignments/RETAIL/PDD') {
        result = await updatePlatforms(JSON.parse(init.body))
      } else if (get && pathname === targetPath) {
        result = await detail()
      } else if (init.method === 'PATCH' && pathname === targetPath + '/roles') {
        result = await role(JSON.parse(init.body))
      } else if (init.method === 'PATCH' && pathname === targetPath + '/status') {
        result = await status(JSON.parse(init.body))
      } else {
        throw new Error('意外请求 ' + path)
      }
      return result instanceof Response ? result : Response.json(result)
    }),
  )
})

async function start(component = AdminUsersPage, path = '/admin/users') {
  const router = createWorkspaceRouter(createMemoryHistory(), authSession)
  await router.push(path)
  wrapper = mount(component, {
    attachTo: document.body,
    props: component === AdminLayout ? { user: authSession.user.value } : {},
    global: { plugins: [router], stubs: { teleport: true, ElSelect: true } },
  })
  await flushPromises()
  return router
}
async function clickLabel(label) {
  await wrapper.get('[aria-label="' + label + '"]').trigger('click')
  await flushPromises()
}
async function click(root, label) {
  await button(root, label).trigger('click')
  await flushPromises()
}
afterEach(() => {
  wrapper?.unmount()
  authSession.setLogin(null)
  ElMessage.closeAll()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

it('同一用户管理菜单下切换两项栏目，角色编辑归入该菜单', async () => {
  const router = await start(AdminLayout)
  const menu = () => wrapper.get('nav[aria-label="用户管理栏目"]')
  const sidebar = wrapper.get('#admin-sidebar')
  expect(sidebar.findAll('a').filter((link) => link.text() === '用户管理')).toHaveLength(1)
  expect(sidebar.text()).not.toContain('角色管理')
  expect(menu().get('[aria-current="page"]').text()).toBe('用户列表')

  await menu()
    .findAll('a')
    .find((link) => link.text() === '角色管理')
    .trigger('click')
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/admin/users/roles'))
  await flushPromises()
  expect(menu().get('[aria-current="page"]').text()).toBe('角色管理')
  expect(
    wrapper
      .get('[aria-label="当前位置"]')
      .findAll('.el-breadcrumb__inner')
      .map((crumb) => crumb.text()),
  ).toEqual(['系统管理', '用户管理', '角色管理'])
  expect(sidebar.get('a.is-active').text()).toBe('用户管理')
  expect(button(wrapper, '创建角色')).toBeDefined()
  await menu()
    .findAll('a')
    .find((link) => link.text() === '用户列表')
    .trigger('click')
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/admin/users'))
  await flushPromises()
  expect(wrapper.get('[aria-label="筛选用户"]').exists()).toBe(true)
})

it('仅可管理角色的账号通过用户管理菜单进入角色页，仅加载角色目录', async () => {
  authSession.setLogin({ user: { ...owner, roleCodes: ['CUSTOM'], permissions: ['roles.manage'] } })
  await start(AdminLayout)
  expect(wrapper.get('#admin-sidebar a.is-active').text()).toBe('用户管理')
  expect(wrapper.get('nav[aria-label="用户管理栏目"]').text()).toBe('角色管理')
  expect(button(wrapper, '创建角色')).toBeDefined()
  expect(fetch.mock.calls.map(([url]) => new URL(url, 'http://localhost').pathname)).toEqual([
    '/api/admin/roles',
  ])
})

it('仅可管理用户的账号访问角色直达地址时看到用户列表', async () => {
  authSession.setLogin({ user: { ...owner, roleCodes: ['CUSTOM'], permissions: ['users.manage'] } })
  await start(AdminLayout, '/admin/users/roles')
  expect(wrapper.get('nav[aria-label="用户管理栏目"]').text()).toBe('用户列表')
  expect(wrapper.get('[aria-label="筛选用户"]').exists()).toBe(true)
  expect(button(wrapper, '创建角色')).toBeUndefined()
})

it('筛选与分页仅查询列表，拒绝用当前页推算全公司统计', async () => {
  await start()
  expect(list).toHaveBeenLastCalledWith({ page: '1', pageSize: '10' })
  expect(stats).toHaveBeenCalledWith('')
  expect(wrapper.get('.admin-user-metric[data-tone="blue"]').text()).toMatch(/20\s*人/)
  wrapper.getComponent(ElPagination).vm.$emit('update:current-page', 2)
  await flushPromises()
  expect(list).toHaveBeenLastCalledWith({ page: '2', pageSize: '10' })
  wrapper.getComponent(ElPagination).vm.$emit('update:page-size', 20)
  await flushPromises()
  expect(list).toHaveBeenLastCalledWith({ page: '1', pageSize: '20' })
  await wrapper.get('#admin-user-keyword').setValue('  采购  ')
  wrapper.findAllComponents(ElSelect)[0].vm.$emit('update:modelValue', 'OPERATIONS')
  wrapper.findAllComponents(ElSelect)[1].vm.$emit('update:modelValue', 'ENABLED')
  expect(list).toHaveBeenCalledTimes(3)
  list.mockReturnValueOnce({ items: [], total: 0 })
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(list).toHaveBeenLastCalledWith({
    page: '1',
    pageSize: '20',
    keyword: '采购',
    roleCode: 'OPERATIONS',
    status: 'ENABLED',
  })
  expect(wrapper.text()).toContain('没有符合条件的用户')
  await click(wrapper, '重置筛选')
  expect(list).toHaveBeenLastCalledWith({ page: '1', pageSize: '20' })
  expect(stats).toHaveBeenCalledTimes(1)
})

it('角色修改使用最新详情的旧值，禁止修改本人，成功后重读三个区域', async () => {
  await start()
  expect(wrapper.get('[aria-label="调整角色 当前管理员"]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('[aria-label="停用用户 当前管理员"]').attributes('disabled')).toBeDefined()
  await clickLabel('查看用户 采购同事')
  expect(drawer().text()).toContain('ou_purchase')
  const fresh = { ...profile, status: 'DISABLED' }
  detail.mockResolvedValueOnce(fresh)
  await click(drawer(), '调整角色')
  expect(dialog().text()).toContain('已停用')
  dialog().getComponent(ElSelect).vm.$emit('update:modelValue', ['OPERATIONS', 'ADMIN'])
  await flushPromises()
  const updated = { ...fresh, roleCodes: ['OPERATIONS', 'ADMIN'] }
  role.mockResolvedValueOnce(updated)
  detail.mockResolvedValueOnce(updated)
  list.mockResolvedValueOnce({ items: [updated], total: 1 })
  stats.mockResolvedValueOnce({ ...counts, enabledUsers: 15, disabledUsers: 3 })
  await click(dialog(), '确认调整')
  expect(role).toHaveBeenCalledWith({
    roleCodes: ['OPERATIONS', 'ADMIN'],
    expectedVersion: 3,
  })
  expect(list).toHaveBeenCalledTimes(2)
  expect(stats).toHaveBeenCalledTimes(2)
  expect(detail).toHaveBeenCalledTimes(3)
  expect(table().text()).toContain('管理员')
  expect(drawer().text()).toContain('管理员')
  expect(wrapper.get('.admin-user-metric[data-tone="gray"]').text()).toMatch(/3\s*人/)
})

it('停用后列表末页回退，已打开的详情仍能启用原用户', async () => {
  await start()
  wrapper.findAllComponents(ElSelect)[1].vm.$emit('update:modelValue', 'ENABLED')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  wrapper.getComponent(ElPagination).vm.$emit('update:current-page', 2)
  await flushPromises()
  await clickLabel('查看用户 采购同事')
  await click(drawer(), '停用账号')
  await click(dialog(), '取消')
  expect(status).not.toHaveBeenCalled()
  await click(drawer(), '停用账号')
  const disabled = { ...profile, status: 'DISABLED' }
  status.mockResolvedValueOnce(disabled)
  detail.mockResolvedValue(disabled)
  list
    .mockResolvedValueOnce({ items: [], total: 10 })
    .mockResolvedValueOnce({ items: [owner], total: 10 })
  await click(dialog(), '确认停用')
  expect(status).toHaveBeenCalledWith({
    status: 'DISABLED',
    expectedVersion: 3,
  })
  expect(list).toHaveBeenLastCalledWith({ page: '1', pageSize: '10', status: 'ENABLED' })
  expect(table().text()).not.toContain('采购同事')
  expect(drawer().text()).toContain('已停用')
  await click(drawer(), '启用账号')
  status.mockResolvedValueOnce(profile)
  detail.mockResolvedValueOnce(profile)
  await click(dialog(), '确认启用')
  expect(status).toHaveBeenLastCalledWith({
    status: 'ENABLED',
    expectedVersion: 3,
  })
  expect(drawer().text()).toContain('已启用')
  expect(stats).toHaveBeenCalledTimes(3)
})

it('并发冲突重读详情，保留目标角色，再次确认才发起第二次写入', async () => {
  await start()
  await clickLabel('调整角色 采购同事')
  dialog().getComponent(ElSelect).vm.$emit('update:modelValue', ['OPERATIONS', 'ADMIN'])
  await flushPromises()
  role.mockResolvedValueOnce(failure(409, '用户状态已变化'))
  detail.mockResolvedValueOnce({ ...profile, status: 'DISABLED' })
  await click(dialog(), '确认调整')
  expect(dialog().text()).toContain('用户状态已变化')
  expect(dialog().text()).toContain('已停用')
  expect(dialog().getComponent(ElSelect).props('modelValue')).toEqual(['OPERATIONS', 'ADMIN'])
  expect(role).toHaveBeenCalledTimes(1)
  role.mockResolvedValueOnce({ ...profile, status: 'DISABLED', roleCodes: ['OPERATIONS', 'ADMIN'] })
  await click(dialog(), '确认调整')
  expect(role).toHaveBeenLastCalledWith({
    roleCodes: ['OPERATIONS', 'ADMIN'],
    expectedVersion: 3,
  })
})

it('写入中防重复提交，结果未知时先查证，不能把停用反向提交为启用', async () => {
  await start()
  await clickLabel('停用用户 采购同事')
  let rejectWrite
  status.mockImplementationOnce(
    () =>
      new Promise((_, reject) => {
        rejectWrite = reject
      }),
  )
  await click(dialog(), '确认停用')
  expect(button(dialog(), '确认停用').attributes('disabled')).toBeDefined()
  expect(button(dialog(), '取消').attributes('disabled')).toBeDefined()
  detail.mockResolvedValueOnce({ ...profile, status: 'DISABLED' })
  rejectWrite(new Error('连接已中断'))
  await flushPromises()
  expect(dialog().text()).toContain('写入结果待核对')
  expect(dialog().text()).toContain('账号已处于目标状态')
  expect(button(dialog(), '确认停用').attributes('disabled')).toBeDefined()
  expect(status).toHaveBeenCalledTimes(1)
})

it('读取失败可独立重试，详情失败时不能提交修改', async () => {
  list.mockResolvedValueOnce(failure(503, '列表暂不可用'))
  stats.mockResolvedValueOnce(failure(503, '统计暂不可用'))
  await start()
  expect(wrapper.text()).toContain('列表暂不可用')
  expect(wrapper.text()).toContain('统计暂不可用')
  expect(wrapper.text()).not.toContain('没有符合条件的用户')
  await click(wrapper, '重新读取列表')
  await click(wrapper, '重新读取统计')
  detail.mockResolvedValueOnce(failure(404, '用户不在可见范围内'))
  await clickLabel('调整角色 采购同事')
  expect(dialog().text()).toContain('用户不在可见范围内')
  expect(button(dialog(), '确认调整').attributes('disabled')).toBeDefined()
  await click(dialog(), '重新读取详情')
  expect(dialog().text()).toContain('采购同事')
})

it('关闭弹窗取消在途读取，迟到的详情不能覆盖下一次操作', async () => {
  await start()
  let resolveOld
  detail.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        resolveOld = resolve
      }),
  )
  await clickLabel('停用用户 采购同事')
  await click(dialog(), '取消')
  await clickLabel('调整角色 采购同事')
  resolveOld({ ...profile, roleCodes: ['OPERATIONS', 'ADMIN'] })
  await flushPromises()
  expect(dialog().getComponent(ElSelect).props('modelValue')).toEqual(['OPERATIONS'])
  expect(status).not.toHaveBeenCalled()
})

it('同一渠道平台可同时选多名成员，改派只保存一名提交人并校验版本', async () => {
  const team = {
    perspective: 'RETAIL',
    platformCode: 'PDD',
    version: 4,
    members: [{ userId: owner.id, name: owner.name, memberKind: 'SUBMITTER', canSubmit: true }],
  }
  platformOwners.mockReturnValue([
    team,
    { ...team, perspective: 'BILLION', members: [], version: 0 },
  ])
  await start(PlatformTeamsPage)
  await click(wrapper, '分配')
  wrapper.findAllComponents(ElCheckbox)[1].vm.$emit('change', true)
  wrapper.findAllComponents(ElCheckbox)[2].vm.$emit('change', true)
  await flushPromises()
  wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', '3')
  await flushPromises()
  expect(button(wrapper, '保存分工').element.disabled).toBe(true)
  wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', target.id)
  await flushPromises()
  await click(wrapper, '保存分工')
  expect(updatePlatforms).toHaveBeenCalledExactlyOnceWith({
    expectedVersion: 4,
    members: [
      { userId: owner.id, memberKind: 'MEMBER' },
      { userId: target.id, memberKind: 'SUBMITTER' },
      { userId: '3', memberKind: 'MEMBER' },
    ],
  })
})
