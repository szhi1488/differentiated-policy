// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { ElDatePicker } from 'element-plus'
import PolicySubmissionCoverage from '../../src/views/apply/components/PolicySubmissionCoverage.vue'
import { readSubmissionPreview } from '../../src/utils/policy-applicability'
import { endOption, matchedPreview, retailColorScope } from '../fixtures/policy-applicability'

let wrapper
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})
const button = (label) => wrapper.findAll('button').find((b) => b.text() === label)
async function render(applicability) {
  const preview = readSubmissionPreview({ applicability, confirmationToken: 'test' })
  wrapper = mount(PolicySubmissionCoverage, { props: { preview }, attachTo: document.body })
  await flushPromises()
}

it('分别展示每段的新旧补贴、场景及胜出单号，只发送明确选择', async () => {
  await render(matchedPreview(true))
  const [a, b] = wrapper.findAll('.scope-segment')
  expect(a.text()).toContain('2026-11-01 09:30:00')
  expect(a.text()).toContain('指定适用机型 · 浮光白 · 数量 0')
  expect(a.text()).toContain('Find X8 墨黑')
  expect(a.text()).toContain('比例补贴 · 4.50% · 零售价')
  expect(a.text()).not.toContain('ZC-000119')
  expect(b.text()).not.toContain('ZC-000118')
  await a.get('input[value="118"]').setValue()
  expect(button('确认整单并提交').element.disabled).toBe(true)
  await b.get('input[value="STACK"]').setValue()
  await button('确认整单并提交').trigger('click')
  expect(wrapper.emitted('confirm')).toBeUndefined()
  await button('确认提交').trigger('click')
  const decisions = wrapper.emitted('confirm')[0][0]
  expect(decisions).toEqual([
    {
      scope: matchedPreview(true).scopes[0].scope,
      through: '2026-11-07T23:59:59+08:00',
      choice: 'CHOOSE_ONE',
      policyId: '118',
    },
    {
      scope: matchedPreview(true).scopes[0].scope,
      through: '2026-11-22T23:59:59+08:00',
      choice: 'STACK',
    },
  ])
})

it('直接分页展示各平台全部判定结果，翻页保留各段选择', async () => {
  const result = matchedPreview()
  result.scopes = Array.from({ length: 11 }, (_, i) => {
    const scope = retailColorScope(String(8001 + i), '浮光白')
    for (const policy of scope.policies) {
      policy.selections[0].products[0].spuId = String(81 + i)
    }
    scope.scope.platformCode = i === 10 ? 'PDD' : 'JD'
    return scope
  })
  await render(result)
  const dialog = wrapper.get('.submission-coverage-dialog')
  expect(dialog.text()).not.toContain('普通商品按机型和配置汇总')
  for (const label of ['筛选平台', '筛选判定结果', '覆盖商品关键词']) {
    expect(dialog.find(`[aria-label="${label}"]`).exists()).toBe(false)
  }
  expect(wrapper.findAll('.scope-card')).toHaveLength(10)
  expect(wrapper.find('.segment-groups').text()).toContain('国补＋地补')
  await wrapper.get('.scope-segment input[value="321"]').setValue(true)
  await wrapper.get('.btn-next').trigger('click')
  expect(wrapper.findAll('.scope-card')).toHaveLength(1)
  expect(wrapper.get('.scope-platform h3').text()).toBe('拼多多')
  await wrapper.get('.scope-segment input[value="118"]').setValue(true)
  await wrapper.get('.btn-prev').trigger('click')
  expect(wrapper.get('.scope-platform h3').text()).toBe('京东')
  expect(wrapper.get('.scope-segment input[value="321"]').element.checked).toBe(true)
  expect(wrapper.get('.scope-footer').text()).toContain('2 / 22')
  expect(wrapper.findAll('.scope-segment')).toHaveLength(20)
  const results = wrapper.findAll('.scope-card')[0].findAll('.segment-heading .el-tag')
  expect(results.map((tag) => tag.text())).toEqual(['跨组可叠加', '同组选一'])
  await wrapper.get('.btn-next').trigger('click')
  expect(wrapper.get('.scope-segment input[value="118"]').element.checked).toBe(true)
  expect(button('确认整单并提交').element.disabled).toBe(true)
})

