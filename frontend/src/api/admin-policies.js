import { request } from './request'

const base = '/api/admin/policies'
const policyPath = (id) => `${base}/${encodeURIComponent(id)}`

export function listAdminPolicies(filters, signal) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (value !== '' && value !== null && value !== undefined) {
      params.set(key, value)
    }
  }
  return request(`${base}?${params}`, { signal })
}

export function readAdminPolicy(id, signal) {
  return request(policyPath(id), { signal })
}

export function reviewAdminPolicy(id, body, signal) {
  return request(`${policyPath(id)}/review`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  })
}
