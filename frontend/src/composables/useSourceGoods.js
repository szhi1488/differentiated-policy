import { computed, onScopeDispose, ref, watch } from 'vue'
import {
  cancelGoodsJob,
  readGoodsJobs,
  readSourceGoods,
  resumeGoodsJob,
  startGoodsJob,
} from '../api/catalog'
import { useLatestQuery } from './useLatestQuery'

const isActive = (job) => ['QUEUED', 'RUNNING', 'PAUSED'].includes(job.status)
const hasSettled = (job) => ['PAUSED', 'CANCELLED', 'SUCCEEDED'].includes(job.status)

function createRequestId() {
  // 客户端本地预览也可能使用普通 HTTP，此时 randomUUID 不一定可用。
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64
  bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join('-')
}

export function useSourceGoods() {
  const brand = ref('all')
  const input = ref('')
  const keyword = ref('')
  const createdDateInput = ref(null)
  const createdDate = ref(null)
  const page = ref(1)
  const size = ref(20)
  const goods = useLatestQuery()
  const jobs = ref([])
  const configured = ref(false)
  const jobsError = ref('')
  const actionError = ref('')
  const action = ref('')
  const activeJob = computed(() => jobs.value.find(isActive))
  const latest = computed(() => Object.fromEntries(jobs.value.map((job) => [job.brandName, job])))
  const requestKeys = new Map()
  let timer
  let jobRead
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

  function reset() {
    brand.value = 'all'
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

  function stopPolling() {
    clearTimeout(timer)
    jobRead?.abort()
  }

  async function refreshJobs() {
    if (disposed || action.value) {
      return
    }
    stopPolling()
    const read = new AbortController()
    jobRead = read
    try {
      const result = await readGoodsJobs(read.signal)
      if (disposed || read.signal.aborted) {
        return
      }
      const refreshTable = result.jobs.some((job) => {
        const previous = latest.value[job.brandName]
        return (
          previous && hasSettled(job) && (previous.id !== job.id || previous.status !== job.status)
        )
      })
      configured.value = result.configured
      jobs.value = result.jobs
      jobsError.value = ''
      if (refreshTable) {
        void loadGoods()
      }
    } catch (error) {
      if (!disposed && !read.signal.aborted) {
        jobsError.value = error.message
      }
    } finally {
      if (!disposed && !read.signal.aborted) {
        timer = setTimeout(refreshJobs, activeJob.value ? 4000 : 12000)
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
    if (!configured.value || activeJob.value || jobsError.value || (mode !== 'FULL' && !date)) {
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
    if (!configured.value || !job.canResume || jobsError.value) {
      return
    }
    return mutate(job.brandName, (signal) => resumeGoodsJob(job, signal))
  }

  function cancel(job) {
    if (job.status !== 'PAUSED' || jobsError.value) {
      return
    }
    return mutate(job.brandName, (signal) => cancelGoodsJob(job, signal))
  }

  void refreshJobs()
  onScopeDispose(() => {
    disposed = true
    stopPolling()
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
