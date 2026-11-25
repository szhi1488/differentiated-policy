// @vitest-environment jsdom
import { RETAIL_PLATFORMS, BILLION_PLATFORMS } from '../../src/constants/policy'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import {
  ElDatePicker,
  ElForm,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElSelect,
  ElUpload,
} from 'element-plus'
import ApplicantLayout from '../../src/layouts/ApplicantLayout.vue'
import { createWorkspaceRouter } from '../../src/router'
import { createMemoryHistory } from 'vue-router'
import { createAuthSession, authSession } from '../../src/composables/useAuthSession'
import { catalogCenterSamples } from '../fixtures/catalog-center'
import { direct, endOption, matchedPreview } from '../fixtures/policy-applicability'
import { createForm } from '../../src/utils/policy-form'

const retail = RETAIL_PLATFORMS.map(([platformCode]) => ({ perspective: 'RETAIL', platformCode }))
const crossChannel = [
  { perspective: 'RETAIL', platformCode: 'JD' },
  ...BILLION_PLATFORMS.map(([platformCode]) => ({ perspective: 'BILLION', platformCode })),
]

let permitted
let wrapper

const active = catalogCenterSamples.filter(({ status }) => status === 'active')
const idFor = (value, values) => String(values.indexOf(value) + 1)
const unique = (values) => [...new Set(values)]
const brands = unique(active.map(({ brand }) => brand))
const series = unique(active.map(({ series }) => series))
const catalog = active.map((item, index) => {
  const [ram, rom] = item.config.split('+').map((value) => value + 'GB')
  return {
    goodsId: String(index + 1),
    brandId: idFor(item.brand, brands),
    brandName: item.brand,
    seriesId: idFor(item.series, series),
    seriesName: item.series,
    spuId: item.spuId,
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
let applicability
let submitFailure
let submitResponseMode
let auditRounds
let submissionVersion
let uploadFailure
let rejectedAdjustments
beforeEach(() => {
  permitted = retail
  rows = catalog
  draft = null
  savedStatus = 'DRAFT'
  version = 0
  applicability = direct
  submitFailure = null
  submitResponseMode = null
  auditRounds = []
  submissionVersion = 0
  uploadFailure = null
  rejectedAdjustments = []
  fetchMock = vi.fn(async (path, init) => {
    const url = new URL(path, 'http://test.local')
    if (url.pathname === '/api/policies/platforms') {
      return reply(permitted)
    }
    if (url.pathname === '/api/attachments' && init.method === 'POST') {
      if (uploadFailure) {
        return reply({ message: uploadFailure }, 503)
      }
      const file = init.body.get('file')
      return reply({ id: `file-${file.name}`, originalName: file.name }, 201)
    }
    if (url.pathname.startsWith('/api/attachments/') && init.method === 'DELETE') {
      return reply(null, 204)
    }
    if (
      url.pathname === '/api/catalog/options' ||
      url.pathname === '/api/catalog/subsidy-options'
    ) {
      return reply(options)
    }
    if (url.pathname === '/api/policies/subsidy-timeline') {
      return reply({ asOf: '2026-11-23T12:00:00+08:00', configurationGoodsCount: 0, tracks: [] })
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
    if (url.pathname === '/api/policies/321/draft' && init.method === 'DELETE') {
      if (!draft) {
        return reply({ code: 'POLICY_NOT_FOUND', message: '未找到此申请。' }, 404)
      }
      if (
        savedStatus !== 'DRAFT' ||
        submissionVersion !== 0 ||
        Number(url.searchParams.get('recordVersion')) !== version
      ) {
        return reply(
          { code: 'POLICY_VERSION_CONFLICT', message: '草稿已变更或已提交，请刷新列表后重试。' },
          409,
        )
      }
      draft = null
      return reply(null, 204)
    }
    if (url.pathname === '/api/policies/drafts' || url.pathname === '/api/policies/321/draft') {
      draft = JSON.parse(init.body)
      version++
      return reply(record(), init.method === 'POST' ? 201 : 200)
    }
    if (url.pathname === '/api/policies/321/submission-preview') {
      if (submitFailure) {
        return reply({ code: 'POLICY_VALIDATION_FAILED', message: submitFailure }, 400)
      }
      if (init.method === 'POST') {
        expect(JSON.parse(init.body).recordVersion).toBe(version)
        return reply({
          snapshot: { form: draft, goods: rows },
          applicability,
          confirmationToken: 'adjusted-' + version,
        })
      }
      expect(Number(url.searchParams.get('recordVersion'))).toBe(version)
      return reply({
        snapshot: { form: draft, goods: rows },
        applicability,
        confirmationToken: 'scope-' + version,
      })
    }
    if (url.pathname === '/api/policies/321/submit') {
      if (submitFailure) {
        return reply({ code: 'POLICY_VALIDATION_FAILED', message: submitFailure }, 400)
      }
      if (submitResponseMode === 'no-longer-matched') {
        submitResponseMode = null
        applicability = { ...direct, adjustmentOptions: [endOption] }
        return reply({ code: 'POLICY_COVERAGE_CHANGED', message: '交叉范围变化' }, 409)
      }
      if (submitResponseMode === 'changed') {
        submitResponseMode = null
        applicability = matchedPreview(false, '238')
        const code = JSON.parse(init.body).decisions
          ? 'POLICY_COVERAGE_CHANGED'
          : 'POLICY_CONFIRMATION_REQUIRED'
        return reply({ code, message: '覆盖范围已变化' }, 409)
      }
      if (submitResponseMode === 'unknown') {
        return reply({ message: '服务暂时不可用' }, 503)
      }
      savedStatus = 'PENDING'
      version++
      submissionVersion++
      if (submitResponseMode === 'confirmed') {
        return reply({ message: '提交响应中断' }, 503)
      }
      return reply(record())
    }
    if (url.pathname === '/api/policies/321') {
      if (!draft) {
        return reply({ code: 'POLICY_NOT_FOUND', message: '未找到此申请。' }, 404)
      }
      return reply({
        ...record(),
        form: draft,
        goods: rows,
        endAdjustments: savedStatus === 'REJECTED' ? rejectedAdjustments : [],
      })
    }
    throw new Error(`意外请求 ${path}`)
  })
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  ElMessage.closeAll()
  ElMessageBox.close()
  ElMessage.success.mockRestore?.()
  ElMessageBox.confirm.mockRestore?.()
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
async function confirmFormSubmission() {
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

async function selectPlatform(name) {
  await wrapper
    .findAll('.el-check-tag')
    .find((item) => item.text() === name)
    .trigger('click')
  await flushPromises()
}

async function openGoodsPicker(specified = false) {
  await button('选择适用商品').trigger('click')
  await flushPromises()
  if (specified) {
    const label = [...document.querySelectorAll('.policy-goods-picker label')].find(
      (item) => item.textContent.trim() === '指定适用机型',
    )
    label.querySelector('input').click()
    await flushPromises()
  }
}

async function addModel() {
  await openGoodsPicker()
  const { spuId, ram, rom } = catalog[0]
  await chooseGoodsFilter('商品配置', [[spuId, ram, rom].join('/')])
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

async function openMenu(router, label) {
  const navigated = new Promise((resolve) => {
    const stop = router.afterEach(() => {
      stop()
      resolve()
    })
  })
  await button(label).trigger('click')
  await navigated
  await flushPromises()
}

describe('申请人工作台组件协作', () => {
  it('重试同轮修改任务保留输入与暂存附件，遇到新服务端版本仅在确认后替换', async () => {
    savedStatus = 'REJECTED'
    submissionVersion = 2
    draft = {
      perspective: 'RETAIL',
      selectedPlatforms: ['JD'],
      specialRemark: '已保存内容',
      attachmentInfo: [],
      goodsForm: { cards: [] },
    }
    const revision = '/apply?policyId=321&round=2'
    const router = await render(revision)
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const remark = () => wrapper.get('[aria-label="特别备注"]')
    await remark().setValue('本地修改')
    const upload = wrapper.get('input[type="file"]')
    Object.defineProperty(upload.element, 'files', { value: [new File(['proof'], '暂存证明.pdf')] })
    await upload.trigger('change')
    await flushPromises()
    await router.push('/apply/submissions')
    const handle = fetchMock.getMockImplementation()
    let unavailable = true
    fetchMock.mockImplementation((path, init) =>
      path === '/api/policies/321' && unavailable
        ? reply({ message: '暂时读不到本轮' }, 503)
        : handle(path, init),
    )
    await router.push(revision)
    await vi.waitFor(() => expect(wrapper.text()).toContain('暂时读不到本轮'))
    unavailable = false
    await button('重新核对本轮').trigger('click')
    await vi.waitFor(() => expect(remark().element.value).toBe('本地修改'))
    expect(wrapper.text()).toContain('暂存证明.pdf')
    expect(confirm).not.toHaveBeenCalled()

    await router.push('/apply?policyId=888&round=2')
    expect(router.currentRoute.value.fullPath).toBe(revision)
    expect(fetchMock.mock.calls.some(([path]) => path === '/api/policies/888')).toBe(false)
    expect(remark().element.value).toBe('本地修改')
    confirm.mockClear()

    await router.push('/apply/submissions')
    version++
    draft.specialRemark = '服务端最新内容'
    await router.push(revision)
    await vi.waitFor(() => expect(wrapper.text()).toContain('服务端版本需重新核对'))
    expect(confirm).not.toHaveBeenCalled()
    expect(remark().element.value).toBe('本地修改')
    expect(wrapper.text()).toContain('暂存证明.pdf')
    expect(button('保存草稿').element.disabled).toBe(true)
    const reads = () => fetchMock.mock.calls.filter(([path]) => path === '/api/policies/321').length
    const before = reads()
    await button('读取服务端版本').trigger('click')
    await vi.waitFor(() => expect(remark().element.value).toBe('本地修改'))
    expect(reads()).toBe(before)
    expect(wrapper.text()).toContain('暂存证明.pdf')
    confirm.mockResolvedValueOnce('confirm')
    await button('读取服务端版本').trigger('click')
    await vi.waitFor(() => expect(remark().element.value).toBe('服务端最新内容'))
    expect(reads()).toBe(before + 1)
    expect(wrapper.text()).not.toContain('暂存证明.pdf')
  })

  it('退回链接只打开本人当前驳回轮，取消替换保留输入且不读目标', async () => {
    const router = await render()
    await wrapper.get('[aria-label="特别备注"]').setValue('本地输入')
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel')
    await router.push('/apply?policyId=321&round=1')
    expect(router.currentRoute.value.fullPath).toBe('/apply')
    expect(wrapper.get('[aria-label="特别备注"]').element.value).toBe('本地输入')
    expect(fetchMock.mock.calls.some(([path]) => path === '/api/policies/321')).toBe(false)
    confirm.mockResolvedValue('confirm')
    savedStatus = 'REJECTED'
    submissionVersion = 2
    draft = {
      perspective: 'RETAIL',
      selectedPlatforms: ['JD'],
      specialRemark: '本轮可修改内容',
      attachmentInfo: [],
      goodsForm: { cards: [] },
    }
    await router.push('/apply?policyId=321&round=1')
    await vi.waitFor(() => expect(wrapper.text()).toContain('轮次已变更'))
    expect(wrapper.find('form').exists()).toBe(false)
    await router.push('/apply?policyId=321&round=2')
    await vi.waitFor(() =>
      expect(wrapper.get('[aria-label="特别备注"]').element.value).toBe('本轮可修改内容'),
    )
    savedStatus = 'PENDING'
    await router.push('/apply/submissions')
    await router.push('/apply?policyId=321&round=2')
    await vi.waitFor(() => expect(wrapper.text()).toContain('当前没有退回修改任务'))
    expect(wrapper.find('form').exists()).toBe(false)
    draft = null
    await button('重新核对本轮').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('未找到此申请'))
    expect(wrapper.find('form').exists()).toBe(false)
  })
  it(
    '从申请表查负责的渠道平台，返回保留草稿，再进入时按最新分工查询',
    { timeout: 15000 },
    async () => {
      permitted = [
        { perspective: 'RETAIL', platformCode: 'PDD' },
        { perspective: 'BILLION', platformCode: 'PDD' },
      ]
      const requests = () =>
        fetchMock.mock.calls.map(([path]) => new URL(path, 'http://test.local'))
      const queries = () =>
        requests().filter((url) => url.pathname === '/api/policies/subsidy-timeline')
      const platform = () =>
        wrapper.findAllComponents(ElSelect).find((select) => select.props('ariaLabel') === '平台')
      const router = await render()
      await wrapper.get('[aria-label="特别备注"]').setValue('查补贴后继续填写')
      await openMenu(router, '机型补贴查询')
      expect(queries()).toHaveLength(1)
      expect(wrapper.findAll('h1').map((el) => el.text())).toEqual(['机型补贴查询'])
      expect(
        platform()
          .findAllComponents(ElOption)
          .map((option) => option.props('label')),
      ).toEqual(expect.arrayContaining(['全部负责平台', '零售 · 拼多多', '百亿 · 拼多多']))
      expect(platform().findAllComponents(ElOption)).toHaveLength(3)
      platform().vm.$emit('update:modelValue', 'BILLION:PDD')
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(Object.fromEntries(queries().at(-1).searchParams)).toMatchObject({
        perspective: 'BILLION',
        platformCode: 'PDD',
      })
      await openMenu(router, '提交政策')
      expect(wrapper.get('[aria-label="特别备注"]').element.value).toBe('查补贴后继续填写')
      permitted = [{ perspective: 'RETAIL', platformCode: 'PDD' }]
      await openMenu(router, '机型补贴查询')
      expect(queries()).toHaveLength(3)
      expect(queries().at(-1).searchParams.has('perspective')).toBe(false)
      expect(
        platform()
          .findAllComponents(ElOption)
          .map((option) => option.props('label')),
      ).toEqual(['全部负责平台', '零售 · 拼多多'])
      await openMenu(router, '我的提交')
      permitted = []
      await openMenu(router, '机型补贴查询')
      expect(wrapper.text()).toContain('尚未分配负责平台')
      expect(queries()).toHaveLength(3)
      expect(wrapper.find('.model-subsidy-workspace').exists()).toBe(false)
      expect(requests().some((url) => url.pathname.startsWith('/api/admin/'))).toBe(false)
    },
  )

  it('只列出当前渠道分给本人的平台，移交后可保留内容并手动移除', async () => {
    permitted = [
      { perspective: 'RETAIL', platformCode: 'JD' },
      { perspective: 'BILLION', platformCode: 'TAOBAO' },
    ]
    await render()
    const options = () =>
      wrapper.findAll('.platform-options .el-check-tag').map((item) => item.text())
    expect(options()).toEqual(['京东'])
    await selectPlatform('京东')
    await wrapper.get('[aria-label="特别备注"]').setValue('移交后保留输入')
    permitted = [{ perspective: 'BILLION', platformCode: 'TAOBAO' }]
    window.dispatchEvent(new Event('focus'))
    await flushPromises()
    expect(options()).toEqual([])
    expect(button('提交申请').element.disabled).toBe(true)
    await button('移除已移交的京东').trigger('click')
    await flushPromises()
    expect(wrapper.get('[aria-label="特别备注"]').element.value).toBe('移交后保留输入')
    wrapper
      .findAllComponents(ElSelect)
      .find((select) => select.props('ariaLabel') === '渠道')
      .vm.$emit('update:modelValue', 'BILLION')
    await flushPromises()
    expect(options()).toEqual(['淘宝'])
  })

  it('政策提交分组可收起和展开，保留表单内容及菜单往返状态', async () => {
    const router = await render()
    await wrapper.get('[aria-label="特别备注"]').setValue('收起菜单后仍保留')
    const group = wrapper.get('button[aria-controls="applicant-policy-links"]')
    expect(group.attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('.nav-item span')).toHaveLength(0)
    await group.trigger('click')
    expect(group.attributes('aria-expanded')).toBe('false')
    expect(router.currentRoute.value.name).toBe('apply')
    expect(wrapper.get('[aria-label="特别备注"]').element.value).toBe('收起菜单后仍保留')
    await group.trigger('click')
    expect(group.attributes('aria-expanded')).toBe('true')
    await openMenu(router, '我的提交')
    expect(router.currentRoute.value.name).toBe('submissions')
    expect(button('我的提交').attributes('aria-current')).toBe('page')
    await openMenu(router, '提交政策')
    expect(router.currentRoute.value.name).toBe('apply')
    expect(wrapper.get('[aria-label="特别备注"]').element.value).toBe('收起菜单后仍保留')
  })

  it('提交普通申请时，没有交叉即使有可提前结束的旧单也直接送审', async () => {
    applicability = { ...direct, adjustmentOptions: [endOption] }
    await render()
    await selectPlatform('京东')
    await addModel()
    await confirmFormSubmission()
    expect(wrapper.find('.submission-coverage-dialog').exists()).toBe(false)
    const submissions = fetchMock.mock.calls.filter(([url]) => url.endsWith('/submit'))
    expect(submissions).toHaveLength(1)
    expect(JSON.parse(submissions[0][1].body)).toEqual({ recordVersion: 1 })
    expect(savedStatus).toBe('PENDING')
  })

  it.each([false, true])(
    '我的提交可直接批准单张和混合批量时间调整，处理延期交叉（%s）',
    async (mixed) => {
      const base = '/api/policies/time-adjustments'
      const extension = {
        ...endOption,
        target: { ...endOption.target, policyId: '519' },
        policyCode: 'ZC-000519',
        canExtend: true,
        canShorten: false,
      }
      const originalFetch = fetchMock.getMockImplementation()
      const requests = []
      fetchMock.mockImplementation(async (path, init) => {
        if (path === base + '/options') {
          return reply([endOption, extension])
        }
        if (path === base + '/preview') {
          return reply({
            applicability: mixed ? matchedPreview() : direct,
            confirmationToken: 'approval-token',
          })
        }
        if (path === base) {
          requests.push({ body: JSON.parse(init.body), key: init.headers['Idempotency-Key'] })
          return requests.length === 1
            ? reply({ message: '网络异常' }, 503)
            : reply(
                requests[0].body.endAdjustments.map(({ target }) => ({
                  id: target.policyId,
                  status: 'APPROVED',
                })),
              )
        }
        return originalFetch(path, init)
      })
      const router = await render('/apply')
      expect(wrapper.get('.form-actions').text()).not.toContain('调整政策时间')
      await router.push('/apply/submissions')
      await flushPromises()
      await wrapper.get('.submissions-filters .submissions-adjust').trigger('click')
      await flushPromises()
      const editor = wrapper.get('.policy-end-dialog')
      await editor.get('.end-options input[value="518"]').setValue(true)
      if (mixed) {
        await editor.get('.end-options input[value="519"]').setValue(true)
      }
      await button('添加所选政策').trigger('click')
      const cards = editor.findAll('.end-card')
      cards[0].getComponent(ElDatePicker).vm.$emit('update:modelValue', '2026-12-15T12:34:56')
      if (mixed) {
        cards[1].getComponent(ElDatePicker).vm.$emit('update:modelValue', '2097-01-01T00:00:01')
        await cards[1].get('textarea').setValue('活动延期')
      }
      await flushPromises()
      expect(button('核对调整').element.disabled).toBe(true)
      await cards[0].get('textarea').setValue('提前结束')
      await button('核对调整').trigger('click')
      await flushPromises()
      if (mixed) {
        expect(button('确认适用范围').element.disabled).toBe(true)
        await wrapper.get('.scope-segment input[value="118"]').setValue(true)
        await wrapper.get('.scope-segment input[value="119"]').setValue(true)
        await button('确认适用范围').trigger('click')
        await flushPromises()
      }
      expect(requests).toHaveLength(0)
      expect(wrapper.get('.policy-end-confirm-dialog').text()).toContain('将直接通过')
      await button('确认并直接通过').trigger('click')
      await flushPromises()
      expect(wrapper.get('.policy-end-confirm-dialog').text()).toContain('同一个提交编号')
      await button('确认并直接通过').trigger('click')
      await flushPromises()
      expect(requests).toHaveLength(2)
      expect(requests[0]).toEqual(requests[1])
      expect(requests[0].key).toMatch(/^[\da-f-]{36}$/)
      expect(requests[0].body.confirmationToken).toBe('approval-token')
      expect(requests[0].body.decisions).toHaveLength(mixed ? 2 : 0)
      const adjustments = [
        { target: endOption.target, endTime: '2026-12-15T12:34:56+08:00', reason: '提前结束' },
      ]
      if (mixed) {
        adjustments.push({
          target: extension.target,
          endTime: '2097-01-01T00:00:01+08:00',
          reason: '活动延期',
        })
      }
      expect(requests[0].body.endAdjustments).toEqual(adjustments)
      expect(wrapper.find('.policy-end-dialog').exists()).toBe(false)
      expect(router.currentRoute.value.fullPath).toBe('/apply/submissions')
      expect(
        fetchMock.mock.calls.some(([url]) => url.includes('/draft') || url.endsWith('/submit')),
      ).toBe(false)
    },
  )

  it('直接通过时旧单截止已变，重新读取候选并保留原因，重新核对后才能提交', async () => {
    const base = '/api/policies/time-adjustments'
    const updatedTarget = {
      ...endOption.target,
      recordVersion: 4,
      previousEnd: '2026-12-30T23:59:59+08:00',
    }
    let updated = false
    const originalFetch = fetchMock.getMockImplementation()
    const attempts = []
    fetchMock.mockImplementation(async (path, init) => {
      if (path === base + '/options') {
        return reply([updated ? { ...endOption, target: updatedTarget } : endOption])
      }
      if (path === base + '/preview') {
        return reply({ applicability: direct, confirmationToken: updated ? 'new' : 'old' })
      }
      if (path === base) {
        attempts.push(JSON.parse(init.body))
        if (!updated) {
          updated = true
          return reply({ message: '原单截止已变化', code: 'POLICY_ADJUSTMENT_CHANGED' }, 409)
        }
        return reply([{ id: endOption.target.policyId, status: 'APPROVED' }])
      }
      return originalFetch(path, init)
    })
    await render('/apply/submissions')
    await button('调整政策时间').trigger('click')
    await flushPromises()
    const editor = wrapper.get('.policy-end-dialog')
    await editor.get('.end-options input[value="518"]').setValue(true)
    await button('添加所选政策').trigger('click')
    editor.getComponent(ElDatePicker).vm.$emit('update:modelValue', '2026-12-15T12:34:56')
    await editor.get('textarea').setValue('活动结束')
    await button('核对调整').trigger('click')
    await flushPromises()
    await button('确认并直接通过').trigger('click')
    await flushPromises()
    expect(editor.text()).toContain('原单截止已变化')
    expect(editor.text()).toContain('2026-12-30 23:59:59')
    expect(editor.get('textarea').element.value).toBe('活动结束')
    expect(attempts).toHaveLength(1)
    await button('核对调整').trigger('click')
    await flushPromises()
    await button('确认并直接通过').trigger('click')
    await flushPromises()
    expect(attempts[1].endAdjustments[0].target).toEqual(updatedTarget)
    expect(attempts[1].confirmationToken).toBe('new')
    expect(wrapper.find('.policy-end-dialog').exists()).toBe(false)
  })

  it('负责平台移交后移除已失去资格的原单', async () => {
    const base = '/api/policies/time-adjustments'
    const successor = {
      ...endOption,
      policyCode: 'ZC-000519',
      target: { ...endOption.target, policyId: '519', auditLogId: '720' },
    }
    let replaced = false
    const originalFetch = fetchMock.getMockImplementation()
    fetchMock.mockImplementation(async (path, init) => {
      if (path === base + '/options') {
        return reply([replaced ? successor : endOption])
      }
      if (path === base + '/preview') {
        replaced = true
        return reply(
          { message: '所选政策的负责平台已变化', code: 'POLICY_ADJUSTMENT_CHANGED' },
          409,
        )
      }
      return originalFetch(path, init)
    })
    await render('/apply/submissions')
    await button('调整政策时间').trigger('click')
    await flushPromises()
    const editor = wrapper.get('.policy-end-dialog')
    await editor.get('.end-options input[value="518"]').setValue(true)
    await button('添加所选政策').trigger('click')
    editor.getComponent(ElDatePicker).vm.$emit('update:modelValue', '2026-12-15T12:34:56')
    await editor.get('textarea').setValue('缩短活动时间')
    await button('核对调整').trigger('click')
    await flushPromises()
    expect(editor.get('.end-card').text()).toContain('这张政策已不可调整')
    expect(button('核对调整').element.disabled).toBe(true)
    await button('移除调整').trigger('click')
    expect(editor.find('.end-options input[value="518"]').exists()).toBe(false)
    await editor.get('.end-options input[value="519"]').setValue(true)
    await button('添加所选政策').trigger('click')
    expect(editor.get('.end-card').text()).toContain('ZC-000519')
    expect(fetchMock.mock.calls.some(([path]) => path === base)).toBe(false)
  })

  it('返回编辑保留已保存草稿、商品及附件，直至逐段确认执行政策', async () => {
    applicability = matchedPreview()
    await render()
    await selectPlatform('京东')
    await addModel()
    await wrapper.get('[aria-label="特别备注"]').setValue('待重新核对')
    const input = wrapper.get('input[type=file]')
    Object.defineProperty(input.element, 'files', {
      value: [new File(['%PDF-1.7'], '审批资料.pdf')],
    })
    await input.trigger('change')
    await flushPromises()
    await confirmFormSubmission()
    expect(button('确认整单并提交').element.disabled).toBe(true)
    await button('返回编辑').trigger('click')
    await flushPromises()
    expect(savedStatus).toBe('DRAFT')
    expect(wrapper.get('[aria-label="特别备注"]').element.value).toBe('待重新核对')
    expect(wrapper.text()).toContain('审批资料.pdf')
    expect(wrapper.find('.policy-model-card').exists()).toBe(true)
    expect(fetchMock.mock.calls.some(([url]) => url.endsWith('/submit'))).toBe(false)
    await confirmFormSubmission()
    await wrapper.get('.scope-segment input[value="118"]').setValue(true)
    await wrapper.get('.scope-segment input[value="119"]').setValue(true)
    await button('确认整单并提交').trigger('click')
    expect(wrapper.text()).toContain('确认提交整单？')
    expect(fetchMock.mock.calls.filter(([url]) => url.endsWith('/submit'))).toHaveLength(0)
    await button('返回核对').trigger('click')
    expect(savedStatus).toBe('DRAFT')
    expect(wrapper.findAll('.scope-segment')).toHaveLength(2)
    expect(wrapper.get('.scope-segment input[value="118"]').element.checked).toBe(true)
    expect(wrapper.get('.scope-segment input[value="119"]').element.checked).toBe(true)
    expect(wrapper.get('.scope-footer').text()).toContain('2 / 2')
    expect(wrapper.text()).toContain('审批资料.pdf')
    expect(fetchMock.mock.calls.filter(([url]) => url.endsWith('/submit'))).toHaveLength(0)
    await button('确认整单并提交').trigger('click')
    const submit = button('确认提交')
    await Promise.all([submit.trigger('click'), submit.trigger('click')])
    await flushPromises()
    const requests = fetchMock.mock.calls.filter(([url]) => url.endsWith('/submit'))
    expect(requests).toHaveLength(1)
    const posted = JSON.parse(requests[0][1].body)
    expect(posted.confirmationToken).toBe('scope-2')
    expect(posted.decisions.map((d) => d.selectedPolicyIds)).toEqual([['118'], ['119']])
    expect(savedStatus).toBe('PENDING')
  })

  it.each([false, true])(
    '直提或确认后范围变化，重新展示全部时段并按最新选择送审（原先有命中 %s）',
    async (hit) => {
      if (hit) {
        applicability = matchedPreview()
      }
      await render()
      await selectPlatform('京东')
      await addModel()
      submitResponseMode = 'changed'
      await confirmFormSubmission()
      if (hit) {
        await wrapper.get('.scope-segment input[value="118"]').setValue(true)
        await wrapper.get('.scope-segment input[value="119"]').setValue(true)
        await button('确认整单并提交').trigger('click')
        await button('确认提交').trigger('click')
        await flushPromises()
      }
      expect(wrapper.text()).toContain('适用范围已变化')
      expect(wrapper.text()).toContain('ZC-000238')
      expect(button('确认整单并提交').element.disabled).toBe(true)
      expect(
        fetchMock.mock.calls.filter(([url]) => url.includes('/submission-preview?')),
      ).toHaveLength(2)
      expect(wrapper.findAll('.scope-segment')).toHaveLength(2)
      expect(wrapper.get('.scope-segment input[value="119"]').element.checked).toBe(false)
      expect(savedStatus).toBe('DRAFT')
      await wrapper.get('.scope-segment input[value="238"]').setValue(true)
      await wrapper.get('.scope-segment input[value="119"]').setValue(true)
      await button('确认整单并提交').trigger('click')
      await button('确认提交').trigger('click')
      await flushPromises()
      const submissions = fetchMock.mock.calls.filter(([url]) => url.endsWith('/submit'))
      expect(submissions).toHaveLength(2)
      const retried = JSON.parse(submissions[1][1].body)
      expect(retried.decisions.map((d) => d.selectedPolicyIds)).toEqual([['238'], ['119']])
      expect(savedStatus).toBe('PENDING')
    },
  )

  it('交叉段全部消失后按最新直提结果提交，不再携带旧决定', async () => {
    applicability = matchedPreview()
    await render()
    await selectPlatform('京东')
    await addModel()
    submitResponseMode = 'no-longer-matched'
    await confirmFormSubmission()
    await wrapper.get('.scope-segment input[value="118"]').setValue(true)
    await wrapper.get('.scope-segment input[value="119"]').setValue(true)
    await button('确认整单并提交').trigger('click')
    await button('确认提交').trigger('click')
    await flushPromises()
    const submits = fetchMock.mock.calls.filter(([url]) => url.endsWith('/submit'))
    expect(submits).toHaveLength(2)
    expect(JSON.parse(submits[1][1].body)).toEqual({ recordVersion: 1 })
    expect(savedStatus).toBe('PENDING')
  })

  it('过期或缺失新预览结果时保留草稿供修改', async () => {
    await render()
    await selectPlatform('京东')
    await addModel()
    for (const result of [
      { status: 'NO_EFFECTIVE_PERIOD', canDirectSubmit: false, scopes: [] },
      null,
    ]) {
      applicability = result
      await confirmFormSubmission()
      expect(savedStatus).toBe('DRAFT')
      expect(wrapper.find('.submission-coverage-dialog').exists()).toBe(false)
      expect(fetchMock.mock.calls.some(([url]) => url.endsWith('/submit'))).toBe(false)
    }
  })

  it.each([null, 'confirmed'])(
    '提交成功后返回空白新申请，清理附件缓存并保留服务端申请（响应模式 %s）',
    async (responseMode) => {
      const router = await render()
      await selectPlatform('京东')
      await addModel()
      await wrapper.get('[aria-label="特别备注"]').setValue('已提交备注')
      const input = wrapper.get('input[type="file"]')
      Object.defineProperty(input.element, 'files', {
        value: [new File(['%PDF-1.7'], '提交附件.pdf')],
        configurable: true,
      })
      await input.trigger('change')
      await flushPromises()
      expect(wrapper.text()).toContain('提交附件.pdf')
      submitResponseMode = responseMode
      await confirmFormSubmission()
      await flushPromises()
      await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/apply'))
      expect(wrapper.findComponent(ElForm).props('model')).toEqual(createForm())
      expect(wrapper.findComponent(ElForm).props('disabled')).toBe(false)
      expect(wrapper.find('.policy-record-summary').exists()).toBe(false)
      expect(wrapper.find('.policy-model-card').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('提交附件.pdf')
      expect(button('提交申请').attributes('disabled')).toBeUndefined()
      expect(document.body.textContent).toContain(
        responseMode ? '已核对，申请提交成功' : '申请已提交，等待审核',
      )
      expect(draft.specialRemark).toBe('已提交备注')
      expect(draft.attachmentInfo).toHaveLength(1)
      expect(draft.goodsForm.cards).toHaveLength(1)
      expect(savedStatus).toBe('PENDING')
      expect(fetchMock.mock.calls.filter(([url]) => url.endsWith('/321/submit'))).toHaveLength(1)
      expect(
        fetchMock.mock.calls.some(
          ([url, init]) => url.includes('/attachments/') && init.method === 'DELETE',
        ),
      ).toBe(false)
      await button('我的提交').trigger('click')
      await vi.waitFor(() => expect(wrapper.text()).toContain('待审核'))
      await button('提交政策').trigger('click')
      await flushPromises()
      expect(router.currentRoute.value.fullPath).toBe('/apply')
      expect(wrapper.findComponent(ElForm).props('model')).toEqual(createForm())
    },
  )

  it('提交结果未确认时保留申请地址及全部输入，不清空为新申请', async () => {
    const router = await render()
    await selectPlatform('京东')
    await addModel()
    await wrapper.get('[aria-label="特别备注"]').setValue('待核对内容')
    const previous = JSON.parse(JSON.stringify(wrapper.findComponent(ElForm).props('model')))
    submitResponseMode = 'unknown'
    await confirmFormSubmission()
    await flushPromises()
    expect(router.currentRoute.value.query.policyId).toBe('321')
    expect(wrapper.findComponent(ElForm).props('model')).toEqual(previous)
    expect(document.body.textContent).toContain('请求结果待核对。当前输入已保留')
    expect(wrapper.text()).toContain('QA-321')
    expect(button('提交申请').attributes('disabled')).toBeDefined()
  })

  it('删除本人草稿需确认，取消保留内容，成功后刷新并清除该草稿编辑缓存', async () => {
    await render()
    await wrapper.get('[aria-label="特别备注"]').setValue('即将删除的草稿')
    expect(wrapper.find('[aria-label="其他说明"]').exists()).toBe(false)
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(draft).not.toHaveProperty('otherExplanation')
    await button('我的提交').trigger('click')
    await flushPromises()
    await vi.waitFor(() =>
      expect(wrapper.find('[aria-label="删除草稿 QA-321"]').exists()).toBe(true),
    )
    await button('删除').trigger('click')
    await flushPromises()
    const cancel = [...document.querySelectorAll('.el-message-box button')].findLast(
      (item) => item.textContent.trim() === '取消',
    )
    cancel.click()
    await flushPromises()
    expect(draft.specialRemark).toBe('即将删除的草稿')
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(0)
    await button('删除').trigger('click')
    await flushPromises()
    const confirm = [...document.querySelectorAll('.el-message-box button')].findLast(
      (item) => item.textContent.trim() === '删除草稿',
    )
    confirm.click()
    confirm.click()
    await flushPromises()
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(1)
    expect(fetchMock.mock.calls.find(([, init]) => init?.method === 'DELETE')[0]).toBe(
      '/api/policies/321/draft?recordVersion=1',
    )
    expect(draft).toBeNull()
    expect(wrapper.text()).toContain('没有符合条件的申请')
    expect(document.body.textContent).toContain('草稿已删除')
    await button('提交政策').trigger('click')
    await flushPromises()
    expect(wrapper.get('[aria-label="特别备注"]').element.value).toBe('')
    expect(wrapper.text()).not.toContain('申请单号 QA-321')
  })

  it('确认期间草稿已提交时删除失败，刷新后隐藏删除入口并保留编辑缓存', async () => {
    await render()
    await wrapper.get('[aria-label="特别备注"]').setValue('保留未保存内容')
    await button('保存草稿').trigger('click')
    await flushPromises()
    await button('我的提交').trigger('click')
    await flushPromises()
    await vi.waitFor(() =>
      expect(wrapper.find('[aria-label="删除草稿 QA-321"]').exists()).toBe(true),
    )
    await button('删除').trigger('click')
    await flushPromises()
    savedStatus = 'PENDING'
    submissionVersion = 1
    version++
    const confirm = [...document.querySelectorAll('.el-message-box button')].findLast(
      (item) => item.textContent.trim() === '删除草稿',
    )
    confirm.click()
    await flushPromises()
    expect(draft).not.toBeNull()
    expect(document.body.textContent).toContain('草稿已变更或已提交')
    expect(wrapper.find('[aria-label="删除草稿 QA-321"]').exists()).toBe(false)
    await button('提交政策').trigger('click')
    await flushPromises()
    expect(wrapper.get('[aria-label="特别备注"]').element.value).toBe('保留未保存内容')
  })

  it('删除响应丢失后查询确认草稿不存在，清除缓存且不重放删除请求', async () => {
    await render()
    await button('保存草稿').trigger('click')
    await flushPromises()
    await button('我的提交').trigger('click')
    await flushPromises()
    await vi.waitFor(() =>
      expect(wrapper.find('[aria-label="删除草稿 QA-321"]').exists()).toBe(true),
    )
    const normal = fetchMock.getMockImplementation()
    fetchMock.mockImplementation((path, init) => {
      if (init?.method === 'DELETE') {
        draft = null
        return reply({ message: '响应丢失' }, 503)
      }
      return normal(path, init)
    })
    await button('删除').trigger('click')
    await flushPromises()
    const confirm = [...document.querySelectorAll('.el-message-box button')].findLast(
      (item) => item.textContent.trim() === '删除草稿',
    )
    confirm.click()
    await flushPromises()
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(1)
    expect(wrapper.text()).toContain('没有符合条件的申请')
    expect(document.body.textContent).toContain('草稿已删除')
  })

  it('版本冲突弹出错误后仍可读取服务端版本恢复编辑', async () => {
    await render()
    await button('保存草稿').trigger('click')
    await flushPromises()
    fetchMock.mockImplementationOnce(async () =>
      reply({ code: 'POLICY_VERSION_CONFLICT', message: '申请已被更新，请重新读取。' }, 409),
    )
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '申请已被更新，请重新读取。',
    )
    expect(wrapper.find('.el-alert').exists()).toBe(false)
    expect(button('保存草稿').attributes('disabled')).toBeDefined()
    await button('读取服务端版本').trigger('click')
    await flushPromises()
    const confirm = [...document.querySelectorAll('.el-message-box button')].find((item) =>
      item.textContent.includes('读取服务端版本'),
    )
    confirm.click()
    await flushPromises()
    expect(button('保存草稿').attributes('disabled')).toBeUndefined()
  })

  it('选品加载失败弹出错误，保留重试入口并可恢复查询', async () => {
    await render()
    await selectPlatform('京东')
    const normal = fetchMock.getMockImplementation()
    fetchMock.mockImplementation((path, init) =>
      path === '/api/catalog/options'
        ? reply({ message: '商品筛选项暂不可用' }, 503)
        : normal(path, init),
    )
    await openGoodsPicker()
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '商品筛选项暂不可用',
    )
    expect(document.querySelector('.policy-goods-picker [role="alert"]')).toBeNull()
    fetchMock.mockImplementation(normal)
    const retry = [...document.querySelectorAll('.policy-goods-picker button')].find((item) =>
      item.textContent.includes('重试筛选项'),
    )
    retry.click()
    await flushPromises()
    expect(document.querySelector('.policy-goods-picker').textContent).not.toContain('重试筛选项')
  })

  it('多文件上传失败阻止保存，重试后绑定、刷新回填及移除引用', async () => {
    const savedMessage = vi.spyOn(ElMessage, 'success')
    await render()
    uploadFailure = '文件存储暂不可用'
    const input = wrapper.get('input[type="file"]')
    const files = [new File(['%PDF-1.7'], '政策一.pdf'), new File(['%PDF-1.7'], '政策二.pdf')]
    Object.defineProperty(input.element, 'files', { value: files, configurable: true })
    await input.trigger('change')
    await flushPromises()
    expect(wrapper.text()).toContain('政策一.pdf')
    expect(wrapper.text()).toContain('政策二.pdf')
    expect(wrapper.text()).toContain('上传失败')
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(draft).toBeNull()
    expect(savedMessage).not.toHaveBeenCalled()
    expect(document.querySelector('.el-message--warning')?.textContent).toContain('有文件上传失败')
    expect(wrapper.text()).not.toContain('有文件上传失败')
    uploadFailure = null
    await button('重试').trigger('click')
    await flushPromises()
    await button('重试').trigger('click')
    await flushPromises()
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(draft.attachmentInfo).toEqual(
      files.map(({ name }) => ({ type: 'FILE', id: `file-${name}`, originalName: name })),
    )
    expect(savedMessage).toHaveBeenCalledExactlyOnceWith('草稿保存成功')
    expect(wrapper.text()).not.toContain('草稿已保存。')
    wrapper.unmount()
    wrapper = null
    const router = await render('/apply?policyId=321')
    expect(wrapper.text()).toContain('政策一.pdf')
    expect(wrapper.text()).toContain('政策二.pdf')
    expect(wrapper.get('[aria-label="下载附件 政策一.pdf"]').text()).toBe('政策一.pdf')
    await wrapper.get('[aria-label="移除附件 政策一.pdf"]').trigger('click')
    await flushPromises()
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(draft.attachmentInfo.map(({ originalName }) => originalName)).toEqual(['政策二.pdf'])
    expect(savedMessage).toHaveBeenCalledTimes(2)
    expect(
      fetchMock.mock.calls.some(
        ([path, init]) => path.includes('/api/attachments/') && init?.method === 'DELETE',
      ),
    ).toBe(false)
    expect(router.currentRoute.value.query.policyId).toBe('321')
  })

  it('零售场景可全选、清空或单独取消，保存后回填并在本人详情显示', async () => {
    await render('/apply')
    const inputs = () => wrapper.get('[aria-label="涉及场景"]').findAll('input[type="checkbox"]')
    const checked = () => inputs().map((item) => item.element.checked)
    expect(checked()).toEqual([false, true, false, false])
    await inputs()[0].setValue(true)
    expect(checked()).toEqual([true, true, true, true])
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(draft.sceneTypes).toEqual(['NATIONAL_SUB', 'LOCAL_SUB', 'SELF_SUB'])
    await inputs()[0].setValue(false)
    expect(checked()).toEqual([false, false, false, false])
    await inputs()[0].setValue(true)
    await inputs()[2].setValue(false)
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(draft.sceneTypes).toEqual(['NATIONAL_SUB', 'SELF_SUB'])
    wrapper.unmount()
    wrapper = null
    const router = await render('/apply?policyId=321')
    expect(checked()).toEqual([false, true, false, true])
    await router.push('/apply/submissions')
    await flushPromises()
    await button('详情').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('国补、自补')
  })

  it.each(['同一轮次', '已变更截止', '已无分工'])(
    '驳回后保存并返回仍核对上轮批量调整、原因和文件（%s）',
    async (scenario) => {
      draft = {
        perspective: 'RETAIL',
        selectedPlatforms: ['JD'],
        verifyType: 'SALES',
        sceneTypes: ['NATIONAL_SUB'],
        estimated: false,
        startTime: null,
        endTime: null,
        specialRemark: '',
        attachmentInfo: [],
        goodsForm: { cards: [] },
      }
      savedStatus = 'REJECTED'
      version = 3
      submissionVersion = 1
      const target =
        scenario === '已变更截止'
          ? { ...endOption.target, previousEnd: '2026-12-27T12:34:56+08:00' }
          : endOption.target
      const files = [{ type: 'FILE', id: '上轮证明', originalName: '活动说明.pdf' }]
      rejectedAdjustments = [
        {
          target: endOption.target,
          endTime: '2026-12-15T12:34:56+08:00',
          reason: '平台已确认',
          attachmentInfo: files,
        },
      ]
      applicability = {
        ...direct,
        adjustmentOptions: scenario === '已无分工' ? [] : [{ ...endOption, target }],
      }
      const router = await render('/apply?policyId=321')
      await addModel()
      await button('保存草稿').trigger('click')
      await flushPromises()
      await router.push('/apply/submissions')
      await flushPromises()
      await button('继续编辑').trigger('click')
      await flushPromises()
      await confirmFormSubmission()
      const editor = wrapper.get('.policy-end-dialog')
      expect(fetchMock.mock.calls.some(([url]) => url.endsWith('/submit'))).toBe(false)
      if (scenario === '已无分工') {
        expect(editor.text()).toContain('这张政策已不可调整')
        expect(button('核对调整').element.disabled).toBe(true)
        await button('移除调整').trigger('click')
      } else {
        expect(editor.get('textarea').element.value).toBe('平台已确认')
        expect(editor.text()).toContain('活动说明.pdf')
        expect(editor.text()).toContain(target.previousEnd.slice(0, 19).replace('T', ' '))
      }
      await button('核对调整').trigger('click')
      await flushPromises()
      await button('确认调整').trigger('click')
      await flushPromises()
      if (scenario === '已无分工') {
        await button('直接提交').trigger('click')
      } else {
        await button('确认整单并提交').trigger('click')
        await button('确认提交').trigger('click')
      }
      await flushPromises()
      const submitted = fetchMock.mock.calls.find(([url]) => url.endsWith('/submit'))
      const expected = scenario === '已无分工' ? [] : [{ ...rejectedAdjustments[0], target }]
      expect(JSON.parse(submitted[1].body).endAdjustments).toEqual(expected)
      expect(submissionVersion).toBe(2)
    },
  )

  it('申请人仅查看当前申请和驳回原因，并以最新版本重新提交原单', async () => {
    draft = {
      perspective: 'RETAIL',
      selectedPlatforms: ['JD'],
      verifyType: 'SALES',
      sceneTypes: ['NATIONAL_SUB'],
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
    expect(wrapper.text()).not.toContain('提交轮次')
    await button('详情').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前可编辑说明')
    expect(wrapper.get('.policy-rejection').text()).toContain('请核对活动说明')
    expect(wrapper.text()).not.toContain('历轮审核')
    expect(wrapper.text()).not.toContain('提交快照')
    expect(wrapper.text()).not.toContain('第 1 轮')
    expect(wrapper.find('[role="tab"]').exists()).toBe(false)
    expect(fetchMock.mock.calls.some(([url]) => url.endsWith('/9007199254740993'))).toBe(false)
    await button('继续编辑').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('.policy-form').exists()).toBe(true))
    expect(router.currentRoute.value.query.policyId).toBe('321')
    expect(wrapper.text()).toContain('已驳回')
    expect(wrapper.get('.policy-rejection').text()).toContain('请核对活动说明')
    await addModel()
    await confirmFormSubmission()
    await flushPromises()
    expect(document.body.textContent).toContain('申请已提交，等待审核')
    expect(fetchMock.mock.calls.find(([url]) => url.endsWith('/321/submit'))[1].body).toBe(
      '{"recordVersion":4}',
    )
    expect(router.currentRoute.value.fullPath).toBe('/apply')
    expect(wrapper.findComponent(ElForm).props('model')).toEqual(createForm())
    expect(wrapper.text()).toContain('提交申请')
    await button('我的提交').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('.submissions-panel').exists()).toBe(true))
    await vi.waitFor(() => expect(wrapper.text()).toContain('待审核'))
    expect(wrapper.text()).not.toContain('继续编辑')
  })

  it('打开另一份草稿前确认，取消后菜单返回仍保留未保存内容', async () => {
    permitted = crossChannel
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
      item.textContent.includes('打开申请'),
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

  it('直接切换申请地址先确认，取消保留地址、输入和附件，确认后才读取新申请', async () => {
    const router = await render()
    await wrapper.get('[aria-label="特别备注"]').setValue('尚未保存的备注')
    const upload = wrapper.get('input[type="file"]')
    Object.defineProperty(upload.element, 'files', {
      value: [new File(['%PDF-1.7'], '保留附件.pdf')],
      configurable: true,
    })
    await upload.trigger('change')
    await flushPromises()
    permitted = crossChannel
    draft = {
      perspective: 'BILLION',
      selectedPlatforms: ['PDD'],
      specialRemark: '目标申请',
      attachmentInfo: [],
      goodsForm: { cards: [] },
    }
    version = 1
    const confirm = vi
      .spyOn(ElMessageBox, 'confirm')
      .mockRejectedValueOnce('cancel')
      .mockResolvedValueOnce('confirm')
    await router.push('/apply?policyId=321')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/apply')
    expect(wrapper.get('[aria-label="特别备注"]').element.value).toBe('尚未保存的备注')
    expect(wrapper.text()).toContain('保留附件.pdf')
    expect(fetchMock.mock.calls.some(([url]) => url === '/api/policies/321')).toBe(false)
    await router.push('/apply?policyId=321')
    await flushPromises()
    expect(router.currentRoute.value.query.policyId).toBe('321')
    expect(wrapper.get('[aria-label="特别备注"]').element.value).toBe('目标申请')
    expect(wrapper.text()).not.toContain('保留附件.pdf')
    expect(confirm).toHaveBeenCalledTimes(2)
    confirm.mockRestore()
  })

  it('地址 Hash 变化期间确认框仍可操作，取消后恢复原路由及输入', async () => {
    const router = await render()
    await wrapper.get('[aria-label="特别备注"]').setValue('Hash 变化不能丢失')
    const pending = router.push('/apply?policyId=321')
    await flushPromises()
    window.dispatchEvent(new HashChangeEvent('hashchange'))
    await flushPromises()
    const dialog = [...document.querySelectorAll('.el-message-box button')].findLast(
      (item) => item.textContent.trim() === '保留内容',
    )
    expect(dialog).toBeTruthy()
    dialog.click()
    await pending
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/apply')
    expect(wrapper.get('[aria-label="特别备注"]').element.value).toBe('Hash 变化不能丢失')
    expect(button('保存草稿').attributes('disabled')).toBeUndefined()
  })

  it('目标申请加载期间阻止切回旧地址，失败后仍可重新读取原申请', async () => {
    draft = {
      perspective: 'RETAIL',
      selectedPlatforms: ['JD'],
      specialRemark: '原申请',
      attachmentInfo: [],
      goodsForm: { cards: [] },
    }
    version = 1
    const router = await render('/apply?policyId=321')
    let complete
    const original = fetchMock.getMockImplementation()
    fetchMock.mockImplementation((url, init) =>
      url === '/api/policies/654'
        ? new Promise((resolve) => {
            complete = resolve
          })
        : original(url, init),
    )
    await router.push('/apply?policyId=654')
    await flushPromises()
    await router.push('/apply?policyId=321')
    expect(router.currentRoute.value.query.policyId).toBe('654')
    complete(reply({ message: '读取目标申请失败' }, 503))
    await flushPromises()
    await router.push('/apply?policyId=321')
    await flushPromises()
    expect(router.currentRoute.value.query.policyId).toBe('321')
    expect(wrapper.get('[aria-label="特别备注"]').element.value).toBe('原申请')
    expect(button('保存草稿').attributes('disabled')).toBeUndefined()
  })

  it('从我的提交直接打开另一申请地址，取消保留菜单页，确认后不会跳回旧申请', async () => {
    draft = {
      perspective: 'RETAIL',
      selectedPlatforms: ['JD'],
      specialRemark: '已保存的申请',
      attachmentInfo: [],
      goodsForm: { cards: [] },
    }
    version = 1
    const router = await render('/apply?policyId=321')
    await wrapper.get('[aria-label="特别备注"]').setValue('旧申请未保存的内容')
    await router.push('/apply/submissions')
    const original = fetchMock.getMockImplementation()
    fetchMock.mockImplementation((url, init) =>
      url === '/api/policies/654'
        ? reply({
            id: '654',
            policyCode: 'QA-654',
            status: 'DRAFT',
            recordVersion: 1,
            submissionVersion: 0,
            form: { ...draft, specialRemark: '另一申请' },
            goods: [],
          })
        : original(url, init),
    )
    const confirm = vi
      .spyOn(ElMessageBox, 'confirm')
      .mockRejectedValueOnce('cancel')
      .mockResolvedValueOnce('confirm')
    await router.push('/apply?policyId=654')
    expect(router.currentRoute.value.name).toBe('submissions')
    await router.push('/apply?policyId=654')
    await flushPromises()
    expect(router.currentRoute.value.query.policyId).toBe('654')
    expect(wrapper.get('[aria-label="特别备注"]').element.value).toBe('另一申请')
    expect(confirm).toHaveBeenCalledTimes(2)
    confirm.mockRestore()
  })

  it('整组跨页包含全部在售商品，翻页和搜索仅改变预览，草稿和正式提交保留整组', async () => {
    const black = { ...white, goodsId: '999', colorName: '星耀黑' }
    const otherColors = Array.from({ length: 100 }, (_, index) => ({
      ...white,
      goodsId: String(100 + index),
      colorName: `测试颜色${index}`,
    }))
    rows = [white, ...otherColors, black]
    const router = await render()
    await selectPlatform('京东')
    await openGoodsPicker()
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
    expect(rowFor('浮光白').querySelector('input:not(:disabled)')).toBeNull()

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
    expect(rowFor('星耀黑')).toBeDefined()
    await jump(1)
    expect(rowFor('浮光白').querySelector('input:not(:disabled)')).toBeNull()
    const search = drawer.querySelector('[aria-label="商品关键词"]')
    search.value = '浮光白'
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    await flushPromises()
    expect(rowFor('浮光白').querySelector('input:not(:disabled)')).toBeNull()
    drawer.querySelector('.policy-picker-footer .el-button--primary').click()
    await flushPromises()
    expect(wrapper.findAll('.policy-model-card')).toHaveLength(1)
    expect(wrapper.findAll('.policy-model-configs .el-tag').map((item) => item.text())).toEqual([
      '16GB+512GB',
    ])
    expect(wrapper.get('.policy-model-configs').text()).not.toMatch(/星耀黑|浮光白/)
    expect(wrapper.find('[aria-label="京东 数量"]').exists()).toBe(false)
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.policyId).toBe('321')
    expect(draft.goodsForm.cards[0].targets.map(({ goodsId }) => goodsId)).toEqual(
      rows.map(({ goodsId }) => goodsId),
    )
    expect(draft.goodsForm.cards[0].targets[0]).toMatchObject({
      spuId: white.spuId,
      ram: '16GB',
      rom: '512GB',
    })

    wrapper.unmount()
    wrapper = null
    await render('/apply?policyId=321')
    expect(wrapper.findAll('.policy-model-configs .el-tag').map((item) => item.text())).toEqual([
      '16GB+512GB',
    ])
    expect(wrapper.get('.policy-model-configs').text()).not.toMatch(/星耀黑|浮光白/)
    const picker = wrapper.findComponent(ElDatePicker)
    picker.vm.$emit('update:modelValue', ['2026-11-10T09:00:00', '2026-11-20T18:00:00'])
    await wrapper.get('[aria-label="方案 1 补贴金额"]').setValue('0.10')
    await confirmFormSubmission()
    await flushPromises()
    expect(document.body.textContent).toContain('申请已提交，等待审核')
    expect(fetchMock.mock.calls.find(([url]) => url.endsWith('/321/submit'))[1].body).toBe(
      '{"recordVersion":2}',
    )
    expect(draft.goodsForm.cards[0].targets.map(({ goodsId }) => goodsId)).toEqual(
      rows.map(({ goodsId }) => goodsId),
    )
  })

  it('配置可整组多选或全选，全部配置遵循当前机型范围', async () => {
    await render()
    await selectPlatform('京东')
    await openGoodsPicker()
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
    expect(drawer.querySelector('.policy-picker-row input:not(:disabled)')).toBeNull()
    await chooseGoodsFilter('商品配置', [whiteConfig, 'ALL_CONFIGS'])
    expect(drawer.querySelectorAll('.policy-picker-row')).toHaveLength(2)
    expect(drawer.textContent).not.toContain(otherModel.modelName)
    expect(drawer.querySelector('.policy-picker-footer').textContent).toContain('已选 2 件')
    await chooseGoodsFilter('商品配置', ['ALL_CONFIGS', anotherConfig])
    expect(drawer.querySelectorAll('.policy-picker-row')).toHaveLength(1)
    expect(drawer.textContent).toContain(another.colorName)
    expect(drawer.querySelector('.policy-picker-row input:not(:disabled)')).toBeNull()
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
    await openGoodsPicker(true)
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
    expect(drawer.querySelectorAll('.policy-picker-row input[type="radio"]:checked')).toHaveLength(
      1,
    )
    drawer.querySelector('.policy-picker-footer .el-button--primary').click()
    await flushPromises()
    const [standard, special] = wrapper.findAll('.policy-model-card')
    expect(special.find('.policy-model-edit').exists()).toBe(false)
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

  it('取消配置只暂存移除，取消抽屉保留原卡，保存和提交仅包含剩余配置', async () => {
    const another = catalog.find(
      (item) => item.spuId === white.spuId && item.ram + item.rom !== white.ram + white.rom,
    )
    const anotherConfig = [another.spuId, another.ram, another.rom].join('/')
    rows = [white, { ...white, goodsId: '999', colorName: '星耀黑' }, another]
    await render()
    await selectPlatform('京东')
    await openGoodsPicker()
    await chooseGoodsFilter('商品配置', [whiteConfig, anotherConfig])
    document.querySelector('.policy-picker-footer .el-button--primary').click()
    await flushPromises()
    await wrapper.get('[aria-label="方案 1 补贴金额"]').setValue('50.00')
    for (const save of [false, true]) {
      await wrapper.get('.policy-model-edit').trigger('click')
      await flushPromises()
      await chooseGoodsFilter('商品配置', [anotherConfig])
      const drawer = document.querySelector('.policy-goods-picker')
      expect(drawer.querySelector('.policy-picker-footer').textContent).toContain('移除 2 件')
      expect(wrapper.findAll('.policy-model-configs .el-tag')).toHaveLength(2)
      const action = [...drawer.querySelectorAll('.policy-picker-footer button')].find(
        (item) => item.textContent.trim() === (save ? '保存配置' : '取消'),
      )
      action.click()
      await flushPromises()
      expect(wrapper.findAll('.policy-model-configs .el-tag')).toHaveLength(save ? 1 : 2)
      expect(wrapper.get('[aria-label="方案 1 补贴金额"]').element.value).toBe('50.00')
    }
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(draft.goodsForm.cards[0].targets.map(({ goodsId }) => goodsId)).toEqual([
      another.goodsId,
    ])
    wrapper.unmount()
    wrapper = null
    await render('/apply?policyId=321')
    expect(wrapper.findAll('.policy-model-configs .el-tag')).toHaveLength(1)
    wrapper
      .findComponent(ElDatePicker)
      .vm.$emit('update:modelValue', ['2026-11-10T09:00:00', '2026-11-20T18:00:00'])
    await confirmFormSubmission()
    await flushPromises()
    expect(savedStatus).toBe('PENDING')
    expect(draft.goodsForm.cards[0].targets.map(({ goodsId }) => goodsId)).toEqual([
      another.goodsId,
    ])
  })

  it('顶部新建独立普通卡，编辑只向当前卡追加配置，草稿回填两张卡', async () => {
    const another = catalog.find(
      ({ spuId, goodsId }) => spuId === white.spuId && goodsId !== white.goodsId,
    )
    const anotherConfig = [another.spuId, another.ram, another.rom].join('/')
    rows = [...catalog, { ...white, goodsId: '999', colorName: '星耀黑' }]
    await render()
    await selectPlatform('京东')
    await selectPlatform('拼多多')
    await openGoodsPicker()
    await chooseGoodsFilter('商品配置', [whiteConfig])
    document.querySelector('.policy-picker-footer .el-button--primary').click()
    await flushPromises()
    await addModel()
    const [first, second] = wrapper.findAll('.policy-model-card')
    await first.get('[aria-label="方案 1 补贴金额"]').setValue('50.00')
    await second.get('[aria-label="方案 1 补贴金额"]').setValue('20.00')
    await first
      .findAll('button')
      .find((item) => item.text() === '调整平台')
      .trigger('click')
    await first.get('input[value="PDD"]').setValue(false)

    const edit = first.get('.policy-model-edit')
    for (const confirm of [false, true]) {
      await edit.trigger('click')
      await flushPromises()
      const drawer = document.querySelector('.policy-goods-picker')
      expect(drawer.textContent).toContain('编辑适用配置')
      const goodsRows = drawer.querySelectorAll('.policy-picker-row')
      expect(goodsRows).toHaveLength(2)
      const filters = drawer.querySelector('.policy-picker-filters').textContent
      for (const label of [white.brandName, white.seriesName, white.modelName, '16GB+512GB']) {
        expect(filters).toContain(label)
      }
      expect(goodsRows[0].textContent).toContain('已加入')
      expect(goodsRows[0].querySelector('input:not(:disabled)')).toBeNull()
      expect(goodsRows[1].textContent).toContain('星耀黑')
      expect(goodsRows[1].querySelector('input:not(:disabled)')).toBeNull()
      await chooseGoodsFilter('商品配置', [whiteConfig, anotherConfig])
      expect(drawer.querySelector('.policy-picker-footer').textContent).toContain('新增 1 件')
      const action = [...drawer.querySelectorAll('.policy-picker-footer button')].find(
        (item) => item.textContent.trim() === (confirm ? '保存配置' : '取消'),
      )
      action.click()
      await flushPromises()
      expect(first.findAll('.policy-model-configs .el-tag')).toHaveLength(confirm ? 2 : 1)
      expect(second.findAll('.policy-model-configs .el-tag')).toHaveLength(1)
    }

    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(
      draft.goodsForm.cards.map(({ cardType, targets, subsidyPlans, platformAssignments }) => ({
        cardType,
        goodsIds: targets.map(({ goodsId }) => goodsId),
        amount: subsidyPlans[0].fixedAmount,
        platforms: platformAssignments.map(({ platformCode }) => platformCode),
      })),
    ).toEqual([
      {
        cardType: 'COMMON',
        goodsIds: [white.goodsId, '999', another.goodsId],
        amount: '50.00',
        platforms: ['JD'],
      },
      {
        cardType: 'COMMON',
        goodsIds: [catalog[0].goodsId],
        amount: '20.00',
        platforms: ['JD', 'PDD'],
      },
    ])
    wrapper.unmount()
    wrapper = null
    await render('/apply?policyId=321')
    expect(
      wrapper
        .findAll('.policy-model-card')
        .map((card) => [
          card.findAll('.policy-model-configs .el-tag').length,
          card.get('[aria-label="方案 1 补贴金额"]').element.value,
        ]),
    ).toEqual([
      [2, '50.00'],
      [1, '20.00'],
    ])
  })

  it('新建普通卡时不同颜色也不能重复占用同一配置，全部配置会跳过已占用项', async () => {
    const black = { ...white, goodsId: '999', colorName: '星耀黑' }
    rows = [...catalog, black]
    await render()
    await selectPlatform('京东')
    await openGoodsPicker()
    await chooseGoodsFilter('商品机型', white.spuId)
    await chooseGoodsFilter('商品配置', [whiteConfig])
    document.querySelector('.policy-picker-footer .el-button--primary').click()
    await flushPromises()

    await openGoodsPicker()
    await chooseGoodsFilter('商品机型', white.spuId)
    const configuration = wrapper
      .findAllComponents(ElOption)
      .find((item) => item.props('value') === whiteConfig)
    expect(configuration.props('disabled')).toBe(true)
    await chooseGoodsFilter('商品配置', ['ALL_CONFIGS'])
    const drawer = document.querySelector('.policy-goods-picker')
    const blockedRows = [...drawer.querySelectorAll('.policy-picker-row')].filter((row) =>
      row.textContent.includes('已在其他卡片'),
    )
    expect(blockedRows).toHaveLength(2)
    for (const row of blockedRows) {
      expect(row.querySelector('input:not(:disabled)')).toBeNull()
    }
    expect(drawer.querySelector('.policy-picker-footer').textContent).toContain('已选 1 件')
    drawer.querySelector('.policy-picker-footer .el-button--primary').click()
    await flushPromises()
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(
      draft.goodsForm.cards.map(({ targets }) => targets.map(({ goodsId }) => goodsId)),
    ).toEqual([
      [white.goodsId, black.goodsId],
      [
        catalog.find((item) => item.spuId === white.spuId && item.goodsId !== white.goodsId)
          .goodsId,
      ],
    ])
  })

  it('旧草稿的部分普通卡只在明确重选并保存后补齐在售商品，保留平台和原补贴', async () => {
    const black = { ...white, goodsId: '999', colorName: '星耀黑' }
    rows = [white]
    await render()
    await selectPlatform('京东')
    await openGoodsPicker()
    await chooseGoodsFilter('商品配置', [whiteConfig])
    document.querySelector('.policy-picker-footer .el-button--primary').click()
    await flushPromises()
    await wrapper.get('[aria-label="方案 1 补贴金额"]').setValue('50.00')
    await button('保存草稿').trigger('click')
    await flushPromises()
    rows = [white, black]
    wrapper.unmount()
    wrapper = null
    await render('/apply?policyId=321')
    for (const save of [false, true]) {
      await wrapper.get('.policy-model-edit').trigger('click')
      await flushPromises()
      const drawer = document.querySelector('.policy-goods-picker')
      const refresh = [...drawer.querySelectorAll('button')].find((button) =>
        button.textContent.includes('按在售商品重选整组'),
      )
      refresh.click()
      await flushPromises()
      expect(drawer.querySelector('.policy-picker-footer').textContent).toContain('新增 1 件')
      const action = [...drawer.querySelectorAll('.policy-picker-footer button')].find(
        (item) => item.textContent.trim() === (save ? '保存配置' : '取消'),
      )
      action.click()
      await flushPromises()
      await button('保存草稿').trigger('click')
      await flushPromises()
      const [card] = draft.goodsForm.cards
      expect(card.targets.map(({ goodsId }) => goodsId)).toEqual(
        save ? [white.goodsId, black.goodsId] : [white.goodsId],
      )
      expect(card.subsidyPlans[0].fixedAmount).toBe('50.00')
      expect(card.platformAssignments[0].platformCode).toBe('JD')
    }
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
    permitted = crossChannel
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
      item.textContent.includes('确认切换'),
    )
    confirm.click()
    await flushPromises()
    expect(channel.props('modelValue')).toBe('BILLION')
    expect(wrapper.findAll('.el-radio-group')).toHaveLength(0)
    expect(wrapper.find('.policy-model-card').exists()).toBe(false)
  })

  it('日期精确到秒且可清空，文件上传后能移除，提交失败保留草稿', async () => {
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
    const file = new File(['%PDF-1.7'], '说明.pdf', { type: 'application/pdf' })
    file.uid = 123
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()
    expect(upload.props('autoUpload')).toBe(false)
    expect(wrapper.text()).toContain('说明.pdf')
    expect(wrapper.text()).toContain('已上传')
    await wrapper.get('[aria-label="移除附件 说明.pdf"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('说明.pdf')
    submitFailure = '请选择整单平台。'
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '草稿已保存。请选择整单平台。',
    )
    expect(wrapper.find('.el-alert').exists()).toBe(false)
    ElMessage.closeAll()
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(document.querySelector('.el-message--error')?.textContent).toContain(
      '草稿已保存。请选择整单平台。',
    )
    expect(wrapper.text()).not.toContain('申请已提交')
    expect(draft.attachmentInfo).toEqual([])
  })
  it('身份变化或退出清除已填数据和暂存附件，同一用户刷新身份保持表单', async () => {
    authSession.setLogin({ user: { id: 'owner', tenantKey: 'tenant' } })
    await render()
    await selectPlatform('京东')
    await addModel()
    const file = new File(['%PDF-1.7'], '本人附件.pdf')
    file.uid = 987
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()
    expect(wrapper.text()).toContain('本人附件.pdf')
    authSession.setLogin({ user: { id: 'owner', tenantKey: 'tenant', name: '姓名刷新' } })
    await flushPromises()
    expect(wrapper.text()).toContain('OPPO / Reno 系列 / Reno12')
    authSession.setLogin({ user: { id: 'another', tenantKey: 'tenant' } })
    await flushPromises()
    expect(wrapper.find('.policy-model-card').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('本人附件.pdf')
    await selectPlatform('京东')
    await addModel()
    authSession.invalidate()
    await flushPromises()
    expect(wrapper.find('.policy-model-card').exists()).toBe(false)
  })
})
