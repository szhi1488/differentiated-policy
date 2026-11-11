import { describe, expect, it } from 'vitest'
import { useAdminPolicies } from './useAdminPolicies'

describe('管理员政策展示筛选', () => {
  it('只有点击查询才应用条件，重置还原分页与详情', () => {
    const state = useAdminPolicies()
    state.draft.channel = '百亿渠道'
    expect(state.filtered.value).toHaveLength(7)
    state.search()
    expect(state.filtered.value.every((item) => item.channel === '百亿渠道')).toBe(true)
    state.page.value = 2
    state.selected.value = state.filtered.value[0]
    state.reset()
    expect(state.filtered.value).toHaveLength(7)
    expect(state.page.value).toBe(1)
    expect(state.selected.value).toBeNull()
  })
  it('待审队列仅显示待审，支持时间与平台组合筛选且不修改数据', () => {
    const state = useAdminPolicies(true)
    expect(state.baseRows.value.every((item) => item.status === 'PENDING')).toBe(true)
    state.draft.dates = ['2026-11-11', '2026-11-11']
    state.draft.platform = '京东'
    state.search()
    expect(state.filtered.value.map((item) => item.id)).toEqual(['demo-p1'])
    state.draft.keyword = '不存在的编号'
    state.search()
    expect(state.filtered.value).toEqual([])
    expect(state.baseRows.value).toHaveLength(3)
  })
  it('渠道切换清空不兼容的筛选条件', () => {
    const state = useAdminPolicies()
    Object.assign(state.draft, { platform: '抖音', scene: '国补', verifyType: '销售维度' })
    state.changeChannel()
    expect([state.draft.platform, state.draft.scene, state.draft.verifyType]).toEqual(['', '', ''])
  })
  it('日期可单边筛选，倒置范围提示且不应用错误条件', () => {
    const state = useAdminPolicies()
    state.draft.dates = ['2026-11-11', '']
    state.search()
    expect(state.filtered.value).toHaveLength(2)
    state.draft.dates = ['', '2026-11-09']
    state.search()
    expect(state.filtered.value).toHaveLength(2)
    state.draft.dates = ['2026-11-12', '2026-11-09']
    state.search()
    expect(state.dateError.value).toBe('开始日期不能晚于结束日期')
    expect(state.filtered.value).toHaveLength(2)
  })
})
