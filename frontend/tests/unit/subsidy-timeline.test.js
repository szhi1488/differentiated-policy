import { describe, expect, it } from 'vitest'
import { nextTick, ref } from 'vue'
import { useSubsidyTimelineNavigation } from '../../src/composables/useSubsidyTimelineNavigation'
import { readTimelineHistory } from '../../src/utils/policy-timeline'
import {
  billionGroupHistory,
  history,
  mixedBillionHistory,
  retailGroupHistory,
} from '../fixtures/subsidy-history'
import {
  timelineContainsAt,
  timelinePlanCaption,
  timelineTimeFromValue,
  timelineTimeValue,
} from '../../src/utils/subsidy-timeline'

describe('零售时间轴按当段商品范围展示', () => {
  it('整组原单单独出现时只显示一行；同轮次各商品补贴金额不同则分别显示', () => {
    const sample = retailGroupHistory()
    const [first, rest] = sample.tracks
    const whole = structuredClone(rest)
    whole.goodsIds.unshift('61')
    whole.policies[0].selections[0].products.unshift(first.policies[0].selections[0].products[0])
    expect(
      readTimelineHistory({ ...sample, tracks: [whole] }).rows.map((row) => row.segment.label),
    ).toEqual(['整组配置'])

    const different = {
      ...rest,
      goodsIds: first.goodsIds,
      policies: [first.policies[0]],
    }
    different.policies[0].selections[0].subsidy = {
      subsidyMode: 'FIXED',
      fixedAmount: '0.20',
      maxSubsidyAmount: null,
    }
    const { rows } = readTimelineHistory({ ...sample, tracks: [different, rest] })
    expect(rows.map((row) => row.plan.fixedAmount).sort()).toEqual(['0.10', '0.20'])
    expect(rows.some((row) => row.segment.label === '整组配置')).toBe(false)
  })

  it.each(['COMMON', 'SPECIFIED'])('单个商品的 %s 政策只在其时段拆开整组', (cardType) => {
    const { segments, rows } = readTimelineHistory(retailGroupHistory(cardType))
    function slices(at) {
      return segments.filter((part) => timelineContainsAt(part, '2026-11-' + at))
    }
    function baseRows(at) {
      return rows.filter(
        (row) =>
          row.plan.policyCode === 'ZC-000023' &&
          row.parts.some((part) => timelineContainsAt(part, '2026-11-' + at)),
      )
    }
    for (const time of ['14T23:59:59', '18T00:00:00']) {
      expect(slices(time)).toHaveLength(1)
      const [whole] = slices(time)
      expect(whole.label).toBe('整组配置')
      expect(whole.policies.map((plan) => plan.policyCode)).toEqual(['ZC-000023'])
      expect(whole.policies[0].goods.map((g) => g.id).sort()).toEqual(['61', '62', '63'])
      expect(baseRows(time).map((row) => row.segment.label)).toEqual(['整组配置'])
    }
    for (const time of ['15T00:00:00', '16T12:00:00', '16T12:00:01', '17T23:59:59']) {
      const parts = slices(time)
      expect(parts).toHaveLength(2)
      const brown = parts.find((part) => part.goods.some((g) => g.id === '61'))
      const others = parts.find((part) => !part.goods.some((g) => g.id === '61'))
      expect(brown.goods.map((p) => p.id)).toEqual(['61'])
      expect(others.goods.map((p) => p.id).sort()).toEqual(['62', '63'])
      expect(brown.policies[0].policyCode).toBe('ZC-000024')
      expect(brown.excluded[0].policyCode).toBe('ZC-000023')
      expect(others.policies[0].policyCode).toBe('ZC-000023')
      expect(
        baseRows(time)
          .map((row) => row.segment.label)
          .sort(),
      ).toEqual([brown.label, others.label].sort())
    }
    expect(slices('16T12:00:00').every((s) => !s.planned)).toBe(true)
    expect(slices('16T12:00:01').every((s) => s.planned)).toBe(true)
    expect(slices('21T00:00:00')).toHaveLength(0)
  })

  it('仅部分配置商品命中、不同平台合计覆盖、旧响应缺少总数时均保留具体商品范围', () => {
    const sample = retailGroupHistory()
    const [one, two] = sample.tracks
    for (const response of [
      { ...sample, tracks: [two] },
      { ...sample, tracks: [{ ...one, platformCode: 'TMALL' }, two] },
      { asOf: sample.asOf, tracks: sample.tracks },
    ]) {
      const { segments, rows } = readTimelineHistory(response)
      expect(segments.length).toBeGreaterThan(0)
      expect(segments.some((s) => s.label === '整组配置')).toBe(false)
      expect(rows.some((r) => r.segment.label === '整组配置')).toBe(false)
    }
  })

  it('同名平台的零售与百亿分别分组，百亿历史商品轨道保留原定范围', () => {
    const sample = retailGroupHistory()
    const modern = { ...billionGroupHistory().tracks[0], platformCode: 'JD' }
    const legacy = { ...history.tracks[0], perspective: 'BILLION', platformCode: 'PDD' }
    const { segments } = readTimelineHistory({
      ...sample,
      tracks: [...sample.tracks, modern, legacy],
    })
    const billion = segments.filter((s) => s.channel === 'BILLION')
    const jd = billion.filter((s) => s.platformCode === 'JD')
    expect(
      jd.filter((s) => timelineContainsAt(s, '2026-11-18T00:00:00')).map((s) => s.label),
    ).toEqual(['整组配置'])
    expect(
      jd
        .filter((s) => timelineContainsAt(s, '2026-11-15T00:00:00'))
        .map((s) => s.label)
        .sort(),
    ).toEqual(['初雪白、奇遇喵', '可可棕'].sort())
    expect([
      ...new Set(billion.filter((s) => s.platformCode === 'PDD').map((s) => s.label)),
    ]).toEqual(['浮光白'])
  })
})

