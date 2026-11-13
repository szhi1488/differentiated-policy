import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import {
  readActiveCleaningJob,
  readCleaningJob,
  readCleaningJobs,
  readCleaningPreview,
  readCleaningResults,
  readCleaningSource,
  retryCleaningSource,
  reviewCleaningSource,
  startCleaningJob,
} from '../api/catalog'
import { createRequestId } from '../utils/request-id'
import { useLatestQuery } from './useLatestQuery'

const emptyFilters = { brandName: '', dateMode: 'ALL', createdDate: null, keyword: '' }
const previewDefaults = {
  brandName: '',
  dateMode: 'DATE',
  createdDate: null,
  includeReviews: false,
}

export function useCatalogCleaning() {
  const filters = reactive({ ...emptyFilters })
  const applied = ref({ ...emptyFilters })
  const tab = ref('')
  const page = ref(1)
  const size = ref(20)
  const table = useLatestQuery()
  const total = computed(() => table.data.value?.total ?? 0)

  const startOpen = ref(false)
  const scope = reactive({ ...previewDefaults })
  const preview = useLatestQuery()
  const previewPage = ref(1)
  const previewSize = 10
  const previewSearch = ref('')
  const previewKeyword = ref('')
  const previewReady = computed(() =>
    Boolean(scope.brandName && (scope.dateMode === 'ALL' || scope.createdDate)),
  )
  const submittedJob = ref(null)
  const action = ref('')
  const actionError = ref('')
  const busy = computed(() => Boolean(action.value))

  const historyOpen = ref(false)
  const history = useLatestQuery()
  const historyPage = ref(1)
  const historySize = 10
  const active = ref(null)
  const progressError = ref('')
  const detailId = ref('')
  const detail = useLatestQuery()
  const reviewError = ref('')
  const reviewConflict = ref(false)
  let timer
  let readProgress
  let write
  let disposed = false
  let startRequest
  let retryRequest

  function loadResults() {
    const { brandName, dateMode, createdDate, keyword } = applied.value
    return table.run((signal) =>
      readCleaningResults(
        {
          brandName,
          createdDate: dateMode === 'DATE' ? createdDate : null,
          unknownDate: dateMode === 'UNKNOWN',
          keyword,
          status: tab.value,
          page: page.value,
          size: size.value,
        },
        signal,
      ),
    )
  }

  watch([applied, tab, page, size], loadResults, { immediate: true })
  watch(table.data, (result) => {
    if (result) {
      page.value = Math.min(page.value, Math.max(1, Math.ceil(result.total / size.value)))
    }
  })

  function search() {
    if (filters.dateMode === 'DATE' && !filters.createdDate) {
      return
    }
    page.value = 1
    applied.value = { ...filters, keyword: filters.keyword.trim() }
  }

  function reset() {
    Object.assign(filters, emptyFilters)
    tab.value = ''
    search()
  }

  function selectTab(value) {
    tab.value = value
    page.value = 1
  }

  function resize(value) {
    page.value = 1
    size.value = value
  }

  function openStart() {
    if (busy.value) {
      return
    }
    Object.assign(scope, previewDefaults, { brandName: applied.value.brandName })
    if (applied.value.dateMode === 'DATE') {
      scope.createdDate = applied.value.createdDate
    }
    previewSearch.value = ''
    previewKeyword.value = ''
    previewPage.value = 1
    submittedJob.value = null
    actionError.value = ''
    startOpen.value = true
  }

  function loadPreview() {
    if (!startOpen.value || !previewReady.value || submittedJob.value) {
      return preview.run(async () => null)
    }
    return preview.run((signal) =>
      readCleaningPreview(
        {
          brandName: scope.brandName,
          createdDate: scope.dateMode === 'DATE' ? scope.createdDate : null,
          includeReviews: scope.includeReviews,
          keyword: previewKeyword.value,
          page: previewPage.value,
          size: previewSize,
        },
        signal,
      ),
    )
  }

  watch(
    () => [scope.brandName, scope.dateMode, scope.createdDate, scope.includeReviews],
    () => {
      previewPage.value = 1
    },
    { flush: 'sync' },
  )
  watch([scope, previewKeyword, previewPage, startOpen], loadPreview)
  watch(preview.data, (result) => {
    if (result) {
      previewPage.value = Math.min(
        previewPage.value,
        Math.max(1, Math.ceil(result.total / previewSize)),
      )
    }
  })

  function searchPreview() {
    previewPage.value = 1
    previewKeyword.value = previewSearch.value.trim()
  }

  function loadHistory() {
    if (historyOpen.value) {
      return history.run((signal) => readCleaningJobs(historyPage.value, historySize, signal))
    }
  }

  watch([historyOpen, historyPage], loadHistory)

  function openHistory() {
    historyOpen.value = true
    void pollProgress()
  }

  async function pollProgress() {
    clearTimeout(timer)
    readProgress?.abort()
    const read = new AbortController()
    readProgress = read
    try {
      const previous = active.value
      const current = await readActiveCleaningJob(read.signal)
      if (disposed || read.signal.aborted) {
        return
      }
      active.value = current
      const shown = submittedJob.value
      if (shown && shown.status === 'RUNNING') {
        const next =
          current?.id === shown.id ? current : await readCleaningJob(shown.id, read.signal)
        if (disposed || read.signal.aborted) {
          return
        }
        if (submittedJob.value?.id === shown.id) {
          submittedJob.value = next
        }
      }
      if (
        (previous?.id && previous.id !== current?.id) ||
        previous?.processed !== current?.processed
      ) {
        void loadResults()
        void loadHistory()
      }
      progressError.value = ''
    } catch (error) {
      if (!disposed && !read.signal.aborted) {
        progressError.value = error.message
      }
    } finally {
      if (!disposed && !read.signal.aborted) {
        timer = setTimeout(
          pollProgress,
          active.value || historyOpen.value || startOpen.value ? 2500 : 10000,
        )
      }
    }
  }

  function confirmedScope() {
    const expectedTotal = preview.data.value?.eligible ?? 0
    if (
      !startOpen.value ||
      !previewReady.value ||
      submittedJob.value ||
      busy.value ||
      !expectedTotal
    ) {
      return null
    }
    return {
      brandName: scope.brandName,
      createdDate: scope.dateMode === 'DATE' ? scope.createdDate : null,
      includeReviews: scope.includeReviews,
      expectedTotal,
    }
  }

  async function start(confirmed) {
    const current = confirmedScope()
    if (!current || JSON.stringify(current) !== JSON.stringify(confirmed)) {
      return false
    }
    const identity = JSON.stringify(current)
    if (startRequest?.identity !== identity) {
      startRequest = { identity, key: createRequestId() }
    }
    return runWrite(
      'start',
      async (signal) => {
        const job = await startCleaningJob({ ...current, requestKey: startRequest.key }, signal)
        if (disposed) {
          return
        }
        startRequest = null
        submittedJob.value = job
        track(job)
      },
      (error) => {
        if (error.code === 'CATALOG_CLEAN_SCOPE_CHANGED') {
          startRequest = null
          void loadPreview()
        }
      },
    )
  }

  async function retry(row) {
    const identity = row.id + ':' + row.recordVersion
    if (retryRequest?.identity !== identity) {
      retryRequest = { identity, key: createRequestId() }
    }
    return runWrite(row.id, async (signal) => {
      const job = await retryCleaningSource(
        {
          id: row.id,
          recordVersion: row.recordVersion,
          requestKey: retryRequest.key,
        },
        signal,
      )
      if (disposed) {
        return
      }
      retryRequest = null
      historyOpen.value = true
      track(job)
    })
  }

  function track(job) {
    active.value = job.status === 'RUNNING' ? job : null
    void loadResults()
    void loadHistory()
    void pollProgress()
  }

  function loadDetail() {
    reviewError.value = ''
    reviewConflict.value = false
    return detail.run((signal) => readCleaningSource(detailId.value, signal))
  }

  function openDetail(row) {
    detailId.value = row.id
    void loadDetail()
  }

  async function review(body) {
    const row = detail.data.value?.result
    if (!row || reviewConflict.value) {
      return false
    }
    reviewError.value = ''
    return runWrite(
      'review',
      async (signal) => {
        await reviewCleaningSource(row.id, { ...body, recordVersion: row.recordVersion }, signal)
        if (disposed) {
          return
        }
        detailId.value = ''
        void loadResults()
      },
      (error) => {
        reviewError.value = error.message
        reviewConflict.value = error.status === 409
      },
    )
  }

  async function runWrite(name, operation, onError) {
    if (busy.value) {
      return false
    }
    write = new AbortController()
    action.value = name
    actionError.value = ''
    try {
      await operation(write.signal)
      return !disposed
    } catch (error) {
      if (!disposed && error.kind !== 'cancelled') {
        if (name !== 'review') {
          actionError.value = ['timeout', 'network', 'parse'].includes(error.kind)
            ? '提交结果待确认，请先在清洗记录核对是否已受理。'
            : error.message
        }
        onError?.(error)
        if (name !== 'review') {
          void pollProgress()
        }
      }
      return false
    } finally {
      if (!disposed) {
        action.value = ''
      }
    }
  }

  onScopeDispose(() => {
    disposed = true
    clearTimeout(timer)
    readProgress?.abort()
    write?.abort()
  })

  void pollProgress()

  return {
    filters,
    tab,
    page,
    size,
    total,
    table,
    loadResults,
    search,
    reset,
    selectTab,
    resize,
    startOpen,
    scope,
    preview,
    previewPage,
    previewSize,
    previewSearch,
    previewReady,
    submittedJob,
    openStart,
    loadPreview,
    searchPreview,
    confirmedScope,
    start,
    historyOpen,
    history,
    historyPage,
    historySize,
    openHistory,
    loadHistory,
    active,
    progressError,
    action,
    actionError,
    busy,
    retry,
    detailId,
    detail,
    openDetail,
    loadDetail,
    review,
    reviewError,
    reviewConflict,
  }
}
