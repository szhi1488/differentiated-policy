// @vitest-environment jsdom
import { roleSession } from '../fixtures/role-session'
import { expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createWorkspaceRouter } from '../../src/router/index'
import { createAuthSession } from '../../src/composables/useAuthSession'
import { visibleGroups } from '../../src/constants/navigation'

it.each([
  ['OPERATIONS', '/apply'],
  ['ADMIN', '/admin/catalog/center'],
])('待审列表已删除，%s 访问原地址按普通未知地址处理', async (roleType, destination) => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  await router.push('/reviews')
  expect(router.currentRoute.value.name).toBeUndefined()
  session.setLogin({ user: roleSession(roleType) })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.fullPath).toBe(destination)
  await router.push('/reviews?roleType=ADMIN')
  expect(router.currentRoute.value.fullPath).toBe(destination)
})

it('两种身份共用按轮详情，含多值、越界或未定义参数的链接不进入业务页', async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  for (const roleType of ['OPERATIONS', 'ADMIN']) {
    session.setLogin({ user: roleSession(roleType) })
    const path = '/reviews/9007199254740993?round=2'
    await router.push(path)
    expect(router.currentRoute.value.fullPath).toBe(path)
    for (const invalid of [
      '5?round=1&round=2',
      '5?round=0',
      '5?round=2147483648',
      '9223372036854775808?round=1',
      '5?round=1&node=PRODUCT_REVIEW',
      '5?round=1&node=PURCHASE_REVIEW',
      '5?round=1&node=REVISION',
      '5?round=1&redirect=/apply',
      '5',
    ]) {
      await router.push('/reviews/' + invalid)
      expect(router.currentRoute.value.name).toBe('review-link-unavailable')
    }
    await router.push('/apply?policyId=5&round=1')
    expect(router.currentRoute.value.fullPath).toBe(
      roleType === 'ADMIN' ? '/reviews/5?round=1' : '/apply?policyId=5&round=1',
    )
    await router.push('/apply?policyId=5&round=0')
    expect(router.currentRoute.value.name).toBe('review-link-unavailable')
  }
})

it('采购链接对管理员定位差异化政策指定轮次，非管理员不能进入管理页', async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  const current = '/admin/policies?policyId=9007199254740993&round=2'
  await router.push(current)
  session.setLogin({ user: { ...roleSession('ADMIN') } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.fullPath).toBe(current)
  await router.push('/admin/policies?policyId=12&round=2&round=3')
  expect(router.currentRoute.value.name).toBe('review-link-unavailable')
  await router.push(current)
  expect(router.currentRoute.value.fullPath).toBe(current)
  session.setLogin({ user: { ...roleSession('OPERATIONS') } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.path).toBe('/reviews/unavailable')
})

it('采购可直达清洗页并切回源商品库，加任管理员后转到商品中心', { timeout: 15000 }, async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  const adminPaths = router
    .getRoutes()
    .filter((route) => route.path.startsWith('/admin/'))
    .map((route) => route.path)
  expect(adminPaths.sort()).toEqual(
    [
      '/admin/catalog/sources',
      '/admin/catalog/cleaning',
      '/admin/catalog/center',
      '/admin/policies',
      '/admin/model-subsidies',
      '/admin/users',
      '/admin/users/roles',
      '/admin/review-settings',
      '/admin/roles',
      '/admin/platform-teams',
    ].sort(),
  )
  await router.push('/admin/catalog/cleaning')
  session.setLogin({ user: roleSession('PURCHASE') })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.path).toBe('/admin/catalog/cleaning')
  expect(router.currentRoute.value.matched).toHaveLength(1)
  await router.push({ name: 'catalog-center' })
  expect(router.currentRoute.value.path).toBe('/admin/catalog/center')
  await router.push({ name: 'catalog-sources' })
  expect(router.currentRoute.value.path).toBe('/admin/catalog/sources')
  session.setLogin({ user: roleSession('PURCHASE', 'ADMIN') })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.name).toBe('catalog-center')
})

