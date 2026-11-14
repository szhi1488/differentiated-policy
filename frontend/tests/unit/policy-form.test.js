import { BILLION_PLATFORMS, RETAIL_PLATFORMS } from '../../src/constants/policy'
import { describe, expect, it } from 'vitest'
import {
  changeChannel,
  changeSubsidy,
  createForm,
  createPolicyModel,
  createSubsidy,
} from '../../src/utils/policy-form'
describe('申请表单的渠道和平台明细', () => {
  it('切换百亿清除零售专属值与平台明细', () => {
    const form = createForm()
    form.platforms = ['JD']
    form.model = createPolicyModel()
    changeChannel(form, 'BILLION')
    expect(form.platforms).toEqual([])
    expect(form.model).toBeNull()
    expect([form.verifyType, form.scene, form.estimated]).toEqual([null, null, null])
    expect(BILLION_PLATFORMS.map(([code]) => code)).toEqual(['PDD', 'TAOBAO', 'UNICOM'])
    expect(RETAIL_PLATFORMS).toHaveLength(10)
    changeChannel(form, 'RETAIL')
    expect(form.estimated).toBe(false)
  })
  it('每张新表单独立初始化配置、平台和补贴状态', () => {
    const first = createPolicyModel()
    const second = createPolicyModel()
    first.goods.push({ config: '12+256' })
    first.subsidies[0].fixedAmount = '0.10'
    expect(second.goods).toEqual([])
    expect(second.platforms).toEqual({})
    expect(second.subsidies[0].fixedAmount).toBe('')
  })
  it('补贴方式切换清除互斥参数', () => {
    const row = { ...createSubsidy(), fixedAmount: '500.00', maxAmount: '800.00' }
    changeSubsidy(row, 'RATIO')
    expect([row.fixedAmount, row.maxAmount]).toEqual(['', ''])
    row.ratio = '15.00'
    row.priceBase = '到手价'
    changeSubsidy(row, 'FIXED')
    expect([row.ratio, row.priceBase]).toEqual(['', ''])
  })
})
