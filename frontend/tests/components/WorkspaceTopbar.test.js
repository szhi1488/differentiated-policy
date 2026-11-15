// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ElAutocomplete } from 'element-plus'
import WorkspaceTopbar from '../../src/layouts/components/WorkspaceTopbar.vue'
import { ADMIN_NAV_GROUPS, APPLICANT_NAV_GROUPS } from '../../src/constants/navigation'

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
    expect(results.map(({ name }) => name)).toEqual(['apply', 'submissions'])
    search.vm.$emit('select', results[1])
    await flushPromises()
    expect(wrapper.emitted('navigate')).toEqual([['submissions']])
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
