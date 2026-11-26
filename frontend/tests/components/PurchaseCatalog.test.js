// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { authSession } from '../../src/composables/useAuthSession'
import CatalogCenterPage from '../../src/views/admin/catalog/CatalogCenterPage.vue'
import CatalogCleaningPage from '../../src/views/admin/catalog/CatalogCleaningPage.vue'
import CatalogSourcesPage from '../../src/views/admin/catalog/CatalogSourcesPage.vue'
import { roleSession } from '../fixtures/role-session'

let wrapper
afterEach(() => {
  wrapper?.unmount()
  authSession.setLogin(null)
  vi.unstubAllGlobals()
})
const button = (label) => wrapper.findAll('button').find((item) => item.text() === label)

function catalogApi(brands, importBrands = brands) {
  const fetch = vi.fn(async (path, options) => {
    const { pathname } = new URL(path, 'http://localhost')
    if (pathname === '/api/admin/catalog/source-brands') {
      return Response.json({ brandNames: importBrands })
    }
    if (pathname === '/api/admin/catalog/goods/summary') {
      return Response.json({ total: 0, active: 0, stopped: 0, brandNames: brands, series: [] })
    }
    if (pathname === '/api/admin/goods-sync/jobs') {
      return Response.json(
        options.method === 'POST'
          ? { id: '1', brandName: '一加手机', status: 'QUEUED' }
          : { configured: true, jobs: [] },
      )
    }
    if (pathname === '/api/admin/catalog/cleaning/jobs/active') {
      return new Response(null, { status: 204 })
    }
    return Response.json({ items: [], total: 0, counts: {} })
  })
  vi.stubGlobal('fetch', fetch)
  return fetch
}

it('采购商品中心只读，商品为空时仍显示负责品牌，可直接进入吉客云导入', async () => {
  const brands = ['一加手机']
  const fetch = catalogApi(brands)
  const push = vi.fn()
  authSession.setLogin({ user: roleSession('PURCHASE') })
  wrapper = mount(CatalogCenterPage, { global: { mocks: { $router: { push } } } })
  await flushPromises()
  expect(wrapper.text()).toContain('一加手机')
  expect(wrapper.text()).not.toMatch(/新增商品|下载导入模板|批量导入|数据字典/)
  expect(fetch.mock.calls.some(([path]) => path.includes('/dictionary'))).toBe(false)
  await button('导入商品').trigger('click')
  expect(push).toHaveBeenCalledWith({ name: 'catalog-sources', query: { import: '1' } })

  wrapper.unmount()
  wrapper = mount(CatalogSourcesPage, { props: { showImport: true } })
  await flushPromises()
  expect(wrapper.get('.source-import-form').text()).toContain('一加手机')
  expect(wrapper.get('[aria-label="导入品牌"]').element.disabled).toBe(true)
  await button('开始导入').trigger('click')
  await flushPromises()
  const start = fetch.mock.calls.find(([, options]) => options.method === 'POST')
  expect(JSON.parse(start[1].body)).toMatchObject({ brandName: '一加手机', mode: 'FULL' })
  expect(wrapper.text()).not.toContain('OPPO手机')
})

it('尚未分工时提示管理员配置，自定义标准品牌可查看但仅支持的源品牌能导入', async () => {
  const brands = []
  catalogApi(brands, [])
  authSession.setLogin({ user: roleSession('PURCHASE') })
  wrapper = mount(CatalogCenterPage)
  await flushPromises()
  expect(wrapper.text()).toContain('尚未分配负责品牌')
  expect(button('导入商品').element.disabled).toBe(true)
  brands.push('手工维护品牌')
  await button('刷新负责品牌').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('手工维护品牌')
  expect(wrapper.text()).not.toContain('尚未分配负责品牌')
  expect(button('导入商品').element.disabled).toBe(true)
})

it('清洗预览锁定本人的品牌，重置结果筛选仍显示该品牌', async () => {
  const fetch = catalogApi(['一加手机'])
  wrapper = mount(CatalogCleaningPage)
  await flushPromises()
  await button('重置').trigger('click')
  await flushPromises()
  const results = fetch.mock.calls.filter(([path]) => path.includes('/cleaning/results?'))
  expect(new URL(results.at(-1)[0], 'http://localhost').searchParams.get('brandName')).toBe(
    '一加手机',
  )
  await button('开始清洗').trigger('click')
  await flushPromises()
  expect(wrapper.get('.cleaning-scope-form').text()).toContain('一加手机')
  expect(wrapper.get('[aria-label="清洗品牌"]').element.disabled).toBe(true)
  expect(wrapper.text()).not.toContain('OPPO手机')
})

it('管理员更换品牌分工后，采购返回已打开的商品中心会按新品牌查询', async () => {
  const brands = ['一加手机']
  const fetch = catalogApi(brands)
  authSession.setLogin({ user: roleSession('PURCHASE') })
  wrapper = mount(CatalogCenterPage)
  await flushPromises()
  expect(wrapper.text()).toContain('一加手机')
  brands[0] = 'OPPO手机'
  window.dispatchEvent(new Event('focus'))
  await flushPromises()
  const lists = fetch.mock.calls.filter(([path]) => path.startsWith('/api/admin/catalog/goods?'))
  expect(new URL(lists.at(-1)[0], 'http://localhost').searchParams.get('brandName')).toBe(
    'OPPO手机',
  )
  expect(wrapper.text()).toContain('OPPO手机')
  expect(wrapper.text()).not.toContain('一加手机')
})
