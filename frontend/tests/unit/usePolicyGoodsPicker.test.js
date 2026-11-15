import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { readApplicantCatalogOptions, readApplicantGoods } from '../../src/api/catalog'
import { usePolicyGoodsPicker } from '../../src/composables/usePolicyGoodsPicker'
import { applicantGood } from '../../src/utils/policy-draft'

vi.mock('../../src/api/catalog', () => ({
  readApplicantCatalogOptions: vi.fn(),
  readApplicantGoods: vi.fn(),
}))

const item = (id, ram = '12GB') => ({
  goodsId: String(id),
  brandId: '1',
  brandName: '测试品牌',
  seriesId: '1',
  seriesName: '测试系列',
  spuId: '1',
  modelName: '测试机型',
  ram,
  rom: '256GB',
  goodsName: `测试商品${id}`,
  colorName: `颜色${id}`,
  onSale: true,
})
const key = (ram = '12GB') => `1/${ram}/256GB`
let scope
let rows

beforeEach(() => {
  scope = effectScope()
  rows = [item(1), item(2), item(3, '16GB')]
  readApplicantCatalogOptions.mockResolvedValue({
    brands: [{ id: '1', name: '测试品牌' }],
    series: [{ id: '1', name: '测试系列', brandId: '1' }],
    models: [{ id: '1', name: '测试机型', seriesId: '1' }],
    configurations: ['12GB', '16GB'].map((ram) => ({ spuId: '1', ram, rom: '256GB' })),
  })
  readApplicantGoods.mockImplementation(async ({ configuration = [], page, size }) => {
    const filtered = rows.filter(
      (row) => !configuration.length || configuration.includes(key(row.ram)),
    )
    return { items: filtered.slice((page - 1) * size, page * size), total: filtered.length }
  })
})
afterEach(() => {
  scope.stop()
  vi.resetAllMocks()
})

async function picker(existing = rows, editing = true) {
  const goods = existing.map(applicantGood)
  const state = scope.run(() =>
    usePolicyGoodsPicker({
      initialGoods: goods,
      existingGoods: goods,
      addedKeys: goods.map(({ id }) => id),
      blockedConfigurations: new Set(),
      special: false,
      editing,
    }),
  )
  await flushPromises()
  return state
}

describe('商品配置取消与重选', () => {
  it('取消已有配置时移除全部颜色，保留其他配置，再选回时恢复原有商品', async () => {
    const state = await picker()
    state.changeConfig([key('16GB')])
    await flushPromises()
    expect([...state.removed.value]).toEqual(['1', '2'])
    state.changeConfig([key(), key('16GB')])
    await flushPromises()
    expect([...state.removed.value]).toEqual([])
    expect([...state.selected.value.keys()]).toEqual([])
  })

  it('单独取消的已有颜色在取消配置、重选及全部配置后仍不恢复', async () => {
    const state = await picker()
    state.toggle(applicantGood(rows[0]), false)
    state.changeConfig([key('16GB')])
    state.changeConfig([key(), key('16GB')])
    await flushPromises()
    state.changeConfig(['ALL_CONFIGS'])
    await flushPromises()
    expect([...state.removed.value]).toEqual(['1'])
    state.toggle(applicantGood(rows[0]), true)
    expect([...state.removed.value]).toEqual([])
  })

  it('跨页已有商品全部移除，清空全部配置也能提交移除意图', async () => {
    rows = Array.from({ length: 105 }, (_, index) => item(index + 1))
    const state = await picker()
    expect(state.rows.value).toHaveLength(20)
    state.changeConfig([])
    await flushPromises()
    expect(state.removed.value.size).toBe(105)
    expect(state.removed.value.has('105')).toBe(true)
  })

  it('取消本次新增配置清理新增商品，手动取消项在再次选配置后仍保留取消', async () => {
    const state = await picker([], false)
    state.changeConfig([key()])
    await flushPromises()
    state.toggle(applicantGood(rows[0]), false)
    state.changeConfig([])
    await flushPromises()
    expect(state.selected.value.size).toBe(0)
    state.changeConfig([key()])
    await flushPromises()
    expect([...state.selected.value.keys()]).toEqual(['2'])
    expect(state.removed.value.size).toBe(0)
  })
})
