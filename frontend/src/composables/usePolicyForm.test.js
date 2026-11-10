import { describe, expect, it, vi } from 'vitest'
import { usePolicyForm } from './usePolicyForm'

describe('申请表单状态', () => {
  it('平台间分别维护机型和数量，全选不覆盖已填写内容', () => {
    const state = usePolicyForm()
    state.togglePlatform('JD')
    state.rows.value[0].quantity = '12'
    state.rows.value[0].fixedAmount = '0.10'
    state.togglePlatform('PDD')
    expect(state.rows.value[0].quantity).toBe('')
    state.selectAll()
    expect(state.form.value.details.JD[0].fixedAmount).toBe('0.10')
    expect(state.form.value.platforms).toHaveLength(10)
  })

  it('取消清除操作时保留原平台和渠道，金额 0 也触发确认', () => {
    const confirm = vi.fn().mockReturnValue(false)
    const state = usePolicyForm(confirm)
    state.togglePlatform('JD')
    state.rows.value[0].fixedAmount = 0
    state.togglePlatform('JD')
    expect(confirm).toHaveBeenCalledOnce()
    expect(state.form.value.platforms).toEqual(['JD'])
    expect(state.switchChannel('BILLION')).toBe(false)
    expect(state.form.value.channel).toBe('RETAIL')
    confirm.mockReturnValue(true)
    state.switchChannel('BILLION')
    state.selectAll()
    expect(state.form.value.platforms).toEqual(['PDD', 'TAOBAO', 'UNICOM'])
    expect(state.form.value.verifyType).toBeNull()
    expect(state.form.value.details.JD).toBeUndefined()
  })
})
