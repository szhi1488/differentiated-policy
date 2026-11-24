// @vitest-environment jsdom
import { expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createWorkspaceRouter } from '../../src/router/index'
import { createAuthSession } from '../../src/composables/useAuthSession'

it.each([
  ['APPLICANT', '/apply'],
  ['ADMIN', '/admin/catalog/sources'],
])('待审列表已删除，%s 访问原地址按普通未知地址处理', async (roleType, destination) => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  await router.push('/reviews')
  expect(router.currentRoute.value.name).toBeUndefined()
  session.setLogin({ user: { roleType } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.fullPath).toBe(destination)
  await router.push('/reviews?roleType=ADMIN')
  expect(router.currentRoute.value.fullPath).toBe(destination)
})

it('两种身份共用按轮详情，含多值、越界或未定义参数的链接不进入业务页', async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  for (const roleType of ['APPLICANT', 'ADMIN']) {
    session.setLogin({ user: { roleType } })
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
      roleType === 'ADMIN' ? '/reviews/unavailable' : '/apply?policyId=5&round=1',
    )
  }
})

it('采购链接对管理员定位差异化政策指定轮次，非管理员不能进入管理页', async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  const current = '/admin/policies?policyId=9007199254740993&round=2'
  await router.push(current)
  session.setLogin({ user: { roleType: 'ADMIN' } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.fullPath).toBe(current)
  await router.push('/admin/policies?policyId=12&round=2&round=3')
  expect(router.currentRoute.value.name).toBe('review-link-unavailable')
  await router.push(current)
  expect(router.currentRoute.value.fullPath).toBe(current)
  session.setLogin({ user: { roleType: 'APPLICANT' } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.path).toBe('/apply')
})

it('管理员可直达清洗页，恢复会话后保留地址并可切回源商品库', { timeout: 15000 }, async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  expect(
    router
      .getRoutes()
      .filter((route) => route.meta.role === 'ADMIN')
      .map((route) => route.path),
  ).toEqual([
    '/admin/catalog/sources',
    '/admin/catalog/cleaning',
    '/admin/catalog/center',
    '/admin/policies',
    '/admin/model-subsidies',
    '/admin/users',
    '/admin/review-settings',
  ])
  await router.push('/admin/catalog/cleaning')
  session.setLogin({ user: { roleType: 'ADMIN' } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.path).toBe('/admin/catalog/cleaning')
  expect(router.currentRoute.value.matched).toHaveLength(1)
  await router.push({ name: 'catalog-sources' })
  expect(router.currentRoute.value.path).toBe('/admin/catalog/sources')
})

it('管理员恢复会话后保留商品中心直达地址', { timeout: 15000 }, async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  await router.push('/admin/catalog/center')
  session.setLogin({ user: { roleType: 'ADMIN' } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.name).toBe('catalog-center')
})
it('管理员恢复会话后保留差异化政策直达地址', { timeout: 15000 }, async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  await router.push('/admin/policies')
  session.setLogin({ user: { roleType: 'ADMIN' } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.name).toBe('admin-policies')
})
it('管理员恢复会话后保留用户管理直达地址', { timeout: 15000 }, async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  await router.push('/admin/users')
  session.setLogin({ user: { roleType: 'ADMIN' } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.name).toBe('admin-users')
})
it('管理员打开已删除的页面或未知地址时回到源商品库', async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  session.setLogin({ user: { roleType: 'ADMIN' } })
  for (const path of ['/admin/catalog/sync', '/admin/missing']) {
    await router.push(path)
    expect(router.currentRoute.value.path).toBe('/admin/catalog/sources')
  }
})

it('申请人手动访问管理员地址仍回到申请人页，查询参数不能授予管理员角色', async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  await router.push('/admin/catalog/cleaning?roleType=ADMIN')
  session.setLogin({ user: { roleType: 'APPLICANT' } })
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

it('申请人恢复会话后可以直达本人平台的补贴查询，管理员进入申请人地址时回到管理员工作台', async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  await router.push('/apply/model-subsidies')
  session.setLogin({ user: { roleType: 'APPLICANT' } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.name).toBe('applicant-model-subsidies')
  session.setLogin({ user: { roleType: 'ADMIN' } })
  await router.syncSessionRoute()
  expect(router.currentRoute.value.name).toBe('catalog-sources')
})
