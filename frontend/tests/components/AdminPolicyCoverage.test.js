// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { ElSelect } from 'element-plus'
import AdminPolicyCoverage from '../../src/views/admin/policies/AdminPolicyCoverage.vue'
import AdminPoliciesPage from '../../src/views/admin/policies/AdminPoliciesPage.vue'
import AdminPolicyReferenceDialog from '../../src/views/admin/policies/AdminPolicyReferenceDialog.vue'
import { coverageDetail, referenceDetail, groupedCoverageDetail } from '../fixtures/policy-coverage'
import { coverageProducts, groupCoverageProducts } from '../../src/utils/policy-coverage-groups'

let wrapper
afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})
const button = (label) => wrapper.findAll('button').find((node) => node.text() === label)

it('汇总按商品平台范围计数，结果/平台/关键词组合筛选并重置分页，零结果有空态', async () => {
  wrapper = mount(AdminPolicyCoverage, { props: { detail: coverageDetail() } })
  await flushPromises()
  expect(wrapper.get('.coverage-summary').text()).toContain('全部 12 项')
  expect(wrapper.get('.coverage-summary').text()).toContain('生效时覆盖 10 项')
  await wrapper.get('.btn-next').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('测试商品12')
  await button('生效时覆盖 10 项').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('测试商品1')
  expect(wrapper.text()).not.toContain('测试商品12')
  wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'PDD')
  await flushPromises()
  expect(wrapper.text()).toContain('没有符合条件的覆盖结果')
  await button('重置').trigger('click')
  await wrapper.get('input[aria-label="覆盖商品关键词"]').setValue('测试商品12')
  await flushPromises()
  expect(wrapper.text()).toContain('展示 1 组，涉及 1 项商品平台范围 / 全部 12 项')
  expect(wrapper.get('.coverage-summary').text()).toContain('生效时覆盖 10 项')
  await button('对比补贴').trigger('click')
  expect(wrapper.emitted('show-reference')[0][0]).toMatchObject({
    goodsId: '111',
    platformCode: 'PDD',
    outcome: 'RETAINED',
  })
})

it('配置合并行展示已选颜色和商品明细，颜色搜索不带入同组其他商品', async () => {
  wrapper = mount(AdminPolicyCoverage, { props: { detail: groupedCoverageDetail() } })
  await flushPromises()
  expect(wrapper.text()).toContain('展示 4 组，涉及 8 项商品平台范围 / 全部 8 项')
  expect(wrapper.find('.coverage-product-details').exists()).toBe(false)
  await wrapper.find('.el-table__expand-icon').trigger('click')
  await flushPromises()
  const details = wrapper.get('.coverage-product-details')
  expect(details.text()).toContain('可可棕')
  expect(details.text()).toContain('初雪白')
  expect(details.text()).toContain('奇遇喵')
  expect(details.findAll('.el-table__row').map((node) => node.text())).toEqual([
    expect.stringContaining('100'),
    expect.stringContaining('101'),
    expect.stringContaining('102'),
  ])
  await wrapper.get('input[aria-label="覆盖商品关键词"]').setValue('初雪白')
  await flushPromises()
  expect(wrapper.text()).toContain('展示 2 组，涉及 2 项商品平台范围 / 全部 8 项')
  expect(wrapper.text()).not.toContain('可可棕')
  expect(wrapper.text()).not.toContain('奇遇喵')
})

