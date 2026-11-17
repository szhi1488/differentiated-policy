// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ElMessage, ElPagination, ElRadioGroup, ElSelect } from 'element-plus'
import AdminUsersPage from '../../src/views/admin/users/AdminUsersPage.vue'
import { authSession } from '../../src/composables/useAuthSession'

const base = '/api/admin/users'
const owner = {
  id: '1',
  name: '当前管理员',
  roleType: 'ADMIN',
  status: 'ENABLED',
  createdAt: '2026-11-17 09:00:00',
}
const target = {
  ...owner,
  id: '9007199254740993',
  name: '采购同事',
  roleType: 'APPLICANT',
  department: null,
}
const targetPath = base + '/' + target.id
const profile = { ...target, openId: 'ou_purchase' }
const counts = { total: 20, enabledAdmins: 2, enabledApplicants: 16, disabledUsers: 2 }
const failure = (status, message) => Response.json({ message }, { status })
const button = (root, label) => root.findAll('button').find((item) => item.text() === label)
const dialog = () => wrapper.get('.admin-user-action-dialog')
const drawer = () => wrapper.get('.admin-user-drawer')
const table = () => wrapper.get('.el-table__body')
let wrapper, list, stats, detail, role, status

beforeEach(() => {
  authSession.setLogin({ user: owner })
  list = vi.fn(() => ({ items: [owner, target], total: 20 }))
  stats = vi.fn(() => counts)
  detail = vi.fn(() => profile)
  role = vi.fn()
  status = vi.fn()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path, init) => {
      const { pathname, searchParams } = new URL(path, 'http://localhost')
      const get = init.method === undefined || init.method === 'GET'
      let result
      if (get && pathname === base) {
        result = await list(Object.fromEntries(searchParams))
      } else if (get && pathname === base + '/stats') {
        result = await stats(searchParams.toString())
      } else if (get && pathname === targetPath) {
        result = await detail()
      } else if (init.method === 'PATCH' && pathname === targetPath + '/role') {
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

async function start() {
  wrapper = mount(AdminUsersPage, {
    attachTo: document.body,
    global: { stubs: { teleport: true, ElSelect: true } },
  })
  await flushPromises()
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
  wrapper.findAllComponents(ElSelect)[0].vm.$emit('update:modelValue', 'APPLICANT')
  wrapper.findAllComponents(ElSelect)[1].vm.$emit('update:modelValue', 'ENABLED')
  expect(list).toHaveBeenCalledTimes(3)
  list.mockReturnValueOnce({ items: [], total: 0 })
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(list).toHaveBeenLastCalledWith({
    page: '1',
    pageSize: '20',
    keyword: '采购',
    roleType: 'APPLICANT',
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
  wrapper.getComponent(ElRadioGroup).vm.$emit('update:modelValue', 'ADMIN')
  await flushPromises()
  const updated = { ...fresh, roleType: 'ADMIN' }
  role.mockResolvedValueOnce(updated)
  detail.mockResolvedValueOnce(updated)
  list.mockResolvedValueOnce({ items: [updated], total: 1 })
  stats.mockResolvedValueOnce({ ...counts, enabledApplicants: 15, disabledUsers: 3 })
  await click(dialog(), '确认调整')
  expect(role).toHaveBeenCalledWith({
    roleType: 'ADMIN',
    expectedRoleType: 'APPLICANT',
    expectedStatus: 'DISABLED',
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
    expectedRoleType: 'APPLICANT',
    expectedStatus: 'ENABLED',
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
    expectedRoleType: 'APPLICANT',
    expectedStatus: 'DISABLED',
  })
  expect(drawer().text()).toContain('已启用')
  expect(stats).toHaveBeenCalledTimes(3)
})

it('并发冲突重读详情，保留目标角色，再次确认才发起第二次写入', async () => {
  await start()
  await clickLabel('调整角色 采购同事')
  wrapper.getComponent(ElRadioGroup).vm.$emit('update:modelValue', 'ADMIN')
  await flushPromises()
  role.mockResolvedValueOnce(failure(409, '用户状态已变化'))
  detail.mockResolvedValueOnce({ ...profile, status: 'DISABLED' })
  await click(dialog(), '确认调整')
  expect(dialog().text()).toContain('用户状态已变化')
  expect(dialog().text()).toContain('已停用')
  expect(wrapper.getComponent(ElRadioGroup).props('modelValue')).toBe('ADMIN')
  expect(role).toHaveBeenCalledTimes(1)
  role.mockResolvedValueOnce({ ...profile, status: 'DISABLED', roleType: 'ADMIN' })
  await click(dialog(), '确认调整')
  expect(role).toHaveBeenLastCalledWith({
    roleType: 'ADMIN',
    expectedRoleType: 'APPLICANT',
    expectedStatus: 'DISABLED',
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
  resolveOld({ ...profile, roleType: 'ADMIN' })
  await flushPromises()
  expect(wrapper.getComponent(ElRadioGroup).props('modelValue')).toBe('APPLICANT')
  expect(status).not.toHaveBeenCalled()
})
