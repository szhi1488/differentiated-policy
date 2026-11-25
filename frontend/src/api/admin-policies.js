import { request, RequestError } from './request'
import { readPolicyReviewRound } from './policy-reviews'
import { notifySubsidyUpdate } from './subsidy-timeline'

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

export async function readAdminPolicy(id, signal, linkedVersion) {
  // 从飞书打开时先核对指定轮次。旧轮和非终审节点只展示该轮快照。
  let round
  if (linkedVersion !== undefined) {
    round = await readPolicyReviewRound(id, linkedVersion, signal)
    if (
      round.currentSubmissionVersion !== linkedVersion ||
      round.reviewStage !== 'PURCHASE_REVIEW' ||
      round.status !== 'PENDING'
    ) {
      return { linkedRound: round }
    }
  }
  const { policy, snapshot, audit, timeHistory } = await request(policyPath(id), { signal })
  const detail = { policy, snapshot, audit, timeHistory }
  if (
    detail.policy.id !== id ||
    detail.snapshot.id !== id ||
    detail.snapshot.submissionVersion !== detail.audit.submissionVersion ||
    detail.policy.submissionVersion !== detail.audit.submissionVersion
  ) {
    throw new Error('返回内容与所查申请或提交轮次不符，请重新读取政策。')
  }
  return round ? { ...detail, round: verifyCurrentRound(detail, round) } : detail
}

export function verifyCurrentRound(detail, round) {
  if (
    detail.policy.id !== round.id ||
    detail.audit.submissionVersion !== round.submissionVersion ||
    round.currentSubmissionVersion !== detail.policy.submissionVersion ||
    round.recordVersion !== detail.policy.recordVersion
  ) {
    throw new RequestError(
      '读取期间本轮审核状态已变化，请重新读取政策。',
      'business',
      409,
      'ROUND_RELOAD_REQUIRED',
    )
  }
  return round
}

export function listAdminPolicyAuditLogs(id, { page, pageSize }, signal) {
  const params = new URLSearchParams({ page, pageSize })
  return request(`${policyPath(id)}/audit-logs?${params}`, { signal })
}

export async function readAdminPolicyAuditLog(id, logId, signal) {
  const detail = await request(`${policyPath(id)}/audit-logs/${encodeURIComponent(logId)}`, {
    signal,
  })
  if (
    detail.log.id !== logId ||
    detail.snapshot.id !== id ||
    detail.snapshot.submissionVersion !== detail.log.submissionVersion
  ) {
    throw new Error('返回内容与所查申请或历史轮次不符，请重新读取。')
  }
  return detail
}

export async function reviewAdminPolicy(id, body, signal) {
  const result = await request(`${policyPath(id)}/review`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  })
  if (body.decision === 'APPROVE') {
    notifySubsidyUpdate()
  }
  return result
}
