// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import PolicySubmissionGoods from '../../src/views/apply/components/PolicySubmissionGoods.vue'

function product(goodsId, overrides = {}) {
  return {
    goodsId,
    spuId: 'model-1',
    brandName: 'OPPO手机',
    seriesName: 'Reno系列',
    modelName: 'Reno17 Pro',
    edition: null,
    ram: '12GB',
    rom: '256GB',
    colorName: '可可棕',
    goodsName: '不应重复的完整商品名称',
    ...overrides,
  }
}
const target = ({ goodsId, spuId, ram, rom }) => ({ goodsId, spuId, ram, rom })
const fixed = (key = 'fixed') => ({
  key,
  subsidyMode: 'FIXED',
  fixedAmount: '0.10',
  maxSubsidyAmount: '100.00',
})
const ratio = { key: 'ratio', subsidyMode: 'RATIO', ratio: '10.00', priceBase: 'RETAIL_PRICE' }
function card(goods, overrides = {}) {
  return {
    key: 'card-1',
    cardType: 'COMMON',
    followsOrderPlatforms: true,
    targets: goods.map(target),
    subsidyPlans: [fixed()],
    platformAssignments: [{ platformCode: 'JD', planKey: 'fixed', quantity: null }],
    ...overrides,
  }
}

it('同机型按配置汇总实际勾选颜色，只显示一次名称并保留 1T 配置', () => {
  const selected = [
    product('1'),
    product('2', { colorName: '初雪白' }),
    product('3', { rom: '1024GB', colorName: '浅雾蓝' }),
  ]
  const wrapper = mount(PolicySubmissionGoods, {
    props: {
      cards: [card(selected)],
      goods: [...selected, product('4', { colorName: '未勾选颜色' })],
    },
  })
  expect(wrapper.findAll('.submission-model h5').map((node) => node.text())).toEqual(['Reno17 Pro'])
  expect(wrapper.findAll('.submission-configuration dt').map((node) => node.text())).toEqual([
    '12GB+256GB',
    '12GB+1T',
  ])
  expect(wrapper.findAll('.submission-colors').map((node) => node.text())).toEqual([
    '可可棕初雪白',
    '浅雾蓝',
  ])
  expect(wrapper.text()).toContain('2 个配置 · 3 件商品')
  expect(wrapper.text()).not.toContain('不应重复的完整商品名称')
  expect(wrapper.text()).not.toContain('未勾选颜色')
  wrapper.unmount()
})

it('共用补贴的平台放在同一行，不同方案直接对应各自平台且金额精度不变', () => {
  const goods = [product('1')]
  const wrapper = mount(PolicySubmissionGoods, {
    props: {
      goods,
      cards: [
        card(goods, {
          subsidyPlans: [fixed(), ratio],
          platformAssignments: [
            { platformCode: 'JD', planKey: 'fixed' },
            { platformCode: 'GOV_ENTERPRISE', planKey: 'fixed' },
            { platformCode: 'PDD', planKey: 'ratio' },
          ],
        }),
      ],
    },
  })
  const rows = wrapper.findAll('.submission-subsidy-row')
  expect(rows).toHaveLength(2)
  expect(rows[0].findAll('.el-tag').map((node) => node.text())).toEqual(['京东', '政企'])
  expect(rows[0].get('strong').text()).toBe('0.10 元')
  expect(rows[0].text()).toContain('补贴上限 100.00 元')
  expect(rows[0].text()).not.toContain('比例补贴')
  expect(rows[1].findAll('.el-tag').map((node) => node.text())).toEqual(['拼多多'])
  expect(rows[1].get('strong').text()).toBe('10.00%')
  expect(rows[1].text()).toContain('计算基数 零售价')
  expect(wrapper.find('.submission-quantity').exists()).toBe(false)
  wrapper.unmount()
})

