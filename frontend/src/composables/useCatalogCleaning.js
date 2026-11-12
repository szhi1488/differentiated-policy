import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import {
  readCleaningLogs,
  readCleaningResults,
  readCleaningSource,
  readLatestCleaningJob,
  reprocessCleaningReviews,
  retryCleaningJob,
  retryCleaningSource,
  reviewCleaningSource,
  startCleaningJob,
} from '../api/catalog'
import { GOODS_BRANDS } from '../constants/catalog'
import { useLatestQuery } from './useLatestQuery'

const emptyFilters = { status: '', ruleVersion: '', keyword: '' }
const changed = (before, after) =>
  before?.status !== after?.status ||
  before?.recordVersion !== after?.recordVersion ||
  before?.processed !== after?.processed ||
  before?.total !== after?.total ||
  before?.reprocessable !== after?.reprocessable ||
  Object.entries(after?.resultCounts ?? {}).some(
    ([status, count]) => before?.resultCounts[status] !== count,
  )

export function useCatalogCleaning() {
  const filters = reactive({ ...emptyFilters })
  const applied = ref({ ...emptyFilters })
  const brand = ref('')
  const tab = ref('all')
  const page = ref(1)
  const size = ref(10)
  const total = ref(0)
  const jobs = ref([])
  const jobsLoading = ref(true)
  const jobsError = ref('')
  const action = ref('')
  const actionError = ref('')
  const reviewError = ref('')
  const reviewConflict = ref(false)
  const detailId = ref('')
  const logsJobId = ref('')
  const logsPage = ref(1)
  const logsTotal = ref(0)
  const logsSize = 10
  const table = useLatestQuery()
  const detail = useLatestQuery()
  const logs = useLatestQuery()
  let timer
  let jobRead
  let write
  let disposed = false

  const job = computed(() => {
    if (brand.value) {
      return jobs.value.find((item) => item.brandName === brand.value)
    }
    return jobs.value.reduce(
      (latest, item) =>
        !latest || BigInt(item.importJobId) > BigInt(latest.importJobId) ? item : latest,
      null,
    )
  })
  const jobId = computed(() => job.value?.importJobId ?? '')
  const ruleVersions = computed(() => [...new Set(jobs.value.map((item) => item.ruleVersion))])
  const busy = computed(() => Boolean(action.value))
  const canChangeTask = computed(() => Boolean(brand.value) && !jobsError.value && !busy.value)
  const canStart = computed(() => canChangeTask.value && job.value?.status === 'NOT_STARTED')
  const canRetryJob = computed(
    () =>
      canChangeTask.value &&
      ['FAILED', 'PARTIAL'].includes(job.value?.status) &&
      job.value.resultCounts.ERROR > 0,
  )
  const canReprocess = computed(
    () => canChangeTask.value && job.value?.status !== 'RUNNING' && job.value?.reprocessable > 0,
  )

  function loadResults() {
    if (!jobId.value) {
      return table.run(async () => ({ items: [], total: 0 }))
    }
    return table.run((signal) =>
      readCleaningResults(
        {
          ...applied.value,
          status: tab.value === 'all' ? applied.value.status : tab.value,
          importJobId: jobId.value,
          page: page.value,
          size: size.value,
        },
        signal,
      ),
    )
  }

  watch(
    jobId,
    () => {
      page.value = 1
      total.value = 0
    },
    { flush: 'sync' },
  )
  watch([jobId, applied, tab, page, size], loadResults)
  watch(table.data, (result) => {
    if (result) {
      total.value = result.total
      page.value = Math.min(page.value, Math.max(1, Math.ceil(total.value / size.value)))
    }
  })

  function search() {
    page.value = 1
    if (filters.status) {
      tab.value = 'all'
    }
    applied.value = { ...filters, keyword: filters.keyword.trim() }
  }

  function reset() {
    Object.assign(filters, emptyFilters)
    tab.value = 'all'
    search()
  }

  function selectBrand(value) {
    brand.value = value ?? ''
    actionError.value = ''
    reset()
  }

  function selectTab(value) {
    tab.value = value
    filters.status = ''
    applied.value = { ...applied.value, status: '' }
    page.value = 1
  }

  function resize(value) {
    page.value = 1
    size.value = value
  }

  function loadDetail() {
    if (detailId.value) {
      reviewError.value = ''
      reviewConflict.value = false
      return detail.run((signal) => readCleaningSource(detailId.value, signal))
    }
  }

  function openDetail(row) {
    detailId.value = row.id
    void loadDetail()
  }

  function loadLogs() {
    if (logsJobId.value) {
      return logs.run((signal) =>
        readCleaningLogs(logsJobId.value, logsPage.value, logsSize, signal),
      )
    }
  }

  function openLogs() {
    logsTotal.value = 0
    logsPage.value = 1
    logsJobId.value = jobId.value
  }

  watch([logsJobId, logsPage], loadLogs)
  watch(logs.data, (result) => {
    if (result) {
      logsTotal.value = result.total
    }
  })

  async function refreshJobs({ refresh = false } = {}) {
    if (disposed || busy.value) {
      return
    }
    clearTimeout(timer)
    jobRead?.abort()
    const controller = new AbortController()
    jobRead = controller
    try {
      const next = await Promise.all(
        GOODS_BRANDS.map((brand) => readLatestCleaningJob(brand, controller.signal)),
      )
      if (controller.signal.aborted) {
        return
      }
      const before = job.value
      jobs.value = next.filter(Boolean)
      jobsError.value = ''
      if (before?.importJobId === jobId.value && (refresh || changed(before, job.value))) {
        void loadResults()
      }
      if (logsJobId.value && (refresh || changed(before, job.value))) {
        void loadLogs()
      }
    } catch (error) {
      if (!controller.signal.aborted) {
        jobsError.value = error.message
      }
    } finally {
      if (!controller.signal.aborted) {
        jobsLoading.value = false
        timer = setTimeout(
          refreshJobs,
          jobs.value.some((item) => item.status === 'RUNNING') ? 2000 : 10000,
        )
      }
    }
  }

  async function mutate(key, send) {
    if (busy.value || disposed) {
      return false
    }
    clearTimeout(timer)
    jobRead?.abort()
    action.value = key
    actionError.value = ''
    reviewError.value = ''
    write = new AbortController()
    try {
      const result = await send(write.signal)
      if (disposed) {
        return false
      }
      if (key === 'review') {
        detailId.value = ''
      } else {
        jobs.value = jobs.value.map((item) =>
          item.importJobId === result.importJobId ? result : item,
        )
      }
      return true
    } catch (error) {
      if (!disposed) {
        const message =
          error.kind === 'business' ? error.message : '暂无法确认操作结果，请核对处理状态后再试。'
        if (key === 'review') {
          reviewError.value = message
          reviewConflict.value = error.status === 409
        } else {
          actionError.value = message
        }
      }
      return false
    } finally {
      if (!disposed) {
        action.value = ''
        void refreshJobs({ refresh: true })
      }
    }
  }

  function start() {
    if (canStart.value) {
      const selected = job.value
      return mutate('start', (signal) => startCleaningJob(selected, signal))
    }
    if (canRetryJob.value) {
      const selected = job.value
      return mutate('batch-retry', (signal) => retryCleaningJob(selected, signal))
    }
  }

  function retry(row) {
    if (row.status === 'ERROR' && job.value?.status !== 'RUNNING' && !jobsError.value) {
      return mutate(row.id, (signal) => retryCleaningSource(row, signal))
    }
  }

  function reprocess() {
    if (canReprocess.value) {
      const selected = job.value
      return mutate('reprocess', (signal) => reprocessCleaningReviews(selected, signal))
    }
  }

  function review(body) {
    const row = detail.data.value?.result
    if (row?.id === detailId.value && row.status === 'REVIEW_REQUIRED' && !reviewConflict.value) {
      return mutate('review', (signal) =>
        reviewCleaningSource(row.id, { ...body, recordVersion: row.recordVersion }, signal),
      )
    }
  }

  void refreshJobs()
  onScopeDispose(() => {
    disposed = true
    clearTimeout(timer)
    jobRead?.abort()
    write?.abort()
  })

  return {
    brand,
    selectBrand,
    filters,
    tab,
    page,
    size,
    total,
    search,
    reset,
    selectTab,
    resize,
    job,
    jobsLoading,
    jobsError,
    ruleVersions,
    refreshJobs,
    canStart,
    canRetryJob,
    canReprocess,
    table,
    loadResults,
    detail,
    detailId,
    openDetail,
    loadDetail,
    review,
    reviewError,
    reviewConflict,
    logs,
    logsJobId,
    logsPage,
    logsSize,
    logsTotal,
    openLogs,
    loadLogs,
    action,
    actionError,
    busy,
    start,
    retry,
    reprocess,
  }
}
