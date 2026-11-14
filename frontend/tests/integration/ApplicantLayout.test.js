// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElDatePicker, ElSelect, ElUpload } from 'element-plus'
import ApplicantLayout from '../../src/layouts/ApplicantLayout.vue'
import { createWorkspaceRouter } from '../../src/router'
import { createMemoryHistory } from 'vue-router'
import { createAuthSession, authSession } from '../../src/composables/useAuthSession'
import { catalogCenterSamples } from '../../src/mocks/fixtures/catalog-center'
import { policyModelKey } from '../../src/utils/policy-form'

let wrapper

const active = catalogCenterSamples.filter(({ status }) => status === 'active')
const idFor = (value, values) => String(values.indexOf(value) + 1)
const unique = (values) => [...new Set(values)]
const brands = unique(active.map(({ brand }) => brand))
const series = unique(active.map(({ series }) => series))
const models = unique(active.map(policyModelKey))
const catalog = active.map((item, index) => {
  const [ram, rom] = item.config.split('+').map((value) => value + 'GB')
  return {
    goodsId: String(index + 1),
    brandId: idFor(item.brand, brands),
    brandName: item.brand,
    seriesId: idFor(item.series, series),
    seriesName: item.series,
    spuId: idFor(policyModelKey(item), models),
    modelName: item.model,
    edition: item.version,
    ram,
    rom,
    goodsName: item.name,
    colorName: item.color,
    onSale: true,
  }
})
const white = catalog.find(
  ({ modelName, colorName }) => modelName === 'Find X8' && colorName === '浮光白',
)
const whiteConfig = [white.spuId, white.ram, white.rom].join('/')
const distinct = (items) =>
  unique(items.map((item) => JSON.stringify(item))).map((item) => JSON.parse(item))
const options = {
  brands: distinct(catalog.map(({ brandId: id, brandName: name }) => ({ id, name }))),
  series: distinct(
    catalog.map(({ seriesId: id, seriesName: name, brandId }) => ({ id, name, brandId })),
  ),
  models: distinct(
    catalog.map(({ spuId: id, modelName: name, seriesId, edition }) => ({
      id,
      name,
      seriesId,
      edition,
    })),
  ),
  configurations: distinct(catalog.map(({ spuId, ram, rom }) => ({ spuId, ram, rom }))),
}
const reply = (body, status = 200) => ({ status, ok: status < 400, json: async () => body })
let rows
let draft
let savedStatus
let version
let fetchMock
let submitFailure
let auditRounds
let submissionVersion
beforeEach(() => {
  rows = catalog
  draft = null
  savedStatus = 'DRAFT'
  version = 0
  submitFailure = null
  auditRounds = []
  submissionVersion = 0
  fetchMock = vi.fn(async (path, init) => {
    const url = new URL(path, 'http://test.local')
    if (url.pathname === '/api/catalog/options') {
      return reply(options)
    }
    if (url.pathname === '/api/catalog/goods') {
      const filters = ['brandId', 'seriesId', 'spuId', 'ram', 'rom']
      const configurations = url.searchParams.getAll('configuration')
      const matches = rows.filter(
        (item) =>
          filters.every(
            (name) => !url.searchParams.has(name) || item[name] === url.searchParams.get(name),
          ) &&
          (!configurations.length ||
            configurations.includes([item.spuId, item.ram, item.rom].join('/'))) &&
          (!url.searchParams.has('keyword') ||
            item.colorName.includes(url.searchParams.get('keyword'))),
      )
      const size = Number(url.searchParams.get('size'))
      const start = (Number(url.searchParams.get('page')) - 1) * size
      return reply({ items: matches.slice(start, start + size), total: matches.length })
    }
    const record = () => ({
      id: '321',
      policyCode: 'QA-321',
      status: savedStatus,
      recordVersion: version,
      submissionVersion,
    })
    if (url.pathname === '/api/policies/mine') {
      const status = url.searchParams.get('status')
      const items =
        draft && (!status || status === savedStatus)
          ? [
              {
                ...record(),
                perspective: draft.perspective,
                selectedPlatforms: draft.selectedPlatforms,
                updatedAt: '2026-11-14T09:00:00+08:00',
              },
            ]
          : []
      return reply({ items, total: items.length, page: 1, pageSize: 20 })
    }
    if (url.pathname === '/api/policies/321/audit-logs') {
      return reply({
        items: auditRounds.map(({ log }) => log),
        total: auditRounds.length,
        page: 1,
        pageSize: 20,
      })
    }
    if (url.pathname.startsWith('/api/policies/321/audit-logs/')) {
      return reply(auditRounds.find(({ log }) => url.pathname.endsWith('/' + log.id)))
    }
    if (url.pathname === '/api/policies/drafts' || url.pathname === '/api/policies/321/draft') {
      draft = JSON.parse(init.body)
      version++
      return reply(record(), init.method === 'POST' ? 201 : 200)
    }
    if (url.pathname === '/api/policies/321/submit') {
      if (submitFailure) {
        return reply({ code: 'POLICY_VALIDATION_FAILED', message: submitFailure }, 400)
      }
      savedStatus = 'PENDING'
      version++
      submissionVersion++
      return reply(record())
    }
    if (url.pathname === '/api/policies/321') {
      return reply({ ...record(), form: draft, goods: rows })
    }
    throw new Error(`意外请求 ${path}`)
  })
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.unstubAllGlobals()
})