it('管理员恢复会话后保留商品中心直达地址', { timeout: 15000 }, async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  await router.push('/admin/catalog/center')
  session.setLogin({ user: { ...roleSession('ADMIN') } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.name).toBe('catalog-center')
})
it('管理员恢复会话后保留差异化政策直达地址', { timeout: 15000 }, async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  await router.push('/admin/policies')
  session.setLogin({ user: { ...roleSession('ADMIN') } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.name).toBe('admin-policies')
})
it('管理员恢复会话后保留用户管理直达地址', { timeout: 15000 }, async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  await router.push('/admin/users')
  session.setLogin({ user: { ...roleSession('ADMIN') } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.name).toBe('admin-users')
})
it('旧角色地址重定向到用户管理，恢复会话和撤下角色维护权限时重新选择可用栏目', async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  await router.push('/admin/roles')
  session.setLogin({ user: roleSession('ADMIN') })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.path).toBe('/admin/users/roles')

  session.setLogin({ user: { roleCodes: ['CUSTOM'], permissions: ['roles.manage'] } })
  await router.push('/admin/users')
  expect(router.currentRoute.value.path).toBe('/admin/users/roles')

  session.setLogin({ user: { roleCodes: ['CUSTOM'], permissions: ['users.manage'] } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.path).toBe('/admin/users')
  await router.push('/admin/roles')
  expect(router.currentRoute.value.path).toBe('/admin/users')
})
it.each([
  ['系统管理员', roleSession('ADMIN')],
  ['兼任采购的系统管理员', roleSession('PURCHASE', 'ADMIN')],
])('%s 商品菜单只有商品中心，进旧商品地址或未知地址时回到商品中心', async (_label, user) => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  session.setLogin({ user })
  const catalog = visibleGroups(user).find((group) => group.label === '商品')
  expect(catalog.links.map((link) => link.name)).toEqual(['catalog-center'])
  for (const path of [
    '/admin/catalog/sources',
    '/admin/catalog/cleaning',
    '/admin/catalog/sync',
    '/admin/missing',
  ]) {
    await router.push(path)
    expect(router.currentRoute.value.fullPath).toBe('/admin/catalog/center')
  }
})

it.each([
  ['catalog.import', ['catalog-sources', 'catalog-center']],
  ['catalog.clean', ['catalog-cleaning']],
  ['catalog.manage', ['catalog-center']],
])('自定义岗位仅有 %s 权限时进入对应的商品页面', async (permission, pages) => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  const user = { roleCodes: ['CUSTOM'], permissions: [permission] }
  session.setLogin({ user })
  const catalog = visibleGroups(user).find((group) => group.label === '商品')
  expect(catalog.links.map((link) => link.name)).toEqual(pages)
  for (const name of ['catalog-sources', 'catalog-cleaning', 'catalog-center']) {
    await router.push({ name })
    expect(router.currentRoute.value.name).toBe(pages.includes(name) ? name : pages[0])
  }
})

it('申请人手动访问管理员地址仍回到申请人页，查询参数不能授予管理员角色', async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  await router.push('/admin/catalog/cleaning?roleType=ADMIN')
  session.setLogin({ user: { ...roleSession('OPERATIONS') } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.path).toBe('/apply')
  await router.push('/admin/catalog/sources')
  expect(router.currentRoute.value.path).toBe('/apply')
  await router.push('/admin/catalog/center')
  expect(router.currentRoute.value.path).toBe('/apply')
  await router.push('/admin/policies?roleType=ADMIN')
  expect(router.currentRoute.value.path).toBe('/apply')
  await router.push('/admin/model-subsidies')
  expect(router.currentRoute.value.path).toBe('/apply')
  await router.push('/admin/users?roleType=ADMIN')
  expect(router.currentRoute.value.path).toBe('/apply')
})