describe('百亿时间轴按原单商品展示，保留配置级审核结果', () => {
  it('混合轨按秒切换逐商品与配置级审核，相同配置段只算一次并按平台隔开', () => {
    const sample = mixedBillionHistory()
    const [jd] = billionGroupHistory().tracks
    jd.platformCode = 'JD'
    jd.policies = [jd.policies[0]]
    const sameRound = (part) => ({
      ...part,
      adoptedAuditLogIds: ['1023'],
      excludedAuditLogIds: [],
      configurationDecision: true,
    })
    jd.segments = jd.segments.map(sameRound)
    jd.plannedSegments = jd.plannedSegments.map(sameRound)
    const { rows, segments } = readTimelineHistory({ ...sample, tracks: [...sample.tracks, jd] })
    function at(time, platform = 'PDD') {
      return segments.filter(
        (s) => s.platformCode === platform && timelineContainsAt(s, '2026-11-' + time),
      )
    }
    const old = at('07T23:59:59')
    expect(old).toHaveLength(2)
    for (const s of old) {
      expect(s.configurationDecision).toBeFalsy()
      expect(s.label).toBe(s.goods.length === 1 ? '可可棕' : '初雪白、奇遇喵')
      expect(s.policies.map((p) => p.policyCode)).toEqual([
        s.goods.length === 1 ? 'ZC-000021' : 'ZC-000022',
      ])
    }
    for (const time of ['08T00:00:00', '14T23:59:59', '18T00:00:00']) {
      const full = at(time)
      expect(full).toHaveLength(1)
      expect(full[0]).toMatchObject({ configurationDecision: true, label: '整组配置' })
      expect(full[0].policies.map((p) => p.policyCode)).toEqual(['ZC-000023'])
    }
    for (const time of ['15T00:00:00', '16T12:00:00', '16T12:00:01', '17T23:59:59']) {
      const split = at(time)
      expect(split.map((s) => s.label).sort()).toEqual(['可可棕', '初雪白、奇遇喵'].sort())
      for (const scope of split) {
        expect(scope.configurationDecision).toBe(true)
        expect([...scope.policies, ...scope.excluded].map((p) => p.policyCode).sort()).toEqual([
          'ZC-000023',
          'ZC-000024',
        ])
      }
      const pddRows = rows.filter(
        (r) =>
          r.segment.platformCode === 'PDD' &&
          r.parts.some((p) => timelineContainsAt(p, '2026-11-' + time)),
      )
      expect(pddRows).toHaveLength(3)
      expect(at(time, 'JD').map((s) => s.label)).toEqual(['整组配置'])
    }
  })

  it.each(['COMMON', 'SPECIFIED'])(
    '单色 %s 原单仅在重叠时段拆组，换单和叠加保留整份名单',
    (type) => {
      const { rows, segments } = readTimelineHistory(billionGroupHistory(type))
      const active = (items, time) => items.filter((p) => timelineContainsAt(p, '2026-11-' + time))
      const rowsAt = (time) => rows.filter((row) => active(row.parts, time).length)
      for (const time of ['14T23:59:59', '18T00:00:00', '20T23:59:59']) {
        const slice = active(segments, time)
        expect(slice).toHaveLength(1)
        expect(slice[0]).toMatchObject({ label: '整组配置', fullConfiguration: true })
        expect(rowsAt(time).map((row) => row.segment.label)).toEqual(['整组配置'])
      }
      for (const [time, stacked, planned] of [
        ['15T00:00:00', false, false],
        ['16T12:00:00', false, false],
        ['16T12:00:01', false, true],
        ['17T00:00:00', true, true],
        ['17T23:59:59', true, true],
      ]) {
        const slices = active(segments, time)
        expect(slices.map((s) => s.label).sort()).toEqual(['初雪白、奇遇喵', '可可棕'].sort())
        const baseRows = rowsAt(time).filter((row) => row.plan.policyCode === 'ZC-000023')
        expect(baseRows.map((row) => row.segment.label).sort()).toEqual(
          slices.map((s) => s.label).sort(),
        )
        for (const row of baseRows) {
          expect(active(row.parts, time)[0].used).toBe(stacked)
        }
        const [single] = rowsAt(time).filter((row) => row.plan.policyCode === 'ZC-000024')
        expect(single.segment.label).toBe(type === 'SPECIFIED' ? '独立商品 · 可可棕' : '可可棕')
        for (const slice of slices) {
          expect(slice).toMatchObject({
            fullConfiguration: false,
            configurationDecision: true,
            planned,
            decision: stacked ? '同配置叠加' : '单独适用',
          })
          expect(slice.policies.map((p) => p.policyCode).sort()).toEqual(
            stacked ? ['ZC-000023', 'ZC-000024'] : ['ZC-000024'],
          )
          expect(slice.excluded.map((p) => p.policyCode)).toEqual(stacked ? [] : ['ZC-000023'])
          const scopeGoods = slice.goods.map((g) => g.id).sort()
          expect(scopeGoods).toEqual(slice.label === '可可棕' ? ['61'] : ['62', '63'])
          const allPlans = [...slice.policies, ...slice.excluded]
          const original = allPlans.find((p) => p.policyCode === 'ZC-000023')
          expect(original.goods.map((g) => g.id).sort()).toEqual(['61', '62', '63'])
          expect(original.fixedAmount).toBe('0.10')
          const singlePlan = allPlans.find((p) => p.policyCode === 'ZC-000024')
          expect(singlePlan.goods.map((g) => g.id)).toEqual(['61'])
          expect(singlePlan.ratio).toBe('2.50')
          if (type === 'SPECIFIED') {
            expect(singlePlan.quantity).toBe(0)
          }
        }
      }
      expect(active(segments, '21T00:00:00')).toHaveLength(0)
    },
  )

  it('单报部分普通商品显示颜色，缺少配置总数的历史配置轨沿用原始范围', () => {
    const partial = billionGroupHistory()
    partial.tracks[0].policies[0].selections[0].products.pop()
    const projected = readTimelineHistory(partial)
    expect(projected.rows.some((row) => row.segment.label === '整组配置')).toBe(false)
    expect(projected.segments.some((slice) => slice.label === '整组配置')).toBe(false)
    const { asOf, tracks } = billionGroupHistory()
    const historical = readTimelineHistory({ asOf, tracks })
    expect(new Set(historical.segments.map((slice) => slice.label))).toEqual(new Set(['整组配置']))
  })

  it('原单没有已知时段时仍能看到完整或部分范围，不补画采用时段；无色名的商品按 ID 显示', () => {
    const sample = billionGroupHistory()
    const [track] = sample.tracks
    track.segments = []
    delete track.plannedSegments
    track.policies[1].selections[0].products = [{ goodsId: '61' }]
    const { rows, segments } = readTimelineHistory(sample)
    expect(segments).toEqual([])
    expect(rows.map((r) => [r.plan.policyCode, r.segment.label, r.parts])).toEqual([
      ['ZC-000023', '整组配置', []],
      ['ZC-000024', '#61', []],
    ])
  })

  it('同单不同普通卡合起来覆盖全配置且补贴一致时只画一行，原两项分配可核对；上限不同须拆开', () => {
    const sample = billionGroupHistory()
    const [track] = sample.tracks
    const allocations = track.policies[0].selections
    const [first] = allocations
    const rest = first.products.splice(1)
    allocations.push({
      ...first,
      products: rest,
      subsidy: { ...first.subsidy, fixedAmount: '0.1' },
    })
    const at = '2026-11-14T23:59:59'
    function beforeSingle() {
      const { rows, segments } = readTimelineHistory(sample)
      return {
        rows: rows.filter((r) => r.parts.some((p) => timelineContainsAt(p, at))),
        segments: segments.filter((p) => timelineContainsAt(p, at)),
      }
    }
    const uniform = beforeSingle()
    expect(uniform.rows.map((r) => r.segment.label)).toEqual(['整组配置'])
    expect(uniform.segments).toHaveLength(1)
    expect(uniform.segments[0].policies.map((p) => p.goods.map((g) => g.id))).toEqual([
      ['61'],
      ['62', '63'],
    ])
    allocations[1].subsidy.maxSubsidyAmount = '1.00'
    const different = beforeSingle()
    expect(different.rows.map((r) => r.segment.label).sort()).toEqual(
      ['可可棕', '初雪白、奇遇喵'].sort(),
    )
    expect(different.segments).toHaveLength(2)
  })
})

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
