import { request } from './request'

const jobsPath = '/api/admin/goods-sync/jobs'

export function readGoodsJobs(signal) {
  return request(jobsPath, { signal })
}

export function readSourceGoods({ brand, keyword, page, size }, signal) {
  const params = new URLSearchParams({ page, size })
  if (brand) {
    params.set('brandName', brand)
  }
  if (keyword) {
    params.set('keyword', keyword)
  }
  return request(`/api/admin/source-goods?${params}`, { signal })
}

function post(path, body, signal) {
  return request(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  })
}

export function startGoodsJob(brandName, idempotencyKey, signal) {
  return post(jobsPath, { brandName, idempotencyKey }, signal)
}

export function resumeGoodsJob({ id, recordVersion }, signal) {
  return post(`${jobsPath}/${encodeURIComponent(id)}/resume`, { recordVersion }, signal)
}

export function cancelGoodsJob({ id, recordVersion }, signal) {
  return post(`${jobsPath}/${encodeURIComponent(id)}/cancel`, { recordVersion }, signal)
}
