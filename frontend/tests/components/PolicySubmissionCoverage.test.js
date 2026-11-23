// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, expect, it } from 'vitest'
import { ElSelect } from 'element-plus'
import PolicySubmissionCoverage from '../../src/views/apply/components/PolicySubmissionCoverage.vue'
import { readSubmissionPreview } from '../../src/utils/policy-applicability'
import { matchedPreview, retailColorScope } from '../fixtures/policy-applicability'

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

it('筛选和翻页保留各平台全部范围的决定', async () => {
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
  expect(wrapper.findAll('.scope-card')).toHaveLength(10)
  expect(wrapper.find('.segment-groups').text()).toContain('国补＋地补')
  await wrapper.get('.scope-segment input[value="321"]').setValue(true)
  await wrapper.get('.btn-next').trigger('click')
  expect(wrapper.findAll('.scope-card')).toHaveLength(1)
  expect(wrapper.get('.scope-platform h3').text()).toBe('拼多多')
  await wrapper.get('.scope-segment input[type="checkbox"]').setValue(true)
  wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'JD')
  await flushPromises()
  expect(wrapper.get('.scope-platform h3').text()).toBe('京东')
  expect(wrapper.get('.scope-segment input[value="321"]').element.checked).toBe(true)
  expect(wrapper.get('.scope-footer').text()).toContain('2 / 22')
  wrapper.findAllComponents(ElSelect)[1].vm.$emit('update:modelValue', '同组选一')
  await flushPromises()
  expect(wrapper.findAll('.scope-segment')).toHaveLength(10)
  expect(wrapper.get('.segment-heading .el-tag').text()).toBe('同组选一')
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
  expect(wrapper.emitted('confirm')[0][0].map((d) => d.selectedPolicyIds)).toEqual([
    ['120', '121'],
    ['119'],
  ])
})

it('同配置相同结果只确认一次，按原商品与时段提交，颜色筛选仅确认可见商品', async () => {
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
  const search = wrapper.get('input[aria-label="覆盖商品关键词"]')
  await search.setValue('墨黑')
  await wrapper.get('.scope-segment input[value="118"]').setValue(true)
  expect(wrapper.get('.scope-heading summary').text()).not.toContain('浮光白')
  await search.setValue('')
  expect(wrapper.find('.partial-choice').exists()).toBe(true)
  expect(button('确认整单并提交').element.disabled).toBe(true)
  await wrapper.get('.scope-segment input[value="118"]').setValue(true)
  expect(wrapper.get('.scope-footer').text()).toContain('1 / 2')
  await wrapper.findAll('.scope-segment')[1].get('input[value="119"]').setValue(true)
  await button('确认整单并提交').trigger('click')
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
