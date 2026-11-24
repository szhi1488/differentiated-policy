// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { ElMessage, ElPagination } from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import PolicyReviewSettingsPage from '../../src/views/admin/review-settings/PolicyReviewSettingsPage.vue'
import PolicyReviewReassignment from '../../src/views/admin/review-settings/PolicyReviewReassignment.vue'

const first = { id: '9007199254740993', name: '林宁', department: '运营一部' }
const second = { id: '9007199254740994', name: '林宁', department: '运营二部' }
const buyer = { id: '31', name: '采购甲', department: '采购部' }
const newBuyer = { id: '32', name: '采购乙', department: '采购部' }
const initialSettings = {
  version: 2,
  policyChatConfigured: true,
  productReviewers: [],
  purchaseReviewers: [buyer],
}
const settingsPath = '/api/admin/policy-review-settings'
const deliveriesPath = '/api/admin/policy-review-deliveries'
const member = (person) => ({ ...person, userId: person.id, memberStatus: 'ACTIVE' })
const round = {
  id: '5',
  policyCode: 'ZC-000005',
  submissionVersion: 2,
  currentSubmissionVersion: 2,
  recordVersion: 4,
  settingsVersion: 1,
  status: 'PENDING',
  reviewStage: 'PRODUCT_REVIEW',
  productReviewers: [member(first), { ...member(second), memberStatus: 'REMOVED' }],
  purchaseReviewers: [member(buyer)],
}
let wrapper

