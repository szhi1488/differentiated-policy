// @vitest-environment jsdom
import { expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createWorkspaceRouter } from './index'
import { ADMIN_PAGES } from '../constants/admin'

it('管理员路由可直达、刷新后恢复，未知地址回到全部政策', async () => {
  const router = createWorkspaceRouter(createMemoryHistory())
  await router.push('/admin/catalog/exceptions')
  await router.setSessionUser({ roleType: 'ADMIN' })
  expect(router.currentRoute.value.path).toBe('/admin/catalog/exceptions')
  for (const page of ADMIN_PAGES) {
    await router.push(page.path)
    expect(router.currentRoute.value.path).toBe(page.path)
    expect(router.currentRoute.value.matched).toHaveLength(1)
  }
  await router.push('/admin/missing')
  expect(router.currentRoute.value.path).toBe('/admin/policies')
})

it('申请人手动访问管理员地址仍回到申请人页，查询参数不能授予管理员角色', async () => {
  const router = createWorkspaceRouter(createMemoryHistory())
  await router.push('/admin/policies?roleType=ADMIN')
  await router.setSessionUser({ roleType: 'APPLICANT' })
  expect(router.currentRoute.value.path).toBe('/apply')
  await router.push('/admin/reviews')
  expect(router.currentRoute.value.path).toBe('/apply')
})
