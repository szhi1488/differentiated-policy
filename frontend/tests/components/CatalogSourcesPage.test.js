// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import CatalogSourcesPage from '../../src/views/admin/catalog/CatalogSourcesPage.vue'

it('按接口字段分别显示 SKU 和商品修改时间，并保留毫秒差异', async () => {
  const items = [
    {
      id: '1',
      createdDate: '2026-09-16',
      gmtModified: 1748428975000,
      goodsGmtModified: 1714359067000,
    },
    { id: '2', gmtModified: 1748428975001, goodsGmtModified: 1748428975012 },
    { id: '3', gmtModified: null, goodsGmtModified: 1714359067000 },
  ]
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path) => {
      const body = path.startsWith('/api/admin/source-goods?')
        ? { items, total: items.length }
        : { configured: true, jobs: [] }
      return Response.json(body)
    }),
  )
  const wrapper = mount(CatalogSourcesPage)
  try {
    await flushPromises()
    const fields = wrapper
      .findAll('.source-table thead th')
      .map((column) => column.element.querySelector('small')?.textContent)
    const skuTime = fields.indexOf('gmtModified')
    const goodsTime = fields.indexOf('goodsGmtModified')
    const times = wrapper.findAll('.source-table tbody tr').map((row) => {
      const cells = row.findAll('td')
      return [cells[skuTime].text(), cells[goodsTime].text()]
    })
    expect(times).toEqual([
      ['2025/5/28 18:42:55', '2024/4/29 10:51:07'],
      ['2025/5/28 18:42:55.001', '2025/5/28 18:42:55.012'],
      ['—', '2024/4/29 10:51:07'],
    ])
    expect(wrapper.get('.source-table tbody tr').text()).toContain('2026-09-16')
  } finally {
    wrapper.unmount()
    vi.unstubAllGlobals()
  }
})

it('从建档日任务查看该日商品，重置后恢复全部来源', async () => {
  const fetch = vi.fn(async (path) => {
    if (path.startsWith('/api/admin/source-goods?')) {
      return Response.json({ items: [], total: 0 })
    }
    return Response.json({
      configured: true,
      jobs: [
        {
          id: '1',
          brandName: '一加手机',
          status: 'SUCCEEDED',
          syncMode: 'CREATED',
          syncDate: '2026-09-16',
          contentCount: 2,
        },
      ],
    })
  })
  vi.stubGlobal('fetch', fetch)
  const wrapper = mount(CatalogSourcesPage)
  try {
    await flushPromises()
    const button = (label) => wrapper.findAll('button').find((item) => item.text() === label)
    const listParams = () => {
      const path = fetch.mock.calls
        .filter(([url]) => url.startsWith('/api/admin/source-goods?'))
        .at(-1)[0]
      return new URLSearchParams(path.split('?')[1])
    }
    await wrapper.get('[id="tab-一加手机"]').trigger('click')
    await button('导入商品').trigger('click')
    await flushPromises()
    expect(wrapper.get('.source-import-form').text()).toContain('一加手机')
    expect(wrapper.get('.source-job').text()).toContain('2026-09-16')
    await button('查看该日商品').trigger('click')
    await flushPromises()
    expect(listParams().get('createdDate')).toBe('2026-09-16')
    expect(listParams().get('brandName')).toBe('一加手机')
    expect(listParams().get('page')).toBe('1')
    await button('重置').trigger('click')
    await flushPromises()
    expect(listParams().has('createdDate')).toBe(false)
    expect(listParams().has('brandName')).toBe(false)
  } finally {
    wrapper.unmount()
    vi.unstubAllGlobals()
  }
})
