import { request } from './request'

const settings = '/api/admin/policy-review-settings'

export function readReviewSettings(signal) {
  return request(settings, { signal })
}

export function listReviewerCandidates({ reviewGroup, name, department, page, pageSize }, signal) {
  const query = new URLSearchParams({ reviewGroup, page, pageSize })
  if (name) {
    query.set('name', name)
  }
  if (department) {
    query.set('department', department)
  }
  return request(`${settings}/candidates?${query}`, { signal })
}

function write(path, method, body, signal) {
  return request(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  })
}

export function saveReviewSettings(body, signal) {
  return write(settings, 'PUT', body, signal)
}

export function reassignReviewers(id, body, signal) {
  return write(`/api/admin/policy-reviews/${encodeURIComponent(id)}/reassign`, 'POST', body, signal)
}