it('完整场景组合内换选，跨组可叠加部分旧单并跳过本次申请', async () => {
  const result = matchedPreview()
  const { policies, segments } = result.scopes[0]
  const first = segments[0]
  const extra = (id) => ({ ...policies[1], policyId: id, policyCode: 'ZC-000' + id })
  policies.push(extra('120'), extra('121'))
  first.groups[1].policyIds.push('120')
  first.groups.push({ sceneTypes: ['SELF_SUB'], verifyType: 'SALES', policyIds: ['121'] })
  first.eligiblePolicyIds.push('120', '121')
  await render(result)
  const [a, b] = wrapper.findAll('.scope-segment')
  const select = (range, id, checked = true) =>
    range.get('input[value="' + id + '"]').setValue(checked)
  expect(a.get('.segment-heading .el-tag').text()).toBe('跨组可叠加')
  expect(b.get('.segment-heading .el-tag').text()).toBe('同组选一')
  await select(a, '321')
  await select(a, '118')
  expect(a.text()).toContain('本段叠加执行')
  await select(a, '120')
  expect(a.get('input[value="118"]').element.checked).toBe(false)
  await select(a, '121')
  await select(a, '321', false)
  await select(b, '321')
  await select(b, '321', false)
  expect(button('确认整单并提交').element.disabled).toBe(true)
  await select(b, '119')
  await button('确认整单并提交').trigger('click')
  await button('确认提交').trigger('click')
  expect(wrapper.emitted('confirm')[0][0].map((d) => d.selectedPolicyIds)).toEqual([
    ['120', '121'],
    ['119'],
  ])
})

it('同配置相同结果只确认一次，提交每个原始商品和时段的决定', async () => {
  const result = matchedPreview()
  const black = retailColorScope('8002', '墨黑')
  black.segments[0].groups.reverse()
  black.segments[0].eligiblePolicyIds.reverse()
  black.policies[0].selections[0].subsidy.fixedAmount = '150'
  result.scopes.push(black)
  await render(result)
  expect(wrapper.findAll('.scope-card')).toHaveLength(1)
  expect(wrapper.findAll('.scope-segment')).toHaveLength(2)
  const list = wrapper.get('.scope-heading details')
  expect(list.element.open).toBe(false)
  expect(list.get('summary').text()).toContain('浮光白、墨黑')
  expect(list.findAll('li')).toHaveLength(2)
  expect(wrapper.find('.segment-policy .scope-products').exists()).toBe(false)
  await wrapper.get('.scope-segment input[value="118"]').setValue(true)
  expect(button('确认整单并提交').element.disabled).toBe(true)
  expect(wrapper.get('.scope-footer').text()).toContain('1 / 2')
  await wrapper.findAll('.scope-segment')[1].get('input[value="119"]').setValue(true)
  await button('确认整单并提交').trigger('click')
  await button('确认提交').trigger('click')
  expect(wrapper.emitted('confirm')[0][0]).toEqual(
    result.scopes.flatMap((item) =>
      item.segments.map(({ through }, i) => ({
        scope: item.scope,
        through,
        choice: 'SELECT',
        selectedPolicyIds: [i ? '119' : '118'],
      })),
    ),
  )
})

it('同一配置内按时段和差异展示具体颜色，同色不同商品可以展开核对', async () => {
  const result = matchedPreview()
  const black = retailColorScope('8002', '墨黑', '120')
  const secondWhite = retailColorScope('8003', '浮光白')
  secondWhite.policies[1].selections[0].subsidy.ratio = '5.00'
  black.segments[1].from = '2026-11-13T00:00:00+08:00'
  result.scopes.push(black, secondWhite)
  await render(result)
  expect(wrapper.findAll('.scope-card')).toHaveLength(1)
  const segments = wrapper.findAll('.scope-segment')
  expect(segments).toHaveLength(5)
  const blackRange = segments.find((s) => s.text().includes('ZC-000120'))
  expect(blackRange.get('summary').text()).toContain('本段适用颜色 · 墨黑')
  const higherRate = segments.find((s) => s.text().includes('5.00%'))
  expect(higherRate.get('.scope-products li').text()).toContain('8003')
  expect(
    segments
      .find((s) => s.text().includes('2026-11-13'))
      .get('summary')
      .text(),
  ).toContain('墨黑')
  await blackRange.get('input[value="120"]').setValue(true)
  expect(wrapper.get('.scope-footer').text()).toContain('1 / 5')
  expect(segments.filter((s) => s.find('input:checked').exists())).toHaveLength(1)
})

