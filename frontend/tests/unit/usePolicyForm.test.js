import { RETAIL_PLATFORMS, BILLION_PLATFORMS } from '../../src/constants/policy'
import { describe, expect, it, vi } from 'vitest'
import { usePolicyForm } from '../../src/composables/usePolicyForm'
import { catalogCenterSamples } from '../fixtures/catalog-center'

const retail = RETAIL_PLATFORMS.map(([platformCode]) => ({ perspective: 'RETAIL', platformCode }))
const crossChannel = [
  { perspective: 'RETAIL', platformCode: 'JD' },
  ...BILLION_PLATFORMS.map(([platformCode]) => ({ perspective: 'BILLION', platformCode })),
]

const [firstGoods, secondGoods] = catalogCenterSamples

describe('申请表单状态', () => {
  it('清理使待确认动作过期，重复点击不清空新状态', async () => {
    let accept
    const confirm = vi
      .fn()
      .mockReturnValueOnce(true)
      .mockImplementation(
        () =>
          new Promise((resolve) => {
            accept = resolve
          }),
      )
    const state = usePolicyForm({ value: retail }, confirm)
    await state.togglePlatform('JD')
    state.addGoods([firstGoods, secondGoods])
    await state.removeModel(state.form.value.cards[0].key)
    expect(state.form.value.cards).toEqual([])
    const pending = state.switchChannel('BILLION')
    expect(await state.switchChannel('BILLION')).toBe(false)
    state.clear()
    accept(true)
    expect(await pending).toBe(false)
    expect(state.form.value.channel).toBe('RETAIL')
    expect(state.form.value.platforms).toEqual([])
    expect(confirm).toHaveBeenCalledTimes(2)
  })

  it('普通型号可共用或分配不同方案，仅指定特殊商品可填写数量', async () => {
    const state = usePolicyForm({ value: retail }, () => true)
    await state.togglePlatform('KUAISHOU')
    await state.togglePlatform('PDD')
    await state.togglePlatform('JD')
    state.addGoods([firstGoods])
    const [model] = state.form.value.cards
    const [shared] = model.subsidies
    await state.setModelPlatforms(['KUAISHOU', 'PDD', 'JD', 'UNKNOWN'], model.key)
    state.updateModelPlatform('KUAISHOU', 'quantity', '12', model.key)
    state.updateSubsidy(shared.key, 'fixedAmount', '0.10', model.key)
    state.addSubsidy(model.key)
    const separate = model.subsidies[1]
    state.updateSubsidy(separate.key, 'mode', 'RATIO', model.key)
    state.updateSubsidy(separate.key, 'ratio', '8.5', model.key)
    state.updateModelPlatform('JD', 'subsidyKey', separate.key, model.key)
    state.selectAll()
    expect(Object.keys(model.platforms)).toHaveLength(10)
    expect(model.platforms.JD.subsidyKey).toBe(separate.key)
    expect(model.platforms.KUAISHOU).toEqual({ subsidyKey: shared.key })
    expect(shared.fixedAmount).toBe('0.10')
    expect(separate).toMatchObject({ mode: 'RATIO', ratio: '8.5', fixedAmount: '' })
    expect(state.form.value.platforms).toHaveLength(10)
    await state.removeSubsidy(separate.key, model.key)
    expect(model.platforms.JD.subsidyKey).toBe(shared.key)
    state.addSpecialGood(firstGoods)
    const [, special] = state.form.value.cards
    expect(Object.keys(special.platforms)).toHaveLength(10)
    await state.setModelPlatforms(['KUAISHOU', 'PDD'], special.key)
    expect(Object.keys(special.platforms)).toEqual(['KUAISHOU', 'PDD'])
    expect(special.platforms.KUAISHOU.quantity).toBe('')
    state.updateModelPlatform('KUAISHOU', 'quantity', '12', special.key)
    expect(special.platforms.KUAISHOU.quantity).toBe('12')
    expect(special.platforms.PDD.quantity).toBe('')
    await state.setModelPlatforms(null, special.key)
    expect(Object.keys(special.platforms)).toHaveLength(10)
    expect(special.platforms.KUAISHOU.quantity).toBe('12')
    state.updateModelPlatform('KUAISHOU', 'quantity', '', special.key)
    expect(special.platforms.KUAISHOU.quantity).toBe('')
  })

  it('取消清除时保留已填写的平台数量，确认后仍保留机型公共字段', async () => {
    const confirm = vi.fn().mockReturnValue(false)
    const state = usePolicyForm({ value: crossChannel }, confirm)
    await state.togglePlatform('JD')
    state.addSpecialGood(firstGoods)
    const [model] = state.form.value.cards
    state.updateModelPlatform('JD', 'quantity', '0', model.key)
    await state.setModelPlatforms([], model.key)
    expect(confirm).toHaveBeenCalledOnce()
    expect(model.platforms.JD.quantity).toBe('0')
    await state.togglePlatform('JD')
    expect(state.form.value.platforms).toEqual(['JD'])
    expect(await state.switchChannel('BILLION')).toBe(false)
    confirm.mockReturnValue(true)
    await state.togglePlatform('JD')
    expect(model.platforms).toEqual({})
    expect(model.goods[0]).toMatchObject({ name: firstGoods.name, config: firstGoods.config })
    await state.switchChannel('BILLION')
    state.selectAll()
    expect(state.form.value.platforms).toEqual(['PDD', 'TAOBAO', 'UNICOM'])
    expect(state.form.value.verifyType).toBeNull()
    expect(state.form.value.cards).toEqual([])
  })

  it('同规格不同颜色保留各自 ID，可一起加入并保留已填补贴', async () => {
    const state = usePolicyForm({ value: retail })
    await state.togglePlatform('JD')
    const [first, second] = catalogCenterSamples.filter((goods) => goods.model === 'Find X8')
    const anotherColor = { ...first, id: 'other-color', color: '黑色' }
    const offline = catalogCenterSamples.find((goods) => goods.status === 'offline')
    state.addGoods([first])
    const [model] = state.form.value.cards
    state.updateSubsidy(model.subsidies[0].key, 'fixedAmount', '50.00', model.key)
    state.addGoods([anotherColor, second, offline, second], model.key)
    expect(model.goods.map(({ id }) => id)).toEqual([first.id, anotherColor.id, second.id])
    expect(model.subsidies).toHaveLength(1)
    expect(model.subsidies[0].fixedAmount).toBe('50.00')
    state.removeGoods([first.id, anotherColor.id], model.key)
    expect(model.goods.map(({ id }) => id)).toEqual([second.id])
    state.addGoods([firstGoods], model.key)
    expect(model.goods.map(({ name }) => name)).toEqual([second.name, firstGoods.name])
    expect(model.subsidies[0].fixedAmount).toBe('50.00')
  })
})
