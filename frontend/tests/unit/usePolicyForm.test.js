import { describe, expect, it, vi } from 'vitest'
import { usePolicyForm } from '../../src/composables/usePolicyForm'

describe('申请表单状态', () => {
  it('清理使待确认动作过期，重复点击不清空新状态，按 key 修改及删除明细', async () => {
    let accept
    const confirm = vi.fn(
      () =>
        new Promise((resolve) => {
          accept = resolve
        }),
    )
    const state = usePolicyForm(confirm)
    await state.togglePlatform('JD')
    const key = state.rows.value[0].key
    state.updateDetail(key, 'fixedAmount', '0.10')
    state.addModel()
    const second = state.rows.value[1].key
    state.removeDetail(key)
    state.updateDetail(second, 'quantity', '')
    expect(state.rows.value).toHaveLength(1)
    expect(state.rows.value[0]).toMatchObject({ key: second, quantity: '' })
    state.updateDetail(second, 'fixedAmount', '0.10')
    state.updateDetail(second, 'mode', 'RATIO')
    expect(state.rows.value[0].fixedAmount).toBe('')
    const pending = state.switchChannel('BILLION')
    expect(await state.switchChannel('BILLION')).toBe(false)
    state.clear()
    accept(true)
    expect(await pending).toBe(false)
    expect(state.form.value.channel).toBe('RETAIL')
    expect(state.form.value.platforms).toEqual([])
    expect(confirm).toHaveBeenCalledOnce()
  })
  it('平台间分别维护机型和数量，全选不覆盖已填写内容', async () => {
    const state = usePolicyForm()
    await state.togglePlatform('JD')
    state.rows.value[0].quantity = '12'
    state.rows.value[0].fixedAmount = '0.10'
    await state.togglePlatform('PDD')
    expect(state.rows.value[0].quantity).toBe('')
    state.selectAll()
    expect(state.form.value.details.JD[0].fixedAmount).toBe('0.10')
    expect(state.form.value.platforms).toHaveLength(10)
  })

  it('取消清除操作时保留原平台和渠道，金额 0 也触发确认', async () => {
    const confirm = vi.fn().mockReturnValue(false)
    const state = usePolicyForm(confirm)
    await state.togglePlatform('JD')
    state.rows.value[0].fixedAmount = 0
    await state.togglePlatform('JD')
    expect(confirm).toHaveBeenCalledOnce()
    expect(state.form.value.platforms).toEqual(['JD'])
    expect(await state.switchChannel('BILLION')).toBe(false)
    expect(state.form.value.channel).toBe('RETAIL')
    confirm.mockReturnValue(true)
    await state.switchChannel('BILLION')
    state.selectAll()
    expect(state.form.value.platforms).toEqual(['PDD', 'TAOBAO', 'UNICOM'])
    expect(state.form.value.verifyType).toBeNull()
    expect(state.form.value.details.JD).toBeUndefined()
  })
})
