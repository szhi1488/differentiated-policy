import { BILLION_PLATFORMS, RETAIL_PLATFORMS } from '../../src/constants/policy'
import { describe, expect, it } from 'vitest'
import { changeChannel, changeSubsidy, createDetail, createForm } from '../../src/utils/policy-form'
describe('申请表单的渠道和平台明细', () => {
  it('切换百亿清除零售专属值与平台明细', () => {
    const form = createForm()
    form.platforms = ['JD']
    form.details.JD = [createDetail()]
    changeChannel(form, 'BILLION')
    expect(form.platforms).toEqual([])
    expect(form.details).toEqual({})
    expect([form.verifyType, form.scene, form.estimated]).toEqual([null, null, null])
    expect(BILLION_PLATFORMS.map(([code]) => code)).toEqual(['PDD', 'TAOBAO', 'UNICOM'])
    expect(RETAIL_PLATFORMS).toHaveLength(10)
    changeChannel(form, 'RETAIL')
    expect(form.estimated).toBe(false)
  })
  it('各平台机型独立保存，未填数量不转为零，金额保持十进制字符串', () => {
    const form = createForm()
    form.details = { JD: [createDetail()], PDD: [createDetail()] }
    form.details.JD[0].quantity = '12'
    form.details.JD[0].fixedAmount = '0.10'
    expect(form.details.PDD[0].quantity).toBe('')
    expect(form.details.PDD[0].fixedAmount).toBe('')
    expect(form.details.JD[0].fixedAmount).toBe('0.10')
  })
  it('补贴方式切换清除互斥参数', () => {
    const row = { ...createDetail(), fixedAmount: '500.00', maxAmount: '800.00' }
    changeSubsidy(row, 'RATIO')
    expect([row.fixedAmount, row.maxAmount]).toEqual(['', ''])
    row.ratio = '15.00'
    row.priceBase = '到手价'
    changeSubsidy(row, 'FIXED')
    expect([row.ratio, row.priceBase]).toEqual(['', ''])
  })
})
