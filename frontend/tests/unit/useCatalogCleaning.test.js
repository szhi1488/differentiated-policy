import { expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { readCleaningResults, readLatestCleaningJob, startCleaningJob } from '../../src/api/catalog'
import { useCatalogCleaning } from '../../src/composables/useCatalogCleaning'

vi.mock('../../src/api/catalog', () => ({
  readCleaningResults: vi.fn(),
  readLatestCleaningJob: vi.fn(),
  startCleaningJob: vi.fn(),
  readCleaningLogs: vi.fn(),
  readCleaningSource: vi.fn(),
  reprocessCleaningReviews: vi.fn(),
  retryCleaningJob: vi.fn(),
  retryCleaningSource: vi.fn(),
  reviewCleaningSource: vi.fn(),
}))

it('需要明确选择品牌，启动时使用该品牌的批次和版本', async () => {
  const oppo = { brandName: 'OPPO手机', importJobId: '2', recordVersion: 3, status: 'NOT_STARTED' }
  const oneplus = {
    brandName: '一加手机',
    importJobId: '5',
    recordVersion: 8,
    status: 'NOT_STARTED',
  }
  vi.mocked(readLatestCleaningJob).mockImplementation(async (brand) =>
    brand === 'OPPO手机' ? oppo : oneplus,
  )
  vi.mocked(readCleaningResults).mockResolvedValue({ items: [], total: 0 })
  vi.mocked(startCleaningJob).mockImplementation(async (job) => ({ ...job, status: 'RUNNING' }))
  const scope = effectScope()
  try {
    const cleaning = scope.run(useCatalogCleaning)
    await vi.waitFor(() => expect(cleaning.job.value).toEqual(oneplus))
    await cleaning.start()
    expect(startCleaningJob).not.toHaveBeenCalled()

    cleaning.selectBrand('OPPO手机')
    expect(cleaning.canStart.value).toBe(true)
    await cleaning.start()
    expect(startCleaningJob).toHaveBeenLastCalledWith(oppo, expect.any(AbortSignal))

    cleaning.selectBrand('一加手机')
    cleaning.reset()
    expect(cleaning.brand.value).toBe('一加手机')
    await cleaning.start()
    expect(startCleaningJob).toHaveBeenLastCalledWith(oneplus, expect.any(AbortSignal))
  } finally {
    scope.stop()
  }
})
