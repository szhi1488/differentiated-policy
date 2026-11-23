import { request } from './request'

const base = '/api/policy-reviews'
const roundPath = (id) => `${base}/${encodeURIComponent(id)}`

export function listMyPolicyReviews({ page, pageSize }, signal) {
  return request(`${base}/mine?${new URLSearchParams({ page, pageSize })}`, { signal })
}

export function readPolicyReviewRound(id, submissionVersion, signal) {
  return request(`${roundPath(id)}?${new URLSearchParams({ submissionVersion })}`, { signal })
}

export function reviewRoundAttachmentUrl(id, submissionVersion, fileId) {
  return `${roundPath(id)}/attachments/${encodeURIComponent(fileId)}/content?${new URLSearchParams({ submissionVersion })}`
}

export function reviewProductPolicy(id, decision, signal) {
  return request(`${roundPath(id)}/product-review`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(decision),
    signal,
  })
}