it.each(['PENDING', 'APPROVED'])(
  '同配置 %s 部分覆盖先汇总，展开逐件核对结果和各自对照轮次',
  async (status) => {
    const detail = groupedCoverageDetail()
    detail.policy.status = status
    detail.coverage.items.forEach((row) => {
      row.outcome = status === 'PENDING' ? 'NEW' : 'EFFECTIVE'
    })
    Object.assign(detail.coverage.items[0], {
      outcome: status === 'PENDING' ? 'REPLACES' : 'SUPERSEDED',
      winner: { id: '4', policyCode: 'ZC-000004', auditLogId: '800', submissionVersion: 1 },
      existingSubsidy: { subsidyMode: 'FIXED', fixedAmount: '40.00' },
    })
    wrapper = mount(AdminPolicyCoverage, { props: { detail } })
    await flushPromises()
    expect(wrapper.text()).toContain('展示 4 组，涉及 8 项商品平台范围')
    expect(wrapper.findAll('.el-tag').filter((node) => node.text() === '部分覆盖')).toHaveLength(1)
    expect(wrapper.text()).toContain(
      status === 'PENDING' ? '生效时覆盖 1 件' : '已被较新申请覆盖 1 件',
    )
    expect(wrapper.find('[aria-label="查看对照政策 ZC-000004"]').exists()).toBe(false)
    await wrapper.find('.el-table__expand-icon').trigger('click')
    await flushPromises()
    const details = wrapper.get('.coverage-product-details')
    expect(details.findAll('.el-table__row')).toHaveLength(3)
    expect(details.findAll('.el-table__row')[0].text()).toContain(
      status === 'PENDING' ? '生效时覆盖' : '已被较新申请覆盖',
    )
    expect(details.findAll('.el-table__row')[1].text()).toContain(
      status === 'PENDING' ? '生效时新增' : '当前生效',
    )
    await details
      .findAll('button')
      .find((node) => node.text() === '对比补贴')
      .trigger('click')
    expect(wrapper.emitted('show-reference')[0][0]).toMatchObject({
      goodsId: '100',
      platformCode: 'ZHAOSHANG',
      winner: { auditLogId: '800' },
      showWhole: false,
    })
    expect(wrapper.emitted('show-reference')[0][0].members).toBeUndefined()
  },
)

it('对照政策在独立只读窗口读取命中轮次，返回保留筛选、审核目标和驳回原因', async () => {
  let fail = true
  const detail = coverageDetail()
  const fetch = vi.fn(async (path, options) => {
    if (options.method === 'POST') {
      return Response.json({ message: '保留审核结果供核对' }, { status: 409 })
    }
    if (path === '/api/admin/policies/4/audit-logs/800') {
      if (fail) {
        return Response.json({ message: '对照读取失败' }, { status: 503 })
      }
      return Response.json(referenceDetail())
    }
    if (path === '/api/admin/policies/5') {
      return Response.json(detail)
    }
    return Response.json({
      items: [
        { ...detail.policy, perspective: 'RETAIL', platforms: ['JD'], applicant: '测试申请人' },
      ],
      total: 1,
      counts: { PENDING: 1 },
    })
  })
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(AdminPoliciesPage, {
    attachTo: document.body,
    global: { stubs: { teleport: true, ElSelect: true } },
  })
  await flushPromises()
  await wrapper.get('[aria-label="审核政策 ZC-000005"]').trigger('click')
  await flushPromises()
  await button('驳回').trigger('click')
  await flushPromises()
  await wrapper.get('textarea').setValue('核对后仍需修改的原因')
  await button('取消').trigger('click')
  await wrapper.get('input[aria-label="覆盖商品关键词"]').setValue('测试商品1')
  await wrapper.findAll('[aria-label="查看对照政策 ZC-000004"]')[0].trigger('click')
  await flushPromises()
  expect(wrapper.get('.admin-policy-reference-dialog').text()).toContain('对照读取失败')
  fail = false
  await button('重试读取对照政策').trigger('click')
  await flushPromises()
  const reference = wrapper.get('.admin-policy-reference-dialog')
  expect(reference.text()).toContain('固定金额：增加 19.90 元')
  expect(reference.text()).toContain('第一轮原始备注')
  expect(
    reference.findAll('button').some((node) => ['通过', '驳回', '确认驳回'].includes(node.text())),
  ).toBe(false)
  await button('返回原申请').trigger('click')
  await flushPromises()
  expect(wrapper.get('input[aria-label="覆盖商品关键词"]').element.value).toBe('测试商品1')
  await button('驳回').trigger('click')
  await flushPromises()
  expect(wrapper.get('textarea').element.value).toBe('核对后仍需修改的原因')
  await button('确认驳回').trigger('click')
  await flushPromises()
  const [path, options] = fetch.mock.calls.find(([, options]) => options.method === 'POST')
  expect(path).toBe('/api/admin/policies/5/review')
  expect(JSON.parse(options.body)).toEqual({
    recordVersion: 4,
    submissionVersion: 2,
    decision: 'REJECT',
    reviewRemark: '核对后仍需修改的原因',
  })
  expect(fetch.mock.calls.some(([path]) => path === '/api/admin/policies/4')).toBe(false)
})