it('指定卡与配置资料缺失的商品单列，保留独立数量，历史指定卡也独立确认', async () => {
  const result = matchedPreview()
  const specified = retailColorScope('8002', '墨黑')
  Object.assign(specified.policies[0].selections[0], { cardType: 'SPECIFIED', quantity: 0 })
  const unknown = retailColorScope('8003', '红色')
  unknown.policies[0].selections[0].products[0].modelName = null
  const priorSpecified = retailColorScope('8004', '银色')
  Object.assign(priorSpecified.policies[1].selections[0], { cardType: 'SPECIFIED', quantity: 3 })
  result.scopes.push(specified, unknown, priorSpecified)
  await render(result)
  const cards = wrapper.findAll('.scope-card')
  expect(cards).toHaveLength(3)
  expect(cards[0].findAll('.scope-segment')).toHaveLength(3)
  expect(cards[0].text()).toContain('指定适用机型 · 银色 · 数量 3')
  expect(cards[1].text()).toContain('指定适用机型 · 墨黑 · 数量 0')
  expect(cards[2].get('.scope-heading').text()).toContain('商品 ID 8003')
})

it.each([
  { title: '有交叉，返回修改', selectedIds: ['118', '119'], closeWithIcon: false },
  { title: '无交叉，关闭确认窗', selectedIds: [], closeWithIcon: true },
])(
  '在独立确认窗核对调整，底层编辑窗保留输入与原有选择（$title）',
  async ({ selectedIds, closeWithIcon }) => {
    const scopeView = () =>
      selectedIds.length
        ? readSubmissionPreview({ applicability: matchedPreview(), confirmationToken: '初始标识' })
        : { scopes: [], ranges: [] }
    const recalculate = vi.fn(async () => ({
      ...scopeView(),
      confirmationToken: '新的核对标识',
      options: [endOption],
    }))
    const preview = { ...scopeView(), options: [endOption], recalculate }
    wrapper = mount(PolicySubmissionCoverage, {
      props: { preview },
      attachTo: document.body,
    })
    await flushPromises()
    const checkbox = (id) => wrapper.get('.scope-segment input[value="' + id + '"]')
    for (const id of selectedIds) {
      await checkbox(id).setValue(true)
    }
    await button('调整政策时间').trigger('click')
    await flushPromises()
    const editor = () => wrapper.get('.end-adjustments')
    await editor().get('.end-options input[value="518"]').setValue(true)
    await button('添加所选政策').trigger('click')
    editor().findComponent(ElDatePicker).vm.$emit('update:modelValue', '2026-12-15T12:34:56')
    await editor().get('textarea').setValue('   ')
    expect(button('核对调整').element.disabled).toBe(true)
    const editDialog = wrapper.get('.policy-end-dialog')
    const reasonInput = editor().get('textarea')
    await reasonInput.setValue('活动提前结束')
    await button('核对调整').trigger('click')
    await flushPromises()
    const confirmation = wrapper.get('.policy-end-confirm-dialog')
    expect(confirmation.isVisible()).toBe(true)
    expect(editDialog.isVisible()).toBe(true)
    expect(editDialog.element.contains(confirmation.element)).toBe(false)
    expect(editDialog.get('textarea').element).toBe(reasonInput.element)
    expect(confirmation.text()).toContain('确认政策时间调整')
    expect(confirmation.text()).toContain(endOption.policyCode)
    expect(confirmation.text()).toContain('2026-12-15 12:34:56')
    expect(confirmation.text()).toContain('活动提前结束')
    expect(recalculate).not.toHaveBeenCalled()
    if (closeWithIcon) {
      await confirmation.get('.el-dialog__headerbtn').trigger('click')
    } else {
      await button('返回修改').trigger('click')
    }
    await vi.waitFor(() => {
      const dialog = wrapper.find('.policy-end-confirm-dialog')
      expect(dialog.exists() && dialog.isVisible()).toBe(false)
    })
    expect(editDialog.isVisible()).toBe(true)
    expect(editDialog.get('textarea').element).toBe(reasonInput.element)
    expect(reasonInput.element.value).toBe('活动提前结束')
    expect(editor().findComponent(ElDatePicker).props('modelValue')).toBe('2026-12-15T12:34:56')
    expect(recalculate).not.toHaveBeenCalled()
    expect(wrapper.emitted('confirm')).toBeUndefined()
    await button('核对调整').trigger('click')
    await flushPromises()
    await button('确认调整').trigger('click')
    await flushPromises()
    const accepted = [
      { target: endOption.target, endTime: '2026-12-15T12:34:56+08:00', reason: '活动提前结束' },
    ]
    expect(recalculate).toHaveBeenCalledExactlyOnceWith(accepted)
    expect(wrapper.emitted('confirm')).toBeUndefined()
    for (const id of selectedIds) {
      expect(checkbox(id).element.checked).toBe(true)
    }
    await button('修改时间调整').trigger('click')
    await flushPromises()
    await editor().get('textarea').setValue('未确认的修改')
    await button('取消').trigger('click')
    expect(recalculate).toHaveBeenCalledTimes(1)
    await button('确认整单并提交').trigger('click')
    await button('返回核对').trigger('click')
    expect(wrapper.emitted('confirm')).toBeUndefined()
    await button('确认整单并提交').trigger('click')
    await button('确认提交').trigger('click')
    const submitted = wrapper.emitted('confirm')[0][0]
    expect(submitted.confirmationToken).toBe('新的核对标识')
    expect(submitted.endAdjustments).toEqual(accepted)
    expect(submitted.decisions.map(({ selectedPolicyIds }) => selectedPolicyIds)).toEqual(
      selectedIds.map((id) => [id]),
    )
  },
)

