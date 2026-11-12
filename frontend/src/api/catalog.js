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

export function readLatestCleaningJob(brandName, signal) {
  const params = new URLSearchParams({ brandName })
  return request(`${cleaningPath}/latest?${params}`, { signal })
}

export function readCleaningResults(
  { importJobId, brandName, status, ruleVersion, keyword, page, size },
  signal,
) {
  const params = new URLSearchParams({ importJobId, page, size })
  for (const [name, value] of Object.entries({ brandName, status, ruleVersion, keyword })) {
    if (value) {
      params.set(name, value)
    }
  }
  return request(`${cleaningPath}/results?${params}`, { signal })
}

export function readCleaningSource(id, signal) {
  return request(`${sourcesPath}/${encodeURIComponent(id)}`, { signal })
}

export function readCleaningLogs(importJobId, page, size, signal) {
  const params = new URLSearchParams({ page, size })
  return request(`${jobsPath}/${encodeURIComponent(importJobId)}/clean-logs?${params}`, { signal })
}

export function startCleaningJob({ importJobId, recordVersion }, signal) {
  return post(
    `${jobsPath}/${encodeURIComponent(importJobId)}/clean-start`,
    { recordVersion },
    signal,
  )
}

export function retryCleaningJob({ importJobId, recordVersion }, signal) {
  return post(
    `${jobsPath}/${encodeURIComponent(importJobId)}/clean-retry`,
    { recordVersion },
    signal,
  )
}

export function retryCleaningSource({ id, recordVersion }, signal) {
  return post(`${sourcesPath}/${encodeURIComponent(id)}/clean-retry`, { recordVersion }, signal)
}

export function reprocessCleaningReviews({ importJobId, recordVersion }, signal) {
  return post(
    `${jobsPath}/${encodeURIComponent(importJobId)}/clean-reprocess`,
    { recordVersion },
    signal,
  )
}

export function reviewCleaningSource(id, body, signal) {
  return post(`${sourcesPath}/${encodeURIComponent(id)}/review`, body, signal)
}