it('关闭与切换对照范围取消旧读取，迟到响应不能替换新窗口内容', async () => {
  let finishOld
  const fetch = vi.fn((path) => {
    if (path.endsWith('/800')) {
      return new Promise((resolve) => {
        finishOld = resolve
      })
    }
    const next = referenceDetail()
    next.snapshot.id = '7'
    next.snapshot.form.specialRemark = '新的对照内容'
    return Promise.resolve(Response.json(next))
  })
  vi.stubGlobal('fetch', fetch)
  wrapper = mount(AdminPolicyReferenceDialog, {
    props: { originCode: 'ZC-000005' },
    global: { stubs: { teleport: true } },
  })
  const row = {
    ...coverageDetail().coverage.items[0],
    name: '测试商品1',
    showWhole: true,
    proposedSubsidy: { subsidyMode: 'FIXED', fixedAmount: '0.30' },
  }
  wrapper.vm.open(row)
  await flushPromises()
  const oldSignal = fetch.mock.calls[0][1].signal
  await button('返回原申请').trigger('click')
  await flushPromises()
  expect(oldSignal.aborted).toBe(true)
  wrapper.vm.open({ ...row, winner: { ...row.winner, id: '7', auditLogId: '801' } })
  await flushPromises()
  expect(wrapper.text()).toContain('新的对照内容')
  finishOld(Response.json(referenceDetail()))
  await flushPromises()
  expect(wrapper.text()).toContain('新的对照内容')
  expect(wrapper.text()).not.toContain('第一轮原始备注')
})

it('合并行对照校验全组商品的旧补贴，一项不一致时不拿首个商品代表整组', async () => {
  const detail = groupedCoverageDetail()
  const prior = referenceDetail()
  prior.snapshot.goods = detail.snapshot.goods
  prior.snapshot.form.goodsForm = structuredClone(detail.snapshot.form.goodsForm)
  const plan = prior.snapshot.form.goodsForm.cards[0].subsidyPlans[0]
  Object.assign(plan, { subsidyMode: 'FIXED', fixedAmount: '0.10' })
  detail.coverage.items.forEach((row) => {
    if (row.platformCode === 'ZHAOSHANG') {
      Object.assign(row, {
        outcome: 'REPLACES',
        winner: { id: '4', policyCode: 'ZC-000004', auditLogId: '800', submissionVersion: 1 },
        existingSubsidy: plan,
      })
    }
  })
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => Response.json(prior)),
  )
  wrapper = mount(AdminPolicyReferenceDialog, {
    props: { originCode: 'ZC-000005' },
    global: { stubs: { teleport: true } },
  })
  const row = groupCoverageProducts(coverageProducts(detail))[0]
  wrapper.vm.open({ ...row, showWhole: false })
  await flushPromises()
  expect(wrapper.text()).toContain('本组包含已选 3 色、3 件商品')
  expect(wrapper.text()).toContain('补贴方式变更，金额与比例不直接比较')
  wrapper.vm.close()
  await flushPromises()
  prior.snapshot.form.goodsForm.cards[0].targets.pop()
  wrapper.vm.open({ ...row, showWhole: false })
  await flushPromises()
  expect(wrapper.text()).toContain('本组商品的对照补贴缺失或不一致')
  expect(wrapper.find('.reference-plans').exists()).toBe(false)
})
