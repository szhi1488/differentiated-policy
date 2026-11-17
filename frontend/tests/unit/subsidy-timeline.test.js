import { describe, expect, it } from 'vitest'
import { nextTick, ref } from 'vue'
import { useSubsidyTimelineNavigation } from '../../src/composables/useSubsidyTimelineNavigation'
import {
  timelineContainsAt,
  timelinePlanCaption,
  timelineTimeFromValue,
  timelineTimeValue,
} from '../../src/utils/subsidy-timeline'

describe('补贴时间轴的展示与联动状态', () => {
  it('适用区间包含起点、排除结束时点，不把查询截止时间当成政策结束', () => {
    const segment = { startAt: '2026-10-01T09:00:00', endAt: '2026-10-12T09:00:00' }
    expect(timelineContainsAt(segment, segment.startAt)).toBe(true)
    expect(timelineContainsAt(segment, '2026-10-12T08:59:59')).toBe(true)
    expect(timelineContainsAt(segment, segment.endAt)).toBe(false)
    expect(timelineContainsAt({ ...segment, endAt: null }, '2026-11-16T12:00:00')).toBe(true)
    expect(
      timelineContainsAt(
        { ...segment, startAt: '2026-11-20T00:00:00', endAt: null },
        '2026-11-16T12:00:00',
      ),
    ).toBe(false)
  })
  it('比例标题保留计算基数，十进制字符串与固定金额不被修改', () => {
    const plan = { subsidyMode: 'RATIO', ratio: '2.50', priceBase: 'POLICY_PRICE' }
    expect(timelinePlanCaption(plan)).toBe('2.5% · 政策价')
    expect(plan.ratio).toBe('2.50')
    expect(timelinePlanCaption({ ...plan, priceBase: 'RETAIL_PRICE' })).toBe('2.5% · 零售价')
    expect(timelinePlanCaption({ subsidyMode: 'FIXED', fixedAmount: '0.00' })).toBe('固定 0 元')
    expect(timelineTimeFromValue(timelineTimeValue('2026-11-16T12:00:00'))).toBe(
      '2026-11-16T12:00:00',
    )
  })
  it('缩放将指针约束在可视范围，回写相同值不触发新状态', () => {
    const range = ref(['2026-08-01T00:00:00', '2026-11-16T12:00:00'])
    const nav = useSubsidyTimelineNavigation(range)
    nav.setZoom({ start: 20, end: 70 })
    expect(nav.cursorAt.value).toBe(nav.viewRange.value[1])
    const zoom = nav.zoom.value
    nav.setZoom({ start: 20, end: 70 })
    expect(nav.zoom.value).toBe(zoom)
    nav.setCursor('2026-08-01T00:00:00')
    expect(nav.zoom.value).toEqual({ start: 0, end: 100 })
    expect(nav.cursorAt.value).toBe(range.value[0])
    nav.showCurrent()
    expect(nav.cursorAt.value).toBe(range.value[1])
  })
  it('更换查询范围恢复两图缩放，空范围不生成指针或可视区间', async () => {
    const range = ref(['2026-08-01T00:00:00', '2026-11-16T12:00:00'])
    const nav = useSubsidyTimelineNavigation(range)
    nav.setZoom({ start: 30, end: 60 })
    range.value = ['2026-09-01T00:00:00', '2026-10-01T23:59:59']
    await nextTick()
    expect(nav.zoom.value).toEqual({ start: 0, end: 100 })
    expect(nav.cursorAt.value).toBe('2026-10-01T23:59:59')
    nav.setCursor('2026-12-01T00:00:00')
    expect(nav.cursorAt.value).toBe(range.value[1])
    range.value = ['2026-11-20T00:00:00', '2026-11-16T12:00:00']
    await nextTick()
    expect(nav.hasRange.value).toBe(false)
    expect(nav.cursorAt.value).toBe('')
    expect(nav.viewRange.value).toEqual([])
  })
})
