// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { ElMessage, ElPagination } from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import PolicyReviewSettingsPage from '../../src/views/admin/review-settings/PolicyReviewSettingsPage.vue'

const first = { id: '9007199254740993', name: '林宁', department: '运营一部' }
const second = { id: '9007199254740994', name: '林宁', department: '运营二部' }
const buyer = { id: '31', name: '采购甲', department: '采购部' }
const newBuyer = { id: '32', name: '采购乙', department: '采购部' }
const initialSettings = {
  version: 2,
  policyChatConfigured: true,
  brands: [
    {
      brandId: '1',
      brandName: 'OPPO手机',
      productMode: 'BY_SERIES',
      purchaseReviewer: buyer,
      brandProductReviewer: null,
      series: [{ seriesId: '6', seriesName: 'Reno', productReviewer: null }],
    },
  ],
}
const settingsPath = '/api/admin/policy-review-settings'
let wrapper

const oppo = initialSettings.brands[0]
const onePlus = {
  brandId: '2',
  brandName: '一加手机',
  productMode: 'BY_SERIES',
  purchaseReviewer: newBuyer,
  brandProductReviewer: null,
  series: [
    { seriesId: '31', seriesName: 'Ace', productReviewer: first },
    { seriesId: '32', seriesName: '数字系列', productReviewer: first },
    { seriesId: '33', seriesName: 'Turbo', productReviewer: second },
  ],
}

