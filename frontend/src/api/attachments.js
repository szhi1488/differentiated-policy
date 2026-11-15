import { request } from './request'

export function uploadAttachment(file, signal) {
  const body = new FormData()
  body.append('file', file)
  // 由浏览器生成 multipart boundary，不手动设置 Content-Type。
  return request('/api/attachments', { method: 'POST', body, signal })
}

export function deleteAttachment(id) {
  return request(`/api/attachments/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function readAttachmentContent(url, signal) {
  return request(url, { responseType: 'blob', signal })
}

export function attachmentDownloadUrl(id) {
  return `/api/attachments/${encodeURIComponent(id)}/content`
}

export function policyAttachmentDownloadUrl(policyId, attachmentId, logId, admin = false) {
  const root = admin ? '/api/admin/policies' : '/api/policies'
  const history = logId ? `/audit-logs/${encodeURIComponent(logId)}` : ''
  return `${root}/${encodeURIComponent(policyId)}${history}/attachments/${encodeURIComponent(attachmentId)}/content`
}
