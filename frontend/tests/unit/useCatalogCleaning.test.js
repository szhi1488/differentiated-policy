import { effectScope, nextTick } from 'vue'
import { beforeEach, expect, it, vi } from 'vitest'
import {
  readActiveCleaningJob,
  readCleaningPreview,
  readCleaningResults,
  startCleaningJob,
} from '../../src/api/catalog'
import { useCatalogCleaning } from '../../src/composables/useCatalogCleaning'

vi.mock('../../src/api/catalog', () => ({
  readActiveCleaningJob: vi.fn(),
  readCleaningJob: vi.fn(),
  readCleaningJobs: vi.fn(),
  readCleaningLogs: vi.fn(),
  readCleaningPreview: vi.fn(),
  readCleaningResults: vi.fn(),
  readCleaningSource: vi.fn(),
  readCleaningSourceLogs: vi.fn(),
  retryCleaningSource: vi.fn(),
  reviewCleaningSource: vi.fn(),
  startCleaningJob: vi.fn(),
}))

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(readActiveCleaningJob).mockResolvedValue(null)
  vi.mocked(readCleaningResults).mockResolvedValue({ items: [], total: 0, counts: {} })
  vi.mocked(readCleaningPreview).mockResolvedValue({ items: [], total: 1, eligible: 3 })
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
