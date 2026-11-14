import { request } from './request'

const jobsPath = '/api/admin/goods-sync/jobs'
const sourcesPath = '/api/admin/source-goods'
const cleaningPath = '/api/admin/catalog/cleaning'
const goodsPath = '/api/admin/catalog/goods'
const dictionaryPath = '/api/admin/catalog/dictionary'

export function readCatalogDictionary(signal) {
  return request(dictionaryPath, { signal })
}

export function saveCatalogDictionaryEntry({ type, id, name, parentId, expectedName }) {
  if (!id) {
    return post(dictionaryPath + '/' + type, { name, parentId })
  }
  return request(dictionaryPath + '/' + type + '/' + encodeURIComponent(id), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, expectedName }),
  })
}

export function removeCatalogDictionaryEntry({ type, id, name }) {
  const params = new URLSearchParams({ expectedName: name })
  return request(dictionaryPath + '/' + type + '/' + encodeURIComponent(id) + '?' + params, {
    method: 'DELETE',
  })
}

export function readCatalogGoods(filters, signal) {
  return getPage(goodsPath, filters, signal)
}

export function readCatalogGoodsSummary(signal) {
  return request(goodsPath + '/summary', { signal })
}

export function readCatalogGood(id, signal) {
  return request(goodsPath + '/' + encodeURIComponent(id), { signal })
}

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

function getPage(path, values, signal) {
  const params = new URLSearchParams()
  for (const [name, value] of Object.entries(values)) {
    if (value) {
      params.set(name, value)
    }
  }
  return request(path + '?' + params, { signal })
}

export function readCleaningResults(filters, signal) {
  return getPage(cleaningPath + '/results', filters, signal)
}

export function readCleaningPreview(filters, signal) {
  return getPage(cleaningPath + '/preview', filters, signal)
}

export function readCleaningJobs(page, size, signal) {
  return getPage(cleaningJobsPath, { page, size }, signal)
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
export function createCatalogGood(product) {
  return post(goodsPath, product)
}

export function updateCatalogGood(id, product, expectedVersion) {
  return request(goodsPath + '/' + encodeURIComponent(id), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ expectedVersion, product }),
  })
}

export function deleteCatalogGood(id) {
  return request(goodsPath + '/' + encodeURIComponent(id), { method: 'DELETE' })
}