function button(text) {
  const result = wrapper.findAll('button').find((item) => item.text() === text)
  if (!result) {
    throw new Error(`找不到按钮：${text}`)
  }
  return result
}
async function selectPlatform(name) {
  await wrapper
    .findAll('.el-check-tag')
    .find((item) => item.text() === name)
    .trigger('click')
  await flushPromises()
}

async function addModel() {
  await button('选择手机型号').trigger('click')
  await flushPromises()
  document.querySelector('.policy-picker-row input:not(:disabled)').click()
  await flushPromises()
  document.querySelector('.policy-picker-footer button.el-button--primary').click()
  await flushPromises()
}

async function chooseGoodsFilter(label, value) {
  const picker = wrapper
    .findAllComponents(ElSelect)
    .find((select) => select.props('ariaLabel') === label)
  picker.vm.$emit('update:modelValue', value)
  await flushPromises()
}

async function render(path = '/apply') {
  const router = createWorkspaceRouter(createMemoryHistory(), createAuthSession())
  await router.push(path)
  await router.isReady()
  wrapper = mount(ApplicantLayout, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  return router
}

describe('申请人工作台组件协作', () => {
  it('驳回记录支持查看旧快照，并以最新版本重提原单', async () => {
    draft = {
      perspective: 'RETAIL',
      selectedPlatforms: ['JD'],
      verifyType: 'SALES',
      sceneType: 'NATIONAL_SUB',
      estimated: false,
      startTime: null,
      endTime: null,
      specialRemark: '当前可编辑说明',
      attachmentInfo: [],
      goodsForm: { cards: [] },
    }
    savedStatus = 'REJECTED'
    submissionVersion = 1
    version = 3
    auditRounds = [
      {
        log: {
          id: '9007199254740993',
          submissionVersion: 1,
          auditStatus: 'REJECTED',
          auditorName: '采购审核人',
          auditComment: '请核对活动说明',
          submittedAt: '2026-11-14T09:00:00+08:00',
          auditTime: '2026-11-14T11:20:30+08:00',
        },
        snapshot: {
          applicantName: '申请人',
          form: { ...draft, specialRemark: '当时的说明' },
          goods: [],
        },
      },
    ]
    const router = await render('/apply/submissions')
    await vi.waitFor(() => expect(wrapper.text()).toContain('QA-321'))
    await button('详情').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前可编辑说明')
    await wrapper.get('[role="tab"][id$="history"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('请核对活动说明')
    await button('查看快照').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('第 1 轮提交快照')
    expect(wrapper.text()).toContain('当时的说明')
    expect(fetchMock.mock.calls.some(([url]) => url.endsWith('/9007199254740993'))).toBe(true)
    await button('继续编辑').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('.policy-form').exists()).toBe(true))
    expect(router.currentRoute.value.query.policyId).toBe('321')
    expect(wrapper.text()).toContain('已驳回')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('申请已提交，等待审核')
    expect(fetchMock.mock.calls.find(([url]) => url.endsWith('/321/submit'))[1].body).toBe(
      '{"recordVersion":4}',
    )
    expect(wrapper.text()).not.toContain('提交申请')
    await button('我的提交').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('.submissions-panel').exists()).toBe(true))
    await vi.waitFor(() => expect(wrapper.text()).toContain('待审核'))
    expect(wrapper.text()).not.toContain('继续编辑')
  })

  it('打开另一份草稿前确认，取消后菜单返回仍保留未保存内容', async () => {
    draft = {
      perspective: 'BILLION',
      selectedPlatforms: ['PDD'],
      specialRemark: '草稿备注',
      attachmentInfo: [],
      goodsForm: { cards: [] },
    }
    version = 1
    const router = await render()
    await selectPlatform('京东')
    await button('我的提交').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('QA-321'))
    await button('继续编辑').trigger('click')
    await flushPromises()
    const cancel = [...document.querySelectorAll('.el-message-box button')].findLast((item) =>
      item.textContent.includes('保留内容'),
    )
    cancel.click()
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('submissions')
    await button('提交政策').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('.policy-form').exists()).toBe(true))
    expect(
      wrapper
        .findAll('.el-check-tag')
        .find((item) => item.text() === '京东')
        .classes(),
    ).toContain('is-checked')
    await button('我的提交').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('QA-321'))
    await button('继续编辑').trigger('click')
    await flushPromises()
    const confirm = [...document.querySelectorAll('.el-message-box button')].findLast((item) =>
      item.textContent.includes('确认清除'),
    )
    confirm.click()
    await vi.waitFor(() => expect(wrapper.text()).toContain('申请单号 QA-321'))
    expect(router.currentRoute.value.query.policyId).toBe('321')
    expect(wrapper.findAll('.el-check-tag').map((item) => item.text())).toEqual([
      '拼多多',
      '淘宝',
      '联通',
    ])
  })

  it('跨页默认勾选全部在售商品，取消项在翻页、搜索和正式提交中保持取消', async () => {
    const black = { ...white, goodsId: '999', colorName: '星耀黑' }
    const otherColors = Array.from({ length: 100 }, (_, index) => ({
      ...white,
      goodsId: String(100 + index),
      colorName: `测试颜色${index}`,
    }))
    rows = [white, ...otherColors, black]
    const router = await render()
    await selectPlatform('京东')
    await button('选择手机型号').trigger('click')
    await flushPromises()
    const drawer = document.querySelector('.policy-goods-picker')
    const rowFor = (color) =>
      [...drawer.querySelectorAll('.policy-picker-row')].find((row) =>
        row.textContent.includes(color),
      )
    await chooseGoodsFilter('商品品牌', white.brandId)
    await chooseGoodsFilter('商品系列', white.seriesId)
    await chooseGoodsFilter('商品机型', white.spuId)
    await chooseGoodsFilter('商品配置', [whiteConfig])
    expect(drawer.querySelector('.policy-picker-footer').textContent).toContain('已选 102 件')
    expect(fetchMock.mock.calls.some(([url]) => url.includes('page=2&size=100'))).toBe(true)
    expect(rowFor('浮光白').querySelector('input').checked).toBe(true)
    rowFor('浮光白').querySelector('input').click()
    await flushPromises()

    const jump = async (page) => {
      const choice =
        drawer.querySelector(`.el-pager [aria-label="第 ${page} 页"]`) ??
        [...drawer.querySelectorAll('.el-pager li')].find(
          (item) => item.textContent.trim() === String(page),
        )
      choice.click()
      await flushPromises()
    }
    await jump(6)
    expect(rowFor('星耀黑').querySelector('input').checked).toBe(true)
    await jump(1)
    expect(rowFor('浮光白').querySelector('input').checked).toBe(false)
    const search = drawer.querySelector('[aria-label="商品关键词"]')
    search.value = '浮光白'
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    await flushPromises()
    expect(rowFor('浮光白').querySelector('input').checked).toBe(false)
    drawer.querySelector('.policy-picker-footer .el-button--primary').click()
    await flushPromises()
    expect(wrapper.findAll('.policy-model-card')).toHaveLength(1)
    expect(wrapper.text()).toContain('星耀黑')
    expect(wrapper.text()).not.toContain('浮光白')
    expect(wrapper.find('[aria-label="京东 数量"]').exists()).toBe(false)
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.policyId).toBe('321')
    expect(draft.goodsForm.cards[0].targets.map(({ goodsId }) => goodsId)).toEqual(
      [...otherColors, black].map(({ goodsId }) => goodsId),
    )
    expect(draft.goodsForm.cards[0].targets[0]).toMatchObject({
      spuId: white.spuId,
      ram: '16GB',
      rom: '512GB',
    })

    wrapper.unmount()
    wrapper = null
    await render('/apply?policyId=321')
    expect(wrapper.text()).toContain('星耀黑')
    expect(wrapper.text()).not.toContain('浮光白')
    const picker = wrapper.findComponent(ElDatePicker)
    picker.vm.$emit('update:modelValue', ['2026-11-10T09:00:00', '2026-11-20T18:00:00'])
    await wrapper.get('[aria-label="方案 1 补贴金额"]').setValue('0.10')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('申请已提交，等待审核')
    expect(fetchMock.mock.calls.find(([url]) => url.endsWith('/321/submit'))[1].body).toBe(
      '{"recordVersion":2}',
    )
    expect(draft.goodsForm.cards[0].targets.some(({ goodsId }) => goodsId === white.goodsId)).toBe(
      false,
    )
  })

  it('配置可多选或全选，全部配置遵循机型范围并保留手动取消的商品', async () => {
    await render()
    await selectPlatform('京东')
    await button('选择手机型号').trigger('click')
    await flushPromises()
    const drawer = document.querySelector('.policy-goods-picker')
    const another = catalog.find(
      (item) => item.spuId === white.spuId && (item.ram !== white.ram || item.rom !== white.rom),
    )
    const otherModel = catalog.find((item) => item.spuId !== white.spuId)
    const anotherConfig = [another.spuId, another.ram, another.rom].join('/')
    const otherConfig = [otherModel.spuId, otherModel.ram, otherModel.rom].join('/')
    await chooseGoodsFilter('商品配置', [whiteConfig, otherConfig])
    expect(drawer.querySelectorAll('.policy-picker-row')).toHaveLength(2)
    expect(drawer.textContent).toContain(otherModel.modelName)
    expect(drawer.textContent).not.toContain(another.colorName)
    expect(drawer.querySelector('.policy-picker-footer').textContent).toContain('已选 2 件')
    await chooseGoodsFilter('商品配置', [whiteConfig])
    expect(drawer.querySelector('.policy-picker-footer').textContent).toContain('已选 1 件')
    await chooseGoodsFilter('商品机型', white.spuId)
    drawer.querySelector('.policy-picker-row input').click()
    await flushPromises()
    await chooseGoodsFilter('商品配置', [whiteConfig, 'ALL_CONFIGS'])
    expect(drawer.querySelectorAll('.policy-picker-row')).toHaveLength(2)
    expect(drawer.textContent).not.toContain(otherModel.modelName)
    expect(drawer.querySelector('.policy-picker-footer').textContent).toContain('已选 1 件')
    expect(
      [...drawer.querySelectorAll('.policy-picker-row input')].map((item) => item.checked),
    ).toEqual([false, true])
    await chooseGoodsFilter('商品配置', ['ALL_CONFIGS', anotherConfig])
    expect(drawer.querySelectorAll('.policy-picker-row')).toHaveLength(1)
    expect(drawer.textContent).toContain(another.colorName)
    expect(drawer.querySelector('.policy-picker-row input').checked).toBe(true)
    drawer.querySelector('.policy-picker-footer .el-button--primary').click()
    await flushPromises()
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(draft.goodsForm.cards[0].targets.map(({ goodsId }) => goodsId)).toEqual([
      another.goodsId,
    ])
  })

  it('只有指定适用机型可填写数量，支持填写和留空', async () => {
    await render()
    await selectPlatform('京东')
    await addModel()
    await button('指定适用机型').trigger('click')
    await flushPromises()
    const drawer = document.querySelector('.policy-goods-picker')
    await chooseGoodsFilter('商品机型', white.spuId)
    await chooseGoodsFilter('商品配置', ['ALL_CONFIGS'])
    const rows = drawer.querySelectorAll('.policy-picker-row input[type="radio"]')
    expect(rows).toHaveLength(2)
    expect(drawer.querySelector('.policy-picker-row').textContent).toContain(
      'OPPO / Find 系列 / Find X8',
    )
    expect(drawer.querySelector('.policy-picker-row').textContent).toContain('16GB+512GB · 浮光白')
    rows[0].click()
    rows[1].click()
    rows[0].click()
    await flushPromises()
    expect(drawer.querySelectorAll('input[type="radio"]:checked')).toHaveLength(1)
    drawer.querySelector('.policy-picker-footer .el-button--primary').click()
    await flushPromises()
    const [standard, special] = wrapper.findAll('.policy-model-card')
    expect(special.text()).toContain('浮光白')
    expect(special.text()).not.toContain('标准 SKU')
    expect(standard.find('[aria-label="京东 数量"]').exists()).toBe(false)
    const quantity = special.get('[aria-label="京东 数量"]')
    expect(quantity.element.value).toBe('')
    expect(quantity.attributes('required')).toBeUndefined()
    await quantity.setValue('9')
    expect(quantity.element.value).toBe('9')
    await quantity.setValue('')
    expect(quantity.element.value).toBe('')
  })

  it('切换菜单后保留填写内容，平台独立回显，切换补贴方式清理残值', async () => {
    await render()
    await selectPlatform('京东')
    await addModel()
    await wrapper.get('[aria-label="方案 1 补贴金额"]').setValue('0.10')
    await button('我的提交').trigger('click')
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.find('.policy-form').exists()).toBe(false))
    await button('提交政策').trigger('click')
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.find('.policy-form').exists()).toBe(true))
    expect(wrapper.text()).toContain('OPPO / Reno 系列 / Reno12')
    await selectPlatform('拼多多')
    const platformOptions = wrapper.get('[aria-label="适用平台"]')
    expect(platformOptions.isVisible()).toBe(false)
    expect(wrapper.get('.policy-applicable-platforms').text()).toContain('拼多多')
    await button('调整平台').trigger('click')
    expect(platformOptions.isVisible()).toBe(true)
    await platformOptions.get('input[value="PDD"]').setValue(false)
    expect(wrapper.get('.policy-applicable-platforms').text()).toBe('京东')
    await selectPlatform('抖音')
    expect(wrapper.get('.policy-applicable-platforms').text()).toBe('京东')
    await button('恢复整单平台').trigger('click')
    expect(platformOptions.isVisible()).toBe(false)
    expect(wrapper.get('.policy-applicable-platforms').text()).toContain('抖音')
    await button('＋ 添加方案').trigger('click')
    expect(wrapper.find('[aria-label="京东 补贴方案"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="拼多多 补贴方案"]').exists()).toBe(true)
    expect(wrapper.findAll('.policy-subsidy-platforms .el-tag').map((item) => item.text())).toEqual(
      ['拼多多', '京东', '抖音'],
    )
    expect(wrapper.get('[aria-label="方案 1 补贴金额"]').element.value).toBe('0.10')
    wrapper
      .findAllComponents(ElSelect)
      .find((item) => item.find('input[aria-label="方案 1 补贴方式"]').exists())
      .vm.$emit('update:modelValue', 'RATIO')
    await flushPromises()
    expect(wrapper.find('[aria-label="方案 1 补贴金额"]').exists()).toBe(false)
    wrapper
      .findAllComponents(ElSelect)
      .find((item) => item.find('input[aria-label="方案 1 补贴方式"]').exists())
      .vm.$emit('update:modelValue', 'FIXED')
    await flushPromises()
    expect(wrapper.get('[aria-label="方案 1 补贴金额"]').element.value).toBe('')
  })

  it('同一表单切换百亿后仅显示百亿平台与公共字段', async () => {
    await render()
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'BILLION')
    await flushPromises()
    expect(wrapper.findAll('.el-check-tag').map((item) => item.text())).toEqual([
      '拼多多',
      '淘宝',
      '联通',
    ])
    expect(wrapper.findAll('.el-radio-group')).toHaveLength(0)
    expect(wrapper.findAll('.policy-form')).toHaveLength(1)
  })

  it('取消渠道清除时保留内容，确认后切换渠道并清空明细', async () => {
    await render()
    await selectPlatform('京东')
    await addModel()
    await wrapper.get('[aria-label="方案 1 补贴金额"]').setValue('0')
    const channel = wrapper.findComponent(ElSelect)
    channel.vm.$emit('update:modelValue', 'BILLION')
    await flushPromises()
    const cancel = [...document.querySelectorAll('.el-message-box button')].find((item) =>
      item.textContent.includes('保留内容'),
    )
    cancel.click()
    await flushPromises()
    expect(channel.props('modelValue')).toBe('RETAIL')
    expect(wrapper.get('[aria-label="方案 1 补贴金额"]').element.value).toBe('0')
    channel.vm.$emit('update:modelValue', 'BILLION')
    await flushPromises()
    const confirm = [...document.querySelectorAll('.el-message-box button')].findLast((item) =>
      item.textContent.includes('确认清除'),
    )
    confirm.click()
    await flushPromises()
    expect(channel.props('modelValue')).toBe('BILLION')
    expect(wrapper.findAll('.el-radio-group')).toHaveLength(0)
    expect(wrapper.find('.policy-model-card').exists()).toBe(false)
  })

  it('日期精确到秒且可清空，附件仅暂存并能移除，不伪报提交成功', async () => {
    await render()
    const picker = wrapper.findComponent(ElDatePicker)
    const range = ['2026-11-11T09:10:11', '2026-11-12T23:59:58']
    picker.vm.$emit('update:modelValue', range)
    await flushPromises()
    expect(picker.props('modelValue')).toEqual(range)
    picker.vm.$emit('update:modelValue', null)
    await flushPromises()
    expect(picker.props('modelValue')).toEqual([])
    const upload = wrapper.findComponent(ElUpload)
    const file = new File(['测试附件'], '说明.txt', { type: 'text/plain' })
    file.uid = 123
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()
    expect(upload.props('autoUpload')).toBe(false)
    expect(wrapper.text()).toContain('说明.txt')
    await wrapper.get('.el-upload-list__item .el-icon--close').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('说明.txt')
    submitFailure = '请选择整单平台。'
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('草稿已保存。提交失败，请选择整单平台。')
    expect(wrapper.text()).not.toContain('申请已提交')
    expect(draft.attachmentInfo).toEqual([])
  })
  it('身份变化或退出清除已填数据和暂存附件，同一用户刷新身份保持表单', async () => {
    authSession.setLogin({ user: { id: 'owner', tenantKey: 'tenant' } })
    await render()
    await selectPlatform('京东')
    await addModel()
    const file = new File(['附件'], '本人附件.txt')
    file.uid = 987
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()
    expect(wrapper.text()).toContain('本人附件.txt')
    authSession.setLogin({ user: { id: 'owner', tenantKey: 'tenant', name: '姓名刷新' } })
    await flushPromises()
    expect(wrapper.text()).toContain('OPPO / Reno 系列 / Reno12')
    authSession.setLogin({ user: { id: 'another', tenantKey: 'tenant' } })
    await flushPromises()
    expect(wrapper.find('.policy-model-card').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('本人附件.txt')
    await selectPlatform('京东')
    await addModel()
    authSession.invalidate()
    await flushPromises()
    expect(wrapper.find('.policy-model-card').exists()).toBe(false)
  })
})