async function open(component, props, handlers, stubs = {}) {
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
  wrapper = mount(component, {
    attachTo: document.body,
    props,
    global: { stubs: { teleport: true, ...stubs } },
  })
  await flushPromises()
}
const button = (scope, text) => scope.findAll('button').find((node) => node.text() === text)
async function click(text, scope = wrapper) {
  await button(scope, text).trigger('click')
  await flushPromises()
}
afterEach(() => {
  wrapper?.unmount()
  ElMessage.closeAll()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

it('不填政策单号也可分页核对商品批次的业务提醒', async () => {
  const rows = Array.from({ length: 50 }, (_, i) => ({
    id: String(100 - i),
    action: 'GROUP_MESSAGE',
    recipient: '清洗批次 ' + (100 - i),
    status: 'PENDING',
    nextAttemptAt: '2099-05-01T12:00:00+08:00',
  }))
  const list = vi.fn().mockReturnValueOnce(rows).mockReturnValue([])
  await open(
    PolicyReviewSettingsPage,
    {},
    {
      [`GET ${settingsPath}`]: () => initialSettings,
      [`GET ${deliveriesPath}/notices`]: list,
    },
  )
  const panel = wrapper.get('section[aria-label="飞书投递记录"]')
  await click('查询业务提醒', panel)
  expect(panel.text()).toContain('清洗批次 100')
  expect(panel.text()).toContain('可投递时间 2099-05-01T12:00:00+08:00')
  await click('下一页', panel)
  expect(list).toHaveBeenLastCalledWith({ before: '51' })
  expect(panel.text()).toContain('暂无投递记录')
})

it('按单号和轮次核对单项投递，补回执失败保留输入，成功后重新读取该轮', async () => {
  const row = {
    id: '19',
    event: 'SUBMITTED',
    action: 'CREATE_TASK',
    status: 'UNKNOWN',
    recipient: '采购甲',
    attempts: 3,
    revision: 0,
    canResolve: true,
  }
  const delivered = { ...row, status: 'SENT', canResolve: false, receiptId: 'right-task' }
  const list = vi.fn().mockReturnValueOnce([row]).mockReturnValue([delivered])
  const resolve = vi.fn(({ receiptId }) =>
    receiptId === 'wrong-task'
      ? Response.json(
          { code: 'POLICY_RECEIPT_UNVERIFIED', message: '收件人不匹配' },
          { status: 409 },
        )
      : delivered,
  )
  await open(
    PolicyReviewSettingsPage,
    {},
    {
      [`GET ${settingsPath}`]: () => initialSettings,
      [`GET ${deliveriesPath}`]: list,
      [`POST ${deliveriesPath}/19/resolve`]: resolve,
    },
    {
      ElDialog: {
        props: ['modelValue'],
        template:
          '<section v-if="modelValue" role="dialog"><slot /><slot name="footer" /></section>',
      },
    },
  )
  const panel = wrapper.get('section[aria-label="飞书投递记录"]')
  await panel.get('input').setValue('ZC-000057')
  await panel.get('form').trigger('submit')
  await vi.waitFor(() => expect(panel.text()).toContain('核对并处理'))
  expect(list).toHaveBeenLastCalledWith({ policyId: '57', submissionVersion: '1' })
  await click('核对并处理', panel)
  await vi.waitFor(() => expect(panel.find('[role="dialog"] textarea').exists()).toBe(true))
  const dialog = panel.get('[role="dialog"]')
  await dialog.get('textarea').setValue('逐人核对了任务编号及单号')
  await dialog.get('input[maxlength="128"]').setValue('wrong-task')
  await click('核对并保存回执', dialog)
  await vi.waitFor(() => expect(dialog.text()).toContain('收件人不匹配'))
  expect(dialog.get('textarea').element.value).toBe('逐人核对了任务编号及单号')
  expect(dialog.get('input[maxlength="128"]').element.value).toBe('wrong-task')
  await dialog.get('input[maxlength="128"]').setValue('right-task')
  await click('核对并保存回执', dialog)
  await vi.waitFor(() => expect(panel.text()).toContain('飞书回执 right-task'))
  expect(resolve).toHaveBeenLastCalledWith({
    operation: 'CONFIRM_RECEIPT',
    expectedStatus: 'UNKNOWN',
    expectedAttempts: 3,
    expectedRevision: 0,
    receiptId: 'right-task',
    reason: '逐人核对了任务编号及单号',
  })
  expect(list).toHaveBeenCalledTimes(2)
  expect(button(panel, '核对并处理')).toBeUndefined()
})

it('服务端报告业务群未配置时提示部署人员', async () => {
  await open(
    PolicyReviewSettingsPage,
    {},
    {
      [`GET ${settingsPath}`]: () => initialSettings,
      [`PUT ${settingsPath}`]: () => ({
        ...initialSettings,
        version: 3,
        policyChatConfigured: false,
      }),
    },
  )
  expect(wrapper.text()).not.toContain('FEISHU_POLICY_CHAT_ID')
  await click('保存默认名单')
  expect(wrapper.text()).toContain('请部署人员核对服务端环境变量 FEISHU_POLICY_CHAT_ID')
  expect(button(wrapper, '保存默认名单').element.disabled).toBe(false)
})

it('两组按姓名部门分别查询，跨页勾选同名人员，用字符串 ID 保存并回显新版本', async () => {
  const candidates = vi.fn(({ reviewGroup, page }) =>
    reviewGroup === 'PURCHASE'
      ? { items: [buyer, newBuyer], total: 2 }
      : { items: page === '1' ? [first] : [second], total: 11 },
  )
  const save = vi.fn(() => ({
    version: 3,
    policyChatConfigured: true,
    productReviewers: [first, second],
    purchaseReviewers: [buyer, newBuyer],
  }))
  await open(
    PolicyReviewSettingsPage,
    {},
    {
      [`GET ${settingsPath}`]: () => ({ ...initialSettings, policyChatConfigured: false }),
      [`GET ${settingsPath}/candidates`]: candidates,
      [`PUT ${settingsPath}`]: save,
    },
  )
  const product = wrapper.get('section[aria-label="产品运营审核人"]')
  const purchase = wrapper.get('section[aria-label="采购审核人"]')
  expect(wrapper.text()).toContain('FEISHU_POLICY_CHAT_ID')
  await click('选择人员', product)
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
  await product.get('input[type=checkbox]').setValue(true)
  product.getComponent(ElPagination).vm.$emit('update:current-page', 2)
  await flushPromises()
  expect(product.get('.candidate-rows').text()).toContain('运营二部')
  await product.get('input[type=checkbox]').setValue(true)
  product.getComponent(ElPagination).vm.$emit('update:current-page', 1)
  await flushPromises()
  expect(product.get('input[type=checkbox]').element.checked).toBe(true)
  await click('选择人员', purchase)
  expect(candidates).toHaveBeenLastCalledWith({
    reviewGroup: 'PURCHASE',
    page: '1',
    pageSize: '10',
  })
  await purchase.findAll('input[type=checkbox]')[1].setValue(true)
  await click('保存默认名单')
  expect(save).toHaveBeenCalledExactlyOnceWith({
    expectedVersion: 2,
    productReviewerUserIds: [first.id, second.id],
    purchaseReviewerUserIds: ['31', '32'],
  })
  expect(wrapper.text()).toContain('配置版本 3')
  expect(wrapper.text()).not.toContain('FEISHU_POLICY_CHAT_ID')
  await product.get('button[aria-label^="移除 林宁 · 运营一部"]').trigger('click')
  expect(product.get('input[type=checkbox]').element.checked).toBe(false)
  expect(product.get('.picked-people').text()).toContain('运营二部')
})

it('默认名单版本冲突时保留输入，管理员核对前不能重复保存', async () => {
  const get = vi
    .fn()
    .mockReturnValueOnce(initialSettings)
    .mockReturnValue({ ...initialSettings, version: 3 })
  const save = vi.fn(() => Response.json({ message: '名单版本已变化' }, { status: 409 }))
  await open(
    PolicyReviewSettingsPage,
    {},
    { [`GET ${settingsPath}`]: get, [`PUT ${settingsPath}`]: save },
  )
  await wrapper.get('button[aria-label^="移除 采购甲"]').trigger('click')
  await click('保存默认名单')
  expect(wrapper.text()).toContain('名单版本已变化')
  expect(wrapper.text()).not.toContain('采购甲')
  expect(button(wrapper, '保存默认名单').element.disabled).toBe(true)
  await click('载入最新名单并替换当前选择')
  expect(wrapper.text()).toContain('配置版本 3')
  expect(wrapper.text()).toContain('采购甲')
  expect(save).toHaveBeenCalledTimes(1)
})

it('在初审中改派未来采购，按旧轮次版本提交保留和新增的全部 ID 及原因', async () => {
  const save = vi.fn(() => ({
    id: '5',
    recordVersion: 5,
    submissionVersion: 2,
    reviewStage: 'PRODUCT_REVIEW',
  }))
  await open(
    PolicyReviewReassignment,
    { round },
    {
      [`GET ${settingsPath}/candidates`]: () => ({ items: [buyer, newBuyer], total: 2 }),
      'POST /api/admin/policy-reviews/5/reassign': save,
    },
  )
  await click('改派本轮成员')
  expect(wrapper.get('.picked-people').text()).not.toContain('运营二部')
  await wrapper.get('input[value="PURCHASE_REVIEW"]').setValue()
  await click('选择人员')
  await wrapper.findAll('input[type=checkbox]')[1].setValue(true)
  expect(wrapper.get('.reassign-diff').text()).toContain('新增：采购乙')
  expect(button(wrapper, '保存改派').element.disabled).toBe(true)
  await wrapper.get('textarea').setValue(' 采购增加值班人员 ')
  await click('保存改派')
  expect(save).toHaveBeenCalledExactlyOnceWith({
    expectedStage: 'PRODUCT_REVIEW',
    targetStage: 'PURCHASE_REVIEW',
    recordVersion: 4,
    submissionVersion: 2,
    assigneeUserIds: ['31', '32'],
    reason: '采购增加值班人员',
  })
  expect(wrapper.emitted('reload')).toHaveLength(1)
  expect(wrapper.emitted('busy')).toEqual([[true], [false]])
})

it('改派晚于其他人的办理时保留原因并关闭重发，旧轮及旧流程没有改派按钮', async () => {
  const save = vi.fn(() => Response.json({ message: '本轮已转入终审' }, { status: 409 }))
  await open(
    PolicyReviewReassignment,
    { round },
    {
      [`GET ${settingsPath}/candidates`]: () => ({ items: [second], total: 1 }),
      'POST /api/admin/policy-reviews/5/reassign': save,
    },
  )
  await click('改派本轮成员')
  await click('选择人员')
  await wrapper.get('input[type=checkbox]').setValue(true)
  await wrapper.get('textarea').setValue('另选值班人员')
  await click('保存改派')
  expect(wrapper.text()).toContain('本轮已转入终审')
  expect(wrapper.get('textarea').element.value).toBe('另选值班人员')
  expect(button(wrapper, '保存改派').element.disabled).toBe(true)
  await click('重新读取本轮详情')
  expect(wrapper.emitted('reload')).toHaveLength(1)
  await wrapper.setProps({ round: { ...round, currentSubmissionVersion: 3 } })
  expect(button(wrapper, '改派本轮成员')).toBeUndefined()
  await wrapper.setProps({ round: { ...round, settingsVersion: null } })
  expect(button(wrapper, '改派本轮成员')).toBeUndefined()
})
