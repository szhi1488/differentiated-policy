import { request } from './request'
import { readPolicyReviewRound } from './policy-reviews'

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

export async function readAdminPolicy(id, signal) {
  const detail = await request(policyPath(id) + '?includeCoverage=false', { signal })
  if (detail.audit.workflowVersion !== 1) {
    return detail
  }
  const round = await readPolicyReviewRound(id, detail.audit.submissionVersion, signal)
  if (
    round.currentSubmissionVersion !== detail.policy.submissionVersion ||
    round.recordVersion !== detail.policy.recordVersion
  ) {
    throw new Error('读取期间本轮审核状态已变化，请重新读取政策。')
  }
  return { ...detail, round }
}

export function listAdminPolicyAuditLogs(id, { page, pageSize }, signal) {
  const params = new URLSearchParams({ page, pageSize })
  return request(`${policyPath(id)}/audit-logs?${params}`, { signal })
}

export async function readAdminPolicyAuditLog(id, logId, signal) {
  const detail = await request(`${policyPath(id)}/audit-logs/${encodeURIComponent(logId)}`, {
    signal,
  })
  if (detail.log.workflowVersion !== 1) {
    return detail
  }
  const round = await readPolicyReviewRound(id, detail.log.submissionVersion, signal)
  return { ...detail, round }
}

export function reviewAdminPolicy(id, body, signal) {
  return request(`${policyPath(id)}/review`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  })
}
