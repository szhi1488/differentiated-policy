import { request } from './request'
import { policyApiTime } from '../utils/policy-adapter'

const path = '/api/policies'
const itemPath = (id) => `${path}/${encodeURIComponent(id)}`
const logsPath = (id) => `${itemPath(id)}/audit-logs`

function write(url, method, body, headers, signal) {
  return request(url, {
    method,
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
    signal,
  })
}

export function createPolicyDraft(body, key, signal) {
  return write(`${path}/drafts`, 'POST', body, { 'Idempotency-Key': key }, signal)
}

export function savePolicyDraft(id, body, signal) {
  return write(`${itemPath(id)}/draft`, 'PUT', body, {}, signal)
}

export function deletePolicyDraft(id, recordVersion, signal) {
  const params = new URLSearchParams({ recordVersion })
  return request(`${itemPath(id)}/draft?${params}`, { method: 'DELETE', signal })
}

export function createPolicyTimeAdjustment(source, endTime, evidence, key, signal) {
  return write(
    `${itemPath(source.id)}/time-adjustment-draft`,
    'POST',
    {
      recordVersion: source.recordVersion,
      endTime: policyApiTime(endTime),
      expectedSourceEnd: policyApiTime(source.endTime),
      ...evidence,
    },
    { 'Idempotency-Key': key },
    signal,
  )
}

export function savePolicyAdjustmentDate(id, recordVersion, endTime, evidence, signal) {
  return write(
    `${itemPath(id)}/time-adjustment-date`,
    'PUT',
    { recordVersion, endTime: policyApiTime(endTime), ...evidence },
    {},
    signal,
  )
}

export function previewPolicySubmission(id, recordVersion, signal) {
  const params = new URLSearchParams({ recordVersion })
  return request(itemPath(id) + '/submission-preview?' + params, { signal })
}

export function previewPolicyAdjustments(id, recordVersion, endAdjustments, signal) {
  return write(
    itemPath(id) + '/submission-preview',
    'POST',
    { recordVersion, endAdjustments },
    {},
    signal,
  )
}

export function submitPolicy(id, body, signal) {
  return write(`${itemPath(id)}/submit`, 'POST', body, {}, signal)
}

export function readPolicy(id, signal) {
  return request(itemPath(id), { signal })
}

export function listMyPolicies({ page, pageSize, status, perspective, platformCode }, signal) {
  const params = new URLSearchParams({ page, pageSize })
  for (const [key, value] of Object.entries({ status, perspective, platformCode })) {
    if (value) {
      params.set(key, value)
    }
  }
  return request(`${path}/mine?${params}`, { signal })
}

export function listPolicyAuditLogs(id, { page, pageSize }, signal) {
  const params = new URLSearchParams({ page, pageSize })
  return request(`${logsPath(id)}?${params}`, { signal })
}

export function readAssignedPlatforms(signal) {
  return request(path + '/platforms', { signal })
}
