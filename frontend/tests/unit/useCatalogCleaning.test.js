// @vitest-environment jsdom
import { effectScope, nextTick } from 'vue'
import { beforeEach, expect, it, vi } from 'vitest'
import {
  readActiveCleaningJob,
  readCleaningJob,
  readCleaningJobs,
  readCleaningPreview,
  readCleaningResults,
  startCleaningJob,
} from '../../src/api/catalog'
import { useCatalogCleaning } from '../../src/composables/useCatalogCleaning'

vi.mock('../../src/api/catalog', () => ({
  readActiveCleaningJob: vi.fn(),
  readCleaningJob: vi.fn(),
  readCleaningJobs: vi.fn(),
  readCleaningPreview: vi.fn(),
  readCleaningResults: vi.fn(),
  readCleaningSource: vi.fn(),
  retryCleaningSource: vi.fn(),
  reviewCleaningSource: vi.fn(),
  startCleaningJob: vi.fn(),
}))

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(readActiveCleaningJob).mockResolvedValue(null)
  vi.mocked(readCleaningJobs).mockResolvedValue({ items: [], total: 0 })
  vi.mocked(readCleaningResults).mockResolvedValue({ items: [], total: 0, counts: {} })
  vi.mocked(readCleaningPreview).mockResolvedValue({ items: [], total: 1, eligible: 3 })
})

it('空闲时停止轮询，打开任务弹窗各查一次，切回可见页面刷新结果和已打开的记录', async () => {
  vi.useFakeTimers()
  const scope = effectScope()
  const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  try {
    const cleaning = scope.run(useCatalogCleaning)
    await vi.advanceTimersByTimeAsync(30000)
    expect(readActiveCleaningJob).toHaveBeenCalledTimes(1)

    cleaning.openStart()
    await vi.advanceTimersByTimeAsync(30000)
    expect(readActiveCleaningJob).toHaveBeenCalledTimes(2)
    cleaning.startOpen.value = false
    cleaning.openHistory()
    await vi.advanceTimersByTimeAsync(30000)
    expect(readActiveCleaningJob).toHaveBeenCalledTimes(3)
    expect(readCleaningJobs).toHaveBeenCalledTimes(1)

    hidden.mockReturnValue(true)
    document.dispatchEvent(new Event('visibilitychange'))
    vi.mocked(readCleaningResults).mockResolvedValue({ items: [{ id: '12' }], total: 1 })
    hidden.mockReturnValue(false)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(30000)
    expect(readActiveCleaningJob).toHaveBeenCalledTimes(4)
    expect(cleaning.table.data.value.items).toEqual([{ id: '12' }])
    expect(readCleaningJobs).toHaveBeenCalledTimes(2)
  } finally {
    scope.stop()
    hidden.mockRestore()
    vi.useRealTimers()
  }
})

it('运行时持续查询，隐藏时取消在途请求，切回读取终态后停止', async () => {
  vi.useFakeTimers()
  const scope = effectScope()
  const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  const job = { id: '19', status: 'RUNNING', processed: 2 }
  let finishOldRead
  vi.mocked(readActiveCleaningJob)
    .mockResolvedValueOnce(job)
    .mockImplementationOnce(() => new Promise((resolve) => (finishOldRead = resolve)))
  try {
    const cleaning = scope.run(useCatalogCleaning)
    await vi.advanceTimersByTimeAsync(2500)
    expect(cleaning.active.value).toEqual(job)
    expect(readActiveCleaningJob).toHaveBeenCalledTimes(2)
    const signal = vi.mocked(readActiveCleaningJob).mock.calls[1][0]
    hidden.mockReturnValue(true)
    document.dispatchEvent(new Event('visibilitychange'))
    expect(signal.aborted).toBe(true)
    await vi.advanceTimersByTimeAsync(30000)
    expect(readActiveCleaningJob).toHaveBeenCalledTimes(2)

    hidden.mockReturnValue(false)
    document.dispatchEvent(new Event('visibilitychange'))
    finishOldRead(job)
    await vi.advanceTimersByTimeAsync(30000)
    expect(cleaning.active.value).toBeNull()
    expect(readActiveCleaningJob).toHaveBeenCalledTimes(3)
  } finally {
    scope.stop()
    hidden.mockRestore()
    vi.useRealTimers()
  }
})