async function open(handlers) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path, init) => {
      const url = new URL(path, 'http://localhost')
      const handler = handlers[`${init.method || 'GET'} ${url.pathname}`]
      if (!handler) {
        throw new Error('意外请求 ' + path)
      }
      const result = await handler(
        init.body ? JSON.parse(init.body) : Object.fromEntries(url.searchParams),
      )
      return result instanceof Response ? result : Response.json(result)
    }),
  )
  wrapper = mount(PolicyReviewSettingsPage, {
    attachTo: document.body,
    global: { stubs: { teleport: true } },
  })
  await flushPromises()
}
const button = (scope, text) => scope.findAll('button').find((node) => node.text() === text)
async function click(text, scope = wrapper) {
  await button(scope, text).trigger('click')
  await flushPromises()
}
async function edit(label) {
  await wrapper.get(`button[aria-label="${label}"]`).trigger('click')
  await flushPromises()
  return wrapper.get('.reviewer-picker-dialog')
}
async function chooseBrand(name) {
  const tab = wrapper.findAll('[role=tab]').find((node) => node.text().includes(name))
  await tab.trigger('click')
  await flushPromises()
  expect(tab.attributes('aria-selected')).toBe('true')
}
afterEach(() => {
  wrapper?.unmount()
  ElMessage.closeAll()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

it('首次读取审核预设即可提示业务群缺失，浏览页面只请求设置接口', async () => {
  await open({
    [`GET ${settingsPath}`]: () => ({ ...initialSettings, policyChatConfigured: false }),
  })
  expect(fetch.mock.calls.map(([url]) => url)).toEqual([settingsPath])
  expect(wrapper.text()).not.toMatch(/在途改派|飞书投递记录|配置版本/)
  expect(wrapper.text()).toContain('FEISHU_POLICY_CHAT_ID')
  expect(wrapper.findAll('button').filter((item) => item.text() === '保存审核分工')).toHaveLength(1)
  const table = wrapper.get('table[aria-label="OPPO手机审核分工"]')
  expect(table.findAll('thead th').map((heading) => heading.text())).toEqual([
    '审核环节',
    '适用范围',
    '审核人',
    '操作',
  ])
  expect(button(wrapper, '保存审核分工').element.disabled).toBe(true)
})

it('品牌和系列分别查询候选，跨页替换同名审核人，按目录 ID 保存并回显审核人', async () => {
  const kSeries = { seriesId: '7', seriesName: 'K', productReviewer: null }
  const candidates = vi.fn(({ reviewGroup, page }) =>
    reviewGroup === 'PURCHASE'
      ? { items: [buyer, newBuyer], total: 2 }
      : { items: page === '1' ? [first] : [second], total: 11 },
  )
  const save = vi.fn(() => ({
    version: 3,
    policyChatConfigured: true,
    brands: [
      {
        ...oppo,
        purchaseReviewer: newBuyer,
        series: [{ ...oppo.series[0], productReviewer: second }, kSeries],
      },
    ],
  }))
  await open({
    [`GET ${settingsPath}`]: () => ({
      ...initialSettings,
      policyChatConfigured: false,
      brands: [{ ...oppo, series: [...oppo.series, kSeries] }],
    }),
    [`GET ${settingsPath}/candidates`]: candidates,
    [`PUT ${settingsPath}`]: save,
  })
  expect(wrapper.text()).toContain('FEISHU_POLICY_CHAT_ID')
  const productRows = wrapper.get('table tbody').findAll('tr')
  expect(productRows.map((row) => row.get('th[scope=row]').text())).toEqual(['Reno', 'K'])
  expect(productRows.map((row) => row.get('button').attributes('aria-label'))).toEqual([
    'OPPO手机 · Reno · 产品运营初审，指定审核人',
    'OPPO手机 · K · 产品运营初审，指定审核人',
  ])
  const product = await edit('OPPO手机 · Reno · 产品运营初审，指定审核人')
  expect(product.get('h2').text()).toBe('选择产品运营初审人')
  await product.get('[aria-label="姓名"]').setValue(' 林宁 ')
  await product.get('[aria-label="部门"]').setValue('运营')
  await product.get('form').trigger('submit')
  await flushPromises()
  expect(candidates).toHaveBeenLastCalledWith({
    name: '林宁',
    department: '运营',
    reviewGroup: 'PRODUCT',
    page: '1',
    pageSize: '10',
  })
  await product.get(`input[type=radio][value="${first.id}"]`).setValue(true)
  product.getComponent(ElPagination).vm.$emit('update:current-page', 2)
  await flushPromises()
  expect(product.get('[role=radiogroup]').text()).toContain('运营二部')
  await product.get(`input[type=radio][value="${second.id}"]`).setValue(true)
  product.getComponent(ElPagination).vm.$emit('update:current-page', 1)
  await flushPromises()
  expect(product.get(`input[type=radio][value="${first.id}"]`).element.checked).toBe(false)
  await click('确认选择', product)
  const purchase = await edit('OPPO手机 · 全部系列 · 采购终审，更换审核人')
  expect(purchase.get('h2').text()).toBe('选择采购终审人')
  expect(candidates).toHaveBeenLastCalledWith({
    reviewGroup: 'PURCHASE',
    page: '1',
    pageSize: '10',
  })
  await purchase.get(`input[type=radio][value="${newBuyer.id}"]`).setValue(true)
  await click('确认选择', purchase)
  expect(save).not.toHaveBeenCalled()
  await click('保存审核分工')
  expect(save).toHaveBeenCalledExactlyOnceWith({
    expectedVersion: 2,
    brands: [
      {
        brandId: '1',
        productMode: 'BY_SERIES',
        purchaseUserId: newBuyer.id,
        brandProductUserId: null,
        series: [{ seriesId: '6', productUserId: second.id }],
      },
    ],
  })
  const assignments = wrapper.get('table[aria-label="OPPO手机审核分工"]')
  expect(assignments.text()).toContain('采购乙')
  expect(assignments.text()).toContain('运营二部')
  expect(wrapper.text()).not.toContain('配置版本')
  expect(wrapper.text()).not.toContain('FEISHU_POLICY_CHAT_ID')
  expect(button(wrapper, '保存审核分工').element.disabled).toBe(true)
})

it('跨品牌逐系列更换初审人，一次保存保留其他系列的分工，新建系列待指定', async () => {
  const [ace, digital, turbo] = onePlus.series
  const newSeries = { seriesId: '34', seriesName: '新建系列', productReviewer: null }
  const save = vi.fn(() => ({
    ...initialSettings,
    version: 3,
    brands: [
      { ...oppo, series: [{ ...oppo.series[0], productReviewer: second }] },
      { ...onePlus, series: [{ ...ace, productReviewer: second }, digital, turbo, newSeries] },
    ],
  }))
  await open({
    [`GET ${settingsPath}`]: () => ({ ...initialSettings, brands: [oppo, onePlus] }),
    [`GET ${settingsPath}/candidates`]: () => ({ items: [first, second], total: 2 }),
    [`PUT ${settingsPath}`]: save,
  })
  const seriesEditor = await edit('OPPO手机 · Reno · 产品运营初审，指定审核人')
  await seriesEditor.get(`input[type=radio][value="${second.id}"]`).setValue(true)
  await click('确认选择', seriesEditor)
  await chooseBrand('一加手机')
  const brandTable = wrapper.get('table[aria-label="一加手机审核分工"]')
  expect(wrapper.find('[aria-label="一加手机初审分工"]').exists()).toBe(false)
  expect(brandTable.findAll('th[scope=row]').map((scope) => scope.text())).toEqual([
    'Ace',
    '数字系列',
    'Turbo',
    '全部系列',
  ])
  expect(brandTable.findAll('button').map((action) => action.attributes('aria-label'))).toEqual([
    '一加手机 · Ace · 产品运营初审，更换审核人',
    '一加手机 · 数字系列 · 产品运营初审，更换审核人',
    '一加手机 · Turbo · 产品运营初审，更换审核人',
    '一加手机 · 全部系列 · 采购终审，更换审核人',
  ])
  const aceEditor = await edit('一加手机 · Ace · 产品运营初审，更换审核人')
  await click('清除选择', aceEditor)
  await click('取消', aceEditor)

  const reopened = await edit('一加手机 · Ace · 产品运营初审，更换审核人')
  expect(reopened.get(`input[type=radio][value="${first.id}"]`).element.checked).toBe(true)
  await reopened.get(`input[type=radio][value="${second.id}"]`).setValue(true)
  await click('确认选择', reopened)
  await chooseBrand('OPPO手机')
  expect(
    wrapper.get('button[aria-label="OPPO手机 · Reno · 产品运营初审，更换审核人"]').isVisible(),
  ).toBe(true)
  expect(save).not.toHaveBeenCalled()

  await chooseBrand('一加手机')
  const draftRows = wrapper.get('table[aria-label="一加手机审核分工"] tbody').findAll('tr')
  expect(draftRows.map((row) => row.get('td small').text())).toEqual([
    second.department,
    first.department,
    second.department,
  ])
  await click('保存审核分工')
  expect(save).toHaveBeenCalledExactlyOnceWith({
    expectedVersion: 2,
    brands: [
      {
        brandId: oppo.brandId,
        productMode: 'BY_SERIES',
        purchaseUserId: buyer.id,
        brandProductUserId: null,
        series: [{ seriesId: '6', productUserId: second.id }],
      },
      {
        brandId: onePlus.brandId,
        productMode: 'BY_SERIES',
        purchaseUserId: newBuyer.id,
        brandProductUserId: null,
        series: [
          { seriesId: ace.seriesId, productUserId: second.id },
          { seriesId: digital.seriesId, productUserId: first.id },
          { seriesId: turbo.seriesId, productUserId: second.id },
        ],
      },
    ],
  })
  const savedStage = wrapper.get('table[aria-label="一加手机审核分工"] tbody')
  expect(savedStage.findAll('th[scope=row]').map((scope) => scope.text())).toEqual([
    'Ace',
    '数字系列',
    'Turbo',
    '新建系列',
  ])
  const newRow = savedStage.findAll('tr').at(-1)
  expect(newRow.get('td').text()).toBe('未指定')
  expect(newRow.get('button').attributes('aria-label')).toBe(
    '一加手机 · 新建系列 · 产品运营初审，指定审核人',
  )
  expect(button(wrapper, '保存审核分工').element.disabled).toBe(true)
})

it('一加尚无标准系列时只能指派品牌采购', async () => {
  await open({
    [`GET ${settingsPath}`]: () => ({ ...initialSettings, brands: [{ ...onePlus, series: [] }] }),
  })
  const table = wrapper.get('table[aria-label="一加手机审核分工"]')
  expect(table.get('tbody').text()).toContain('尚无标准系列')
  expect(table.findAll('button').map((action) => action.attributes('aria-label'))).toEqual([
    '一加手机 · 全部系列 · 采购终审，更换审核人',
  ])
  expect(button(wrapper, '保存审核分工').element.disabled).toBe(true)
})

it('同一采购分配到两个品牌时提示冲突，交换完两位采购后可保存', async () => {
  const save = vi.fn(() => ({
    ...initialSettings,
    version: 3,
    brands: [
      { ...oppo, purchaseReviewer: newBuyer },
      { ...onePlus, purchaseReviewer: buyer },
    ],
  }))
  await open({
    [`GET ${settingsPath}`]: () => ({ ...initialSettings, brands: [oppo, onePlus] }),
    [`GET ${settingsPath}/candidates`]: () => ({ items: [buyer, newBuyer], total: 2 }),
    [`PUT ${settingsPath}`]: save,
  })
  const firstPicker = await edit('OPPO手机 · 全部系列 · 采购终审，更换审核人')
  await firstPicker.get(`input[value="${newBuyer.id}"]`).setValue(true)
  await click('确认选择', firstPicker)
  expect(wrapper.text()).toContain(
    '采购乙只能负责一个品牌，请为「OPPO手机」和「一加手机」选择不同的采购。',
  )
  expect(button(wrapper, '保存审核分工').element.disabled).toBe(true)
  expect(save).not.toHaveBeenCalled()
  await chooseBrand('一加手机')
  const secondPicker = await edit('一加手机 · 全部系列 · 采购终审，更换审核人')
  await secondPicker.get(`input[value="${buyer.id}"]`).setValue(true)
  await click('确认选择', secondPicker)
  await click('保存审核分工')
  expect(save).toHaveBeenCalledOnce()
  expect(save.mock.calls[0][0].brands.map((brand) => brand.purchaseUserId)).toEqual([
    newBuyer.id,
    buyer.id,
  ])
})

it('其他目录品牌选择按品牌初审时仍可保存共用初审人', async () => {
  const brand = { ...onePlus, brandId: '3', brandName: '其他品牌' }
  const save = vi.fn(() => ({
    ...initialSettings,
    version: 3,
    brands: [{ ...brand, productMode: 'BY_BRAND', brandProductReviewer: second }],
  }))
  await open({
    [`GET ${settingsPath}`]: () => ({ ...initialSettings, brands: [brand] }),
    [`GET ${settingsPath}/candidates`]: () => ({ items: [second], total: 1 }),
    [`PUT ${settingsPath}`]: save,
  })
  await wrapper.get('input[type=radio][value="BY_BRAND"]').setValue(true)
  await flushPromises()
  expect(button(wrapper, '保存审核分工').element.disabled).toBe(true)
  const editor = await edit('其他品牌 · 全部系列 · 产品运营初审，指定审核人')
  await editor.get(`input[type=radio][value="${second.id}"]`).setValue(true)
  await click('确认选择', editor)
  await click('保存审核分工')
  expect(save).toHaveBeenCalledExactlyOnceWith({
    expectedVersion: 2,
    brands: [
      {
        brandId: brand.brandId,
        productMode: 'BY_BRAND',
        purchaseUserId: brand.purchaseReviewer.id,
        brandProductUserId: second.id,
        series: [],
      },
    ],
  })
})

it('默认名单版本冲突时保留输入，管理员核对前不能重复保存', async () => {
  const get = vi
    .fn()
    .mockReturnValueOnce(initialSettings)
    .mockReturnValue({ ...initialSettings, version: 3 })
  const save = vi.fn(() => Response.json({ message: '名单版本已变化' }, { status: 409 }))
  await open({
    [`GET ${settingsPath}`]: get,
    [`GET ${settingsPath}/candidates`]: () => ({ items: [buyer], total: 1 }),
    [`PUT ${settingsPath}`]: save,
  })
  const purchase = await edit('OPPO手机 · 全部系列 · 采购终审，更换审核人')
  await click('清除选择', purchase)
  await click('确认选择', purchase)
  await click('保存审核分工')
  expect(wrapper.text()).toContain('名单版本已变化')
  expect(wrapper.text()).not.toContain('采购甲')
  expect(button(wrapper, '保存审核分工').element.disabled).toBe(true)
  await click('载入最新名单并替换当前选择')
  expect(get).toHaveBeenCalledTimes(2)
  expect(button(wrapper, '保存审核分工').element.disabled).toBe(true)
  expect(wrapper.text()).not.toContain('配置版本')
  expect(wrapper.text()).toContain('采购甲')
  expect(save).toHaveBeenCalledTimes(1)
})
