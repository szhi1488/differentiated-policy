// @vitest-environment jsdom
import { expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createWorkspaceRouter } from '../../src/router/index'
import { createAuthSession } from '../../src/composables/useAuthSession'

it('管理员可直达清洗页，恢复会话后保留地址并可切回源商品库', { timeout: 15000 }, async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  expect(
    router
      .getRoutes()
      .filter((route) => route.meta.role === 'ADMIN')
      .map((route) => route.path),
  ).toEqual(['/admin/catalog/sources', '/admin/catalog/cleaning', '/admin/catalog/center'])
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
it('管理员打开已删除的页面或未知地址时回到源商品库', async () => {
  const session = createAuthSession()
  const router = createWorkspaceRouter(createMemoryHistory(), session)
  session.setLogin({ user: { roleType: 'ADMIN' } })
  for (const path of ['/admin/policies', '/admin/catalog/sync', '/admin/missing']) {
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
})
