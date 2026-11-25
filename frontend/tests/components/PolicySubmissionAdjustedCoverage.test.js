// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { ElDatePicker } from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { readSubmissionPreview } from '../../src/utils/policy-applicability'
import PolicySubmissionCoverage from '../../src/views/apply/components/PolicySubmissionCoverage.vue'
import { matchedPreview } from '../fixtures/policy-applicability'

const beginning = '2027-01-01T00:00:00+08:00'
const shortened = '2027-01-07T00:00:00+08:00'
const previousEnd = '2027-01-10T00:00:00+08:00'
const extensionStart = '2027-01-10T00:00:01+08:00'
const applicationEnd = '2027-01-13T00:00:00+08:00'
const extended = '2027-01-14T00:00:00+08:00'
const extensionId = 'extension:30:27'
const option = {
  policyCode: 'ZC-000027',
  perspective: 'RETAIL',
  target: { policyId: '27', auditLogId: '71', recordVersion: 3, previousEnd },
  canShorten: true,
  canExtend: true,
  earliestEnd: beginning,
  latestEnd: '2027-01-09T23:59:59+08:00',
}

// 延期的拟议区间沿用原单补贴。服务端按每个底层时段接收决定。
function applicability({ billion = false, end = previousEnd, continuesPrevious = true } = {}) {
  const result = matchedPreview(billion, '27')
  const item = result.scopes[0]
  const [application, existing] = item.policies
  Object.assign(application, {
    policyId: '30',
    policyCode: 'ZC-000030',
    submissionVersion: 1,
    auditLogId: null,
    effectiveFrom: beginning,
    effectiveThrough: applicationEnd,
  })
  Object.assign(existing, {
    submissionVersion: 2,
    auditLogId: '71',
    effectiveFrom: beginning,
    effectiveThrough: end < previousEnd ? end : previousEnd,
  })
  item.policies = [application, existing]
  function segment(from, through, oldId) {
    return {
      from,
      through,
      action: billion ? 'STACK_OR_CHOOSE' : 'SELECT_RETAIL_POLICIES',
      groups: billion
        ? []
        : [
            { sceneTypes: ['NATIONAL_SUB'], verifyType: 'SALES', policyIds: ['30'] },
            { sceneTypes: ['NATIONAL_SUB'], verifyType: 'ACTIVATION', policyIds: [oldId] },
          ],
      eligiblePolicyIds: ['30', oldId],
    }
  }
  item.segments = [segment(beginning, existing.effectiveThrough, '27')]
  if (end > previousEnd) {
    item.policies.push({
      ...existing,
      policyId: extensionId,
      policyCode: 'ZC-000027（延期）',
      proposed: true,
      auditLogId: null,
      submissionVersion: 1,
      effectiveFrom: extensionStart,
      effectiveThrough: end,
    })
    item.segments.push({
      ...segment(extensionStart, applicationEnd, extensionId),
      continuesPrevious,
    })
  }
  return result
}