it('自己的任务结束时若详情暂时失败，继续核对终态并刷新结果', async () => {
  vi.useFakeTimers()
  const scope = effectScope()
  const job = { id: '18', status: 'RUNNING', processed: 0 }
  const completed = { ...job, status: 'COMPLETED', processed: 3 }
  vi.mocked(startCleaningJob).mockResolvedValue(job)
  vi.mocked(readCleaningJob)
    .mockRejectedValueOnce(new Error('暂时无法读取任务'))
    .mockResolvedValueOnce(completed)
  try {
    const cleaning = scope.run(useCatalogCleaning)
    cleaning.openStart()
    Object.assign(cleaning.scope, { brandName: '一加手机', dateMode: 'ALL' })
    await vi.advanceTimersByTimeAsync(0)
    expect(await cleaning.start(cleaning.confirmedScope())).toBe(true)
    await vi.advanceTimersByTimeAsync(0)
    expect(cleaning.progressError.value).toBe('暂时无法读取任务')
    expect(cleaning.submittedJob.value).toEqual(job)
    vi.mocked(readCleaningResults).mockClear()
    vi.mocked(readActiveCleaningJob).mockClear()
    await vi.advanceTimersByTimeAsync(30000)
    expect(cleaning.submittedJob.value).toEqual(completed)
    expect(cleaning.progressError.value).toBe('')
    expect(readCleaningResults).toHaveBeenCalledTimes(1)
    expect(readActiveCleaningJob).toHaveBeenCalledTimes(1)
  } finally {
    scope.stop()
    vi.useRealTimers()
  }
})

it('默认跨品牌查询，未知建档日和已通过页签使用同一范围', async () => {
  const scope = effectScope()
  try {
    const cleaning = scope.run(useCatalogCleaning)
    expect(readCleaningResults).toHaveBeenCalledWith(
      expect.objectContaining({ brandName: '', unknownDate: false, status: '' }),
      expect.any(AbortSignal),
    )
    Object.assign(cleaning.filters, { brandName: 'OPPO手机', dateMode: 'UNKNOWN', keyword: ' A5 ' })
    cleaning.search()
    cleaning.selectTab('PASSED')
    await nextTick()
    expect(readCleaningResults).toHaveBeenLastCalledWith(
      expect.objectContaining({
        brandName: 'OPPO手机',
        unknownDate: true,
        keyword: 'A5',
        status: 'PASSED',
      }),
      expect.any(AbortSignal),
    )
  } finally {
    scope.stop()
  }
})

it('清洗预览关键词只筛表，确认后按品牌和日期提交', async () => {
  const scope = effectScope()
  const completed = { id: '1', status: 'COMPLETED' }
  vi.mocked(startCleaningJob).mockResolvedValue(completed)
  try {
    const cleaning = scope.run(useCatalogCleaning)
    cleaning.openStart()
    cleaning.scope.brandName = '一加手机'
    await nextTick()
    expect(cleaning.confirmedScope()).toBeNull()
    expect(startCleaningJob).not.toHaveBeenCalled()

    cleaning.scope.createdDate = '2026-11-12'
    cleaning.previewSearch.value = ' 搜索项 '
    cleaning.searchPreview()
    await vi.waitFor(() => expect(cleaning.preview.data.value?.eligible).toBe(3))
    expect(readCleaningPreview).toHaveBeenLastCalledWith(
      expect.objectContaining({
        brandName: '一加手机',
        createdDate: '2026-11-12',
        keyword: '搜索项',
      }),
      expect.any(AbortSignal),
    )
    const request = cleaning.confirmedScope()
    expect(await cleaning.start({ ...request, expectedTotal: 99 })).toBe(false)
    expect(await cleaning.start(request)).toBe(true)
    expect(startCleaningJob).toHaveBeenCalledWith(
      {
        brandName: '一加手机',
        createdDate: '2026-11-12',
        includeReviews: false,
        expectedTotal: 3,
        requestKey: expect.any(String),
      },
      expect.any(AbortSignal),
    )
    expect(cleaning.submittedJob.value).toEqual(completed)
  } finally {
    scope.stop()
  }
})
