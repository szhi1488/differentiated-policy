// @vitest-environment jsdom
import { effectScope } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { readGoodsJobs, readSourceGoods, startGoodsJob } from '../../src/api/catalog'
import { useSourceGoods } from '../../src/composables/useSourceGoods'

vi.mock('../../src/api/catalog', () => ({
  cancelGoodsJob: vi.fn(),
  readGoodsJobs: vi.fn(),
  readSourceGoods: vi.fn(),
  resumeGoodsJob: vi.fn(),
  startGoodsJob: vi.fn(),
}))

const brand = '一加手机'
const job = (status, canResume = false) => ({ id: '1', brandName: brand, status, canResume })
const overview = (...jobs) => ({ configured: true, jobs })
let scope

beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  scope = effectScope()
  readGoodsJobs.mockResolvedValue(overview())
  readSourceGoods.mockResolvedValue({ items: [], total: 0 })
})

afterEach(() => {
  scope.stop()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

it('空闲不再读任务，开窗核对期间禁止发起导入，新发现的已完成任务会刷新商品', async () => {
  const source = scope.run(useSourceGoods)
  await vi.advanceTimersByTimeAsync(60_000)
  expect(readGoodsJobs).toHaveBeenCalledTimes(1)
  const pending = Promise.withResolvers()
  readGoodsJobs.mockReturnValueOnce(pending.promise)
  source.importOpen.value = true
  expect(source.jobsReady.value).toBe(false)
  await source.start(brand, { mode: 'FULL', date: null })
  expect(startGoodsJob).not.toHaveBeenCalled()
  pending.resolve(overview(job('SUCCEEDED')))
  await vi.advanceTimersByTimeAsync(60_000)
  expect(source.jobsReady.value).toBe(true)
  expect(readGoodsJobs).toHaveBeenCalledTimes(2)
  expect(readSourceGoods).toHaveBeenCalledTimes(2)
})

it('排队与运行时持续查询，读取故障后恢复，成功时刷新列表并停止', async () => {
  readGoodsJobs
    .mockResolvedValueOnce(overview(job('QUEUED')))
    .mockRejectedValueOnce(new Error('暂时无法读取进度'))
    .mockResolvedValueOnce(overview(job('RUNNING')))
    .mockResolvedValue(overview(job('SUCCEEDED')))
  const source = scope.run(useSourceGoods)
  await vi.advanceTimersByTimeAsync(3999)
  expect(readGoodsJobs).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(1)
  expect(source.jobsError.value).toBe('暂时无法读取进度')
  await vi.advanceTimersByTimeAsync(4000)
  expect(source.activeJob.value.status).toBe('RUNNING')
  expect(source.jobsError.value).toBe('')
  await vi.advanceTimersByTimeAsync(60_000)
  expect(readGoodsJobs).toHaveBeenCalledTimes(4)
  expect(readSourceGoods).toHaveBeenCalledTimes(2)
})

it('暂停任务仅在弹窗内等待冷却，允许续跑后停止自动查询', async () => {
  readGoodsJobs.mockResolvedValue(overview(job('PAUSED')))
  const source = scope.run(useSourceGoods)
  await vi.advanceTimersByTimeAsync(60_000)
  expect(readGoodsJobs).toHaveBeenCalledTimes(1)
  source.importOpen.value = true
  await vi.advanceTimersByTimeAsync(4000)
  expect(readGoodsJobs).toHaveBeenCalledTimes(3)
  source.importOpen.value = false
  await vi.advanceTimersByTimeAsync(60_000)
  expect(readGoodsJobs).toHaveBeenCalledTimes(3)
  source.importOpen.value = true
  await vi.advanceTimersByTimeAsync(0)
  readGoodsJobs.mockResolvedValue(overview(job('PAUSED', true)))
  await vi.advanceTimersByTimeAsync(60_000)
  expect(readGoodsJobs).toHaveBeenCalledTimes(5)
  expect(source.activeJob.value.canResume).toBe(true)
})