it('指定机型保留具体颜色，共用补贴时数量仍按平台区分 0 和未填写', () => {
  const goods = [product('1')]
  const wrapper = mount(PolicySubmissionGoods, {
    props: {
      goods,
      cards: [
        card(goods, {
          cardType: 'SPECIFIED',
          followsOrderPlatforms: false,
          platformAssignments: [
            { platformCode: 'JD', planKey: 'fixed', quantity: 0 },
            { platformCode: 'PDD', planKey: 'fixed', quantity: null },
          ],
        }),
      ],
    },
  })
  expect(wrapper.text()).toContain('指定适用机型')
  expect(wrapper.text()).toContain('指定平台')
  expect(wrapper.get('.submission-colors').text()).toBe('可可棕')
  expect(wrapper.findAll('.submission-platform').map((node) => node.text())).toEqual([
    '京东 数量 0',
    '拼多多 数量 未填写',
  ])
  expect(wrapper.findAll('.submission-subsidy-value')).toHaveLength(1)
  wrapper.unmount()
})

it('不同机型版本以及不同卡片保持独立，不把补贴或颜色合并到其他卡', () => {
  const goods = [product('1'), product('2', { spuId: 'model-2', edition: '卫星通信版' })]
  const wrapper = mount(PolicySubmissionGoods, {
    props: {
      goods,
      cards: [
        card(goods),
        card([goods[0]], {
          key: 'card-2',
          subsidyPlans: [ratio],
          platformAssignments: [{ platformCode: 'PDD', planKey: 'ratio' }],
        }),
      ],
    },
  })
  const cards = wrapper.findAll('.submission-goods')
  expect(cards).toHaveLength(2)
  expect(cards[0].findAll('.submission-model h5').map((node) => node.text())).toEqual([
    'Reno17 Pro',
    'Reno17 Pro · 卫星通信版',
  ])
  expect(cards[0].text()).toContain('固定补贴')
  expect(cards[0].text()).not.toContain('比例补贴')
  expect(cards[1].text()).toContain('比例补贴')
  expect(cards[1].text()).not.toContain('固定补贴')
  wrapper.unmount()
})

it('草稿未选方案及未分配平台的补贴明确标注，空值不添加金额单位', () => {
  const goods = [product('1')]
  const wrapper = mount(PolicySubmissionGoods, {
    props: {
      goods,
      cards: [
        card(goods, {
          subsidyPlans: [{ ...fixed(), fixedAmount: null, maxSubsidyAmount: '' }],
          platformAssignments: [{ platformCode: 'JD', planKey: null }],
        }),
      ],
    },
  })
  const rows = wrapper.findAll('.submission-subsidy-row')
  expect(rows[0].text()).toContain('京东')
  expect(rows[0].text()).toContain('未选择补贴方案')
  expect(rows[1].text()).toContain('尚未分配平台')
  expect(rows[1].get('strong').text()).toBe('未填写')
  expect(wrapper.text()).not.toContain('未填写 元')
  wrapper.unmount()
})

it('缺失及规格不匹配的目录不借用名称颜色，切换到轮次快照只使用该轮数据', async () => {
  const selected = product('1')
  const wrapper = mount(PolicySubmissionGoods, {
    props: {
      cards: [card([selected, product('2')])],
      goods: [product('1', { ram: '16GB', modelName: '不匹配型号', colorName: '不匹配颜色' })],
    },
  })
  expect(wrapper.text()).toContain('商品 1')
  expect(wrapper.text()).toContain('商品 2')
  expect(wrapper.text()).toContain('颜色信息不可用')
  expect(wrapper.text()).not.toContain('不匹配')
  await wrapper.setProps({
    cards: [card([selected])],
    goods: [product('1', { modelName: '提交时型号', colorName: '提交时颜色' })],
  })
  expect(wrapper.text()).toContain('提交时型号')
  expect(wrapper.text()).toContain('提交时颜色')
  expect(wrapper.text()).not.toContain('商品 2')
  expect(wrapper.text()).not.toContain('颜色信息不可用')
  wrapper.unmount()
})

it('没有商品卡时显示空状态', () => {
  const wrapper = mount(PolicySubmissionGoods, { props: { cards: [], goods: [] } })
  expect(wrapper.text()).toContain('暂无商品')
  expect(wrapper.find('.submission-goods').exists()).toBe(false)
  wrapper.unmount()
})
