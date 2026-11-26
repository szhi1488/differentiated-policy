import { computed, onScopeDispose, ref, watch } from 'vue'
import {
  cancelGoodsJob,
  readGoodsJobs,
  readSourceGoods,
  resumeGoodsJob,
  startGoodsJob,
} from '../api/catalog'
import { createRequestId } from '../utils/request-id'
import { useLatestQuery } from './useLatestQuery'
import { useVisibleTaskPolling } from './useVisibleTaskPolling'

const isActive = (job) => ['QUEUED', 'RUNNING', 'PAUSED'].includes(job.status)
const hasSettled = (job) => ['PAUSED', 'CANCELLED', 'SUCCEEDED'].includes(job.status)

export function useSourceGoods() {
  const brand = ref('all')
  const input = ref('')
  const keyword = ref('')
  const createdDateInput = ref(null)
  const createdDate = ref(null)
  const page = ref(1)
  const size = ref(20)
  const goods = useLatestQuery()
  const jobs = ref(null)
  const importOpen = ref(false)
  const jobsReady = ref(false)
  const configured = ref(false)
  const jobsError = ref('')
  const actionError = ref('')
  const action = ref('')
  const activeJob = computed(() => jobs.value?.find(isActive))
  const latest = computed(() =>
    Object.fromEntries((jobs.value ?? []).map((job) => [job.brandName, job])),
  )
  const requestKeys = new Map()
  let jobWrite
  let disposed = false

  function loadGoods() {
    return goods.run((signal) =>
      readSourceGoods(
        {
          brand: brand.value === 'all' ? '' : brand.value,
          keyword: keyword.value,
          createdDate: createdDate.value,
          page: page.value,
          size: size.value,
        },
        signal,
      ),
    )
  }

  watch([brand, keyword, createdDate, page, size], loadGoods, { immediate: true })

  function search() {
    const next = input.value.trim()
    const nextDate = createdDateInput.value || null
    if (page.value === 1 && keyword.value === next && createdDate.value === nextDate) {
      return loadGoods()
    }
    page.value = 1
    keyword.value = next
    createdDate.value = nextDate
  }

  function reset(defaultBrand = 'all') {
    brand.value = defaultBrand
    input.value = ''
    createdDateInput.value = null
    return search()
  }

  function selectBrand(value) {
    page.value = 1
    brand.value = value
  }

  function viewCreatedDate(job) {
    selectBrand(job.brandName)
    input.value = ''
    createdDateInput.value = job.syncDate
    return search()
  }

  function resize(value) {
    page.value = 1
    size.value = value
  }

  const { refresh: refreshJobs, stop: stopPolling } = useVisibleTaskPolling(readJobs, () => {
    const job = activeJob.value
    if (!job || action.value) {
      return 0
    }
    if (job.status === 'PAUSED' && (!importOpen.value || job.canResume)) {
      return 0
    }
    return 4000
  })

  watch(
    importOpen,
    (open) => {
      if (open) {
        void refreshJobs()
      }
    },
    { flush: 'sync' },
  )

  async function readJobs(signal, reason) {
    if (action.value) {
      return
    }
    if (reason !== 'poll') {
      jobsReady.value = false
    }
    try {
      const result = await readGoodsJobs(signal)
      if (signal.aborted) {
        return
      }
      const refreshTable =
        jobs.value !== null &&
        result.jobs.some((job) => {
          const previous = latest.value[job.brandName]
          return hasSettled(job) && (previous?.id !== job.id || previous?.status !== job.status)
        })
      configured.value = result.configured
      jobs.value = result.jobs
      jobsError.value = ''
      jobsReady.value = true
      if (refreshTable) {
        void loadGoods()
      }
    } catch (error) {
      if (!signal.aborted) {
        jobsError.value = error.message
        jobsReady.value = false
      }
    }
  }

  async function mutate(brandName, send) {
    if (disposed || action.value) {
      return
    }
    stopPolling()
    action.value = brandName
    actionError.value = ''
    jobWrite = new AbortController()
    try {
      const result = await send(jobWrite.signal)
      if (disposed) {
        return
      }
      jobs.value = [...jobs.value.filter((job) => job.brandName !== result.brandName), result]
      if (hasSettled(result)) {
        void loadGoods()
      }
    } catch (error) {
      if (!disposed) {
        actionError.value =
          error.kind === 'business' ? error.message : '暂无法确认操作结果，请核对任务状态后再试。'
      }
    } finally {
      if (!disposed) {
        action.value = ''
        void refreshJobs()
      }
    }
  }

  function start(brandName, { mode, date }) {
    if (!jobsReady.value || !configured.value || activeJob.value || (mode !== 'FULL' && !date)) {
      return
    }
    const scopeKey = JSON.stringify([brandName, mode, date])
    return mutate(brandName, async (signal) => {
      const idempotencyKey = requestKeys.get(scopeKey) ?? createRequestId()
      requestKeys.set(scopeKey, idempotencyKey)
      const result = await startGoodsJob({ brandName, idempotencyKey, mode, date }, signal)
      requestKeys.delete(scopeKey)
      return result
    })
  }

  function resume(job) {
    if (!jobsReady.value || !configured.value || !job.canResume) {
      return
    }
    return mutate(job.brandName, (signal) => resumeGoodsJob(job, signal))
  }

  function cancel(job) {
    if (!jobsReady.value || job.status !== 'PAUSED') {
      return
    }
    return mutate(job.brandName, (signal) => cancelGoodsJob(job, signal))
  }

  void refreshJobs()
  onScopeDispose(() => {
    disposed = true
    jobWrite?.abort()
  })

  return {
    brand,
    input,
    createdDateInput,
    createdDate,
    page,
    size,
    goods,
    latest,
    activeJob,
    importOpen,
    jobsReady,
    configured,
    jobsError,
    actionError,
    action,
    loadGoods,
    search,
    reset,
    selectBrand,
    viewCreatedDate,
    resize,
    refreshJobs,
    start,
    resume,
    cancel,
  }
}
