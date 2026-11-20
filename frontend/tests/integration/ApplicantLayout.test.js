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
import { catalogCenterSamples } from '../../src/mocks/fixtures/catalog-center'
import { createForm, policyModelKey } from '../../src/utils/policy-form'

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
let submitResponseMode
let auditRounds
let submissionVersion
let uploadFailure
beforeEach(() => {
  permitted = retail
  rows = catalog
  draft = null
  savedStatus = 'DRAFT'
  version = 0
  submitFailure = null
  submitResponseMode = null
  auditRounds = []
  submissionVersion = 0
  uploadFailure = null
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
      expect(Number(url.searchParams.get('recordVersion'))).toBe(version)
      const items = draft.goodsForm.cards.flatMap((card) =>
        card.targets.flatMap(({ goodsId }) =>
          card.platformAssignments.map(({ platformCode }) => ({
            goodsId,
            platformCode,
            outcome: 'NEW',
            winner: null,
          })),
        ),
      )
      return reply({
        snapshot: { form: draft, goods: rows },
        coverage: { items, effectiveFrom: '2026-11-20T12:00:00+08:00' },
        confirmationToken: 'scope-' + version,
      })
    }
    if (url.pathname === '/api/policies/321/submit') {
      if (submitFailure) {
        return reply({ code: 'POLICY_VALIDATION_FAILED', message: submitFailure }, 400)
      }
      if (submitResponseMode === 'changed') {
        submitResponseMode = null
        return reply({ code: 'POLICY_COVERAGE_CHANGED', message: '覆盖范围已变化' }, 409)
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
      return reply({ ...record(), form: draft, goods: rows })
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
  await button('确认整单并提交').trigger('click')
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
    await button('我的提交').trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('submissions'))
    expect(button('我的提交').attributes('aria-current')).toBe('page')
    await button('提交政策').trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('apply'))
    expect(wrapper.get('[aria-label="特别备注"]').element.value).toBe('收起菜单后仍保留')
  })

  it('核对范围时返回编辑保留草稿和选品，筛选空结果后仍按整单提交', async () => {
    await render()
    await selectPlatform('京东')
    await addModel()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('本人历史政策对照')
    await button('返回编辑').trigger('click')
    await flushPromises()
    expect(savedStatus).toBe('DRAFT')
    expect(fetchMock.mock.calls.some(([url]) => url.endsWith('/submit'))).toBe(false)
    expect(wrapper.find('.policy-model-card').exists()).toBe(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('[aria-label="覆盖商品关键词"]').setValue('不会命中的颜色')
    expect(wrapper.text()).toContain('没有符合条件的对照结果')
    await button('确认整单并提交').trigger('click')
    await flushPromises()
    expect(savedStatus).toBe('PENDING')
  })

  it('确认期间旧政策变化时重新展示范围，取消后不重发提交', async () => {
    await render()
    await selectPlatform('京东')
    await addModel()
    submitResponseMode = 'changed'
    await confirmFormSubmission()
    await flushPromises()
    expect(wrapper.text()).toContain('商品或本人历史政策已变化，请重新核对整单范围')
    expect(
      fetchMock.mock.calls.filter(([url]) => url.includes('/submission-preview?')),
    ).toHaveLength(2)
    await button('返回编辑').trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.filter(([url]) => url.endsWith('/submit'))).toHaveLength(1)
    expect(savedStatus).toBe('DRAFT')
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
    await button('选择手机型号').trigger('click')
    await flushPromises()
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

  it('申请人仅查看当前申请和驳回原因，并以最新版本重新提交原单', async () => {
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
      '{"recordVersion":4,"confirmationToken":"scope-4"}',
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
    expect(wrapper.findAll('.policy-model-configs .el-tag').map((item) => item.text())).toEqual([
      '16GB+512GB',
    ])
    expect(wrapper.get('.policy-model-configs').text()).not.toMatch(/星耀黑|浮光白/)
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
      '{"recordVersion":2,"confirmationToken":"scope-2"}',
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
    await button('选择手机型号').trigger('click')
    await flushPromises()
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
    await button('选择手机型号').trigger('click')
    await flushPromises()
    await chooseGoodsFilter('商品配置', [whiteConfig])
    const deselect = [...document.querySelectorAll('.policy-picker-row')].find((row) =>
      row.textContent.includes('星耀黑'),
    )
    deselect.querySelector('input').click()
    await flushPromises()
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
      expect(goodsRows[0].querySelector('input').disabled).toBe(false)
      expect(goodsRows[0].querySelector('input').checked).toBe(true)
      expect(goodsRows[1].textContent).toContain('星耀黑')
      expect(goodsRows[1].querySelector('input').checked).toBe(false)
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
        goodsIds: [white.goodsId, another.goodsId],
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
    await button('选择手机型号').trigger('click')
    await flushPromises()
    await chooseGoodsFilter('商品机型', white.spuId)
    document.querySelector('.policy-picker-row input').click()
    await flushPromises()
    document.querySelector('.policy-picker-footer .el-button--primary').click()
    await flushPromises()

    await button('选择手机型号').trigger('click')
    await flushPromises()
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
      expect(row.querySelector('input').disabled).toBe(true)
      expect(row.querySelector('input').checked).toBe(false)
    }
    expect(drawer.querySelector('.policy-picker-footer').textContent).toContain('已选 1 件')
    drawer.querySelector('.policy-picker-footer .el-button--primary').click()
    await flushPromises()
    await button('保存草稿').trigger('click')
    await flushPromises()
    expect(
      draft.goodsForm.cards.map(({ targets }) => targets.map(({ goodsId }) => goodsId)),
    ).toEqual([
      [white.goodsId],
      [
        catalog.find((item) => item.spuId === white.spuId && item.goodsId !== white.goodsId)
          .goodsId,
      ],
    ])
  })

  it('普通卡可手动移除一个颜色给指定卡，保留其他颜色、补贴和参考数量', async () => {
    const black = { ...white, goodsId: '999', colorName: '星耀黑' }
    rows = [...catalog, black]
    await render()
    await selectPlatform('京东')
    await button('选择手机型号').trigger('click')
    await flushPromises()
    await chooseGoodsFilter('商品配置', [whiteConfig])
    document.querySelector('.policy-picker-footer .el-button--primary').click()
    await flushPromises()
    const common = wrapper.get('.policy-model-card')
    await common.get('[aria-label="方案 1 补贴金额"]').setValue('50.00')
    for (const save of [false, true]) {
      await common.get('.policy-model-edit').trigger('click')
      await flushPromises()
      const drawer = document.querySelector('.policy-goods-picker')
      const row = [...drawer.querySelectorAll('.policy-picker-row')].find((item) =>
        item.textContent.includes(white.colorName),
      )
      expect(row.querySelector('input').checked).toBe(true)
      row.querySelector('input').click()
      await flushPromises()
      expect(row.textContent).toContain('待移除')
      drawer
        .querySelector(
          save ? '.policy-picker-footer .el-button--primary' : '.policy-picker-footer button',
        )
        .click()
      await flushPromises()
    }
    await button('指定适用机型').trigger('click')
    await flushPromises()
    await chooseGoodsFilter('商品配置', [whiteConfig])
    document.querySelector('.policy-picker-row input').click()
    await flushPromises()
    document.querySelector('.policy-picker-footer .el-button--primary').click()
    await flushPromises()
    await wrapper.get('[aria-label="京东 数量"]').setValue('0')
    await button('保存草稿').trigger('click')
    await flushPromises()
    const [commonCard, specifiedCard] = draft.goodsForm.cards
    expect(commonCard.targets.map(({ goodsId }) => goodsId)).toEqual([black.goodsId])
    expect(commonCard.subsidyPlans[0].fixedAmount).toBe('50.00')
    expect(specifiedCard.targets[0].goodsId).toBe(white.goodsId)
    expect(specifiedCard.platformAssignments[0].quantity).toBe(0)
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