it('运营恢复会话时保留平台补贴查询，升为管理员后转到公司补贴查询', async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  await router.push('/apply/model-subsidies')
  session.setLogin({ user: { ...roleSession('OPERATIONS') } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.name).toBe('applicant-model-subsidies')
  session.setLogin({ user: { ...roleSession('ADMIN') } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.name).toBe('admin-model-subsidies')
})

it.each([
  ['系统管理员', roleSession('ADMIN')],
  ['兼任运营、产品运营的系统管理员', roleSession('OPERATIONS', 'PRODUCT_OPS', 'ADMIN')],
])(
  '%s 的政策管理只有差异化政策和公司补贴入口，个人页面旧地址回到政策管理',
  async (_label, user) => {
    const session = createAuthSession()
    const router = createWorkspaceRouter(createMemoryHistory(), session)
    session.setLogin({ user })
    const groups = visibleGroups(user)
    expect(groups.map((group) => group.label)).not.toContain('政策提交')
    const policyLinks = groups.find((group) => group.label === '政策管理').links
    expect(policyLinks.map((link) => link.label)).toEqual(['差异化政策', '机型补贴查询'])
    for (const path of [
      '/apply',
      '/apply/submissions',
      '/apply/platform-policies',
      '/reviews/history',
    ]) {
      await router.push(path)
      expect(router.currentRoute.value.fullPath).toBe('/admin/policies')
    }
    await router.push('/apply/model-subsidies')
    expect(router.currentRoute.value.fullPath).toBe('/admin/model-subsidies')
  },
)

it('运营、产品运营及非管理员兼岗保留岗位入口，兼有两种补贴范围时仅展示公司补贴', async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  for (const [user, expectedLinks] of [
    [
      roleSession('OPERATIONS'),
      ['apply', 'submissions', 'platform-policies', 'applicant-model-subsidies'],
    ],
    [roleSession('PRODUCT_OPS'), ['product-history']],
    [
      roleSession('PURCHASE', 'OPERATIONS', 'PRODUCT_OPS'),
      [
        'catalog-sources',
        'catalog-cleaning',
        'catalog-center',
        'admin-policies',
        'product-history',
        'admin-model-subsidies',
        'apply',
        'submissions',
        'platform-policies',
      ],
    ],
  ]) {
    session.setLogin({ user })
    const names = visibleGroups(user).flatMap((group) => group.links.map((link) => link.name))
    expect(names.sort()).toEqual(expectedLinks.sort())
    for (const name of expectedLinks) {
      await router.push({ name })
      expect(router.currentRoute.value.name).toBe(name)
    }
  }
  await router.push('/apply/model-subsidies')
  expect(router.currentRoute.value.name).toBe('admin-model-subsidies')
})

it('采购和产品运营各自仅进入授权页面，兼岗后菜单取并集，空岗位进入等待授权页', async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  for (const [code, allowed, denied, home] of [
    ['PURCHASE', '/admin/policies', '/admin/roles', '/admin/catalog/sources'],
    ['PRODUCT_OPS', '/reviews/history', '/admin/policies', '/reviews/history'],
  ]) {
    session.setLogin({ user: roleSession(code) })
    await router.push(allowed)
    expect(router.currentRoute.value.path).toBe(allowed)
    await router.push(denied)
    expect(router.currentRoute.value.path).toBe(home)
  }
  session.setLogin({
    user: {
      roleCodes: ['CUSTOM'],
      permissions: ['roles.manage', 'policy.read.platform_submitted'],
    },
  })
  for (const path of ['/admin/users/roles', '/apply/platform-policies']) {
    await router.push(path)
    expect(router.currentRoute.value.path).toBe(path)
  }
  session.setLogin({ user: { roleCodes: ['REVIEW_SETTINGS'], permissions: ['review.manage'] } })
  await router.push('/admin/policies')
  expect(router.currentRoute.value.path).toBe('/admin/review-settings')
  session.setLogin({ user: { roleCodes: [], permissions: [] } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.name).toBe('no-access')
})
