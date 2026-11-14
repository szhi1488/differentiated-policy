// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import { ElSelect } from 'element-plus'
import PolicyModelCard from '../../src/views/apply/components/PolicyModelCard.vue'
import { createPolicyModel, policyGoodsKey } from '../../src/utils/policy-form'
import { catalogCenterSamples } from '../../src/mocks/fixtures/catalog-center'

it('回显商品型号，补贴输入只发修改意图，并显示共用方案的平台', async () => {
  const draft = { ...createPolicyModel(), goods: [catalogCenterSamples[0]] }
  const subsidy = Object.freeze({ ...draft.subsidies[0], fixedAmount: '0.10' })
  const model = Object.freeze({
    ...draft,
    platforms: Object.freeze({
      JD: Object.freeze({ subsidyKey: subsidy.key }),
      PDD: Object.freeze({ subsidyKey: subsidy.key }),
    }),
    subsidies: Object.freeze([subsidy]),
  })
  const wrapper = mount(PolicyModelCard, {
    props: {
      model,

      platforms: [
        ['JD', '京东'],
        ['PDD', '拼多多'],
      ],
    },
  })
  await flushPromises()
  expect(wrapper.findAll('.policy-subsidy-platforms .el-tag').map((item) => item.text())).toEqual([
    '京东',
    '拼多多',
  ])
  expect(wrapper.get('.policy-model-header h3').text()).toBe('已选机型配置')
  expect(wrapper.get('.policy-model-summary-row dd').text()).toBe('OPPO / Reno 系列 / Reno12')
  expect(wrapper.get('.policy-model-fields').text()).toContain('12GB+256GB')
  expect(wrapper.get('.policy-model-fields').text()).not.toContain('颜色')
  expect(wrapper.find('[aria-label="拼多多 数量"]').exists()).toBe(false)
  wrapper.findAllComponents(ElSelect).at(-1).vm.$emit('update:modelValue', 'RATIO')
  await wrapper.get('.policy-model-header button').trigger('click')
  expect(wrapper.emitted('update-subsidy')).toEqual([[subsidy.key, 'mode', 'RATIO']])
  expect(wrapper.emitted('remove-model')).toEqual([[]])
  expect(subsidy.fixedAmount).toBe('0.10')
  wrapper.unmount()
})

it('同型号的配置显示在同一行，多个型号和品牌分别显示', async () => {
  const samples = catalogCenterSamples.filter(({ model }) =>
    ['Find X8', 'Reno12', 'Ace 3 Pro'].includes(model),
  )
  const wrapper = mount(PolicyModelCard, {
    props: {
      model: {
        ...createPolicyModel(),
        goods: samples.map((item) => ({ ...item, key: policyGoodsKey(item) })),
      },
      platforms: [],
    },
  })
  await flushPromises()
  const rows = wrapper.findAll('.policy-model-summary-row')
  expect(rows.map((row) => row.get('dd').text())).toEqual([
    'OPPO / Reno 系列 / Reno12',
    '一加 / Ace 系列 / Ace 3 Pro',
    'OPPO / Find 系列 / Find X8',
  ])
  expect(rows[2].findAll('.policy-model-configs .el-tag').map((tag) => tag.text())).toEqual([
    '16GB+512GB · 浮光白',
    `12GB+256GB · ${samples.at(-1).color}`,
  ])
  wrapper.unmount()
})
