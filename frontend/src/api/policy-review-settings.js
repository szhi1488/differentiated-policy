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

export function saveReviewSettings(body, signal) {
  return request(settings, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  })
}
