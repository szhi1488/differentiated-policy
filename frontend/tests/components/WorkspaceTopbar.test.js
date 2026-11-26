// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ElAutocomplete } from 'element-plus'
import WorkspaceTopbar from '../../src/layouts/components/WorkspaceTopbar.vue'
import {
  ADMIN_NAV_GROUPS,
  APPLICANT_NAV_GROUPS,
  visibleGroups,
} from '../../src/constants/navigation'
import { roleSession } from '../fixtures/role-session'

let wrapper
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
async function render(groups = ADMIN_NAV_GROUPS, name = 'catalog-center') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: groups.flatMap(({ links }) =>
      links.map(({ name }) => ({ name, path: `/${name}`, component: { template: '<div />' } })),
    ),
  })
  await router.push({ name })
  wrapper = mount(WorkspaceTopbar, {
    attachTo: document.body,
    props: {
      groups,
      roleLabel: '管理员',
      sidebarId: 'sidebar',
      menuExpanded: true,
      user: { name: '李明', avatarUrl: 'https://example.test/avatar.png' },
    },
    global: { plugins: [router] },
  })
  return router
}
describe('工作台顶部栏', () => {
  it('位置随路由更新，切换导航仅发出事件', async () => {
    const router = await render()
    expect(wrapper.get('[aria-label="当前位置"]').text()).toContain('商品中心')
    await router.push({ name: 'admin-policies' })
    expect(wrapper.get('[aria-label="当前位置"]').text()).toContain('差异化政策')
    await wrapper.get('[aria-controls="sidebar"]').trigger('click')
    expect(wrapper.emitted('toggle-menu')).toHaveLength(1)
  })
  it('搜索仅返回当前角色的已有菜单，选择后由布局处理导航', async () => {
    await render(APPLICANT_NAV_GROUPS, 'apply')
    const search = wrapper.findComponent(ElAutocomplete)
    let results
    search.props('fetchSuggestions')('商品', (items) => (results = items))
    expect(results).toHaveLength(0)
    search.props('fetchSuggestions')('提交', (items) => (results = items))
    expect(results.map(({ name }) => name)).toEqual([
      'apply',
      'submissions',
      'platform-policies',
      'applicant-model-subsidies',
    ])
    search.props('fetchSuggestions')('补贴', (items) => (results = items))
    expect(results.map(({ name }) => name)).toEqual(['applicant-model-subsidies'])
    search.vm.$emit('select', results[0])
    await flushPromises()
    expect(wrapper.emitted('navigate')).toEqual([['applicant-model-subsidies']])
  })
  it('搜索角色管理直达用户管理的角色栏目，收回角色权限后不可搜索', async () => {
    await render(ADMIN_NAV_GROUPS, 'admin-users')
    await wrapper.setProps({ user: { permissions: ['roles.manage'] } })
    const search = wrapper.getComponent(ElAutocomplete)
    const suggest = search.props('fetchSuggestions')
    suggest('角色管理', (items) => {
      expect(items).toEqual([{ name: 'roles', value: '角色管理', group: '系统管理 / 用户管理' }])
      search.vm.$emit('select', items[0])
    })
    expect(wrapper.emitted('navigate')).toEqual([['roles']])
    await wrapper.setProps({ user: { permissions: ['users.manage'] } })
    suggest('角色管理', (items) => expect(items).toEqual([]))
  })
  it('管理员搜索补贴只有公司查询结果，搜索个人政策页面没有结果', async () => {
    const user = roleSession('ADMIN')
    await render(visibleGroups(user), 'admin-policies')
    await wrapper.setProps({ user })
    const suggestions = wrapper.getComponent(ElAutocomplete).props('fetchSuggestions')
    suggestions('补贴', (items) => {
      expect(items).toEqual([
        { name: 'admin-model-subsidies', value: '机型补贴查询', group: '政策管理' },
      ])
    })
    for (const query of ['提交政策', '我的提交', '我的初审记录', '平台政策']) {
      suggestions(query, (items) => expect(items).toEqual([]))
    }
  })
  it.each([
    ['ADMIN', ['catalog-center']],
    ['PURCHASE', ['catalog-sources', 'catalog-cleaning', 'catalog-center']],
  ])('%s 搜索商品时结果与岗位商品菜单一致', async (role, pages) => {
    const user = roleSession(role)
    await render(visibleGroups(user), pages[0])
    await wrapper.setProps({ user })
    const suggestions = wrapper.getComponent(ElAutocomplete).props('fetchSuggestions')
    suggestions('商品', (items) => expect(items.map((item) => item.name)).toEqual(pages))
  })
  it.each([
    ['申请人', APPLICANT_NAV_GROUPS, 'apply'],
    ['管理员', ADMIN_NAV_GROUPS, 'admin-policies'],
  ])('%s 的菜单搜索不再提供待我审核入口', async (_role, groups, page) => {
    await render(groups, page)
    const search = wrapper.findComponent(ElAutocomplete)
    search.props('fetchSuggestions')('待我审核', (items) => expect(items).toEqual([]))
  })
  it('头像加载失败回退姓名，更新地址后重新显示图片', async () => {
    await render()
    expect(wrapper.get('.workspace-user-avatar img').attributes('src')).toBe(
      'https://example.test/avatar.png',
    )
    await wrapper.get('.workspace-user-avatar img').trigger('error')
    expect(wrapper.get('.workspace-user-avatar').text()).toBe('李')
    await wrapper.setProps({ user: { name: '王明', avatarUrl: 'https://example.test/new.png' } })
    expect(wrapper.find('.workspace-user-avatar img').exists()).toBe(true)
    await wrapper.setProps({ user: { name: '王明', avatarUrl: 'javascript:alert(1)' } })
    expect(wrapper.get('.workspace-user-avatar').text()).toBe('王')
    expect(wrapper.find('.workspace-user-avatar img').exists()).toBe(false)
  })
})
