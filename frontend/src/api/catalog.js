import { request } from './request'

const jobsPath = '/api/admin/goods-sync/jobs'
const sourcesPath = '/api/admin/source-goods'
const cleaningPath = '/api/admin/catalog/cleaning'

export function readGoodsJobs(signal) {
  return request(jobsPath, { signal })
}

export function readSourceGoods({ brand, keyword, createdDate, page, size }, signal) {
  const params = new URLSearchParams({ page, size })
  if (brand) {
    params.set('brandName', brand)
  }
  if (keyword) {
    params.set('keyword', keyword)
  }
  if (createdDate) {
    params.set('createdDate', createdDate)
  }
  return request(`${sourcesPath}?${params}`, { signal })
}

function post(path, body, signal) {
  return request(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  })
}

export function startGoodsJob(scope, signal) {
  return post(jobsPath, scope, signal)
}

export function resumeGoodsJob({ id, recordVersion }, signal) {
  return post(`${jobsPath}/${encodeURIComponent(id)}/resume`, { recordVersion }, signal)
}

export function cancelGoodsJob({ id, recordVersion }, signal) {
  return post(`${jobsPath}/${encodeURIComponent(id)}/cancel`, { recordVersion }, signal)
}

const cleaningJobsPath = cleaningPath + '/jobs'

function cleaningQuery(path, values, signal) {
  const params = new URLSearchParams()
  for (const [name, value] of Object.entries(values)) {
    if (value) {
      params.set(name, value)
    }
  }
  return request(path + '?' + params, { signal })
}

export function readCleaningResults(filters, signal) {
  return cleaningQuery(cleaningPath + '/results', filters, signal)
}

export function readCleaningPreview(filters, signal) {
  return cleaningQuery(cleaningPath + '/preview', filters, signal)
}

export function readCleaningJobs(page, size, signal) {
  return cleaningQuery(cleaningJobsPath, { page, size }, signal)
}

export function readActiveCleaningJob(signal) {
  return request(cleaningJobsPath + '/active', { signal })
}

export function readCleaningJob(id, signal) {
  return request(cleaningJobsPath + '/' + encodeURIComponent(id), { signal })
}

export function startCleaningJob(scope, signal) {
  return post(cleaningJobsPath, scope, signal)
}

export function readCleaningSource(id, signal) {
  return request(sourcesPath + '/' + encodeURIComponent(id), { signal })
}

export function readCleaningLogs(id, page, size, signal) {
  return cleaningQuery(
    cleaningJobsPath + '/' + encodeURIComponent(id) + '/logs',
    { page, size },
    signal,
  )
}

export function readCleaningSourceLogs(id, page, size, signal) {
  return cleaningQuery(
    sourcesPath + '/' + encodeURIComponent(id) + '/clean-logs',
    { page, size },
    signal,
  )
}

export function retryCleaningSource({ id, recordVersion, requestKey }, signal) {
  return post(
    sourcesPath + '/' + encodeURIComponent(id) + '/clean-retry',
    { recordVersion, requestKey },
    signal,
  )
}

export function reviewCleaningSource(id, body, signal) {
  return post(sourcesPath + '/' + encodeURIComponent(id) + '/review', body, signal)
}