it('跨搜索勾选多张旧单一次添加，同批分别缩短和延长，按目标提交截止时间与原因', async () => {
  const january = {
    target: { policyId: '519', auditLogId: '100', previousEnd: '2027-01-31T23:59:59+08:00' },
    policyCode: 'ZC-000519',
    perspective: 'RETAIL',
    earliestEnd: '2027-01-01T08:59:59+08:00',
    latestEnd: '2027-01-31T23:59:58+08:00',
    canShorten: true,
    canExtend: true,
  }
  const spare = {
    ...endOption,
    policyCode: 'ZC-000520',
    target: { ...endOption.target, policyId: '520', auditLogId: '101' },
  }
  const preview = { scopes: [], ranges: [], options: [endOption, january, spare] }
  const recalculate = vi.fn(async () => ({ ...preview, confirmationToken: '批量确认标识' }))
  wrapper = mount(PolicySubmissionCoverage, {
    props: { preview: { ...preview, recalculate } },
    attachTo: document.body,
  })
  await flushPromises()
  await button('调整政策时间').trigger('click')
  await flushPromises()
  const editor = wrapper.get('.policy-end-dialog')
  const findOption = (id) => editor.find('.end-options input[value="' + id + '"]')
  const card = (id) => editor.findAll('.end-rows .end-card').find((row) => row.text().includes(id))
  const date = (id) => card(id).getComponent(ElDatePicker)
  const search = editor.get('.end-picker .el-input input')
  await findOption('518').setValue(true)
  await search.setValue('519')
  expect(findOption('518').exists()).toBe(false)
  const allMatches = editor.get('.end-search input[type="checkbox"]')
  await allMatches.setValue(true)
  expect(findOption('519').element.checked).toBe(true)
  await allMatches.setValue(false)
  expect(findOption('519').element.checked).toBe(false)
  await allMatches.setValue(true)
  expect(editor.findAll('.end-rows .end-card')).toHaveLength(0)
  await button('添加所选政策').trigger('click')
  expect(editor.findAll('.end-rows .end-card')).toHaveLength(2)
  await search.setValue('')
  expect(findOption('518').exists()).toBe(false)
  expect(findOption('519').exists()).toBe(false)
  expect(findOption('520').exists()).toBe(true)

  const decemberEnd = '2026-12-15T12:34:56'
  const januaryEnd = '2097-02-01T08:09:10'
  date('518').vm.$emit('update:modelValue', decemberEnd)
  await card('518').get('textarea').setValue('百亿活动提前结束')
  date('519').vm.$emit('update:modelValue', decemberEnd)
  await card('519').get('textarea').setValue('零售下月统一截止')
  expect(button('核对调整').element.disabled).toBe(true)
  date('519').vm.$emit('update:modelValue', januaryEnd)
  await card('519').get('textarea').setValue('   ')
  expect(button('核对调整').element.disabled).toBe(true)
  await card('519').get('textarea').setValue('零售下月统一截止')

  await findOption('520').setValue(true)
  await button('添加所选政策').trigger('click')
  expect(editor.findAll('.end-rows .end-card')).toHaveLength(3)
  expect(button('核对调整').element.disabled).toBe(true)
  await card('520').get('button').trigger('click')
  expect(findOption('520').exists()).toBe(true)
  expect(date('518').props('modelValue')).toBe(decemberEnd)
  expect(date('519').props('modelValue')).toBe(januaryEnd)
  await button('核对调整').trigger('click')
  await flushPromises()
  const confirmedRows = wrapper.get('.policy-end-confirm-dialog').findAll('.end-card')
  expect(confirmedRows).toHaveLength(2)
  expect(confirmedRows[0].text()).toContain('缩短')
  expect(confirmedRows[1].text()).toContain('延长')
  const accepted = [
    { target: endOption.target, endTime: decemberEnd + '+08:00', reason: '百亿活动提前结束' },
    { target: january.target, endTime: januaryEnd + '+08:00', reason: '零售下月统一截止' },
  ]
  for (const { target, endTime, reason } of accepted) {
    const row = confirmedRows.find((item) => item.text().includes(target.policyId))
    expect(row.text()).toContain(endTime.slice(0, 19).replace('T', ' '))
    expect(row.text()).toContain(reason)
  }
  expect(recalculate).not.toHaveBeenCalled()
  await button('确认调整').trigger('click')
  await flushPromises()
  expect(recalculate).toHaveBeenCalledExactlyOnceWith(accepted)
  await button('确认整单并提交').trigger('click')
  await button('确认提交').trigger('click')
  expect(wrapper.emitted('confirm')).toEqual([
    [{ decisions: [], confirmationToken: '批量确认标识', endAdjustments: accepted }],
  ])
})

it('只命中延期区间时显示原单号，提交对应的延期区间决定', async () => {
  const applicability = matchedPreview()
  const { policies, segments } = applicability.scopes[0]
  const synthetic = 'extension:321:500'
  policies.push({ ...policies[0], policyId: synthetic, policyCode: 'ZC-000500（延期）' })
  segments[0].eligiblePolicyIds.push(synthetic)
  segments[0].groups[0].policyIds.push(synthetic)
  await render(applicability)
  const [first, second] = wrapper.findAll('.scope-segment')
  const headings = first.findAll('.segment-policy h5').map((node) => node.text())
  expect(headings.some((text) => text.includes('本次申请') && text.includes('ZC-000321'))).toBe(
    true,
  )
  expect(headings.some((text) => text.includes('已通过') && text.includes('ZC-000500'))).toBe(true)
  expect(first.text()).not.toContain('ZC-000500（延期）')
  await first.get('input[value="500"]').setValue(true)
  await second.get('input[value="119"]').setValue(true)
  await button('确认整单并提交').trigger('click')
  await button('确认提交').trigger('click')
  expect(wrapper.emitted('confirm')[0][0][0].selectedPolicyIds).toEqual([synthetic])
})