let wrapper
afterEach(() => {
  wrapper?.unmount()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

async function render(result, additionalProps = {}) {
  const preview = readSubmissionPreview({ applicability: result, confirmationToken: '已核对' })
  wrapper = mount(PolicySubmissionCoverage, {
    props: { preview: { ...preview, ...additionalProps } },
    attachTo: document.body,
  })
  await flushPromises()
}
const ranges = () => wrapper.findAll('.scope-segment')
const button = (label) => wrapper.findAll('button').find((node) => node.text() === label)
const checkbox = (range, id) => range.get('input[value="' + id + '"]')

async function submit() {
  expect(button('确认整单并提交').element.disabled).toBe(false)
  await button('确认整单并提交').trigger('click')
  await button('确认提交').trigger('click')
  return wrapper.emitted('confirm')[0][0]
}

async function adjustAfterSelecting(adjusted, newEnd, reason) {
  vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-11-25T12:00:00+08:00'))
  const recalculate = vi.fn(async () => ({
    ...readSubmissionPreview({ applicability: adjusted, confirmationToken: '重算' }),
    confirmationToken: '重算',
    options: [option],
  }))
  await render(applicability(), { recalculate, options: [option] })
  await checkbox(ranges()[0], '27').setValue(true)
  await checkbox(ranges()[0], '30').setValue(true)
  await button('调整政策时间').trigger('click')
  await flushPromises()
  const editor = wrapper.get('.policy-end-dialog')
  await editor.get('.end-options input[value="27"]').setValue(true)
  await button('添加所选政策').trigger('click')
  editor.getComponent(ElDatePicker).vm.$emit('update:modelValue', newEnd.slice(0, 19))
  await editor.get('textarea').setValue(reason)
  await button('核对调整').trigger('click')
  await button('确认调整').trigger('click')
  await flushPromises()
  const changes = [{ target: option.target, endTime: newEnd, reason }]
  expect(recalculate).toHaveBeenCalledExactlyOnceWith(changes)
  return changes
}

it.each([
  ['延期', extended, applicationEnd],
  ['提前结束', shortened, shortened],
])('先选两张再%s，交集按新截止延续同一次选择', async (reason, newEnd, overlapEnd) => {
  const adjusted = applicability({ end: newEnd })
  const changes = await adjustAfterSelecting(adjusted, newEnd, reason)
  expect(ranges()).toHaveLength(1)
  const [range] = ranges()
  expect(range.findAll('time').map((node) => node.text())).toEqual([
    '2027-01-01 00:00:00',
    overlapEnd.slice(0, 19).replace('T', ' '),
  ])
  expect(range.findAll('.segment-policy')).toHaveLength(2)
  expect(range.text()).not.toContain('（延期）')
  expect(checkbox(range, '27').element.checked).toBe(true)
  expect(checkbox(range, '30').element.checked).toBe(true)
  expect(wrapper.get('.scope-footer').text()).toContain('1 / 1')
  const { decisions, endAdjustments, confirmationToken } = await submit()
  expect(endAdjustments).toEqual(changes)
  expect(confirmationToken).toBe('重算')
  expect(
    decisions.map((row) => ({ ...row, selectedPolicyIds: [...row.selectedPolicyIds].sort() })),
  ).toEqual(
    adjusted.scopes[0].segments.map(({ through, eligiblePolicyIds }) => ({
      scope: adjusted.scopes[0].scope,
      through,
      choice: 'SELECT',
      selectedPolicyIds: [...eligiblePolicyIds].sort(),
    })),
  )
})

it('延期区间出现新的同组候选时，只需重新选择变化的区间', async () => {
  const result = applicability({ end: extended, continuesPrevious: false })
  const { policies, segments } = result.scopes[0]
  policies.push({
    ...policies[1],
    policyId: '31',
    policyCode: 'ZC-000031',
    auditLogId: '72',
    effectiveFrom: extensionStart,
    effectiveThrough: applicationEnd,
  })
  segments[1].eligiblePolicyIds.push('31')
  segments[1].groups[1].policyIds.push('31')
  await adjustAfterSelecting(result, extended, '延期')
  expect(ranges()).toHaveLength(2)
  const [earlier, later] = ranges()
  expect(checkbox(earlier, '27').element.checked).toBe(true)
  expect(later.find('input:checked').exists()).toBe(false)
  expect(button('确认整单并提交').element.disabled).toBe(true)
  await checkbox(later, '27').setValue(true)
  await checkbox(later, '31').setValue(true)
  expect(checkbox(later, '27').element.checked).toBe(false)
  expect((await submit()).decisions.map(({ selectedPolicyIds }) => selectedPolicyIds)).toEqual([
    ['27', '30'],
    ['31'],
  ])
})

it('同配置三件商品共用一次勾选，六个商品时段各自提交', async () => {
  const result = applicability({ end: extended })
  const [first] = result.scopes
  result.scopes = ['可可棕', '初雪白', '奇遇喵'].map((colorName, index) => {
    const item = structuredClone(first)
    item.scope.goodsId = String(8001 + index)
    for (const policy of item.policies) {
      Object.assign(policy.selections[0].products[0], { goodsId: item.scope.goodsId, colorName })
    }
    return item
  })
  await render(result)
  expect(ranges()).toHaveLength(1)
  const [range] = ranges()
  expect(wrapper.get('.scope-heading summary').text()).toContain('3 件商品')
  await checkbox(range, '27').setValue(true)
  await checkbox(range, '30').setValue(true)
  await checkbox(range, '30').setValue(false)
  expect(await submit()).toEqual(
    result.scopes.flatMap(({ scope }) => [
      { scope, through: previousEnd, choice: 'SELECT', selectedPolicyIds: ['27'] },
      { scope, through: applicationEnd, choice: 'SELECT', selectedPolicyIds: [extensionId] },
    ]),
  )
})

it('原执行名单发生变化时，相邻两段仍能分别选单', async () => {
  const result = applicability({ end: extended, continuesPrevious: false })
  await render(result)
  expect(ranges()).toHaveLength(2)
  const [earlier, later] = ranges()
  await checkbox(earlier, '27').setValue(true)
  expect(button('确认整单并提交').element.disabled).toBe(true)
  await checkbox(later, '30').setValue(true)
  expect(
    (await submit()).map(({ through, selectedPolicyIds }) => [through, selectedPolicyIds]),
  ).toEqual([
    [previousEnd, ['27']],
    [applicationEnd, ['30']],
  ])
})

it.each([
  [
    '中间空一秒',
    ({ segments }) => {
      segments[1].from = '2027-01-10T00:00:02+08:00'
    },
  ],
  [
    '相同候选改为同组选一',
    ({ segments }) => {
      segments[1].groups = [
        { sceneTypes: ['NATIONAL_SUB'], verifyType: 'SALES', policyIds: ['30', extensionId] },
      ]
    },
  ],
  [
    '延期区间补贴变化',
    ({ policies }) => {
      const extension = policies.find(({ policyId }) => policyId === extensionId)
      extension.selections = structuredClone(extension.selections)
      extension.selections[0].subsidy.ratio = '7.00'
    },
  ],
])('服务端误标连续且%s时，必须重新预览', (_, change) => {
  const result = applicability({ end: extended })
  change(result.scopes[0])
  expect(() =>
    readSubmissionPreview({ applicability: result, confirmationToken: '误标连续' }),
  ).toThrow('适用范围预览不完整，请重新预览。')
})

it.each(['27', 'STACK'])('百亿延期交接处选 %s 一次，两个原始时段都收到对应决定', async (value) => {
  const result = applicability({ end: extended, billion: true })
  await render(result)
  expect(ranges()).toHaveLength(1)
  const [range] = ranges()
  expect(
    range
      .findAll('input[type="radio"]')
      .map((node) => node.element.value)
      .sort(),
  ).toEqual(['27', '30', 'STACK'])
  await checkbox(range, value).setValue()
  expect(await submit()).toEqual(
    result.scopes[0].segments.map(({ through, eligiblePolicyIds }) => ({
      scope: result.scopes[0].scope,
      through,
      ...(value === 'STACK'
        ? { choice: 'STACK' }
        : { choice: 'CHOOSE_ONE', policyId: eligiblePolicyIds[1] }),
    })),
  )
})
