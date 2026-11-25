import { request } from './request'

const base = '/api/policy-reviews'
const roundPath = (id) => `${base}/${encodeURIComponent(id)}`

export async function readPolicyReviewRound(id, submissionVersion, signal) {
  const round = await request(`${roundPath(id)}?${new URLSearchParams({ submissionVersion })}`, {
    signal,
  })
  if (round.id !== id || round.submissionVersion !== submissionVersion) {
    throw new Error('返回内容与所查单据或轮次不符，请重新读取。')
  }
  return round
}

export function reviewRoundAttachmentUrl(id, submissionVersion, fileId) {
  return `${roundPath(id)}/attachments/${encodeURIComponent(fileId)}/content?${new URLSearchParams({ submissionVersion })}`
}

export function timeChangeAttachmentUrl(id, submissionVersion, changeId, fileId) {
  return `${roundPath(id)}/time-changes/${encodeURIComponent(changeId)}/attachments/${encodeURIComponent(fileId)}/content?${new URLSearchParams({ submissionVersion })}`
}
