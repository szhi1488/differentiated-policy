import { describe, expect, it, vi } from 'vitest'
import { usePolicyForm } from '../../src/composables/usePolicyForm'
import { catalogCenterSamples } from '../../src/mocks/fixtures/catalog-center'
import { policyGoodsKey } from '../../src/utils/policy-form'

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
    const state = usePolicyForm(confirm)
    await state.togglePlatform('JD')
    state.addGoods([firstGoods, secondGoods])
    await state.removeModel()
    expect(state.form.value.model).toBeNull()
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
    const state = usePolicyForm(() => true)
    await state.togglePlatform('KUAISHOU')
    await state.togglePlatform('PDD')
    await state.togglePlatform('JD')
    state.addGoods([firstGoods])
    const model = state.form.value.model
    const [shared] = model.subsidies
    await state.setModelPlatforms(['KUAISHOU', 'PDD', 'JD', 'UNKNOWN'])
    state.updateModelPlatform('KUAISHOU', 'quantity', '12')
    state.updateSubsidy(shared.key, 'fixedAmount', '0.10')
    state.addSubsidy()
    const separate = model.subsidies[1]
    state.updateSubsidy(separate.key, 'mode', 'RATIO')
    state.updateSubsidy(separate.key, 'ratio', '8.5')
    state.updateModelPlatform('JD', 'subsidyKey', separate.key)
    state.selectAll()
    expect(Object.keys(model.platforms)).toHaveLength(10)
    expect(model.platforms.JD.subsidyKey).toBe(separate.key)
    expect(model.platforms.KUAISHOU).toEqual({ subsidyKey: shared.key })
    expect(shared.fixedAmount).toBe('0.10')
    expect(separate).toMatchObject({ mode: 'RATIO', ratio: '8.5', fixedAmount: '' })
    expect(state.form.value.platforms).toHaveLength(10)
    await state.removeSubsidy(separate.key)
    expect(model.platforms.JD.subsidyKey).toBe(shared.key)
    state.addSpecialGood(firstGoods)
    const [special] = state.form.value.specialGoods
    expect(Object.keys(special.platforms)).toHaveLength(10)
    await state.setModelPlatforms(['KUAISHOU', 'PDD'], firstGoods.id)
    expect(Object.keys(special.platforms)).toEqual(['KUAISHOU', 'PDD'])
    expect(special.platforms.KUAISHOU.quantity).toBe('')
    state.updateModelPlatform('KUAISHOU', 'quantity', '12', firstGoods.id)
    expect(special.platforms.KUAISHOU.quantity).toBe('12')
    expect(special.platforms.PDD.quantity).toBe('')
    await state.setModelPlatforms(null, firstGoods.id)
    expect(Object.keys(special.platforms)).toHaveLength(10)
    expect(special.platforms.KUAISHOU.quantity).toBe('12')
    state.updateModelPlatform('KUAISHOU', 'quantity', '', firstGoods.id)
    expect(special.platforms.KUAISHOU.quantity).toBe('')
  })

  it('取消清除时保留已填写的平台数量，确认后仍保留机型公共字段', async () => {
    const confirm = vi.fn().mockReturnValue(false)
    const state = usePolicyForm(confirm)
    await state.togglePlatform('JD')
    state.addSpecialGood(firstGoods)
    const [model] = state.form.value.specialGoods
    state.updateModelPlatform('JD', 'quantity', '0', firstGoods.id)
    await state.setModelPlatforms([], firstGoods.id)
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
    expect(state.form.value.specialGoods).toEqual([])
  })

  it('同规格不同颜色保留各自 ID，可一起加入并保留已填补贴', async () => {
    const state = usePolicyForm()
    await state.togglePlatform('JD')
    const [first, second] = catalogCenterSamples.filter((goods) => goods.model === 'Find X8')
    const anotherColor = { ...first, id: 'other-color', color: '黑色' }
    const offline = catalogCenterSamples.find((goods) => goods.status === 'offline')
    state.addGoods([first])
    const model = state.form.value.model
    state.updateSubsidy(model.subsidies[0].key, 'fixedAmount', '50.00')
    state.addGoods([anotherColor, second, offline, second])
    expect(model.goods.map(({ id }) => id)).toEqual([first.id, anotherColor.id, second.id])
    expect(model.subsidies).toHaveLength(1)
    expect(model.subsidies[0].fixedAmount).toBe('50.00')
    state.removeGoods(policyGoodsKey(first))
    expect(model.goods.map(({ id }) => id)).toEqual([anotherColor.id, second.id])
    state.addGoods([firstGoods])
    expect(model.goods.map(({ name }) => name)).toEqual([
      anotherColor.name,
      second.name,
      firstGoods.name,
    ])
    expect(model.subsidies[0].fixedAmount).toBe('50.00')
  })
})
